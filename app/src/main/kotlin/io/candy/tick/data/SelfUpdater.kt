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

    /** 轻量 Markdown 清理：剥标题井号/加粗星号/列表符号，压缩空行——正文在 UI 限 4 行展示 */
    private fun plainNotes(md: String): String = md
        .replace(Regex("#{1,6} "), "")
        .replace("**", "")
        .replace(Regex("^[-*] ", RegexOption.MULTILINE), "\u00b7 ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** @return true = 有可比对新版本（可触发自动下载） */
    suspend fun check(): Boolean {
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
                    // 更新说明取 Release 正文（body），轻量清理 Markdown 符号；空则退回标题
                    val notes = latest.body.takeIf { it.isNotBlank() }?.let { plainNotes(it) }
                        ?.let { n ->
                            // 首行若与版本号重复（正文常以 ## vX 开头）剔除
                            val lines = n.lineSequence()
                            if (lines.first() == latest.tagName) lines.drop(1).joinToString("\n") else n
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

    fun install(file: File) = installManager.installApk(file)

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
