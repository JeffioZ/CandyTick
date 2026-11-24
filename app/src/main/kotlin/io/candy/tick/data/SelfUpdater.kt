package io.candy.tick.data

import android.content.Context
import android.os.Build
import io.candy.tick.data.model.ApkAsset
import io.candy.tick.data.model.DownloadedApk
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.data.model.Settings
import io.candy.tick.util.ApkPicker
import io.candy.tick.util.VersionCompare
import io.candy.tick.util.installedVersionName
import io.candy.tick.util.forAppLanguage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 自身更新编排（仅指 CandyTick 自身；监控仓库一律手动安装）。
 * 更新源 = 本仓库 Releases；本地版本走 PackageManager versionName（不用 BuildConfig）。
 */
class SelfUpdater(
    private val context: Context,
    private val githubApi: GitHubApi,
    private val installManager: InstallManager,
    private val store: AppStateStore,
    private val scope: CoroutineScope,
    private val isAppVisible: () -> Boolean,
) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class NewVersion(
            val tag: String,
            val notes: String?,
            val asset: ApkAsset?,
            val incomparable: Boolean = false, // 无法比较版本：不宣称「有更新」
        ) : State

        data class Downloading(val tag: String, val progress: Float?) : State
        data class Downloaded(val tag: String, val file: File) : State
        data class Failed(
            val reason: Reason,
            val tag: String? = null, // DOWNLOAD/INSTALL 失败时携带，供重试与状态复原
            val downloaded: Downloaded? = null, // 上一次完整下载的包，版本可能与本次下载失败的 tag 不同
        ) : State
    }

    enum class Reason { NETWORK, RATE_LIMITED, DOWNLOAD, INSTALL }

    private val updateDir = File(context.filesDir, "updates")
    private val operations = Mutex()
    /** 本进程内静默安装失败过的 tag：worker 不再对其反复补装（每次失败都会发一次「点击安装」通知） */
    @Volatile private var installFailedTag: String? = null
    private val _state = MutableStateFlow<State>(savedDownload() ?: State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var lastNewVersion: State.NewVersion? = null

    private fun savedDownload(): State.Downloaded? {
        val saved = store.state.value.downloadedSelfUpdate ?: return null
        if (File(saved.fileName).name != saved.fileName) return null
        val file = File(updateDir, saved.fileName)
        return if (file.isFile) State.Downloaded(saved.version, file) else null
    }

    private suspend fun discardInstalledDownload() {
        val saved = savedDownload() ?: return
        val installed = localVersionName() ?: return
        // Keep the offline installer until the installed version is confirmed to have caught up.
        val comparison = VersionCompare.compare(saved.tag, installed) ?: return
        if (comparison > 0) return
        saved.file.delete()
        store.update { it.copy(downloadedSelfUpdate = null) }
        Notifications.cancelSelfUpdateReady(context)
        _state.value = State.Idle
    }

    /** true 表示有新版本需要下载，已留存的同版安装包可直接安装。 */
    suspend fun check(allowed: (Settings) -> Boolean = { true }): Boolean {
        val available = checkRelease(allowed = allowed) as? State.NewVersion ?: return false
        return !available.incomparable && available.asset != null
    }

    /** Report the outcome to the open sheet even when a failed check restores the offline installer. */
    suspend fun recheckDownloaded(downloaded: State.Downloaded): State? = checkRelease(expected = downloaded)

    private suspend fun checkRelease(
        expected: State.Downloaded? = null,
        allowed: (Settings) -> Boolean = { true },
    ): State? = operations.withLock {
        if (expected != null && _state.value != expected) return@withLock null
        discardInstalledDownload()
        if (!allowed(store.state.value.settings)) return@withLock null
        val previous = _state.value
        _state.value = State.Checking
        try {
            val latest = githubApi.releases(APP_OWNER, APP_REPO, 1, perPage = 1).firstOrNull()
            val installed = localVersionName()
            val comparison = if (latest != null && installed != null) VersionCompare.compare(latest.tagName, installed) else null
            if (latest == null || (comparison != null && comparison <= 0)) {
                lastNewVersion = null
                _state.value = State.UpToDate
                return@withLock State.UpToDate
            }
            val downloaded = savedDownload()
            if (downloaded?.tag == latest.tagName) {
                _state.value = downloaded
                return@withLock downloaded
            }
            val body = latest.body.takeIf { it.isNotBlank() }?.let { markdown ->
                val lines = markdown.lineSequence()
                if (lines.first().trimStart('#', ' ') == latest.tagName) lines.drop(1).joinToString("\n") else markdown
            }
            val available = State.NewVersion(
                tag = latest.tagName,
                notes = body?.takeIf { it.isNotBlank() } ?: latest.name,
                asset = pickSelfAsset(latest),
                incomparable = comparison == null,
            )
            lastNewVersion = available
            _state.value = available
            available
        } catch (cancelled: CancellationException) {
            _state.value = previous
            throw cancelled
        } catch (e: SourceException) {
            // 限流折成「网络失败」会诱导立刻重试（再吃一次 403），单列出来
            val failure = State.Failed(
                if (e.error is SourceError.RateLimited) Reason.RATE_LIMITED else Reason.NETWORK
            )
            _state.value = savedDownload() ?: failure
            failure
        }
    }

    private fun pickSelfAsset(release: ReleaseRecord): ApkAsset? =
        ApkPicker.pick(release.apkAssets.filter { it.name.endsWith(".apk", true) }, Build.SUPPORTED_ABIS.toList()).first

    /** 只发布完整 APK，取消或失败时保留上一次成功的文件和记录。 */
    suspend fun downloadAutomatically(available: State.NewVersion): State.Downloaded? = withContext(Dispatchers.IO) {
        operations.withLock {
            if (!store.state.value.settings.canAutoDownloadUpdate || available.incomparable ||
                _state.value != available
            ) return@withLock null
            downloadLocked(available)
        }
    }

    private suspend fun downloadLocked(available: State.NewVersion): State.Downloaded? {
        val asset = available.asset ?: return null
        val previous = _state.value
        if (previous !is State.NewVersion && !(previous is State.Failed && previous.reason == Reason.DOWNLOAD)) {
            return null
        }
        var part: File? = null
        return try {
            _state.value = State.Downloading(available.tag, if (asset.size > 0) 0f else null)
            updateDir.mkdirs()
            val temp = File.createTempFile("update-", ".part", updateDir)
            part = temp
            githubApi.download(asset, temp) { progress ->
                _state.value = State.Downloading(available.tag, progress)
            }
            if (asset.size > 0 && temp.length() != asset.size) throw java.io.IOException("Incomplete update")
            val dest = File(updateDir, temp.nameWithoutExtension + ".apk")
            if (!temp.renameTo(dest)) throw java.io.IOException("Unable to publish update")
            val saved = DownloadedApk(dest.name, available.tag, context.packageName, asset.name)
            val downloaded = State.Downloaded(available.tag, dest)
            withContext(NonCancellable) {
                store.update { it.copy(downloadedSelfUpdate = saved) }
                _state.value = downloaded
                if (store.saveStatus.value == AppStateStore.SaveStatus.SAVED) {
                    updateDir.listFiles()?.filter { it != dest }?.forEach { it.delete() }
                }
            }
            downloaded
        } catch (cancelled: CancellationException) {
            _state.value = savedDownload() ?: available
            throw cancelled
        } catch (_: Exception) {
            _state.value = State.Failed(Reason.DOWNLOAD, available.tag, savedDownload())
            null
        } finally {
            part?.delete()
        }
    }

    /** A user-initiated transfer survives Activity recreation. Present the installer only while the app is visible. */
    fun requestManualDownload(expected: State) {
        scope.launch(Dispatchers.IO) {
            val downloaded = operations.withLock {
                if (_state.value != expected) return@launch
                val available = lastNewVersion ?: return@launch
                downloadLocked(available) ?: return@launch
            }
            withContext(Dispatchers.Main.immediate) {
                if (_state.value != downloaded) return@withContext
                val settings = store.state.value.settings
                if (isAppVisible()) {
                    if (settings.autoInstallDownload) installManager.installApk(downloaded.file)
                } else if (settings.notificationsEnabled) {
                    Notifications.notifySelfUpdateReady(context.forAppLanguage(settings.language), downloaded.tag)
                }
            }
        }
    }

    suspend fun installDownloaded(expected: State) = operations.withLock {
        if (_state.value != expected) return@withLock
        val shown = when (expected) {
            is State.Downloaded -> expected
            is State.Failed -> expected.downloaded
            else -> null
        } ?: return@withLock
        val saved = savedDownload()
        if (saved == shown) {
            installManager.installApk(shown.file)
        } else {
            reconcileDownloadLocked(saved)
        }
    }

    fun observeInstallResults() {
        // Installing this app normally replaces its process. Clear the restored download
        // even when the next launch has automatic update checks disabled.
        scope.launch(Dispatchers.IO) { operations.withLock { discardInstalledDownload() } }
        scope.launch {
            installManager.resultsFor(null).collect { result ->
                operations.withLock {
                    when (result) {
                        InstallManager.InstallResult.Success -> {
                            installFailedTag = null
                            discardInstalledDownload()
                        }
                        is InstallManager.InstallResult.Failed -> savedDownload()?.let { saved ->
                            installFailedTag = saved.tag
                            _state.value = State.Failed(Reason.INSTALL, saved.tag, saved)
                            val settings = store.state.value.settings
                            if (settings.notificationsEnabled) Notifications.notifySelfUpdateReady(context.forAppLanguage(settings.language), saved.tag)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    suspend fun clearMissingDownload() = operations.withLock {
        if (savedDownload() == null) reconcileDownloadLocked(null)
    }

    private suspend fun reconcileDownloadLocked(saved: State.Downloaded?) {
        if (saved == null && store.state.value.downloadedSelfUpdate != null) {
            store.update { it.copy(downloadedSelfUpdate = null) }
            Notifications.cancelSelfUpdateReady(context)
        }
        val current = _state.value
        _state.value = when {
            current is State.Failed && current.reason == Reason.DOWNLOAD -> current.copy(downloaded = saved)
            current is State.Downloaded || current is State.Failed && current.reason == Reason.INSTALL ->
                saved ?: lastNewVersion ?: State.Idle
            else -> current
        }
    }

    /** Both enqueue and submission require a known upgrade. Manual downloads may have incomparable tags. */
    fun canAutoInstall(downloaded: State.Downloaded): Boolean {
        val settings = store.state.value.settings
        if (!settings.canAutoDownloadUpdate || !settings.autoInstallDownload || _state.value != downloaded ||
            installFailedTag == downloaded.tag || !downloaded.file.isFile
        ) return false
        val installed = localVersionName() ?: return false
        val comparison = VersionCompare.compare(downloaded.tag, installed) ?: return false
        return comparison > 0 && canInstallSilently()
    }

    suspend fun installAutomatically(downloaded: State.Downloaded): Boolean = withContext(Dispatchers.IO) {
        operations.withLock {
            if (!canAutoInstall(downloaded)) return@withLock false
            installManager.installViaSession(downloaded.file, silentSelfUpdate = true)
            true
        }
    }

    private fun canInstallSilently(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !installManager.canRequestInstalls()) return false
        return runCatching {
            context.packageManager.getInstallSourceInfo(context.packageName)
                .installingPackageName == context.packageName
        }.getOrDefault(false)
    }

    fun localVersionName(): String? = installedVersionName(context, context.packageName)

    companion object {
        // 本仓库 Releases 即更新源（remote 接好后如有出入改这两行）
        const val APP_OWNER = "JeffioZ"
        const val APP_REPO = "candytick"
    }
}
