package io.candy.tick.data.store

import android.content.Context
import android.util.Log
import io.candy.tick.data.AppStateStore
import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.download.ApkVerifier
import io.candy.tick.data.download.SignatureVerdict
import io.candy.tick.data.model.StoreAppUpdate
import io.candy.tick.data.model.StoreHealth
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.model.StoreSignatureRecord
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class StoreCheckProgress(
    val completed: Int,
    val total: Int,
    val runningStoreIds: List<String>,
)

data class StoreCheckSummary(
    val checkedStores: Int,
    val failedStores: List<String>,
    val updatableCount: Int,
    val newlyDiscovered: List<StoreAppUpdate>,
    val skippedByThrottle: Boolean = false,
)

/**
 * 商店更新检查协调器：
 * - 单店异常隔离与 90 秒超时预算（单店超时不影响其余健康商店）；
 * - 连续失败 3 次自动暂停该商店；
 * - Mutex 串行化防止前后台并发检查互相踩踏进度与状态；
 * - 只把「确实有更新」的条目落盘，已装列表始终实时扫描。
 */
class StoreUpdateChecker(
    private val context: Context,
    private val stateStore: AppStateStore,
    private val sources: Map<String, StoreSource>,
) {
    private val _progress = MutableStateFlow<StoreCheckProgress?>(null)
    val progress: StateFlow<StoreCheckProgress?> = _progress.asStateFlow()
    private val checkMutex = Mutex()

    suspend fun checkAll(force: Boolean = false): StoreCheckSummary = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            checkAllLocked(force)
        }
    }

    private suspend fun checkAllLocked(force: Boolean): StoreCheckSummary {
        val state = stateStore.state.value
        val prefs = state.settings.storePrefs
        val activeIds = prefs.enabledStores.filter { id ->
            id in sources && state.storeState.health[id]?.autoDisabled != true
        }
        if (activeIds.isEmpty()) {
            return StoreCheckSummary(0, emptyList(), 0, emptyList())
        }
        val lastCheck = state.storeState.lastCheckedAt
        if (!force && lastCheck != null &&
            Duration.between(lastCheck, Instant.now()) < AUTO_CHECK_THROTTLE
        ) {
            val currentUpdatable = countActionableUpdates(
                updates = state.storeState.updates,
                enabledStores = prefs.enabledStores,
                excluded = prefs.excludedPackages,
                skipped = prefs.skippedVersions,
                pinnedSources = prefs.pinnedSources,
                signatures = state.storeState.signatures,
            )
            return StoreCheckSummary(0, emptyList(), currentUpdatable, emptyList(), skippedByThrottle = true)
        }

        val installed = InstalledAppScanner.scan(context, includeSystemApps = prefs.includeSystemApps)
            .filter { it.packageName !in prefs.excludedPackages }
        if (installed.isEmpty()) {
            stateStore.update { it.copy(storeState = it.storeState.copy(updates = emptyMap(), lastCheckedAt = Instant.now())) }
            return StoreCheckSummary(activeIds.size, emptyList(), 0, emptyList())
        }

        val deviceOemStore = StoreSystemAppPolicy.deviceOemStoreId()
        _progress.value = StoreCheckProgress(completed = 0, total = activeIds.size, runningStoreIds = activeIds)
        val perStoreResults = try {
            coroutineScope {
                activeIds.map { id ->
                    async {
                        val source = sources.getValue(id)
                        val storeApps = installed.filter {
                            StoreSystemAppPolicy.isStoreEligibleForApp(it, id, deviceOemStore)
                        }
                        val result = if (storeApps.isEmpty()) {
                            Result.success(emptyMap())
                        } else {
                            try {
                                Result.success(withTimeout(PER_STORE_TIMEOUT_MS) { source.checkUpdates(storeApps) })
                            } catch (e: TimeoutCancellationException) {
                                // 单店超时必须隔离，不得当作外部取消中止整轮检查
                                Result.failure(SourceException(SourceError.Timeout, e))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                Result.failure(t)
                            }
                        }
                        _progress.update { cur ->
                            cur?.copy(
                                completed = cur.completed + 1,
                                runningStoreIds = cur.runningStoreIds.filterNot { it == id },
                            )
                        }
                        id to result
                    }
                }.awaitAll()
            }
        } finally {
            _progress.value = null
        }

        val now = Instant.now()
        val failedIds = mutableListOf<String>()
        val candidatesByPkg = mutableMapOf<String, MutableList<StoreReleaseInfo>>()
        val checkedHealthDelta = mutableMapOf<String, StoreHealth>()
        val latestHealth = stateStore.state.value.storeState.health

        for ((storeId, outcome) in perStoreResults) {
            outcome.onSuccess { map ->
                checkedHealthDelta[storeId] = StoreHealth(consecutiveFailures = 0, autoDisabled = false, lastError = null)
                for ((pkg, rel) in map) {
                    candidatesByPkg.getOrPut(pkg) { mutableListOf() }.add(rel)
                }
            }.onFailure { err ->
                if (err is CancellationException && err !is TimeoutCancellationException) throw err
                Log.w(TAG, "Store check failed for $storeId", err)
                failedIds += storeId
                val prev = latestHealth[storeId] ?: StoreHealth()
                val failures = prev.consecutiveFailures + 1
                val code = (err as? SourceException)?.error?.let { it.toString() } ?: err.javaClass.simpleName
                checkedHealthDelta[storeId] = StoreHealth(
                    consecutiveFailures = failures,
                    autoDisabled = failures >= AUTO_DISABLE_THRESHOLD,
                    lastError = code,
                )
            }
        }

        val installedByPkg = installed.associateBy { it.packageName }
        val latestState = stateStore.state.value
        val prevUpdates = latestState.storeState.updates
        val currentSigs = latestState.storeState.signatures
        val sigAdditions = mutableMapOf<String, StoreSignatureRecord>()
        val staleSigKeys = mutableSetOf<String>()
        val merged = mutableMapOf<String, StoreAppUpdate>()
        val newlyDiscovered = mutableListOf<StoreAppUpdate>()

        for ((pkg, local) in installedByPkg) {
            val fresh = candidatesByPkg[pkg].orEmpty()
            val preservedFromFailed = if (failedIds.isNotEmpty()) {
                prevUpdates[pkg]?.candidates.orEmpty().filter { it.storeId in failedIds && it.versionCode > local.versionCode }
            } else emptyList()
            val combined = (fresh + preservedFromFailed)
                .filter {
                    it.versionCode > local.versionCode &&
                        StoreSystemAppPolicy.isStoreEligibleForApp(local, it.storeId, deviceOemStore)
                }
                .distinctBy { it.storeId }
            if (combined.isEmpty()) continue

            // 校验已有签名缓存是否因本机重装而失效，并对下发了 sSha2 的商店提前预比对
            if (local.signatureSha256.isNotBlank()) {
                for (cand in combined) {
                    val key = latestState.storeState.signatureKey(pkg, cand.storeId)
                    val existing = currentSigs[key]
                    if (existing != null && existing.localCertSha256.isNotBlank() && existing.localCertSha256 != local.signatureSha256) {
                        staleSigKeys += key
                    }
                    if (cand.signerSha256s.isNotEmpty()) {
                        val verdict = ApkVerifier.compareSignatures(
                            installed = setOf(local.signatureSha256),
                            candidate = cand.signerSha256s.toSet(),
                        )
                        when (verdict) {
                            SignatureVerdict.MATCH -> sigAdditions[key] = StoreSignatureRecord("MATCH", local.signatureSha256)
                            SignatureVerdict.DIFFERENT -> sigAdditions[key] = StoreSignatureRecord("DIFFERENT", local.signatureSha256)
                            SignatureVerdict.UNKNOWN -> Unit
                        }
                    }
                }
            }

            fun resolveVerdict(sid: String): SignatureVerdict {
                val key = latestState.storeState.signatureKey(pkg, sid)
                val rec = sigAdditions[key] ?: currentSigs[key]?.takeIf { key !in staleSigKeys }
                return validSignatureVerdict(rec, local.signatureSha256)
            }

            val best = StoreCatalog.selectBestCandidate(
                candidates = combined,
                enabledOrder = prefs.enabledStores,
                pinnedStoreId = prefs.pinnedSources[pkg],
                verdictOf = ::resolveVerdict,
            ) ?: continue

            val prevBestCode = prevUpdates[pkg]?.candidates?.maxOfOrNull { it.versionCode } ?: 0L
            val isNewVersion = best.versionCode > prevBestCode
            val isSkipped = (prefs.skippedVersions[pkg] ?: 0L) >= best.versionCode
            val entry = StoreAppUpdate(
                packageName = pkg,
                displayName = local.displayName,
                installedVersionCode = local.versionCode,
                installedVersionName = local.versionName,
                candidates = combined,
                checkedAt = now,
                unread = if (isSkipped) false else (isNewVersion || prevUpdates[pkg]?.unread == true),
            )
            merged[pkg] = entry
            if (isNewVersion && !isSkipped) newlyDiscovered += entry
        }

        stateStore.update { cur ->
            // 增量合并：不覆盖检查期间由下载器/用户手动操作写入的新状态
            val nextHealth = cur.storeState.health + checkedHealthDelta
            val autoDisabledNow = checkedHealthDelta.filterValues { it.autoDisabled }.keys
            val nextEnabled = cur.settings.storePrefs.enabledStores.filterNot { it in autoDisabledNow }
            val nextSigs = (cur.storeState.signatures - staleSigKeys) + sigAdditions
            cur.copy(
                settings = cur.settings.copy(
                    storePrefs = cur.settings.storePrefs.copy(enabledStores = nextEnabled),
                ),
                storeState = cur.storeState.copy(
                    updates = merged,
                    signatures = nextSigs,
                    health = nextHealth,
                    lastCheckedAt = now,
                ),
            )
        }

        val finalState = stateStore.state.value
        val actionableCount = countActionableUpdates(
            updates = merged,
            enabledStores = finalState.settings.storePrefs.enabledStores,
            excluded = finalState.settings.storePrefs.excludedPackages,
            skipped = finalState.settings.storePrefs.skippedVersions,
            pinnedSources = finalState.settings.storePrefs.pinnedSources,
            signatures = finalState.storeState.signatures,
        )
        return StoreCheckSummary(
            checkedStores = activeIds.size,
            failedStores = failedIds,
            updatableCount = actionableCount,
            newlyDiscovered = newlyDiscovered,
        )
    }

    /** 单个已装应用点查：向所有已开启商店查询这一个应用的新版本。 */
    suspend fun checkSingleApp(local: LocalInstalledApp): Boolean = withContext(Dispatchers.IO) {
        val state = stateStore.state.value
        val prefs = state.settings.storePrefs
        val deviceOemStore = StoreSystemAppPolicy.deviceOemStoreId()
        val activeIds = prefs.enabledStores.filter {
            it in sources && StoreSystemAppPolicy.isStoreEligibleForApp(local, it, deviceOemStore)
        }
        if (activeIds.isEmpty()) return@withContext false
        val candidates = coroutineScope {
            activeIds.map { id ->
                async {
                    try {
                        withTimeout(25_000L) { sources.getValue(id).checkUpdates(listOf(local))[local.packageName] }
                    } catch (_: TimeoutCancellationException) {
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                }
            }.awaitAll().filterNotNull().filter { it.versionCode > local.versionCode }.distinctBy { it.storeId }
        }
        if (candidates.isEmpty()) return@withContext false

        val sigAdditions = mutableMapOf<String, StoreSignatureRecord>()
        if (local.signatureSha256.isNotBlank()) {
            for (cand in candidates) {
                if (cand.signerSha256s.isEmpty()) continue
                val verdict = ApkVerifier.compareSignatures(
                    installed = setOf(local.signatureSha256),
                    candidate = cand.signerSha256s.toSet(),
                )
                val key = state.storeState.signatureKey(local.packageName, cand.storeId)
                when (verdict) {
                    SignatureVerdict.MATCH -> sigAdditions[key] = StoreSignatureRecord("MATCH", local.signatureSha256)
                    SignatureVerdict.DIFFERENT -> sigAdditions[key] = StoreSignatureRecord("DIFFERENT", local.signatureSha256)
                    SignatureVerdict.UNKNOWN -> Unit
                }
            }
        }
        val combinedSigs = state.storeState.signatures + sigAdditions
        val best = StoreCatalog.selectBestCandidate(
            candidates = candidates,
            enabledOrder = prefs.enabledStores,
            pinnedStoreId = prefs.pinnedSources[local.packageName],
            verdictOf = { sid ->
                validSignatureVerdict(combinedSigs[state.storeState.signatureKey(local.packageName, sid)], local.signatureSha256)
            },
        ) ?: return@withContext false

        val isSkipped = (prefs.skippedVersions[local.packageName] ?: 0L) >= best.versionCode
        val now = Instant.now()
        val entry = StoreAppUpdate(
            packageName = local.packageName,
            displayName = local.displayName,
            installedVersionCode = local.versionCode,
            installedVersionName = local.versionName,
            candidates = candidates,
            checkedAt = now,
            unread = !isSkipped,
        )
        stateStore.update { cur ->
            cur.copy(
                storeState = cur.storeState.copy(
                    updates = cur.storeState.updates + (local.packageName to entry),
                    signatures = cur.storeState.signatures + sigAdditions,
                ),
            )
        }
        true
    }

    /** 装包完成后即时清理已升至最新版本的记录。 */
    suspend fun reconcilePackage(packageName: String) = withContext(Dispatchers.IO) {
        val pkgInfo = runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
        }.getOrNull() ?: return@withContext
        val installedCode = pkgInfo.longVersionCode
        val installedName = pkgInfo.versionName?.takeIf { it.isNotBlank() } ?: installedCode.toString()
        stateStore.update { st ->
            val current = st.storeState.updates[packageName] ?: return@update st
            val remaining = current.candidates.filter { it.versionCode > installedCode }
            val nextUpdates = if (remaining.isEmpty()) {
                st.storeState.updates - packageName
            } else {
                st.storeState.updates + (packageName to current.copy(
                    installedVersionCode = installedCode,
                    installedVersionName = installedName,
                    candidates = remaining,
                ))
            }
            st.copy(storeState = st.storeState.copy(updates = nextUpdates))
        }
    }

    private fun countActionableUpdates(
        updates: Map<String, StoreAppUpdate>,
        enabledStores: List<String>,
        excluded: Set<String>,
        skipped: Map<String, Long>,
        pinnedSources: Map<String, String>,
        signatures: Map<String, StoreSignatureRecord>,
    ): Int {
        val enabledSet = enabledStores.toSet()
        return updates.values.count { entry ->
            if (entry.packageName in excluded) return@count false
            val activeCandidates = entry.candidates.filter { it.storeId in enabledSet }
            val best = StoreCatalog.selectBestCandidate(
                candidates = activeCandidates,
                enabledOrder = enabledStores,
                pinnedStoreId = pinnedSources[entry.packageName],
                verdictOf = { sid ->
                    when (signatures["${entry.packageName}|$sid"]?.verdict) {
                        "MATCH" -> SignatureVerdict.MATCH
                        "DIFFERENT" -> SignatureVerdict.DIFFERENT
                        else -> SignatureVerdict.UNKNOWN
                    }
                },
            ) ?: return@count false
            best.versionCode > (skipped[entry.packageName] ?: 0L)
        }
    }

    companion object {
        private const val TAG = "StoreUpdateChecker"
        private const val PER_STORE_TIMEOUT_MS = 90_000L
        private const val AUTO_DISABLE_THRESHOLD = 3
        private val AUTO_CHECK_THROTTLE = Duration.ofMinutes(30)

        /** 校验签名缓存是否仍与当前本机证书一致；若重装后证书变更则视为 UNKNOWN。 */
        internal fun validSignatureVerdict(
            record: StoreSignatureRecord?,
            localCertSha256: String,
        ): SignatureVerdict {
            if (record == null) return SignatureVerdict.UNKNOWN
            if (localCertSha256.isNotBlank() && record.localCertSha256.isNotBlank() &&
                !record.localCertSha256.equals(localCertSha256, ignoreCase = true)
            ) {
                return SignatureVerdict.UNKNOWN
            }
            return when (record.verdict) {
                "MATCH" -> SignatureVerdict.MATCH
                "DIFFERENT" -> SignatureVerdict.DIFFERENT
                else -> SignatureVerdict.UNKNOWN
            }
        }
    }
}
