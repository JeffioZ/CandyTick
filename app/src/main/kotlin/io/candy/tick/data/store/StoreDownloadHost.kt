package io.candy.tick.data.store

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import io.candy.tick.data.AppStateStore
import io.candy.tick.data.InstallManager
import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.download.ApkDownloader
import io.candy.tick.data.download.ApkVerifier
import io.candy.tick.data.download.DownloadKey
import io.candy.tick.data.download.HashAlgorithm
import io.candy.tick.data.download.SignatureVerdict
import io.candy.tick.data.download.VerificationFailure
import io.candy.tick.data.download.VerificationResult
import io.candy.tick.data.model.DownloadedApk
import io.candy.tick.data.model.StoreApkDetails
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.model.StoreSignatureRecord
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class StoreDownloadFailure {
    NETWORK,
    METERED_CONFIRMATION,
    VERIFY_HASH,
    VERIFY_ARCHIVE,
    SIGNATURE_MISMATCH,
    INSTALL_FAILED,
}

sealed interface StoreTaskState {
    val storeId: String
    val versionName: String? get() = null

    data class Downloading(
        override val storeId: String,
        override val versionName: String,
        val bytesRead: Long,
        val totalBytes: Long,
        val fraction: Float?,
    ) : StoreTaskState

    data class Verifying(override val storeId: String, override val versionName: String) : StoreTaskState

    data class ReadyToInstall(
        override val storeId: String,
        override val versionName: String,
        val fileName: String,
        val is32BitWarning: Boolean = false,
        val extraFileNames: List<String> = emptyList(),
        val localCertSha256: String = "",
        val signatureVerified: Boolean = false,
        val versionCode: Long = 0L,
        val checksum: String = "",
    ) : StoreTaskState {
        fun matches(release: StoreReleaseInfo?): Boolean =
            release != null && storeId == release.storeId && versionCode == release.versionCode && release.matchesArtifactChecksum(checksum)
    }

    data class Installing(override val storeId: String, override val versionName: String) : StoreTaskState

    data class Failed(
        override val storeId: String,
        val versionCode: Long,
        val reason: StoreDownloadFailure,
        val error: SourceError? = null,
    ) : StoreTaskState
}

/**
 * 商店 APK 下载与安装宿主（App 级）：
 * - 复用 [ApkDownloader] 与 [ApkVerifier]；
 * - 签名不匹配时自动删包并记录本机证书摘要，后续重装换签名自动失效；
 * - 「全部更新」最多 2 个并发下载，安装严格串行排队并防连点重复入队。
 */
