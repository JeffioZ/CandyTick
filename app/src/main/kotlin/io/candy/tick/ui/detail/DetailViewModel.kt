package io.candy.tick.ui.detail

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.candy.tick.data.COMMITS_PER_PAGE
import io.candy.tick.data.InstallManager
import io.candy.tick.data.RELEASES_PER_PAGE
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.SourceError
import io.candy.tick.data.model.ApkAsset
import io.candy.tick.data.model.BranchInfo
import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.RepoSnapshot
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.data.model.TrackedRepo
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
    private val installManager: InstallManager,
    private val downloadHost: RepoDownloadHost,
    context: Context,
) : ViewModel() {

    // 下载四相收口 App 级 RepoDownloadHost（离开详情页不中断下载），页面只读绑定

    @Immutable
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
        val download: RepoDownloadState? = null,
        val selectedAssets: Map<String, ApkAsset> = emptyMap(), // releaseKey(tag) → 手选覆盖
        val branches: List<BranchInfo> = emptyList(), // 分支选择器数据（name + tip 时间）
        val branchesLoading: Boolean = false,
        val branchesFailed: Boolean = false,
        val installEpoch: Int = 0, // ON_RESUME 递增：安装完成后破除版本对比的重组跳过
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
    private var releasesGen = 0 // 版本列表代数：刷新时 +1，迟到的旧页响应作废
    private var autoLinkTried = false // 高置信自动关联只尝试一次

    init {
        viewModelScope.launch {
            repository.state.collect { s ->
                _state.update {
                    it.copy(repo = s.repos.find { r -> r.id == repoId }, snapshot = s.snapshots[repoId])
                }
                if (!autoLinkTried) {
                    val r = _state.value.repo ?: return@collect
                    autoLinkTried = true
                    if (r.linkedPackage == null) {
                        // 高置信自动关联（AppMatcher：标签/包名尾段/owner.repo 段；全机唯一才绑）
                        val auto = withContext(Dispatchers.Default) {
                            io.candy.tick.util.AppMatcher.bestAutoMatch(appContext, r.owner, r.repo)
                        }
                        if (auto != null) {
                            repository.setLinkedPackage(repoId, auto.packageName, newLabel = auto.label)
                            _events.send(Event.AutoLinked(auto.packageName))
                            return@collect
                        }
                    }
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
        // 下载进度按 repoId 重绑：App 级宿主持有状态，离开详情页不中断（重进接上进度）
        viewModelScope.launch {
            downloadHost.states.collect { m ->
                _state.update { it.copy(download = m[repoId]) }
            }
        }
        // 装后清理（事件驱动）：系统 PACKAGE_REPLACED/ADDED 广播带包名——装完瞬间触发，
        // 双安装路径通吃（ACTION_VIEW 外部安装器无回调也覆盖）、包名精确匹配零推断；
        // 此前版本比较推断法对不可比 tag（日期+hash）永远失灵
        viewModelScope.launch {
            installManager.packageInstalled.collect { pkg ->
                val record = _state.value.repo?.downloadedApk ?: return@collect
                // null（旧记录）无法精确匹配：宁可不清（下次下载新版本时有清旧文件兜底）
                // 也不能放行——否则系统装任何无关 App 的广播都会误清本仓库的下载文件
                if (record.packageName == null || record.packageName != pkg) return@collect
                File(apkDir, record.fileName).delete()
                repository.setDownloadedApk(repoId, null)
                // 装完即撤本仓「发现新版」通知：使命已完成（不点通知直接装的场景，
                // autoCancel 只在点击时生效——此前会一直挂在通知栏）
                io.candy.tick.data.Notifications.cancelRepoUpdate(context, repoId)
                // 会话态若挂着 Done 指向同文件，一并归位（版本卡回「下载」相；宿主 App 级）
                downloadHost.clearIfVersion(repoId, record.version)
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
            // 纯 per-branch：names + 各分支 tip 时间（并发闸 6，浏览/默认分支优先保时间）
            val list = repository.loadBranchInfos(repoId)
            _state.update {
                if (list != null) {
                    it.copy(branches = list, branchesLoading = false)
                } else {
                    it.copy(branchesLoading = false, branchesFailed = true)
                }
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
                releasesGen++
                _state.update {
                    it.copy(
                        // loading 一并复位：进入即刷新时 init 的在途响应会因 gen 不匹配作废、
                        // 不复位的话下面的 loadCommits/loadReleases 被自家守卫挡回，
                        // 在途响应又不认领 → 列表永久转圈（进入详情必现窗口）
                        commits = emptyList(), commitsEnd = false, commitsFailed = false, commitsLoading = false,
                        releases = emptyList(), releasesEnd = false, releasesFailed = false, releasesLoading = false,
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
                                commitsEnd = data.size < COMMITS_PER_PAGE,
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
            val gen = releasesGen
            val more = runCatching { repository.releases(repoId, page) }
            if (gen != releasesGen) return@launch // 已刷新：旧页响应作废（loading 由新周期接管）
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
                                releasesEnd = data.size < RELEASES_PER_PAGE,
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

    /** 下载 APK → App 级宿主（离开详情页不中断；完成落库/自动关联/自动安装均在宿主） */
    fun downloadAsset(release: ReleaseRecord, asset: ApkAsset, fromHeader: Boolean = false) {
        downloadHost.download(
            repoId = repoId,
            release = release,
            asset = asset,
            fromHeader = fromHeader,
            onAutoLinked = { pkg ->
                viewModelScope.launch { _events.send(Event.AutoLinked(pkg)) }
            },
        )
    }

    /** 取消下载：停任务 + 清半成品，状态归位让动作区回到「下载」相 */
    fun cancelDownload() = downloadHost.cancel(repoId)

    /** 未关联时的建议条：弱匹配（标签包含式）唯一命中才显示，用户点一下才生效 */
    private suspend fun computeLinkSuggestion(): Pair<String, String>? = withContext(Dispatchers.Default) {
        val repo = _state.value.repo ?: return@withContext null
        io.candy.tick.util.AppMatcher.containmentSuggestion(appContext, repo.repo)
            ?.let { it.packageName to it.label }
    }

    fun retryDownload(release: ReleaseRecord, asset: ApkAsset) = downloadAsset(release, asset, fromHeader = true)

    /** 从安装器返回（ON_RESUME）：本机版本可能已变，递增 epoch 触发全部版本对比重算 */
    fun onAppResume() {
        _state.update { it.copy(installEpoch = it.installEpoch + 1) }
    }

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
        // fromHeader 必传：头部入口的进度只在头部显示（此前漏传 → 点下载无进度条，
        // 下载完自动拉起安装，用户看到的是「没进度直接安装」）
        downloadAsset(release, asset, fromHeader = true)
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
        downloadHost.clear(repoId)
        viewModelScope.launch {
            apkDir.listFiles()?.forEach { it.delete() }
            repository.setDownloadedApk(repoId, null)
            _events.send(Event.DownloadDeleted)
        }
    }

    /** 关联 / 解除；newLabel 供手选与建议路径传入（自定义备注由 autoNote 标记保护） */
    fun setLinkedApp(packageName: String?, newLabel: String? = null) {
        viewModelScope.launch {
            repository.setLinkedPackage(repoId, packageName, newLabel)
        }
    }

    /** 详情页删除入口（确认对话框后调用；下载的 APK 文件保留） */
    fun removeRepo() {
        // 退订清场：撤掉在跑的下载任务（否则孤儿 job 会继续下完并给已退订仓库自动装包）
        downloadHost.clear(repoId)
        viewModelScope.launch { repository.removeRepo(repoId) }
    }

    fun dismissBanner() = _state.update { it.copy(banner = null) }
}
