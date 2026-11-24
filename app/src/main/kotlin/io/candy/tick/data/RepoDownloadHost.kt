package io.candy.tick.data

import android.content.Context
import io.candy.tick.data.model.ApkAsset
import io.candy.tick.data.model.DownloadedApk
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.util.ApkPicker
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Transient transfer state. The completed file is owned by TrackedRepo.downloadedApk. */
sealed interface RepoDownloadState {
    /** releaseKey=tagName + assetName 双维匹配：防同名资产的失败/进度态跨版本误显示 */
    val releaseKey: String
    val assetName: String

    data class Active(
        override val releaseKey: String,
        override val assetName: String,
        val progress: Float?,
        val fromHeader: Boolean = false, // 头部入口下载：进度只在头部显示，卡片不重复
    ) : RepoDownloadState

    /** 下载链路上本地环节的失败：界面按资源出文案，不透出异常原文 */
    enum class LocalFailure { INCOMPLETE, SAVE_FAILED }

    data class Failed(
        override val releaseKey: String,
        override val assetName: String,
        val error: SourceError? = null,
        val local: LocalFailure? = null,
        val fromHeader: Boolean = false,
    ) : RepoDownloadState
}

/** 下载流已结束但长度对不上（传输被截断） */
private class IncompleteDownload : IOException()

/** .part 改名为正式文件失败（目录不可写等） */
private class SaveFailed : IOException()

/**
 * 仓库 APK 下载宿主（App 级）：此前下载跑在 DetailViewModel.viewModelScope——离开详情页
 * VM 销毁即中断，弱网下大包静默丢失（design.md:180 承诺的下载连续性）。
 * 状态与协程收口于此：重进详情页按 repoId 重绑进度；下载完成的落库 / 自动关联 / 自动安装
 * 照常发生（与页面生命周期解耦）。
 *
 * 每个下载使用独立文件。完成后再替换记录并清理上一份，失败时保留可安装的旧记录。
 * 同一仓库同时只允许一个下载，代际标识用于丢弃取消后的进度与安装回调。
 */
