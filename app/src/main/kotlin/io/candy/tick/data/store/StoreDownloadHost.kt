package io.candy.tick.data.store

import android.content.Context
import android.os.Build
import io.candy.tick.data.AppStateStore
import io.candy.tick.data.InstallManager
import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.download.ApkDownloader
import io.candy.tick.data.download.ApkVerifier
import io.candy.tick.data.download.DownloadKey
import io.candy.tick.data.download.SignatureVerdict
import io.candy.tick.data.download.VerificationFailure
import io.candy.tick.data.download.VerificationResult
import io.candy.tick.data.model.DownloadedApk
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.model.StoreSignatureRecord
import java.io.File
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

enum class StoreDownloadFailure {
    NETWORK,
    VERIFY_HASH,
    VERIFY_ARCHIVE,
    SIGNATURE_MISMATCH,
    INSTALL_FAILED,
}

sealed interface StoreTaskState {
    data class Downloading(
        val storeId: String,
        val versionName: String,
        val bytesRead: Long,
        val totalBytes: Long,
        val fraction: Float?,
    ) : StoreTaskState

    data class Verifying(val storeId: String, val versionName: String) : StoreTaskState

    data class ReadyToInstall(
        val storeId: String,
        val versionName: String,
        val fileName: String,
        val is32BitWarning: Boolean = false,
        val extraFileNames: List<String> = emptyList(),
        val localCertSha256: String = "",
    ) : StoreTaskState

    data class Installing(val storeId: String, val versionName: String) : StoreTaskState