class StoreDownloadHost(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val stateStore: AppStateStore,
    private val downloader: ApkDownloader,
    private val installManager: InstallManager,
    private val checker: StoreUpdateChecker,
    private val sources: Map<String, StoreSource>,
    private val isAppVisible: () -> Boolean = { true },
) {
    private val _states = MutableStateFlow<Map<String, StoreTaskState>>(emptyMap())
    val states: StateFlow<Map<String, StoreTaskState>> = _states.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val jobGens = ConcurrentHashMap<String, Long>()
    private val genSeq = AtomicLong(0L)
    private val downloadSemaphore = Semaphore(2)
    private val installQueue = Channel<InstallQueueItem>(Channel.UNLIMITED)
    private val queuedInstalls = ConcurrentHashMap.newKeySet<String>()
    private val awaitingPermission = java.util.concurrent.atomic.AtomicReference<Pair<String, StoreTaskState.ReadyToInstall>?>(null)

    private enum class InstallTarget { RECOMMENDED, SELECTED, SELECTED_WHILE_SKIPPED }

    private data class InstallQueueItem(
        val pkg: String,
        val record: DownloadedApk,
        val allowUnknownSignature: Boolean,
        val target: InstallTarget,
    )

    init {
        scope.launch(Dispatchers.IO) {
            stateStore.awaitLoaded()
            reconcileFiles()
        }
        scope.launch(Dispatchers.IO) {
            installManager.packageInstalled.collect { pkg ->
                checker.reconcilePackage(pkg)
                val installed = installedCode(pkg)
                if (installed != null) {
                    _states.update { states ->
                        val failed = states[pkg] as? StoreTaskState.Failed
                        if (failed != null && failed.versionCode <= installed) states - pkg else states
                    }
                }
                reconcileFiles(pkg)
            }
        }
        scope.launch(Dispatchers.IO) {
            for (item in installQueue) {
                try {
                    processInstall(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("StoreDownload", "Could not finish installation", e)
                    val details = requireNotNull(item.record.store)
                    showFailure(item.pkg, details.sourceId, details.versionCode, StoreDownloadFailure.INSTALL_FAILED)
                } finally {
                    queuedInstalls.remove(item.pkg)
                }
            }
        }
    }

    fun busyPackages(): Set<String> = jobs.keys.toSet() + queuedInstalls

    suspend fun onPackageUninstalled(pkg: String) {
        val record = synchronized(this) {
            // The user can reinstall while an earlier uninstall is waiting for the checker.
            if (!checker.isConfirmedUninstalled(pkg)) return
            cancel(pkg)
            _states.update { it - pkg }
            stateStore.state.value.storeState.downloaded[pkg]
        }
        if (record != null) discard(pkg, record, previousState = null)
    }

    private fun installedCode(pkg: String): Long? = runCatching {
        appContext.packageManager.getPackageInfo(pkg, 0).longVersionCode
    }.getOrNull()

    private fun filesFor(pkg: String, record: DownloadedApk): List<File> {
        if (record.packageName != pkg || !safeName(pkg) || !safeName(record.fileName)) return emptyList()
        val dir = storeApkDir(pkg)
        val details = record.store
        if (details == null) {
            // Legacy downloads named every split after the base APK's unique file ID.
            val stem = record.fileName.removeSuffix(".apk")
            val splitName = Regex("${Regex.escape(stem)}_split_[0-9]+\\.apk")
            return listOf(File(dir, record.fileName)) + dir.listFiles().orEmpty().filter { it.name.matches(splitName) }
        }
        val names = listOf(record.fileName) + details.splits
        if (names.any { !safeName(it) } || names.size != names.toSet().size) return emptyList()
        return names.map { File(dir, it) }
    }

    private fun safeName(name: String) = name.isNotBlank() && name != "." && name != ".." &&
        '/' !in name && '\\' !in name

    private fun filesIntact(files: List<File>, details: StoreApkDetails): Boolean =
        files.isNotEmpty() && files.size == details.verifiedLengths.size && files.indices.all {
            files[it].isFile && files[it].length() == details.verifiedLengths[it]
        }

    private fun obsoleteLegacyDownload(pkg: String, record: DownloadedApk, installed: Long?): Boolean {
        val base = filesFor(pkg, record).firstOrNull() ?: return true
        if (Files.notExists(base.toPath())) return true
        if (installed == null) return false
        val archive = runCatching { appContext.packageManager.getPackageArchiveInfo(base.absolutePath, 0) }.getOrNull()
        return archive != null && archive.packageName == pkg && archive.longVersionCode <= installed
    }

    private fun ready(record: DownloadedApk): StoreTaskState.ReadyToInstall {
        val details = requireNotNull(record.store)
        return StoreTaskState.ReadyToInstall(details.sourceId, record.version, record.fileName,
            details.warn32Bit, details.splits, details.localCertificate, details.signatureVerified, details.versionCode, details.checksum)
    }

    private fun showFailure(pkg: String, sourceId: String, versionCode: Long, reason: StoreDownloadFailure, error: SourceError? = null) {
        _states.update { it + (pkg to StoreTaskState.Failed(sourceId, versionCode, reason, error)) }
    }

    suspend fun reconcileFiles(onlyPackage: String? = null) {
        for ((pkg, record) in stateStore.state.value.storeState.downloaded) {
            val previousState = _states.value[pkg]
            if (onlyPackage != null && pkg != onlyPackage || pkg in busyPackages()) continue
            val details = record.store
            val local = installedCode(pkg)
            if (record != stateStore.state.value.storeState.downloaded[pkg] || pkg in busyPackages()) continue
            when {
                details?.sessionId != null -> enqueue(pkg, record)
                local == null && checker.isConfirmedUninstalled(pkg) -> discard(pkg, record, previousState)
                details == null -> if (obsoleteLegacyDownload(pkg, record, local)) discard(pkg, record, previousState)
                !filesIntact(filesFor(pkg, record), details) || local != null && local >= details.versionCode -> discard(pkg, record, previousState)
                local != null -> {
                    // Old index downloads can be identified once from the APK already on disk.
                    val identified = if (details.checksum.isEmpty() && details.sourceId.isIndexStore()) {
                        val hash = try {
                            ApkVerifier.computeFileHash(filesFor(pkg, record).first(), HashAlgorithm.SHA256)
                        } catch (_: java.io.IOException) {
                            continue
                        }
                        record.copy(store = details.copy(checksum = hash)).also { replaceRecord(pkg, record, it) }
                    } else record
                    _states.update { states ->
                        if (states[pkg] is StoreTaskState.Failed || pkg in busyPackages() ||
                            stateStore.state.value.storeState.downloaded[pkg] != identified) states
                        else states + (pkg to ready(identified))
                    }
                }
            }
        }
    }

    private suspend fun processInstall(item: InstallQueueItem) {
        val (pkg, original, allowUnknownSignature, target) = item
        val details = requireNotNull(original.store)
        if (stateStore.state.value.storeState.downloaded[pkg] != original) return
        val files = filesFor(pkg, original)
        val key = DownloadKey.Store(pkg)
        var current = original
        val existingSession = details.sessionId
        _states.update { it + (pkg to StoreTaskState.Installing(details.sourceId, original.version)) }
        val result = if (existingSession != null) {
            installManager.awaitStoreSession(existingSession)
        } else {
            fun canSubmit() = (if (allowUnknownSignature) isAppVisible() else canAutoInstall(details)) &&
                currentTarget(pkg, details.sourceId, details.versionCode, details.checksum, target)
            val local = installedCode(pkg)
            if (!filesIntact(files, details) || local != null && local >= details.versionCode) {
                discard(pkg, original)
                checker.reconcilePackage(pkg)
                return
            }
            if (!canSubmit()) {
                _states.update { it + (pkg to ready(original)) }
                return
            }
            if (local == null) {
                showFailure(pkg, details.sourceId, details.versionCode, StoreDownloadFailure.VERIFY_ARCHIVE)
                return
            }
            val verify = ApkVerifier.verifyApk(appContext, files.first(), expectedPackage = pkg,
                expectedVersionCode = details.versionCode, requireInstalled = true)
            if (verify is VerificationResult.Failed) {
                val reason = if (verify.reason == VerificationFailure.SIGNATURE_MISMATCH) StoreDownloadFailure.SIGNATURE_MISMATCH
                    else StoreDownloadFailure.VERIFY_ARCHIVE
                showFailure(pkg, details.sourceId, details.versionCode, reason)
                return
            }
            if (!allowUnknownSignature && (verify as VerificationResult.Passed).signature != SignatureVerdict.MATCH) {
                _states.update { it + (pkg to ready(original)) }
                return
            }
            installManager.installStoreFiles(files, key) { id ->
                val code = installedCode(pkg)
                val pending = original.copy(store = details.copy(sessionId = id))
                if (code == null || code >= details.versionCode || !canSubmit()) false else {
                    val saved = replaceRecord(pkg, original, pending)
                    if (stateStore.state.value.storeState.downloaded[pkg] == pending) current = pending
                    saved && canSubmit()
                }
            }
        }
        if (stateStore.state.value.storeState.downloaded[pkg] != current) return
        if (result == InstallManager.InstallResult.Success || installedCode(pkg)?.let { it >= details.versionCode } == true) {
            discard(pkg, current)
            checker.reconcilePackage(pkg)
            return
        }
        if (current.store?.sessionId != null && !replaceRecord(pkg, current, original.copy(store = details.copy(sessionId = null)))) {
            showFailure(pkg, details.sourceId, details.versionCode, StoreDownloadFailure.INSTALL_FAILED)
            return
        }
        if (result is InstallManager.InstallResult.Failed && result.signatureMismatch) {
            recordSignature(pkg, details.sourceId, "DIFFERENT", details.localCertificate, details.versionCode, details.checksum)
            discard(pkg, stateStore.state.value.storeState.downloaded[pkg] ?: return)
            showFailure(pkg, details.sourceId, details.versionCode, StoreDownloadFailure.SIGNATURE_MISMATCH)
        } else if (result is InstallManager.InstallResult.Failed) {
            showFailure(pkg, details.sourceId, details.versionCode, StoreDownloadFailure.INSTALL_FAILED)
        } else {
            _states.update { it + (pkg to ready(original)) }
        }
    }

    private suspend fun replaceRecord(pkg: String, expected: DownloadedApk?, replacement: DownloadedApk?): Boolean {
        var applies = false
        val saved = stateStore.updateAndConfirm { state ->
            val records = state.storeState.downloaded
            if (records[pkg] != expected) state else {
                applies = true
                val next = if (replacement == null) records - pkg else records + (pkg to replacement)
                state.copy(storeState = state.storeState.copy(downloaded = next))
            }
        }
        return applies && saved
    }

    private suspend fun discard(pkg: String, record: DownloadedApk, previousState: StoreTaskState? = _states.value[pkg]) {
        if (replaceRecord(pkg, record, null)) {
            filesFor(pkg, record).forEach { it.delete() }
        }
        // Clearing the in-memory task does not depend on a successful disk write.
        if (stateStore.state.value.storeState.downloaded[pkg] != null) return
        val matches = when (previousState) {
            is StoreTaskState.ReadyToInstall -> previousState.fileName == record.fileName
            is StoreTaskState.Failed -> previousState.storeId == record.store?.sourceId &&
                previousState.versionCode == record.store.versionCode
            is StoreTaskState.Installing -> true
            else -> false
        }
        if (matches) _states.update { if (it[pkg] === previousState) it - pkg else it }
    }

    private fun enqueue(
        pkg: String,
        record: DownloadedApk,
        target: InstallTarget = InstallTarget.RECOMMENDED,
        allowUnknownSignature: Boolean = false,
    ): Boolean {
        if (!queuedInstalls.add(pkg)) return false
        if (installQueue.trySend(InstallQueueItem(pkg, record, allowUnknownSignature, target)).isSuccess) return true
        queuedInstalls.remove(pkg)
        return false
    }

    private fun installTarget(pkg: String, version: Long, recommendedOnly: Boolean): InstallTarget = when {
        recommendedOnly -> InstallTarget.RECOMMENDED
        version <= (stateStore.state.value.settings.storePrefs.skippedVersions[pkg] ?: 0L) -> InstallTarget.SELECTED_WHILE_SKIPPED
        else -> InstallTarget.SELECTED
    }

    private fun currentTarget(pkg: String, sourceId: String, version: Long, checksum: String, target: InstallTarget, local: LocalInstalledApp? = null): Boolean {
        val state = stateStore.state.value
        val store = state.storeState
        val prefs = state.settings.storePrefs
        val update = store.updates[pkg] ?: return false
        if (target == InstallTarget.SELECTED && version <= (prefs.skippedVersions[pkg] ?: 0L)) return false
        val current = if (target == InstallTarget.RECOMMENDED) {
            StoreCatalog.actionableCandidate(update, prefs, store.signatures, local)
        } else {
            StoreCatalog.eligibleCandidates(update, prefs, local).find { it.storeId == sourceId }
        } ?: return false
        if (current.storeId != sourceId || current.versionCode != version || !current.matchesArtifactChecksum(checksum)) return false
        if (target == InstallTarget.RECOMMENDED) return true
        val record = store.signatures[store.signatureKey(pkg, sourceId)]
        return StoreUpdateChecker.validSignatureVerdict(record, local?.signatureSha256 ?: update.installedCertSha256, current) != SignatureVerdict.DIFFERENT
    }

    @Synchronized
    fun startUpdate(app: LocalInstalledApp, release: StoreReleaseInfo, meteredConfirmed: Boolean = false, recommendedOnly: Boolean = false): Boolean {
        val pkg = app.packageName
        if (!safeName(pkg) || pkg in busyPackages()) return false
        val target = installTarget(pkg, release.versionCode, recommendedOnly)
        if (!currentTarget(pkg, release.storeId, release.versionCode, release.checksum, target, app)) return false
        val retained = stateStore.state.value.storeState.downloaded[pkg]
        if (retained?.store?.let { it.sourceId == release.storeId && it.versionCode == release.versionCode && release.matchesArtifactChecksum(it.checksum) } == true &&
            filesIntact(filesFor(pkg, retained), requireNotNull(retained.store))) {
            val details = requireNotNull(retained.store)
            if (details.sessionId != null) return enqueue(pkg, retained)
            _states.update { it + (pkg to ready(retained)) }
            return canAutoInstall(details) && enqueue(pkg, retained, target)
        }
        val source = sources[release.storeId] ?: return false
        val gen = genSeq.incrementAndGet()
        jobGens[pkg] = gen
        _states.update { it + (pkg to StoreTaskState.Downloading(release.storeId, release.versionName, 0L, release.sizeBytes, null)) }
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            downloadSemaphore.withPermit { runSingle(app, release, source, gen, meteredConfirmed, target) }
        }
        jobs[pkg] = job
        job.invokeOnCompletion {
            jobs.remove(pkg, job)
            jobGens.remove(pkg, gen)
        }
        return job.start()
    }

    fun updateAll(targets: List<Pair<LocalInstalledApp, StoreReleaseInfo>>, meteredConfirmed: Boolean = false): Int =
        targets.count { (app, release) ->
            startUpdate(app, release, meteredConfirmed, recommendedOnly = true)
        }

    @Synchronized
    fun installReady(packageName: String, expected: StoreTaskState.ReadyToInstall, requestPermission: Boolean = true): Boolean {
        val record = stateStore.state.value.storeState.downloaded[packageName] ?: return false
        val details = record.store ?: return false
        if (_states.value[packageName] != expected || ready(record) != expected || packageName in busyPackages()) return false
        val target = installTarget(packageName, expected.versionCode, recommendedOnly = !requestPermission)
        if (!currentTarget(packageName, expected.storeId, expected.versionCode, expected.checksum, target)) return false
        if (!filesIntact(filesFor(packageName, record), details)) {
            scope.launch(Dispatchers.IO) { reconcileFiles(packageName) }
            return false
        }
        if (installManager.canRequestInstalls()) {
            if (requestPermission || canAutoInstall(details)) {
                return enqueue(packageName, record, target, allowUnknownSignature = requestPermission)
            }
        } else if (requestPermission && isAppVisible()) {
            val pending = packageName to expected
            if (awaitingPermission.compareAndSet(null, pending) && !installManager.openInstallPermissionSettings()) {
                awaitingPermission.compareAndSet(pending, null)
                showFailure(packageName, details.sourceId, details.versionCode, StoreDownloadFailure.INSTALL_FAILED)
            }
        }
        return false
    }

    fun onResume() {
        installManager.resumeStoreConfirmation()
        val (pkg, expected) = awaitingPermission.getAndSet(null) ?: return
        if (installManager.canRequestInstalls()) installReady(pkg, expected)
    }

    private fun canAutoInstall(details: StoreApkDetails) = !details.warn32Bit && details.signatureVerified &&
        isAppVisible() && stateStore.state.value.settings.autoInstallDownload && installManager.canRequestInstalls()

    @Synchronized
    fun cancel(packageName: String) {
        val job = jobs[packageName] ?: return
        jobGens.remove(packageName)
        job.cancel()
        _states.update { it - packageName }
    }

    fun clearFailure(packageName: String) {
        if (_states.value[packageName] is StoreTaskState.Failed) {
            _states.update { it - packageName }
        }
    }

    private fun isCurrentGen(pkg: String, gen: Long): Boolean = jobGens[pkg] == gen

    private suspend fun runSingle(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
        source: StoreSource,
        gen: Long,
        meteredConfirmed: Boolean,
        target: InstallTarget,
    ) {
        val pkg = app.packageName
        val dir = storeApkDir(pkg)
        val fileId = UUID.randomUUID().toString()
        val createdFiles = mutableListOf<File>()
        val dest = File(dir, "$fileId.apk")
        _states.update {
            it + (pkg to StoreTaskState.Downloading(release.storeId, release.versionName, 0L, release.sizeBytes, if (release.sizeBytes > 0) 0f else null))
        }
        try {
            val spec = source.resolveDownload(app, release)
            val allTotalBytes = (spec.sizeBytes.coerceAtLeast(0L) + spec.extraParts.sumOf { it.sizeBytes.coerceAtLeast(0L) })
                .takeIf { it > 0L && spec.sizeBytes > 0L && spec.extraParts.all { p -> p.sizeBytes > 0L } }
                ?: if (spec.extraParts.isEmpty()) spec.sizeBytes else 0L
            val knownSizes = listOf(spec.exactSizeBytes) + spec.extraParts.map { it.exactSizeBytes }
            val exactTotal = if (knownSizes.all { it != null && it > 0L }) knownSizes.filterNotNull().sum() else null
            val policy = stateStore.state.value.settings.storePrefs.meteredDownloadPolicy
            val network = appContext.getSystemService(ConnectivityManager::class.java)
            val metered = runCatching { network?.isActiveNetworkMetered ?: true }.getOrDefault(true)
            if (!meteredConfirmed && metered && policy.requiresConfirmation(exactTotal)) {
                showFailure(pkg, release.storeId, release.versionCode, StoreDownloadFailure.METERED_CONFIRMATION)
                return
            }
            var completedBytes = 0L

            suspend fun downloadOne(url: String, target: File, expectedSize: Long) {
                val part = File(dir, "${target.name}.part")
                createdFiles += part
                createdFiles += target
                downloader.download(
                    url = url,
                    dest = part,
                    expectedSize = expectedSize,
                    headers = spec.headers,
                    onProgress = { p ->
                        if (!isCurrentGen(pkg, gen)) return@download
                        val read = completedBytes + p.bytesRead
                        val total = if (allTotalBytes > 0L) allTotalBytes else if (spec.extraParts.isEmpty()) p.totalBytes else 0L
                        val frac = if (total > 0L) (read.toFloat() / total).coerceIn(0f, 1f) else null
                        _states.update { cur ->
                            if (cur[pkg] is StoreTaskState.Downloading) {
                                cur + (pkg to StoreTaskState.Downloading(release.storeId, release.versionName, read, total, frac))
                            } else cur
                        }
                    },
                )
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                completedBytes += target.length()
            }

            downloadOne(spec.url, dest, spec.exactSizeBytes ?: 0L)
            val extraFiles = mutableListOf<File>()
            spec.extraParts.forEachIndexed { index, extra ->
                val splitDest = File(dir, "${fileId}_split_${index + 1}.apk")
                downloadOne(extra.url, splitDest, extra.exactSizeBytes ?: 0L)
                extraFiles += splitDest
            }

            kotlin.coroutines.coroutineContext.ensureActive()
            if (!isCurrentGen(pkg, gen)) {
                createdFiles.forEach { it.delete() }
                return
            }
            _states.update { it + (pkg to StoreTaskState.Verifying(release.storeId, release.versionName)) }
            val verify = ApkVerifier.verifyApk(
                context = appContext,
                file = dest,
                expectedPackage = pkg,
                expectedVersionCode = release.versionCode,
                requireInstalled = true,
                expectedSize = spec.exactSizeBytes ?: 0L,
                expectedHash = spec.expectedHash,
                requireSignatureMatch = true,
            )
            kotlin.coroutines.coroutineContext.ensureActive()
            if (!isCurrentGen(pkg, gen)) {
                createdFiles.forEach { it.delete() }
                return
            }
            if (verify is VerificationResult.Failed) {
                createdFiles.forEach { it.delete() }
                val reason = when (verify.reason) {
                    VerificationFailure.SIGNATURE_MISMATCH -> {
                        recordSignature(pkg, release.storeId, "DIFFERENT", app.signatureSha256, release.versionCode, release.checksum)
                        StoreDownloadFailure.SIGNATURE_MISMATCH
                    }
                    VerificationFailure.SIZE_MISMATCH,
                    VerificationFailure.HASH_MISMATCH -> StoreDownloadFailure.VERIFY_HASH
                    else -> StoreDownloadFailure.VERIFY_ARCHIVE
                }
                showFailure(pkg, release.storeId, release.versionCode, reason)
                return
            }
            val passed = verify as VerificationResult.Passed
            for (i in extraFiles.indices) {
                val extra = spec.extraParts[i]
                val splitFailure = ApkVerifier.verifyFileIntegrity(
                    file = extraFiles[i],
                    expectedSize = extra.exactSizeBytes ?: 0L,
                    expectedHash = extra.expectedHash,
                )
                if (splitFailure != null) {
                    createdFiles.forEach { it.delete() }
                    showFailure(pkg, release.storeId, release.versionCode, StoreDownloadFailure.VERIFY_HASH)
                    return
                }
            }

            // 合并主包与全部分包的 ABI，避免 32 位原生库放在 split APK 时漏报警告
            val combinedAbis = (listOf(dest) + extraFiles).flatMapTo(mutableSetOf()) { ApkVerifier.extractApkAbis(it) }
            val is32BitWarning = ApkVerifier.is32BitOnlyOn64Bit(combinedAbis, Build.SUPPORTED_ABIS.toList())

            if (passed.signature == SignatureVerdict.MATCH) {
                recordSignature(pkg, release.storeId, "MATCH", app.signatureSha256, release.versionCode, release.checksum)
            }
            if (!isCurrentGen(pkg, gen)) {
                createdFiles.forEach { it.delete() }
                return
            }
            val record = DownloadedApk(dest.name, release.versionName, pkg, "${app.displayName}-${release.versionName}.apk",
                store = StoreApkDetails(release.storeId, passed.versionCode, extraFiles.map { it.name }, app.signatureSha256, is32BitWarning,
                    verifiedLengths = (listOf(dest) + extraFiles).map { it.length() },
                    signatureVerified = passed.signature == SignatureVerdict.MATCH, checksum = release.checksum))
            val previous = stateStore.state.value.storeState.downloaded[pkg]
            val saved = replaceRecord(pkg, previous, record)
            if (saved && previous != null) filesFor(pkg, previous).forEach { it.delete() }
            if (!isCurrentGen(pkg, gen) || stateStore.state.value.storeState.downloaded[pkg] != record) return
            _states.update { it + (pkg to ready(record)) }
            if (saved && canAutoInstall(requireNotNull(record.store))) {
                enqueue(pkg, record, target)
            }
        } catch (e: CancellationException) {
            if (stateStore.state.value.storeState.downloaded[pkg]?.fileName != dest.name) createdFiles.forEach { it.delete() }
            throw e
        } catch (e: Exception) {
            if (stateStore.state.value.storeState.downloaded[pkg]?.fileName != dest.name) createdFiles.forEach { it.delete() }
            if (isCurrentGen(pkg, gen)) {
                val err = (e as? SourceException)?.error
                showFailure(pkg, release.storeId, release.versionCode, StoreDownloadFailure.NETWORK, err)
            }
        }
    }

    private suspend fun recordSignature(
        packageName: String,
        storeId: String,
        verdict: String,
        localCertSha256: String,
        versionCode: Long,
        checksum: String,
    ) {
        stateStore.update { st ->
            val current = st.storeState.updates[packageName]?.candidates?.find { it.storeId == storeId }
            if (current?.versionCode != versionCode || !current.matchesArtifactChecksum(checksum)) return@update st
            val key = st.storeState.signatureKey(packageName, storeId)
            st.copy(
                storeState = st.storeState.copy(
                    signatures = st.storeState.signatures + (key to StoreSignatureRecord(verdict, localCertSha256, versionCode, checksum)),
                ),
            )
        }
    }

    private fun storeApkDir(packageName: String): File =
        File(appContext.filesDir, "apks/store/$packageName").apply { mkdirs() }
}
