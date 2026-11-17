package io.candy.tick.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.candy.tick.data.GitHubApi
import io.candy.tick.data.InstallManager
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.SourceError
import io.candy.tick.data.model.ApkAsset
import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.DownloadedApk
import io.candy.tick.data.model.RepoSnapshot
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.data.model.statusAt
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class DetailViewModel(
    val repoId: Long,
    private val repository: RepoRepository,
    private val githubApi: GitHubApi,
    private val installManager: InstallManager,
    context: Context,
) : ViewModel() {

    sealed interface DownloadState {
        data class Active(val assetName: String, val progress: Float) : DownloadState
        data class Done(val assetName: String, val file: File) : DownloadState
        data class Failed(val assetName: String) : DownloadState
    }

    data class UiState(
        val repo: TrackedRepo? = null,
        val snapshot: RepoSnapshot? = null,
        val refreshing: Boolean = false,
        val banner: SourceError? = null,
        val tab: DetailTab = DetailTab.COMMITS,
        val linkSuggestion: Pair<String, String>? = null, // (包名, 应用标签)，唯一匹配时才有
        val commits: List<CommitRecord> = emptyList(),
        val commitsLoading: Boolean = false,
        val commitsEnd: Boolean = false,
        val commitsFailed: Boolean = false,
        val releases: List<ReleaseRecord> = emptyList(),
        val releasesLoading: Boolean = false,
        val releasesEnd: Boolean = false,
        val releasesFailed: Boolean = false,
        val download: DownloadState? = null,
        val selectedAssets: Map<String, ApkAsset> = emptyMap(), // releaseKey(tag) → 手选覆盖
        val branches: List<String> = emptyList(), // 分支选择器数据
        val branchesLoading: Boolean = false,
        val branchesFailed: Boolean = false,
    )

    sealed interface Event {
        data class InstallResultEvent(val result: InstallManager.InstallResult) : Event
        data class AutoLinked(val packageName: String) : Event
        data object DownloadDeleted : Event
    }

    /** 详情页浏览页签：提交 / 版本（一次只看一类，长列表不再互相挤压） */
    enum class DetailTab { COMMITS, RELEASES }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val appContext: Context = context.applicationContext
    private val apkDir: File = File(context.filesDir, "apks/$repoId")
    private val tab = MutableStateFlow(DetailTab.COMMITS)
    private var commitsPage = 1
    private var releasesPage = 1
    private var commitsGen = 0 // 提交列表代数：切分支/刷新时 +1，迟到的旧响应作废

    init {
        viewModelScope.launch {
            repository.state.collect { s ->
                _state.update {
                    it.copy(repo = s.repos.find { r -> r.id == repoId }, snapshot = s.snapshots[repoId])
                }
                if (_state.value.linkSuggestion == null && _state.value.repo?.linkedPackage == null) {
                    val suggestion = computeLinkSuggestion()
                    _state.update { if (it.linkSuggestion == null) it.copy(linkSuggestion = suggestion) else it }
                }
            }
        }
        viewModelScope.launch {
            tab.collect { t -> _state.update { it.copy(tab = t) } }
        }
        viewModelScope.launch {
            installManager.results.collect { result ->
                _events.send(Event.InstallResultEvent(result))
            }
        }
        loadCommits()
        loadReleases()
    }

    fun setTab(newTab: DetailTab) {
        tab.value = newTab
    }

    /** 分支列表（选择器打开时懒加载，一次即缓存） */
    fun loadBranches() {
        if (_state.value.branches.isNotEmpty() || _state.value.branchesLoading) return
        viewModelScope.launch {
            _state.update { it.copy(branchesLoading = true, branchesFailed = false) }
            val result = runCatching { repository.branches(repoId) }
            _state.update {
                result.fold(
                    onSuccess = { list -> it.copy(branches = list, branchesLoading = false) },
                    onFailure = { _ -> it.copy(branchesLoading = false, branchesFailed = true) },
                )
            }
        }
    }

    /** 切换浏览分支：落库 + 重置提交列表（状态/版本仍按仓库级，不动） */
    fun setBranch(branch: String?) {
        if (_state.value.repo?.branch == branch) return
        viewModelScope.launch {
            repository.setBranch(repoId, branch)
            commitsPage = 1
            commitsGen++
            _state.update {
                it.copy(
                    commits = emptyList(), commitsEnd = false, commitsFailed = false,
                    commitsLoading = false,
                )
            }
            loadCommits()
        }
    }

    /** 下拉刷新：刷新快照 + 重置提交 / Release 列表（与首页同一语义） */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, banner = null) }
            val error = repository.refreshRepo(repoId)
            if (error == null) {
                commitsPage = 1
                commitsGen++
                releasesPage = 1
                _state.update {
                    it.copy(
                        commits = emptyList(), commitsEnd = false, commitsFailed = false,
                        releases = emptyList(), releasesEnd = false, releasesFailed = false,
                    )
                }
                loadCommits()
                loadReleases()
            } else {
                _state.update { it.copy(banner = error) } // 旧数据保留
            }
            _state.update { it.copy(refreshing = false) }
        }
    }

    fun loadCommits() {
        val s = _state.value
        if (s.commitsLoading || s.commitsEnd) return
        viewModelScope.launch {
            _state.update { it.copy(commitsLoading = true, commitsFailed = false) }
            val page = commitsPage
            val gen = commitsGen
            val more = runCatching { repository.commits(repoId, page) }
            if (gen != commitsGen) return@launch // 已切分支/刷新：旧响应作废
            _state.update {
                when (val data = more.getOrNull()) {
                    null -> it.copy(commitsLoading = false, commitsFailed = true)
                    else -> {
                        if (data.isEmpty()) {
                            it.copy(commitsLoading = false, commitsEnd = true)
                        } else {
                            commitsPage = page + 1
                            it.copy(
                                commits = it.commits + data,
                                commitsLoading = false,
                                commitsEnd = data.size < 30,
                            )
                        }
                    }
                }
            }
        }
    }

    fun loadReleases() {
        val s = _state.value
        if (s.releasesLoading || s.releasesEnd) return
        viewModelScope.launch {
            _state.update { it.copy(releasesLoading = true, releasesFailed = false) }
            val page = releasesPage
            val more = runCatching { repository.releases(repoId, page) }
            _state.update {
                when (val data = more.getOrNull()) {
                    null -> it.copy(releasesLoading = false, releasesFailed = true)
                    else -> {
                        if (data.isEmpty()) {
                            it.copy(releasesLoading = false, releasesEnd = true)
                        } else {
                            releasesPage = page + 1
                            it.copy(
                                releases = it.releases + data,
                                releasesLoading = false,
                                releasesEnd = data.size < 30,
                            )
                        }
                    }
                }
            }
        }
    }

    fun selectAsset(releaseKey: String, asset: ApkAsset) {
        _state.update { it.copy(selectedAssets = it.selectedAssets + (releaseKey to asset)) }
    }

    /** 下载 APK → 私有目录（下载保留；同仓库只留最新一份），完成后记录到 TrackedRepo */
    fun downloadAsset(release: ReleaseRecord, asset: ApkAsset) {
        if (_state.value.download is DownloadState.Active) return
        viewModelScope.launch {
            _state.update { it.copy(download = DownloadState.Active(asset.name, 0f)) }
            val dest = File(apkDir, asset.name)
            val result = runCatching {
                githubApi.download(asset.url, dest) { p ->
                    _state.update { st ->
                        val d = st.download
                        if (d is DownloadState.Active) st.copy(download = d.copy(progress = p)) else st
                    }
                }
            }
            result.fold(
                onSuccess = { file ->
                    // 清理同仓库旧文件（下载保留只留当前）
                    apkDir.listFiles()?.filter { it != file }?.forEach { it.delete() }
                    _state.update { it.copy(download = DownloadState.Done(asset.name, file)) }
                    repository.setDownloadedApk(repoId, DownloadedApk(asset.name, release.tagName))
                    // 下载到手 → 免费读包名自动关联（getPackageArchiveInfo，零依赖）
                    autoLinkFromApk(file)
                    // 自动安装（默认开）：直接触发系统安装流程，确认框由系统兜底
                    if (repository.state.value.settings.autoInstallDownload) {
                        installManager.installApk(file)
                    }
                },
                onFailure = {
                    _state.update { it.copy(download = DownloadState.Failed(asset.name)) } // 文件已保留，可重试
                },
            )
        }
    }

    /** APK 已在本机：直接解析包名，未关联时自动建立关联 */
    private fun autoLinkFromApk(file: File) {
        if (_state.value.repo?.linkedPackage != null) return
        val info = runCatching {
            appContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull()
        val pkg = info?.packageName?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            repository.setLinkedPackage(repoId, pkg)
            _events.send(Event.AutoLinked(pkg))
        }
    }

    /** 未关联时的智能建议：仓库名 ↔ 已装应用标签 归一化唯一匹配（零网络） */
    private suspend fun computeLinkSuggestion(): Pair<String, String>? = withContext(Dispatchers.Default) {
        val name = _state.value.repo?.repo?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: return@withContext null
        if (name.isBlank()) return@withContext null
        runCatching {
            val pm = appContext.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .asSequence()
                .filter { it.activityInfo.packageName != appContext.packageName }
                .map { it to it.loadLabel(pm).toString() }
                .filter { (ri, label) ->
                    val norm = label.lowercase().replace(Regex("[^a-z0-9]"), "")
                    norm == name || (norm.length >= 3 && (norm.contains(name) || name.contains(norm)))
                }
                .distinctBy { (ri, _) -> ri.activityInfo.packageName }
                .toList()
        }.getOrNull()?.let { matches ->
            if (matches.size == 1) matches.first().let { (ri, label) -> ri.activityInfo.packageName to label } else null
        }
    }

    fun installFile(file: File) = installManager.installApk(file)

    fun retryDownload(release: ReleaseRecord, asset: ApkAsset) = downloadAsset(release, asset)

    /**
     * 头部「下载更新」入口：只精确匹配最新 tag 的 release（盲下列表第一条可能拿到
     * 比已装更旧的版本）；列表未加载到或无 APK 资产时切到版本页签让用户直视。
     */
    fun downloadUpdate() {
        val s = _state.value
        val tag = s.snapshot?.latestReleaseTag ?: return
        val release = s.releases.find { it.tagName == tag } ?: run {
            tab.value = DetailTab.RELEASES
            return
        }
        val (autoPick, _) = io.candy.tick.util.ApkPicker.pick(
            release.apkAssets, android.os.Build.SUPPORTED_ABIS.toList(),
        )
        val asset = s.selectedAssets[release.tagName] ?: autoPick ?: run {
            tab.value = DetailTab.RELEASES // 该 release 无 APK 资产
            return
        }
        downloadAsset(release, asset)
    }

    /** 下载保留复用：已下载过的文件直接安装 */
    fun installDownloaded(repo: TrackedRepo): Boolean {
        val record = repo.downloadedApk ?: return false
        val file = File(apkDir, record.fileName)
        if (!file.exists()) return false
        installManager.installApk(file)
        return true
    }

    /** 删除已下载的 APK（文件 + 记录；会话态一并清空，避免残留入口） */
    fun deleteDownloaded() {
        viewModelScope.launch {
            apkDir.listFiles()?.forEach { it.delete() }
            repository.setDownloadedApk(repoId, null)
            _state.update { it.copy(download = null) }
            _events.send(Event.DownloadDeleted)
        }
    }

    fun setLinkedApp(packageName: String?) {
        viewModelScope.launch { repository.setLinkedPackage(repoId, packageName) }
    }

    /** 详情页删除入口（确认对话框后调用；下载的 APK 文件保留） */
    fun removeRepo() {
        viewModelScope.launch { repository.removeRepo(repoId) }
    }

    fun dismissBanner() = _state.update { it.copy(banner = null) }
}
