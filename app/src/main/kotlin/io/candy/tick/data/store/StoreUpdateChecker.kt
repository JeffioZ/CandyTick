package io.candy.tick.data.store

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.candy.tick.data.AppStateStore
import io.candy.tick.data.download.ApkVerifier
import io.candy.tick.data.download.SignatureVerdict
import io.candy.tick.data.model.StoreAppUpdate
import io.candy.tick.data.model.StoreHealth
import io.candy.tick.data.model.AppState
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.model.StoreSignatureRecord
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
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

data class StoreCheckProgress(
    val completed: Int,
    val total: Int,
    val runningStoreIds: List<String>,
)

data class StoreCheckSummary(
    val checkedStores: Int,
    val failedStores: List<String>,
    val updatableCount: Int,
    val newlyDiscovered: Map<String, Long>,
    val skippedByThrottle: Boolean = false,
    val complete: Boolean = true,
)

enum class SingleStoreCheckResult { FOUND, UP_TO_DATE, INCOMPLETE, NO_ELIGIBLE_STORE }

/** Serialize full checks, single checks and install events across all entry points. */
class StoreUpdateChecker(
    private val context: Context,
    private val stateStore: AppStateStore,
    private val sources: Map<String, StoreSource>,
) {
    private val _progress = MutableStateFlow<StoreCheckProgress?>(null)
    val progress: StateFlow<StoreCheckProgress?> = _progress.asStateFlow()
    private val checkMutex = Mutex()

    suspend fun checkAll(
        force: Boolean = false,
        scanInstalled: suspend (Boolean) -> InstalledAppScan = { InstalledAppScanner.scan(context, it) },
    ): StoreCheckSummary = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            try {
                var summary = checkAllLocked(force, scanInstalled)
                while (summary == null) {
                    // Settings changed during the request. Check the new scope before publishing a result.
                    summary = checkAllLocked(true, scanInstalled)
                }
                summary
            } finally {
                _progress.value = null
            }
        }
    }

    private suspend fun checkAllLocked(force: Boolean, scanInstalled: suspend (Boolean) -> InstalledAppScan): StoreCheckSummary? {
        val start = stateStore.state.value
        val prefs = start.settings.storePrefs
        val ids = prefs.enabledStores.filter { it in sources }
        if (ids.isEmpty()) return summary(start, checked = 0, complete = false)

        val lastAttempt = start.storeState.lastAttemptAt ?: start.storeState.lastCheckedAt
        if (!force && lastAttempt != null && Duration.between(lastAttempt, Instant.now()) < AUTO_CHECK_THROTTLE) {
            return summary(start, checked = 0, complete = start.storeState.lastCheckComplete == true)
                .copy(skippedByThrottle = true)
        }
        val scan = scanInstalled(prefs.includeSystemApps)
        val storesToQuery = if (scan.apps.isEmpty() && !scan.complete) emptyList() else ids
        val installed = scan.apps.filter { it.packageName !in prefs.excludedPackages && (prefs.includeSystemApps || !it.isSystemApp) }
        val oem = StoreSystemAppPolicy.deviceOemStoreId()
        _progress.value = if (storesToQuery.isEmpty()) null else StoreCheckProgress(0, storesToQuery.size, storesToQuery)
        val results = coroutineScope {
            storesToQuery.map { id ->
                async {
                    val eligible = installed.filter { StoreSystemAppPolicy.isStoreEligibleForApp(it, id, oem) }
                    val prior = start.storeState.health[id]?.takeIf { scan.complete && start.storeState.lastCheckComplete == false }
                    val result = sources.getValue(id).queryWithCoverage(
                        eligible, PER_STORE_TIMEOUT_MS, prior?.nextPackage, prior?.confirmedVersions.orEmpty(),
                    )
                    _progress.update { it?.copy(completed = it.completed + 1, runningStoreIds = it.runningStoreIds - id) }
                    id to result
                }
            }.awaitAll().toMap()
        }
        val failed = results.filterValues { it.unconfirmed.isNotEmpty() }.keys.toList()
        val now = Instant.now()
        val discovered = mutableMapOf<String, Long>()
        var scopeChanged = false
        stateStore.update { cur ->
            val currentPrefs = cur.settings.storePrefs
            if (prefs.enabledStores.toSet() != currentPrefs.enabledStores.toSet() ||
                prefs.includeSystemApps != currentPrefs.includeSystemApps || prefs.excludedPackages != currentPrefs.excludedPackages ||
                (StoreCatalog.ID_FDROID in ids && prefs.fdroidMirror != currentPrefs.fdroidMirror)
            ) {
                scopeChanged = true
                return@update cur
            }
            val signatures = cur.storeState.signatures.toMutableMap()
            val merged = if (scan.complete) mutableMapOf() else cur.storeState.updates.toMutableMap()
            for (local in installed) {
                val pkg = local.packageName
                val old = cur.storeState.updates[pkg]
                val entry = mergePackage(local, results, cur, signatures, now)
                if (entry == null) {
                    merged.remove(pkg)
                    continue
                }
                val best = StoreCatalog.actionableCandidate(entry, currentPrefs, signatures, local)
                val previous = old?.let { StoreCatalog.actionableCandidate(it, currentPrefs, cur.storeState.signatures, local, includeSkipped = true) }
                val isNew = best != null && best.versionCode > (previous?.versionCode ?: 0L) &&
                    results[best.storeId]?.updates?.get(pkg)?.versionCode == best.versionCode
                merged[pkg] = entry.copy(unread = best != null && (isNew || old?.unread == true))
                if (isNew) discovered[pkg] = best.versionCode
            }
            val health = cur.storeState.health.toMutableMap()
            // Carry partial coverage only while a timed-out source is making progress. A persistent
            // failure must not prevent healthy packages from getting a fresh check on the next round.
            val resumeRound = scan.complete && results.values.any {
                it.nextPackage != null && it.confirmedVersions.size > it.checkedEarlier.size
            }
            for (id in storesToQuery) {
                val result = results.getValue(id)
                health[id] = StoreHealth(
                    consecutiveFailures = if (id in failed) (health[id]?.consecutiveFailures ?: 0) + 1 else 0,
                    lastError = result.error,
                    nextPackage = result.nextPackage,
                    confirmedVersions = if (resumeRound) result.confirmedVersions else emptyMap(),
                )
            }
            val complete = scan.complete && failed.isEmpty()
            cur.copy(storeState = cur.storeState.copy(
                updates = merged, signatures = signatures, health = health, lastAttemptAt = now,
                lastCheckedAt = if (complete) now else cur.storeState.lastCheckedAt,
                lastCheckComplete = complete, lastFailedStoreIds = failed,
            ))
        }
        if (scopeChanged) return null
        val finalState = stateStore.state.value
        return summary(finalState, storesToQuery.size, finalState.storeState.lastCheckComplete == true)
            .copy(newlyDiscovered = discovered)
    }

    private fun mergePackage(
        local: LocalInstalledApp,
        results: Map<String, StoreQueryResult>,
        state: AppState,
        signatures: MutableMap<String, StoreSignatureRecord>,
        now: Instant,
    ): StoreAppUpdate? {
        val pkg = local.packageName
        val previous = state.storeState.updates[pkg]
        val prefs = state.settings.storePrefs
        val candidates = prefs.enabledStores.mapNotNull { id ->
            if (!StoreSystemAppPolicy.isStoreEligibleForApp(local, id)) return@mapNotNull null
            val result = results[id]
            val fresh = result?.updates?.get(pkg)
            val old = previous?.candidates?.firstOrNull { it.storeId == id }
            val cached = old?.takeIf { result == null || !result.checkedNow(pkg) }
            val candidate = if (cached != null && (fresh == null || cached.versionCode > fresh.versionCode)) cached else fresh
            candidate?.takeIf { it.versionCode > local.versionCode }?.also { rel ->
                val key = state.storeState.signatureKey(pkg, id)
                if (rel === fresh && old?.versionCode == rel.versionCode && old.checksum.isNotBlank() &&
                    !rel.matchesArtifactChecksum(old.checksum)) signatures.remove(key)
                if (rel === fresh && local.signatureSha256.isNotBlank() && rel.signerSha256s.size == 1) {
                    // A store's metadata may advertise a rotated signer without its history.
                    // A different digest alone cannot rule out a valid signing lineage.
                    val verdict = ApkVerifier.compareSignatures(local.signatureSha256.split('+').toSet(), rel.signerSha256s.toSet())
                    if (verdict == SignatureVerdict.MATCH && validSignatureVerdict(signatures[key], local.signatureSha256, rel) != SignatureVerdict.DIFFERENT) {
                        signatures[key] = StoreSignatureRecord("MATCH", local.signatureSha256, rel.versionCode, rel.checksum)
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null
        return StoreAppUpdate(
            packageName = pkg, displayName = local.displayName, installedVersionCode = local.versionCode,
            installedVersionName = local.versionName, isSystemApp = local.isSystemApp, installedCertSha256 = local.signatureSha256,
            candidates = candidates,
            checkedAt = if (results.any { (id, r) -> StoreSystemAppPolicy.isStoreEligibleForApp(local, id) && r.checkedNow(pkg) }) {
                now
            } else previous?.checkedAt ?: now,
            unread = previous?.unread == true,
        )
    }

    suspend fun checkSingleApp(local: LocalInstalledApp): SingleStoreCheckResult = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            val currentCode = runCatching { context.packageManager.getPackageInfo(local.packageName, 0).longVersionCode }.getOrNull()
            if (currentCode != local.versionCode) return@withLock SingleStoreCheckResult.INCOMPLETE
            val prefs = stateStore.state.value.settings.storePrefs
            val ids = prefs.enabledStores.filter { it in sources && StoreSystemAppPolicy.isStoreEligibleForApp(local, it) }
            if (ids.isEmpty() || local.packageName in prefs.excludedPackages || (local.isSystemApp && !prefs.includeSystemApps)) {
                return@withLock SingleStoreCheckResult.NO_ELIGIBLE_STORE
            }
            val results = coroutineScope {
                ids.map { id -> async { id to sources.getValue(id).queryWithCoverage(listOf(local), 25_000L) } }.awaitAll().toMap()
            }
            var status = SingleStoreCheckResult.INCOMPLETE
            stateStore.update { cur ->
                val latestPrefs = cur.settings.storePrefs
                if (local.packageName in latestPrefs.excludedPackages || (local.isSystemApp && !latestPrefs.includeSystemApps)) {
                    status = SingleStoreCheckResult.NO_ELIGIBLE_STORE
                    return@update cur
                }
                val currentResults = if (prefs.fdroidMirror == latestPrefs.fdroidMirror) results else results - StoreCatalog.ID_FDROID
                val sigs = cur.storeState.signatures.toMutableMap()
                val entry = mergePackage(local, currentResults, cur, sigs, Instant.now())
                val best = entry?.let { StoreCatalog.actionableCandidate(it, latestPrefs, sigs, local, includeSkipped = true) }
                val currentIds = latestPrefs.enabledStores.filter { it in sources && StoreSystemAppPolicy.isStoreEligibleForApp(local, it) }
                status = when {
                    currentIds.isEmpty() -> SingleStoreCheckResult.NO_ELIGIBLE_STORE
                    best != null && currentResults[best.storeId]?.updates?.get(local.packageName)?.versionCode == best.versionCode -> SingleStoreCheckResult.FOUND
                    currentIds.any { id -> currentResults[id]?.unconfirmed?.isNotEmpty() != false } -> SingleStoreCheckResult.INCOMPLETE
                    else -> SingleStoreCheckResult.UP_TO_DATE
                }
                val updates = if (entry == null) cur.storeState.updates - local.packageName
                    else cur.storeState.updates + (local.packageName to entry)
                cur.copy(storeState = cur.storeState.copy(updates = updates, signatures = sigs))
            }
            status
        }
    }

    /** 装包完成后即时清理已升至最新版本的记录。 */
    suspend fun reconcilePackage(packageName: String) = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            val pkgInfo = runCatching {
                context.packageManager.getPackageInfo(packageName, 0)
            }.getOrNull() ?: return@withLock
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
    }

    /** A queued uninstall broadcast may arrive after a reinstall or after a full check. */
    suspend fun removePackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            if (!isConfirmedUninstalled(packageName)) return@withLock false
            stateStore.update { state ->
                state.withoutRemovedPackages(setOf(packageName), state.storeState.observedPreferencePackages - packageName)
            }
            true
        }
    }

    /** Catch uninstalls missed while stopped. A restored preference has no observation on this device yet. */
    suspend fun reconcileTrackedPackages() = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            val start = stateStore.state.value
            val tracked = start.settings.storePrefs.trackedPackages
            val observed = start.storeState.observedPreferencePackages
            if (tracked.isEmpty() && observed.isEmpty() || needsInstalledAppsPermission(context)) return@withLock
            val present = mutableSetOf<String>()
            val missing = mutableSetOf<String>()
            for (pkg in tracked) {
                when (installedOrArchived(pkg)) {
                    true -> present += pkg
                    false -> if (pkg in observed) missing += pkg
                    null -> Unit
                }
            }
            if (needsInstalledAppsPermission(context) ||
                (missing.isEmpty() && present.all { it in observed } && tracked.containsAll(observed))
            ) return@withLock
            stateStore.update { state ->
                val current = state.settings.storePrefs.trackedPackages
                val previous = state.storeState.observedPreferencePackages
                val removed = missing.intersect(current).intersect(previous)
                state.withoutRemovedPackages(removed, (previous + present).intersect(current) - removed)
            }
        }
    }

    internal fun isConfirmedUninstalled(packageName: String): Boolean =
        !needsInstalledAppsPermission(context) && installedOrArchived(packageName) == false

    private fun installedOrArchived(packageName: String): Boolean? = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 35) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ARCHIVED_PACKAGES))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        null
    }

    private fun AppState.withoutRemovedPackages(removed: Set<String>, observed: Set<String>): AppState {
        if (removed.isEmpty()) {
            return if (observed == storeState.observedPreferencePackages) this
            else copy(storeState = storeState.copy(observedPreferencePackages = observed))
        }
        val prefs = settings.storePrefs
        return copy(
            settings = settings.copy(storePrefs = prefs.copy(
                excludedPackages = prefs.excludedPackages - removed,
                skippedVersions = prefs.skippedVersions - removed,
                pinnedSources = prefs.pinnedSources - removed,
            )),
            storeState = storeState.copy(
                updates = storeState.updates - removed,
                signatures = storeState.signatures.filterKeys { it.substringBefore('|') !in removed },
                health = storeState.health.mapValues { (_, health) ->
                    if (removed.none { it in health.confirmedVersions }) health
                    else health.copy(confirmedVersions = health.confirmedVersions - removed)
                },
                observedPreferencePackages = observed,
            ),
        )
    }

    private fun summary(state: AppState, checked: Int, complete: Boolean) = StoreCheckSummary(
        checkedStores = checked,
        failedStores = state.storeState.lastFailedStoreIds,
        updatableCount = state.storeState.updates.values.count {
            StoreCatalog.actionableCandidate(it, state.settings.storePrefs, state.storeState.signatures) != null
        },
        newlyDiscovered = emptyMap(),
        complete = complete,
    )

    companion object {
        private const val PER_STORE_TIMEOUT_MS = 90_000L
        private val AUTO_CHECK_THROTTLE = Duration.ofMinutes(30)

        /** 校验签名缓存是否仍与当前本机证书一致；若重装后证书变更则视为 UNKNOWN。 */
        internal fun validSignatureVerdict(
            record: StoreSignatureRecord?,
            localCertSha256: String,
            candidate: StoreReleaseInfo? = null,
        ): SignatureVerdict {
            if (record == null) return SignatureVerdict.UNKNOWN
            if (candidate != null && (record.versionCode != candidate.versionCode || !candidate.matchesArtifactChecksum(record.checksum) ||
                    localCertSha256.isBlank() || record.localCertSha256.isBlank())) return SignatureVerdict.UNKNOWN
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