    data class Failed(
        val storeId: String,
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
) {
    private val _states = MutableStateFlow<Map<String, StoreTaskState>>(emptyMap())
    val states: StateFlow<Map<String, StoreTaskState>> = _states.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val jobGens = ConcurrentHashMap<String, Long>()
    private val genSeq = AtomicLong(0L)
    private val downloadSemaphore = Semaphore(2)
    private val installQueue = Channel<InstallQueueItem>(Channel.UNLIMITED)
    private val queuedInstalls = ConcurrentHashMap.newKeySet<String>()

    private data class InstallQueueItem(
        val packageName: String,
        val storeId: String,
        val versionName: String,
        val files: List<File>,
        val is32BitWarning: Boolean,
        val localCertSha256: String,
    )

    init {
        scope.launch(Dispatchers.IO) {
            installManager.packageInstalled.collect { pkg ->
                checker.reconcilePackage(pkg)
                // 同包若有正在进行的下载任务，不误清其工作目录
                if (jobs[pkg]?.isActive != true) {
                    cleanupDownloaded(pkg)
                    _states.update { it - pkg }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            for (item in installQueue) {
                try {
                    if (item.files.isEmpty() || item.files.any { !it.isFile }) continue
                    _states.update { it + (item.packageName to StoreTaskState.Installing(item.storeId, item.versionName)) }
                    val key = DownloadKey.Store(item.packageName)
                    // UNDISPATCHED 确保先完成 SharedFlow 订阅再触发 installFilesViaSession，防止同步发射的权限/异常事件丢失
                    val resultJob = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        val outcome = withTimeoutOrNull(120_000L) {
                            installManager.resultsFor(key).first { it !is InstallManager.InstallResult.PendingUserAction }
                        }
                        when (outcome) {
                            is InstallManager.InstallResult.Success -> {
                                checker.reconcilePackage(item.packageName)
                                cleanupDownloaded(item.packageName)
                                _states.update { it - item.packageName }
                            }
                            is InstallManager.InstallResult.Cancelled,
                            is InstallManager.InstallResult.NeedsInstallPermission,
                            null -> {
                                _states.update {
                                    it + (item.packageName to StoreTaskState.ReadyToInstall(
                                        storeId = item.storeId,
                                        versionName = item.versionName,
                                        fileName = item.files.first().name,
                                        is32BitWarning = item.is32BitWarning,
                                        extraFileNames = item.files.drop(1).map { f -> f.name },
                                        localCertSha256 = item.localCertSha256,
                                    ))
                                }
                            }
                            is InstallManager.InstallResult.Failed -> {
                                if (outcome.signatureMismatch) {
                                    recordSignature(item.packageName, item.storeId, "DIFFERENT", item.localCertSha256)
                                    cleanupDownloaded(item.packageName)
                                    _states.update {
                                        it + (item.packageName to StoreTaskState.Failed(
                                            item.storeId,
                                            StoreDownloadFailure.SIGNATURE_MISMATCH,
                                        ))
                                    }
                                } else {
                                    _states.update {
                                        it + (item.packageName to StoreTaskState.Failed(
                                            item.storeId,
                                            StoreDownloadFailure.INSTALL_FAILED,
                                        ))
                                    }
                                }
                            }
                            InstallManager.InstallResult.PendingUserAction -> Unit
                        }
                    }
                    installManager.installFilesViaSession(item.files, key)
                    resultJob.join()
                } finally {
                    queuedInstalls.remove(item.packageName)
                }
            }
        }
    }

    fun startUpdate(app: LocalInstalledApp, release: StoreReleaseInfo) {
        val pkg = app.packageName
        if (jobs[pkg]?.isActive == true) return
        val source = sources[release.storeId] ?: return
        val gen = genSeq.incrementAndGet()
        jobGens[pkg] = gen
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            downloadSemaphore.withPermit {
                runSingle(app, release, source, gen)
            }
        }
        jobs[pkg] = job
        job.invokeOnCompletion { jobs.remove(pkg, job) }
        job.start()
    }

    fun updateAll(targets: List<Pair<LocalInstalledApp, StoreReleaseInfo>>) {
        for ((app, release) in targets) startUpdate(app, release)
    }

    fun installReady(packageName: String) {
        val current = _states.value[packageName] as? StoreTaskState.ReadyToInstall ?: return
        if (!queuedInstalls.add(packageName)) return
        val dir = storeApkDir(packageName)
        val files = buildList {
            add(File(dir, current.fileName))
            current.extraFileNames.forEach { add(File(dir, it)) }
        }
        if (files.any { !it.isFile }) {
            queuedInstalls.remove(packageName)
            _states.update { it - packageName }
            return
        }
        installQueue.trySend(
            InstallQueueItem(
                packageName = packageName,
                storeId = current.storeId,
                versionName = current.versionName,
                files = files,
                is32BitWarning = current.is32BitWarning,
                localCertSha256 = current.localCertSha256,
            ),
        )
    }

    fun cancel(packageName: String) {
        jobGens.incrementAndSet(packageName)
        jobs.remove(packageName)?.cancel()
        _states.update { it - packageName }
    }

    fun clearFailure(packageName: String) {
        if (_states.value[packageName] is StoreTaskState.Failed) {
            _states.update { it - packageName }
        }
    }

    private fun ConcurrentHashMap<String, Long>.incrementAndSet(key: String) {
        put(key, genSeq.incrementAndGet())
    }

    private fun isCurrentGen(pkg: String, gen: Long): Boolean = jobGens[pkg] == gen

    private suspend fun runSingle(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
        source: StoreSource,
        gen: Long,
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

            downloadOne(spec.url, dest, spec.sizeBytes)
            val extraFiles = mutableListOf<File>()
            spec.extraParts.forEachIndexed { index, extra ->
                val splitDest = File(dir, "${fileId}_split_${index + 1}.apk")
                downloadOne(extra.url, splitDest, extra.sizeBytes)
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
                installedVersionCode = app.versionCode,
                expectedSize = spec.sizeBytes,
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
                        recordSignature(pkg, release.storeId, "DIFFERENT", app.signatureSha256)
                        StoreDownloadFailure.SIGNATURE_MISMATCH
                    }
                    VerificationFailure.SIZE_MISMATCH,
                    VerificationFailure.HASH_MISMATCH -> StoreDownloadFailure.VERIFY_HASH
                    else -> StoreDownloadFailure.VERIFY_ARCHIVE
                }
                _states.update { it + (pkg to StoreTaskState.Failed(release.storeId, reason)) }
                return
            }
            val passed = verify as VerificationResult.Passed
            for (i in extraFiles.indices) {
                val extra = spec.extraParts[i]
                val splitFailure = ApkVerifier.verifyFileIntegrity(
                    file = extraFiles[i],
                    expectedSize = extra.sizeBytes,
                    expectedHash = extra.expectedHash,
                )
                if (splitFailure != null) {
                    createdFiles.forEach { it.delete() }
                    _states.update { it + (pkg to StoreTaskState.Failed(release.storeId, StoreDownloadFailure.VERIFY_HASH)) }
                    return
                }
            }

