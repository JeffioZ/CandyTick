package io.candy.tick.ui.detail

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.candy.tick.data.COMMITS_PER_PAGE
import io.candy.tick.data.GitHubApi
import io.candy.tick.data.InstallManager
import io.candy.tick.data.RELEASES_PER_PAGE
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.SourceError
import io.candy.tick.data.model.ApkAsset
import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.DownloadedApk
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
    private val githubApi: GitHubApi,
    private val installManager: InstallManager,
    context: Context,
) : ViewModel() {

    // @Immutable：接口级承诺（各实现全 val 只读），download 参数的稳定判定按此类型走
    @Immutable
    sealed interface DownloadState {
        /** releaseKey=tagName + assetName 双维匹配：防同名资产的失败/进度态跨版本误显示 */
        val releaseKey: String
        val assetName: String

        data class Active(
            override val releaseKey: String,
            override val assetName: String,
            val progress: Float,
            val fromHeader: Boolean = false, // 头部入口下载：进度只在头部显示，卡片不重复
        ) : DownloadState

        data class Done(
            override val releaseKey: String,
            override val assetName: String,
            val file: File,
        ) : DownloadState

        data class Failed(
            override val releaseKey: String,
            override val assetName: String,
            val message: String? = null,
            val error: io.candy.tick.data.SourceError? = null,
        ) : DownloadState
    }

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
        val download: DownloadState? = null,
        val selectedAssets: Map<String, ApkAsset> = emptyMap(), // releaseKey(tag) → 手选覆盖
        val branches: List<String> = emptyList(), // 分支选择器数据
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
                // 会话态若挂着 Done 指向同文件，一并归位（版本卡回「下载」相）
                if (_state.value.download?.releaseKey == record.version) {
                    _state.update { it.copy(download = null) }
                }
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

    /** 下载 APK → 私有目录（下载保留；同仓库只留最新一份），完成后记录到 TrackedRepo */
    private var downloadJob: kotlinx.coroutines.Job? = null

    fun downloadAsset(release: ReleaseRecord, asset: ApkAsset, fromHeader: Boolean = false) {
        if (_state.value.download is DownloadState.Active) return
        downloadJob = viewModelScope.launch {
            _state.update {
                it.copy(download = DownloadState.Active(release.tagName, asset.name, 0f, fromHeader))
            }
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
                    // 取消竞态防护：下载完成瞬间被取消时，cancelDownload 已删文件并清空状态，
                    // 本回调作废（否则会把状态写回 Done 指向已删文件）
                    if (_state.value.download !is DownloadState.Active) return@fold
                    // 清理同仓库旧文件（下载保留只留当前）
                    apkDir.listFiles()?.filter { it != file }?.forEach { it.delete() }
                    _state.update { it.copy(download = DownloadState.Done(release.tagName, asset.name, file)) }
                    // 包名随记录落库：装后广播按包名精确匹配清理（读 archive 同 autoLinkFromApk）
                    val dlPkg = runCatching {
                        appContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
                    }.getOrNull()
                    repository.setDownloadedApk(
                        repoId,
                        DownloadedApk(asset.name, release.tagName, dlPkg),
                    )
                    // 下载到手 → 免费读包名自动关联（getPackageArchiveInfo，零依赖）
                    autoLinkFromApk(file)
                    // 自动安装（默认开）：直接触发系统安装流程，确认框由系统兜底
                    if (repository.state.value.settings.autoInstallDownload) {
                        installManager.installApk(file)
                    }
                },
                onFailure = { e ->
                    // 取消不是失败：状态已由 cancelDownload 清空，这里直接短路防串台
                    if (e is kotlinx.coroutines.CancellationException) return@fold
                    // 结构化错误优先（UI 走 sourceErrorText 本地化），原始 message 兜底
                    val se = (e as? io.candy.tick.data.SourceException)?.error
                    _state.update {
                        it.copy(download = DownloadState.Failed(release.tagName, asset.name, e.message, se))
                    }
                },
            )
        }
    }

    /** 取消下载：停任务 + 清半成品，状态归位让动作区回到「下载」相 */
    fun cancelDownload() {
        val d = _state.value.download as? DownloadState.Active ?: return
        downloadJob?.cancel()
        downloadJob = null
        File(apkDir, d.assetName).delete()
        _state.update { it.copy(download = null) }
    }

    /** APK 已在本机：直接解析包名，未关联时自动建立关联 */
    private fun autoLinkFromApk(file: File) {
        if (_state.value.repo?.linkedPackage != null) return
        val info = runCatching {
            appContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull()
        val pkg = info?.packageName?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val label = io.candy.tick.util.apkLabelOf(appContext, file)
            repository.setLinkedPackage(repoId, pkg, newLabel = label)
            _events.send(Event.AutoLinked(pkg))
        }
    }

    /** 未关联时的建议条：弱匹配（标签包含式）唯一命中才显示，用户点一下才生效 */
    private suspend fun computeLinkSuggestion(): Pair<String, String>? = withContext(Dispatchers.Default) {
        val repo = _state.value.repo ?: return@withContext null
        io.candy.tick.util.AppMatcher.containmentSuggestion(appContext, repo.repo)
            ?.let { it.packageName to it.label }
    }

    fun installFile(file: File) = installManager.installApk(file)

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
        viewModelScope.launch {
            apkDir.listFiles()?.forEach { it.delete() }
            repository.setDownloadedApk(repoId, null)
            _state.update { it.copy(download = null) }
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
        viewModelScope.launch { repository.removeRepo(repoId) }
    }

    fun dismissBanner() = _state.update { it.copy(banner = null) }
}
