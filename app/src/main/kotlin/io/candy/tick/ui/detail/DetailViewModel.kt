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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class DetailViewModel(
    val repoId: Long,
    private val repository: RepoRepository,
    private val installManager: InstallManager,
    private val downloadHost: RepoDownloadHost,
    context: Context,
    initialTab: DetailTab = DetailTab.COMMITS,
) : ViewModel() {

    // 下载四相收口 App 级 RepoDownloadHost（离开详情页不中断下载），页面只读绑定

    @Immutable
    data class UiState(
        val repo: TrackedRepo? = null,
        val snapshot: RepoSnapshot? = null,
        val manualRefresh: Boolean = false,
        val snapshotLoading: Boolean = false,
        val banner: SourceError? = null,
        val tab: DetailTab = DetailTab.COMMITS,
        val linkSuggestion: Pair<String, String>? = null, // (包名, 应用标签)，唯一匹配时才有
        val commits: List<CommitRecord> = emptyList(),
        val commitsLoading: Boolean = false,
        val commitsEnd: Boolean = false,
        val commitsFailed: Boolean = false,
        val releases: List<ReleaseRecord> = emptyList(),
        val notificationRelease: ReleaseRecord? = null,
        val releasesLoading: Boolean = false,
        val releasesEnd: Boolean = false,
        val releasesFailed: Boolean = false,
        // page1 重载进行中（仅 refresh 触发）：footer 用它压掉与刷新头重复的小点，
        // 完成即清。翻页加载（load-more）不走这个标记，小点照常
        val commitsReloading: Boolean = false,
        val releasesReloading: Boolean = false,
        val download: RepoDownloadState? = null,
        val selectedAssets: Map<String, ApkAsset> = emptyMap(), // releaseKey(tag) → 手选覆盖
        val branches: List<BranchInfo> = emptyList(), // 分支选择器数据（name + tip 时间）
        val branchesLoading: Boolean = false,
        val branchesFailed: Boolean = false,
        val installEpoch: Int = 0, // ON_RESUME 递增：安装完成后破除版本对比的重组跳过
    ) {
        val syncing: Boolean get() = snapshotLoading || commitsReloading || releasesReloading
        val refreshing: Boolean get() = manualRefresh && syncing

        /** A pull joins the active refresh and releases the header as the last request settles. */
        internal fun withManualRefresh(requested: Boolean = manualRefresh): UiState {
            val active = requested && syncing
            return if (manualRefresh == active) this else copy(manualRefresh = active)
        }

        internal fun forNewBranch(): UiState = copy(
            commits = emptyList(), commitsEnd = false, commitsFailed = false,
            commitsLoading = false, commitsReloading = syncing,
        )
    }

    sealed interface Event {
        data class InstallResultEvent(val result: InstallManager.InstallResult) : Event
        data class AutoLinked(val displayName: String) : Event
        data object DownloadMissing : Event
    }

    /** 详情页浏览页签：提交 / 版本（一次只看一类，长列表不再互相挤压） */
    enum class DetailTab { COMMITS, RELEASES }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val _state = MutableStateFlow(repository.state.value.let { current ->
        UiState(repo = current.repos.find { it.id == repoId }, snapshot = current.snapshots[repoId], tab = initialTab)
    })
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val appContext: Context = context.applicationContext
    private val apkDir: File = File(context.filesDir, "apks/$repoId")
    private var commitsPage = 1
    private var releasesPage = 1
    private var commitsGen = 0 // 提交列表代数：切分支/刷新时 +1，迟到的旧响应作废
    private var releasesGen = 0 // 版本列表代数：刷新时 +1，迟到的旧页响应作废
    private data class LinkedAppKey(val owner: String, val repo: String, val pkg: String?, val dismissed: Boolean)

    private fun TrackedRepo.linkKey() = LinkedAppKey(owner, repo, linkedPackage, autoLinkDismissed)

    // A candidate belongs to the repository and installed apps, independently of the current link.
    private var suggestionLookup: Pair<String, Int>? = null

    private suspend fun resolveSuggestion(repo: String) {
        val lookup = repo to _state.value.installEpoch
        if (lookup == suggestionLookup) return
        val candidate = withContext(Dispatchers.IO) {
            io.candy.tick.util.AppMatcher.containmentSuggestion(appContext, repo)
        }
        if (repository.state.value.repos.find { it.id == repoId }?.repo != repo) return
        if (suggestionLookup?.let { it.first == repo && it.second > lookup.second } == true) return
        suggestionLookup = lookup
        _state.update { it.copy(linkSuggestion = candidate?.let { app -> app.packageName to app.label }) }
    }

    init {
        viewModelScope.launch {
            repository.state.collect { s ->
                val tracked = s.repos.find { it.id == repoId }
                _state.update {
                    it.copy(
                        repo = tracked,
                        snapshot = s.snapshots[repoId],
                        linkSuggestion = if (it.repo?.repo == tracked?.repo) it.linkSuggestion else null,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.state.map { state ->
                state.repos.find { it.id == repoId }?.linkKey()
            }.distinctUntilChanged().collect { key ->
                if (key == null) return@collect
                if (key.pkg != null) {
                    val installed = withContext(Dispatchers.IO) {
                        try {
                            appContext.packageManager.getPackageInfo(key.pkg, 0)
                            true
                        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                            false
                        } catch (_: SecurityException) {
                            true // A visibility restriction is not evidence of an uninstall.
                        }
                    }
                    if (!installed && repository.state.value.repos.find { it.id == repoId }?.linkKey() == key) {
                        repository.setLinkedPackage(repoId, null, autoClear = true, expectedPackage = key.pkg)
                    }
                } else {
                    val auto = if (key.dismissed) null else withContext(Dispatchers.IO) {
                        io.candy.tick.util.AppMatcher.bestAutoMatch(appContext, key.owner, key.repo)
                    }
                    if (repository.state.value.repos.find { it.id == repoId }?.linkKey() != key) return@collect
                    if (auto != null) {
                        if (repository.setLinkedPackage(repoId, auto.packageName, newLabel = auto.label, onlyIfUnlinked = true)) {
                            _events.send(Event.AutoLinked(auto.label.ifBlank { auto.packageName }))
                        }
                    } else {
                        resolveSuggestion(key.repo)
                    }
                }
            }
        }
        viewModelScope.launch {
            installManager.packageInstalled.collect { pkg ->
                if (repository.state.value.repos.any { it.id == repoId && it.linkedPackage == pkg }) onAppResume()
            }
        }
        viewModelScope.launch {
            installManager.resultsFor(repoId).collect { result ->
                _events.send(Event.InstallResultEvent(result))
            }
        }
        // 下载进度按 repoId 重绑：App 级宿主持有状态，离开详情页不中断（重进接上进度）
        viewModelScope.launch {
            downloadHost.states.collect { m ->
                _state.update { it.copy(download = m[repoId]) }
            }
        }

    }

    fun setTab(newTab: DetailTab) {
        _state.update { it.copy(tab = newTab) }
    }

    /** Lookup is scoped to the notification request in the screen. It never advances the history cursor. */
    suspend fun findNotificationRelease(tag: String): SourceError? {
        return try {
            val release = repository.releaseByTag(repoId, tag)
            if (release?.tagName != tag) return SourceError.NotFound
            _state.update { it.copy(notificationRelease = release) }
            null
        } catch (e: io.candy.tick.data.SourceException) {
            e.error
        }
    }

    fun markVisibleActivity(releaseTags: Set<String> = emptySet(), commitShas: Set<String> = emptySet()) {
        if (releaseTags.isEmpty() && commitShas.isEmpty()) return
        val history = _state.value
        val releaseHead = history.releases.take(RELEASES_PER_PAGE).firstOrNull { it.apkAssets.isNotEmpty() }
            ?.tagName?.takeIf { it in releaseTags }
        val commitHead = history.commits.firstOrNull()?.sha?.takeIf { it in commitShas }
        viewModelScope.launch {
            repository.markActivityRead(repoId, releaseTags, commitShas, releaseHead, commitHead)
        }
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
            _state.update { it.forNewBranch() }
            loadCommits()
        }
    }

    /** The header and both first pages are independent. Start each once for this visit,
     * keeping previous results visible until their replacements arrive. */
    fun refresh(silent: Boolean = false) {
        if (_state.value.syncing) {
            // A user pull can take over the requests already started on entry.
            if (!silent) _state.update { it.withManualRefresh(requested = true) }
            return
        }
        commitsPage = 1
        releasesPage = 1
        commitsGen++
        releasesGen++
        _state.update {
            it.copy(
                manualRefresh = !silent,
                snapshotLoading = true,
                banner = null,
                commitsLoading = false, commitsReloading = true, commitsEnd = false, commitsFailed = false,
                releasesLoading = false, releasesReloading = true, releasesEnd = false, releasesFailed = false,
            )
        }
        loadCommits()
        loadReleases()
        viewModelScope.launch {
            val error = repository.refreshRepo(repoId)
            _state.update { it.copy(snapshotLoading = false, banner = error).withManualRefresh() }
        }
    }

    fun loadCommits() {
        val current = _state.value
        if (current.commitsLoading || current.commitsEnd) return
        val page = commitsPage
        val gen = commitsGen
        _state.update { it.copy(commitsLoading = true, commitsFailed = false) }
        viewModelScope.launch {
            val data = try {
                repository.commits(repoId, page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (gen != commitsGen) return@launch
            if (data != null) commitsPage = page + 1
            _state.update {
                it.copy(
                    commits = mergePage(it.commits, page, data) { entry -> entry.sha },
                    commitsLoading = false, commitsReloading = false,
                    commitsFailed = data == null, commitsEnd = data != null && data.size < COMMITS_PER_PAGE,
                ).withManualRefresh()
            }
        }
    }

    fun loadReleases() {
        val current = _state.value
        if (current.releasesLoading || current.releasesEnd) return
        val page = releasesPage
        val gen = releasesGen
        _state.update { it.copy(releasesLoading = true, releasesFailed = false) }
        viewModelScope.launch {
            val data = try {
                repository.releases(repoId, page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (gen != releasesGen) return@launch
            if (data != null) releasesPage = page + 1
            _state.update {
                it.copy(
                    releases = mergePage(it.releases, page, data) { entry -> entry.tagName },
                    releasesLoading = false, releasesReloading = false,
                    releasesFailed = data == null, releasesEnd = data != null && data.size < RELEASES_PER_PAGE,
                ).withManualRefresh()
            }
        }
    }

    fun selectAsset(releaseKey: String, asset: ApkAsset) {
        _state.update { it.copy(selectedAssets = it.selectedAssets + (releaseKey to asset)) }
    }

    /** 下载 APK → App 级宿主（离开详情页不中断；完成落库/自动关联/自动安装均在宿主） */
    fun downloadAsset(release: ReleaseRecord, asset: ApkAsset, fromHeader: Boolean = false) {
        markVisibleActivity(releaseTags = setOf(release.tagName))
        downloadHost.download(
            repoId = repoId,
            release = release,
            asset = asset,
            fromHeader = fromHeader,
            onAutoLinked = { displayName ->
                viewModelScope.launch { _events.send(Event.AutoLinked(displayName)) }
            },
        )
    }

    /** 取消下载：停任务 + 清半成品，状态归位让动作区回到「下载」相 */
    fun cancelDownload() = downloadHost.cancel(repoId)

    fun retryDownload(release: ReleaseRecord, asset: ApkAsset) = downloadAsset(release, asset)

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
        val tag = s.snapshot?.currentReleaseTag ?: return
        val release = s.releases.find { it.tagName == tag } ?: run {
            setTab(DetailTab.RELEASES)
            return
        }
        val (autoPick, _) = io.candy.tick.util.ApkPicker.pick(
            release.apkAssets, android.os.Build.SUPPORTED_ABIS.toList(),
        )
        val asset = release.apkAssets.find { it.name == s.selectedAssets[release.tagName]?.name } ?: autoPick ?: run {
            setTab(DetailTab.RELEASES) // 该 release 无 APK 资产
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
        if (!file.isFile) {
            viewModelScope.launch {
                downloadHost.deleteRecord(repoId, record)
                _events.send(Event.DownloadMissing)
            }
            return false
        }
        installManager.installApk(file, repoId, record.displayAssetName)
        return true
    }

    /** 删除已下载的 APK（文件 + 记录；会话态一并清空，避免残留入口） */
    fun deleteDownloaded() {
        val record = _state.value.repo?.downloadedApk ?: return
        viewModelScope.launch {
            downloadHost.deleteRecord(repoId, record)
        }
    }

    /** 关联 / 解除；newLabel 供手选与建议路径传入（自定义备注由 autoNote 标记保护） */
    fun setLinkedApp(packageName: String?, newLabel: String? = null) {
        viewModelScope.launch {
            if (packageName == null) {
                val repo = _state.value.repo ?: return@launch
                val previous = repo.linkedPackage ?: return@launch
                // Prepare the next row while its current linked app is still visible.
                resolveSuggestion(repo.repo)
                repository.setLinkedPackage(repoId, null, expectedPackage = previous)
            } else {
                repository.setLinkedPackage(repoId, packageName, newLabel)
            }
        }
    }

    /** 详情页删除入口（确认对话框后调用；下载的 APK 文件保留） */
    fun removeRepo(onRemoved: () -> Unit) {
        // 退订清场：撤掉在跑的下载任务（否则孤儿 job 会继续下完并给已退订仓库自动装包）
        downloadHost.clear(repoId)
        viewModelScope.launch {
            repository.removeRepo(repoId)
            onRemoved()
        }
    }

    fun dismissBanner() = _state.update { it.copy(banner = null) }
}


/** A new first page replaces the snapshot (including an empty page). Later pages can overlap
 * when new commits/releases are published between requests. */
internal fun <T, K> mergePage(previous: List<T>, page: Int, received: List<T>?, key: (T) -> K): List<T> {
    if (received == null) return previous
    return (if (page == 1) received else previous + received).distinctBy(key)
}