            // 合并主包与全部分包的 ABI，避免 32 位原生库放在 split APK 时漏报警告
            val combinedAbis = (listOf(dest) + extraFiles).flatMapTo(mutableSetOf()) { ApkVerifier.extractApkAbis(it) }
            val is32BitWarning = ApkVerifier.is32BitOnlyOn64Bit(combinedAbis, Build.SUPPORTED_ABIS.toList())

            if (passed.signature == SignatureVerdict.MATCH) {
                recordSignature(pkg, release.storeId, "MATCH", app.signatureSha256)
            }
            if (!isCurrentGen(pkg, gen)) {
                createdFiles.forEach { it.delete() }
                return
            }
            val keepNames = buildSet {
                add(dest.name)
                extraFiles.forEach { add(it.name) }
            }
            val record = DownloadedApk(dest.name, release.versionName, pkg, "${app.displayName}-${release.versionName}.apk")
            stateStore.update { st ->
                st.copy(storeState = st.storeState.copy(downloaded = st.storeState.downloaded + (pkg to record)))
            }
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name !in keepNames) f.delete()
            }
            val ready = StoreTaskState.ReadyToInstall(
                storeId = release.storeId,
                versionName = release.versionName,
                fileName = dest.name,
                is32BitWarning = is32BitWarning,
                extraFileNames = extraFiles.map { it.name },
                localCertSha256 = app.signatureSha256,
            )
            _states.update { it + (pkg to ready) }
            if (!is32BitWarning && stateStore.state.value.settings.autoInstallDownload && queuedInstalls.add(pkg)) {
                installQueue.trySend(
                    InstallQueueItem(
                        packageName = pkg,
                        storeId = release.storeId,
                        versionName = release.versionName,
                        files = listOf(dest) + extraFiles,
                        is32BitWarning = false,
                        localCertSha256 = app.signatureSha256,
                    ),
                )
            }
        } catch (e: CancellationException) {
            createdFiles.forEach { it.delete() }
            throw e
        } catch (e: Exception) {
            createdFiles.forEach { it.delete() }
            if (isCurrentGen(pkg, gen)) {
                val err = (e as? SourceException)?.error
                _states.update { it + (pkg to StoreTaskState.Failed(release.storeId, StoreDownloadFailure.NETWORK, err)) }
            }
        }
    }

    private suspend fun recordSignature(
        packageName: String,
        storeId: String,
        verdict: String,
        localCertSha256: String,
    ) {
        stateStore.update { st ->
            val key = st.storeState.signatureKey(packageName, storeId)
            st.copy(
                storeState = st.storeState.copy(
                    signatures = st.storeState.signatures + (key to StoreSignatureRecord(verdict = verdict, localCertSha256 = localCertSha256)),
                ),
            )
        }
    }

    private suspend fun cleanupDownloaded(packageName: String) {
        storeApkDir(packageName).listFiles()?.forEach { if (it.isFile) it.delete() }
        if (stateStore.state.value.storeState.downloaded.containsKey(packageName)) {
            stateStore.update { st ->
                st.copy(storeState = st.storeState.copy(downloaded = st.storeState.downloaded - packageName))
            }
        }
    }

    private fun storeApkDir(packageName: String): File =
        File(appContext.filesDir, "apks/store/$packageName").apply { mkdirs() }
}
