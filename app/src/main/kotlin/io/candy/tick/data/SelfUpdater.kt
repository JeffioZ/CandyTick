package io.candy.tick.data

import android.content.Context
import android.os.Build
import io.candy.tick.data.model.ApkAsset
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.util.ApkPicker
import io.candy.tick.util.VersionCompare
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    private val notificationsEnabled: () -> Boolean = { true },
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
        data class Failed(
            val reason: Reason,
            val tag: String? = null, // DOWNLOAD/INSTALL 失败时携带，供重试与状态复原
            val file: File? = null, // INSTALL 失败时保留的安装包（可直接重装）
        ) : State
    }

    enum class Reason { NETWORK, DOWNLOAD, INSTALL }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 最近一次的 NewVersion 快照：失败重试下载的数据源（Failed 态已不持有 asset） */
    private var lastNewVersion: State.NewVersion? = null

    /** 静默安装会话在途标记：把 installManager.results 里的失败归属到自身更新（仓库安装不受影响） */
    private var sessionInstallInFlight = false

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
            // 自更新装完同样撤「下载完成」通知（与仓库通知同一类陈旧问题）
            Notifications.cancelSelfUpdateReady(context)
        }
    }

    /** @return true = 有可比对新版本（可触发自动下载） */
    suspend fun check(): Boolean {
        cleanupInstalledSelf()
        // 先取证：下一行 Checking 会冲掉 Downloaded。同 tag 已下载待装期间，
        // 每轮同步 hasNew 恒真会反复入队——不保住状态就会重下整包 + 重发通知
        val preDownloaded = _state.value as? State.Downloaded
        _state.value = State.Checking
        return try {
            // 只用 firstOrNull：perPage=1 足够（此前默认拉整页）
            val releases = githubApi.releases(APP_OWNER, APP_REPO, 1, perPage = 1)
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
                            val nv = State.NewVersion(
                                tag = latest.tagName,
                                notes = notes,
                                asset = pickSelfAsset(latest),
                                incomparable = true,
                            )
                            lastNewVersion = nv
                            _state.value = nv
                            false
                        }
                        cmp > 0 -> {
                            // 同 tag 已下载待装：复原 Downloaded 并原样返回——只 return 不复原会把
                            // 状态留在 Checking，下轮 preDownloaded==null 变回 NewVersion 重下重发
                            if (preDownloaded != null && preDownloaded.tag == latest.tagName &&
                                preDownloaded.file.isFile
                            ) {
                                _state.value = preDownloaded
                                return true
                            }
                            // 安装失败（安装包保留）同 tag：恢复 Downloaded 不重下，出口在设置页重试安装
                            val failed = _state.value as? State.Failed
                            if (failed?.reason == Reason.INSTALL &&
                                failed.tag == latest.tagName && failed.file?.isFile == true
                            ) {
                                _state.value = State.Downloaded(latest.tagName, failed.file)
                                return true
                            }
                            val nv = State.NewVersion(
                                tag = latest.tagName,
                                notes = notes,
                                asset = pickSelfAsset(latest),
                            )
                            lastNewVersion = nv
                            _state.value = nv
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
            // 检查失败不冲掉已下载待装（此前会把 Downloaded 冲成 Failed，装按钮凭空消失）
            _state.value = preDownloaded?.takeIf { it.file.isFile } ?: State.Failed(Reason.NETWORK)
            false
        }
    }

    fun pickSelfAsset(release: ReleaseRecord): ApkAsset? =
        ApkPicker.pick(release.apkAssets, Build.SUPPORTED_ABIS.toList()).first

    /** 下载到私有 updates/ 目录（只保留最近一份） */
    suspend fun download(asset: ApkAsset): File? {
        val tag = (_state.value as? State.NewVersion)?.tag
            ?: (lastNewVersion?.takeIf { it.asset == asset }?.tag)
            ?: return null
        val dest = File(updateDir, asset.name)
        return try {
            _state.value = State.Downloading(tag, 0f)
            val file = githubApi.download(asset.url, dest) { p ->
                _state.value = State.Downloading(tag, p)
            }
            updateDir.listFiles()?.filterNot { it == file }?.forEach { it.delete() }
            _state.value = State.Downloaded(tag, file)
            file
        } catch (e: SourceException) {
            dest.delete() // 半截文件不占位：重试从零下（updates/ 只保留完整包）
            _state.value = State.Failed(Reason.DOWNLOAD, tag)
            null
        }
    }

    /** 下载失败重试：Failed 态不持有 asset，从最近的 NewVersion 快照取 */
    suspend fun retryDownload(): File? =
        lastNewVersion?.asset?.let { download(it) }

    /** 用户手动点「安装更新」：走 Intent 通道，用户锁定的第三方安装器（InstallerX/Shizuku 系）可接管 */
    fun install(file: File) = installManager.installApk(file)

    /**
     * 自身更新安装结果收口：App 级唯一消费者（此前 results 只有详情页订阅——自更新
     * 安装失败/签名不匹配全部无反馈，静默安装失败还会把链路卡死在 Downloaded）。
     */
    fun observeInstallResults(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            installManager.results.collect { r ->
                // 首个结果即清在途标记：只有静默会话的第一个结果归属自身更新
                val wasInFlight = sessionInstallInFlight
                sessionInstallInFlight = false
                when (r) {
                    InstallManager.InstallResult.Success -> cleanupInstalledSelf()
                    is InstallManager.InstallResult.Failed -> if (wasInFlight) {
                        val downloaded = _state.value as? State.Downloaded
                        if (downloaded != null) {
                            _state.value = State.Failed(Reason.INSTALL, downloaded.tag, downloaded.file)
                            // 静默装失败 → 回退「点击安装」通知兜底（用户不看设置页也要知道）
                            if (notificationsEnabled()) {
                                Notifications.notifySelfUpdateReady(context, downloaded.tag)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** 缓存清理后状态复位（Downloaded → Idle，安装按钮回到可下载态） */
    fun resetState() {
        _state.value = State.Idle
    }

    /** 后台 worker 自动安装：保留系统会话（后台 startActivity 受限，且自动流程要求免打扰） */
    fun installSilently(file: File) {
        sessionInstallInFlight = true
        installManager.installViaSession(file)
    }

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
