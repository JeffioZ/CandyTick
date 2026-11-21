package io.candy.tick.ui.detail

import android.content.Context
import androidx.compose.runtime.Immutable
import io.candy.tick.data.GitHubApi
import io.candy.tick.data.InstallManager
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.model.ApkAsset
import io.candy.tick.data.model.DownloadedApk
import io.candy.tick.data.model.ReleaseRecord
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 仓库 APK 下载四相（原 DetailViewModel.DownloadState；宿主持有，页面销毁不丢） */
@Immutable
sealed interface RepoDownloadState {
    /** releaseKey=tagName + assetName 双维匹配：防同名资产的失败/进度态跨版本误显示 */
    val releaseKey: String
    val assetName: String

    data class Active(
        override val releaseKey: String,
        override val assetName: String,
        val progress: Float,
        val fromHeader: Boolean = false, // 头部入口下载：进度只在头部显示，卡片不重复
    ) : RepoDownloadState

    data class Done(
        override val releaseKey: String,
        override val assetName: String,
        val file: File,
    ) : RepoDownloadState

    data class Failed(
        override val releaseKey: String,
        override val assetName: String,
        val message: String? = null,
        val error: io.candy.tick.data.SourceError? = null,
    ) : RepoDownloadState
}

/**
 * 仓库 APK 下载宿主（App 级）：此前下载跑在 DetailViewModel.viewModelScope——离开详情页
 * VM 销毁即中断，弱网下大包静默丢失（design.md:180 承诺的下载连续性）。
 * 状态与协程收口于此：重进详情页按 repoId 重绑进度；下载完成的落库 / 自动关联 / 自动安装
 * 照常发生（与页面生命周期解耦）。
 *
 * 并发模型（DSH review 定稿）：
 * - 会话代际 jobGens：每个会话持有唯一 gen，所有状态写入/落库/安装前都校验——旧会话
 *   （被取消或被取代）的任何回调不得触碰新会话的状态与文件。
 * - 独立临时文件 `.part-<gen>`：每个会话写自己的临时文件，成功后 rename 成最终名——
 *   消灭「旧 fd 继续写盘 vs 新会话同路径」的双写/悬空文件一类问题；删除临时文件永远安全
 *   （Linux 下 unlink 后写入进入无主 inode，不污染任何现存文件）。
 * - 重入闸 `jobs[repoId]?.isActive`：jobs 表在调用线程同步写、launch 体异步跑，主线程
 *   串行调用下判定可靠；同仓同时只允许一个下载（换资产先 cancel）。
 */
