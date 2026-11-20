package io.candy.tick.data

import android.content.Context
import android.os.Build
import io.candy.tick.data.model.ApkAsset
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.util.ApkPicker
import io.candy.tick.util.VersionCompare
import java.io.File
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

        data class Downloading(val tag: String, val progress: Float) : State
        data class Downloaded(val tag: String, val file: File) : State
        data class Failed(val reason: Reason) : State
    }

    enum class Reason { NETWORK, DOWNLOAD }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val updateDir: File = File(context.filesDir, "updates")

    /** 装后残留清理：进程被替换杀掉收不到广播，下次 check 时按事实清（版本相等=已装上） */
    private fun cleanupInstalledSelf() {
        val st = _state.value as? State.Downloaded ?: return
        val local = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: return
        val installed = io.candy.tick.util.VersionCompare.compare(st.tag, local)?.let { it <= 0 } == true
        if (installed) {
            updateDir.listFiles()?.forEach { it.delete() }
            _state.value = State.Idle
        }
    }

    /** @return true = 有可比对新版本（可触发自动下载） */
    suspend fun check(): Boolean {
        cleanupInstalledSelf()
        _state.value = State.Checking
        return try {
            val releases = githubApi.releases(APP_OWNER, APP_REPO, 1)
            val latest = releases.firstOrNull()
            val local = localVersionName()
            when {
                latest == null -> {
                    _state.value = State.UpToDate
                    false
                }
                else -> {
                    val cmp = VersionCompare.compare(latest.tagName, local)
                    // 更新说明存原始 body（仅剔首行与版本号重复的标题）——emoji/markdown
                    // 清理与链接标注由 UI 层 ReleaseNotes.annotated 处理，与详情页展开区同源同能
                    val notes = latest.body.takeIf { it.isNotBlank() }
                        ?.let { b ->
                            val lines = b.lineSequence()
                            if (lines.first().trimStart('#', ' ') == latest.tagName) {
                                lines.drop(1).joinToString("\n")
                            } else {
                                b
                            }
                        }
                        ?: latest.name?.takeIf { it.isNotBlank() }
                    when {
                        cmp == null -> {
                            _state.value = State.NewVersion(
                                tag = latest.tagName,
                                notes = notes,
                                asset = pickSelfAsset(latest),
                                incomparable = true,
                            )
                            false
                        }
                        cmp > 0 -> {
                            _state.value = State.NewVersion(
                                tag = latest.tagName,
                                notes = notes,
                                asset = pickSelfAsset(latest),
                            )
                            true
                        }
                        else -> {
                            _state.value = State.UpToDate
                            false
                        }
                    }
                }
            }
        } catch (e: SourceException) {
            _state.value = State.Failed(Reason.NETWORK)
            false
        }
    }

    fun pickSelfAsset(release: ReleaseRecord): ApkAsset? =
        ApkPicker.pick(release.apkAssets, Build.SUPPORTED_ABIS.toList()).first

    /** 下载到私有 updates/ 目录（只保留最近一份） */
    suspend fun download(asset: ApkAsset): File? {
        val tag = (_state.value as? State.NewVersion)?.tag ?: return null
        return try {
            _state.value = State.Downloading(tag, 0f)
            val dest = File(updateDir, asset.name)
            val file = githubApi.download(asset.url, dest) { p ->
                _state.value = State.Downloading(tag, p)
            }
            updateDir.listFiles()?.filterNot { it == file }?.forEach { it.delete() }
            _state.value = State.Downloaded(tag, file)
            file
        } catch (e: SourceException) {
            _state.value = State.Failed(Reason.DOWNLOAD)
            null
        }
    }

    /** 用户手动点「安装更新」：走 Intent 通道，用户锁定的第三方安装器（InstallerX/Shizuku 系）可接管 */
    fun install(file: File) = installManager.installApk(file)

    /** 缓存清理后状态复位（Downloaded → Idle，安装按钮回到可下载态） */
    fun resetState() {
        _state.value = State.Idle
    }

    /** 后台 worker 自动安装：保留系统会话（后台 startActivity 受限，且自动流程要求免打扰） */
    fun installSilently(file: File) = installManager.installViaSession(file)

    /** Android 12+ 且自身当前安装来源为本 app → 可静默安装（首次必然手动一次） */
    fun canInstallSilently(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            context.packageManager.getInstallSourceInfo(context.packageName)
                .installingPackageName == context.packageName
        }.getOrDefault(false)
    }

    fun localVersionName(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    companion object {
        // 本仓库 Releases 即更新源（remote 接好后如有出入改这两行）
        const val APP_OWNER = "JeffioZ"
        const val APP_REPO = "candytick"
    }
}
