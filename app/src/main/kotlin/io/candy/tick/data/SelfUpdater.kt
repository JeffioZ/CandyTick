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
            val file: File? = null, // INSTALL 失败时保留的安装包（可直接重装）
        ) : State
    }

    enum class Reason { NETWORK, DOWNLOAD, INSTALL }

    private val updateDir = File(context.filesDir, "updates")
    private val operations = Mutex()
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
        if (VersionCompare.compare(saved.tag, installed)?.let { it <= 0 } != true) return
        saved.file.delete()
        store.update { it.copy(downloadedSelfUpdate = null) }
        Notifications.cancelSelfUpdateReady(context)
        _state.value = State.Idle
    }

    /** true 表示有新版本需要下载，已留存的同版安装包可直接安装。 */
    suspend fun check(allowed: (Settings) -> Boolean = { true }): Boolean = operations.withLock {
        discardInstalledDownload()
        if (!allowed(store.state.value.settings)) return@withLock false
        val previous = _state.value
        _state.value = State.Checking
        try {
            val latest = githubApi.releases(APP_OWNER, APP_REPO, 1, perPage = 1).firstOrNull()
            val installed = localVersionName()
            val comparison = if (latest != null && installed != null) VersionCompare.compare(latest.tagName, installed) else null
            if (latest == null || (comparison != null && comparison <= 0)) {
                lastNewVersion = null
                _state.value = State.UpToDate
                return@withLock false
            }
            val downloaded = savedDownload()
            if (downloaded?.tag == latest.tagName) {
                _state.value = downloaded
                return@withLock false
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
            comparison != null && available.asset != null
        } catch (cancelled: CancellationException) {
            _state.value = previous
            throw cancelled
        } catch (_: SourceException) {
            _state.value = savedDownload() ?: State.Failed(Reason.NETWORK)
            false
        }
    }

    private fun pickSelfAsset(release: ReleaseRecord): ApkAsset? =
        ApkPicker.pick(release.apkAssets.filter { it.name.endsWith(".apk", true) }, Build.SUPPORTED_ABIS.toList()).first

    /** 只发布完整 APK，取消或失败时保留上一次成功的文件和记录。 */
    suspend fun downloadAutomatically(asset: ApkAsset): File? = withContext(Dispatchers.IO) {
        operations.withLock {
            if (store.state.value.settings.canAutoDownloadUpdate) downloadLocked(asset) else null
        }
    }

    private suspend fun downloadLocked(asset: ApkAsset): File? {
        val available = lastNewVersion?.takeIf { it.asset == asset } ?: return null
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
            withContext(NonCancellable) {
                store.update { it.copy(downloadedSelfUpdate = saved) }
                _state.value = State.Downloaded(available.tag, dest)
                if (store.saveStatus.value == AppStateStore.SaveStatus.SAVED) {
                    updateDir.listFiles()?.filter { it != dest }?.forEach { it.delete() }
                }
            }
            dest
        } catch (cancelled: CancellationException) {
            _state.value = savedDownload() ?: available
            throw cancelled
        } catch (_: Exception) {
            _state.value = State.Failed(Reason.DOWNLOAD, available.tag)
            null
        } finally {
            part?.delete()
        }
    }

    /** A user-initiated transfer survives Activity recreation. Present the installer only while the app is visible. */
    fun requestManualDownload(expected: State) {
        scope.launch(Dispatchers.IO) {
            val result = operations.withLock {
                if (_state.value != expected) return@launch
                val available = lastNewVersion ?: return@launch
                val asset = available.asset ?: return@launch
                val file = downloadLocked(asset) ?: return@launch
                available.tag to file
            }
            withContext(Dispatchers.Main.immediate) {
                val (tag, file) = result
                if ((_state.value as? State.Downloaded)?.file != file) return@withContext
                val settings = store.state.value.settings
                if (isAppVisible()) {
                    if (settings.autoInstallDownload) install(file)
                } else if (settings.notificationsEnabled) {
                    Notifications.notifySelfUpdateReady(context.forAppLanguage(settings.language), tag)
                }
            }
        }
    }

    fun install(file: File) = installManager.installApk(file)

    fun observeInstallResults() {
        // Installing this app normally replaces its process. Clear the restored download
        // even when the next launch has automatic update checks disabled.
        scope.launch(Dispatchers.IO) { operations.withLock { discardInstalledDownload() } }
        scope.launch {
            installManager.resultsFor(null).collect { result ->
                operations.withLock {
                    when (result) {
                        InstallManager.InstallResult.Success -> discardInstalledDownload()
                        is InstallManager.InstallResult.Failed -> savedDownload()?.let { saved ->
                            _state.value = State.Failed(Reason.INSTALL, saved.tag, saved.file)
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
        if (store.state.value.downloadedSelfUpdate != null && savedDownload() == null) {
            store.update { it.copy(downloadedSelfUpdate = null) }
            val current = _state.value
            if (current is State.Downloaded || current is State.Failed && current.reason == Reason.INSTALL) {
                _state.value = lastNewVersion ?: State.Idle
            }
            Notifications.cancelSelfUpdateReady(context)
        }
    }

    suspend fun installSilently(file: File) = withContext(Dispatchers.IO) {
        installManager.installViaSession(file, silentSelfUpdate = true)
    }

    fun canInstallSilently(): Boolean {
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