class RepoDownloadHost(
    private val scope: CoroutineScope,
    private val repository: RepoRepository,
    private val githubApi: GitHubApi,
    private val installManager: InstallManager,
    private val appContext: Context,
) {

    private val _states = MutableStateFlow<Map<Long, RepoDownloadState>>(emptyMap())
    val states: StateFlow<Map<Long, RepoDownloadState>> = _states.asStateFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val jobGens = ConcurrentHashMap<Long, Long>()
    private val nextGen = AtomicLong(0)

    /** 下载 APK → 私有目录（下载保留；同仓库只留最新一份），完成后记录到 TrackedRepo */
    fun download(
        repoId: Long,
        release: ReleaseRecord,
        asset: ApkAsset,
        fromHeader: Boolean = false,
        onAutoLinked: (String) -> Unit = {},
    ) {
        // 重入闸（同步判定）：同仓已在下载则忽略；换资产/换版本先走 cancel
        if (jobs[repoId]?.isActive == true) return
        val apkDir = File(appContext.filesDir, "apks/$repoId")
        val gen = nextGen.incrementAndGet()
        jobGens[repoId] = gen
        jobs[repoId]?.cancel()
        val dest = File(apkDir, asset.name)
        val part = File(apkDir, asset.name + ".part-$gen") // 每会话独立临时文件
        val job = scope.launch {
            // 代际校验：被取代/取消的会话在入口即退出（不写状态不写盘）
            if (jobGens[repoId] != gen) {
                runCatching { part.delete() }
                return@launch
            }
            _states.update { it + (repoId to RepoDownloadState.Active(release.tagName, asset.name, 0f, fromHeader)) }
            val result = runCatching {
                githubApi.download(asset.url, part) { p ->
                    // 代际校验：旧会话的进度 tick 不污染新会话（update = CAS 原子读改写）
                    if (jobGens[repoId] != gen) return@download
                    _states.update { m ->
                        val cur = m[repoId]
                        if (cur is RepoDownloadState.Active) m + (repoId to cur.copy(progress = p)) else m
                    }
                }
            }
            // 会话已失效（cancel/被取代/退订清场）：只清自己的临时文件，绝不触碰最终文件与状态
            if (jobGens[repoId] != gen) {
                runCatching { part.delete() }
                return@launch
            }
            result.fold(
                onSuccess = { _ ->
                    // 原子落位：清旧文件 → 临时文件改名成最终名（改名失败按下载失败处理）
                    apkDir.listFiles()?.filter { it != part }?.forEach { it.delete() }
                    if (!part.renameTo(dest)) {
                        _states.update {
                            it + (repoId to RepoDownloadState.Failed(release.tagName, asset.name, "rename failed"))
                        }
                        jobGens.remove(repoId, gen)
                        return@fold
                    }
                    _states.update { it + (repoId to RepoDownloadState.Done(release.tagName, asset.name, dest)) }
                    // 以下每步前再校验代际：cancel/clear 落在窗口内时放弃落库与安装
                    if (jobGens[repoId] != gen) return@fold
                    val dlPkg = runCatching {
                        appContext.packageManager.getPackageArchiveInfo(dest.absolutePath, 0)?.packageName
                    }.getOrNull()
                    repository.setDownloadedApk(
                        repoId,
                        DownloadedApk(asset.name, release.tagName, dlPkg),
                    )
                    // 下载到手 → 免费读包名自动关联（getPackageArchiveInfo，零依赖）
                    if (dlPkg?.takeIf { it.isNotBlank() } != null &&
                        repository.state.value.repos.find { it.id == repoId }?.linkedPackage == null
                    ) {
                        val label = io.candy.tick.util.apkLabelOf(appContext, dest)
                        repository.setLinkedPackage(repoId, dlPkg, newLabel = label)
                        onAutoLinked(dlPkg)
                    }
                    // 自动安装（默认开）：直接触发系统安装流程，确认框由系统兜底
                    if (repository.state.value.settings.autoInstallDownload) {
                        installManager.installApk(dest)
                    }
                },
                onFailure = { e ->
                    // 取消不是失败：gen 已被摘除，回调在这里只清自己的临时文件
                    if (jobGens[repoId] != gen) {
                        runCatching { part.delete() }
                        return@fold
                    }
                    if (e is kotlinx.coroutines.CancellationException) return@fold
                    // 结构化错误优先（UI 走 sourceErrorText 本地化），原始 message 兜底
                    val se = (e as? io.candy.tick.data.SourceException)?.error
                    _states.update {
                        it + (repoId to RepoDownloadState.Failed(release.tagName, asset.name, e.message, se))
                    }
                    jobGens.remove(repoId, gen)
                },
            )
        }
        jobs[repoId] = job
        // 收尾清理：只有仍是当前会话时才摘表（旧 job 的 completion 不得动新会话的条目）
        job.invokeOnCompletion {
            if (jobGens[repoId] == gen) {
                jobGens.remove(repoId, gen)
                jobs.remove(repoId, job)
            }
        }
    }

    /** 取消下载：停任务 + 清全部临时文件，状态归位让动作区回到「下载」相 */
    fun cancel(repoId: Long) {
        val d = _states.value[repoId] as? RepoDownloadState.Active ?: return
        jobs.remove(repoId)?.cancel()
        jobGens.remove(repoId) // 先摘代际：旧 job 的任何后续回调立即作废
        File(appContext.filesDir, "apks/$repoId").listFiles()
            ?.filter { it.name.endsWith(".part") || it.name.contains(".part-") }
            ?.forEach { runCatching { it.delete() } }
        clear(repoId)
    }

    /** 装后广播命中：会话态 Done 指向同版本则归位（版本卡回「下载」相） */
    fun clearIfVersion(repoId: Long, version: String) {
        if (_states.value[repoId]?.releaseKey == version) clear(repoId)
    }

    /** 退订 / 删除已下载 / 会话态清理：cancel 在跑的任务 + 摘表 + 清状态（DSH review：退订原路径漏接） */
    fun clear(repoId: Long) {
        jobs.remove(repoId)?.cancel()
        jobGens.remove(repoId)
        _states.update { it - repoId }
    }
}