class RepoDownloadHost(
    private val scope: CoroutineScope,
    private val repository: RepoRepository,
    private val githubApi: GitHubApi,
    private val installManager: InstallManager,
    private val appContext: Context,
    private val isAppVisible: () -> Boolean,
) {

    private val _states = MutableStateFlow<Map<Long, RepoDownloadState>>(emptyMap())
    val states: StateFlow<Map<Long, RepoDownloadState>> = _states.asStateFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val jobGens = ConcurrentHashMap<Long, Long>()
    private val nextGen = AtomicLong(0)

    init {
        // Installation can finish after navigation. One process-wide receiver handles all repos.
        scope.launch(Dispatchers.IO) {
            installManager.packageInstalled.collect { pkg ->
                val repos = repository.state.value.repos
                val matching = repos.mapNotNull { repo ->
                    repo.downloadedApk?.takeIf { it.packageName == pkg }?.let { repo.id to it }
                }
                if (matching.isNotEmpty()) {
                    val pm = appContext.packageManager
                    val installed = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
                    if (installed != null) for ((id, record) in matching) {
                        val file = File(appContext.filesDir, "apks/$id/${record.fileName}")
                        val archive = runCatching { pm.getPackageArchiveInfo(file.absolutePath, 0) }.getOrNull()
                        if (archive != null && archive.packageName == pkg &&
                            archive.longVersionCode == installed.longVersionCode && archive.versionName == installed.versionName
                        ) deleteRecord(id, record)
                    }
                }
                if (repos.any { it.linkedPackage == pkg }) {
                    Notifications.reconcileRepoNotifications(appContext, repository.state.value)
                }
            }
        }
    }

    fun download(
        repoId: Long,
        release: ReleaseRecord,
        asset: ApkAsset,
        fromHeader: Boolean = false,
        onAutoLinked: (String) -> Unit = {},
    ) {
        if (jobs[repoId]?.isActive == true) return
        val subscription = repository.state.value.repos.find { it.id == repoId } ?: return
        val extension = ApkPicker.packageExtension(asset.name) ?: return
        val dir = File(appContext.filesDir, "apks/$repoId")
        val gen = nextGen.incrementAndGet()
        jobGens[repoId] = gen
        // Each version/ABI owns its file, even when several releases use the same asset name.
        val fileId = java.util.UUID.randomUUID().toString()
        val dest = File(dir, "$fileId.$extension")
        val part = File(dir, "$fileId.part")
        val active = RepoDownloadState.Active(release.tagName, asset.name, if (asset.size > 0) 0f else null, fromHeader)
        _states.update { it + (repoId to active) }
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                githubApi.download(asset, part) { p ->
                    _states.update { states ->
                        if (jobGens[repoId] == gen && states[repoId] is RepoDownloadState.Active) {
                            states + (repoId to active.copy(progress = p))
                        } else states
                    }
                }
                if (jobGens[repoId] != gen) return@launch
                if (asset.size > 0 && part.length() != asset.size) throw IncompleteDownload()
                if (!part.renameTo(dest)) throw SaveFailed()
                val pkg = if (extension == "apk") {
                    appContext.packageManager.getPackageArchiveInfo(dest.absolutePath, 0)?.packageName
                } else null
                val record = DownloadedApk(dest.name, release.tagName, pkg, asset.name)
                val old = repository.replaceDownloadedApk(subscription, record) { jobGens[repoId] == gen }
                old?.let { File(dir, it.fileName).delete() }
                if (jobGens[repoId] != gen) return@launch
                val repo = repository.state.value.repos.find { it.id == repoId } ?: return@launch
                if (repo.downloadedApk?.fileName != dest.name) return@launch
                if (!pkg.isNullOrBlank() && repo.linkedPackage == null && !repo.autoLinkDismissed) {
                    val label = io.candy.tick.util.apkLabelOf(appContext, dest)
                    if (jobGens[repoId] != gen) return@launch
                    if (repository.setLinkedPackage(repoId, pkg, newLabel = label, onlyIfUnlinked = true, expectedDownload = record)) {
                        onAutoLinked(label?.takeIf { it.isNotBlank() } ?: pkg)
                    }
                }
                // Decide on the same thread as Activity visibility changes, after the file is saved.
                val completedInBackground = withContext(Dispatchers.Main.immediate) {
                    if (!isAppVisible()) return@withContext true
                    val current = repository.state.value
                    if (current.settings.autoInstallDownload && jobGens[repoId] == gen &&
                        current.repos.any { it.id == repoId && it.downloadedApk == record }
                    ) {
                        installManager.installApk(dest, repoId, asset.name)
                    }
                    false
                }
                if (completedInBackground && jobGens[repoId] == gen) {
                    Notifications.notifyRepoDownloadReady(appContext, repository.state, repoId, record)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = (e as? SourceException)?.error
                val local = when (e) {
                    is IncompleteDownload -> RepoDownloadState.LocalFailure.INCOMPLETE
                    is SaveFailed -> RepoDownloadState.LocalFailure.SAVE_FAILED
                    else -> null
                }
                _states.update { states ->
                    if (jobGens[repoId] != gen) states
                    else states + (repoId to RepoDownloadState.Failed(
                        release.tagName, asset.name, error = error, local = local, fromHeader = fromHeader,
                    ))
                }
            } finally {
                _states.update { states ->
                    if (jobGens[repoId] == gen && states[repoId] is RepoDownloadState.Active) states - repoId else states
                }
                part.delete()
                // Failed/cancelled before publication: the previously committed APK stays usable.
                if (repository.state.value.repos.find { it.id == repoId }?.downloadedApk?.fileName != dest.name) {
                    dest.delete()
                }
            }
        }
        jobs[repoId] = job
        job.invokeOnCompletion {
            jobGens.remove(repoId, gen)
            jobs.remove(repoId, job)
        }
        job.start()
    }

    fun cancel(repoId: Long) {
        if (_states.value[repoId] is RepoDownloadState.Active) clear(repoId)
    }

    suspend fun deleteRecord(repoId: Long, record: DownloadedApk) {
        repository.clearDownloadedApkIfMatches(repoId, record)
        val file = File(appContext.filesDir, "apks/$repoId/${record.fileName}")
        withContext(Dispatchers.IO) { file.delete() }
    }

    /** 退订 / 删除已下载 / 会话态清理：cancel 在跑的任务 + 摘表 + 清状态（DSH review：退订原路径漏接） */
    fun clear(repoId: Long) {
        jobGens.remove(repoId)
        jobs.remove(repoId)?.cancel()
        _states.update { it - repoId }
    }
}
