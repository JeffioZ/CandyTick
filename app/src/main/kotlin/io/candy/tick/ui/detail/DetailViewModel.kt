package io.candy.tick.ui.detail

import io.candy.tick.data.RepoDownloadState
import io.candy.tick.data.RepoDownloadHost
import android.content.Context
import android.util.LruCache
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
import io.candy.tick.data.model.DownloadedApk
import java.io.File
import java.time.Instant
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
        val commitsPagingPaused: Boolean = false,
        val releases: List<ReleaseRecord> = emptyList(),
        val notificationRelease: ReleaseRecord? = null,
        val releasesLoading: Boolean = false,
        val releasesEnd: Boolean = false,
        val releasesFailed: Boolean = false,
        val releasesPagingPaused: Boolean = false,
        // page1 重载进行中（仅 refresh 触发）：footer 用它压掉与刷新头重复的小点，
        // 完成即清。翻页加载（load-more）不走这个标记，小点照常
        val commitsReloading: Boolean = false,
        val releasesReloading: Boolean = false,
        val download: RepoDownloadState? = null,
        val selectedAssets: Map<String, ApkAsset> = emptyMap(), // releaseKey(tag) → 手选覆盖
        val branches: List<BranchInfo>? = null, // null until a successful response, which may be empty
        val branchesLoading: Boolean = false,
        val branchesFailed: Boolean = false,
        val nextBranchPage: Int? = 1,
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
            commits = emptyList(), commitsEnd = false, commitsFailed = false, commitsPagingPaused = false,
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
        val tracked = current.repos.find { it.id == repoId }
        val snapshot = current.snapshots[repoId]
        val cache = tracked?.let { DetailHistoryCache.pages(it) }
        val commits = cache?.takeIf { it.branch == (tracked.branch ?: snapshot?.defaultBranch) }?.commits
        val releases = cache?.releases
        UiState(
            repo = tracked, snapshot = snapshot, tab = initialTab,
            commits = commits.orEmpty(), commitsEnd = commits != null && commits.size < COMMITS_PER_PAGE,
            releases = releases.orEmpty(), releasesEnd = releases != null && releases.size < RELEASES_PER_PAGE,
        )
    })
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val appContext: Context = context.applicationContext
    private val apkDir: File = File(context.filesDir, "apks/$repoId")
    private val commitCursor = HistoryCursor(COMMITS_PER_PAGE, CommitRecord::sha)
    private val releaseCursor = HistoryCursor(RELEASES_PER_PAGE, ReleaseRecord::tagName)
    private var commitsGen = 0 // 提交列表代数：切分支/刷新时 +1，迟到的旧响应作废
    private var releasesGen = 0 // 版本列表代数：刷新时 +1，迟到的旧页响应作废
    // Main-thread requests update their cursors independently. During a pull, only their
    // network results are held; downloads, installation and user choices stay live in _state.
    private var pendingPull: UiState? = null

    private fun updateHistory(change: (UiState) -> UiState) {
        val shown = _state.value
        val pending = pendingPull
        val next = change(pending ?: shown)
        if (next.refreshing) {
            pendingPull = next
            _state.value = shown.copy(
                manualRefresh = true, snapshotLoading = true,
                commitsReloading = true, commitsLoading = !shown.commitsEnd && !shown.commitsFailed,
                releasesReloading = true, releasesLoading = !shown.releasesEnd && !shown.releasesFailed,
            )
        } else if (pending != null) {
            pendingPull = null
            val store = repository.state.value
            _state.value = shown.copy(
                repo = store.repos.firstOrNull { it.id == repoId }, snapshot = store.snapshots[repoId],
                manualRefresh = false, snapshotLoading = next.snapshotLoading, banner = next.banner,
                commits = next.commits, commitsEnd = next.commitsEnd, commitsFailed = next.commitsFailed,
                commitsLoading = next.commitsLoading, commitsReloading = next.commitsReloading, commitsPagingPaused = next.commitsPagingPaused,
                releases = next.releases, releasesEnd = next.releasesEnd, releasesFailed = next.releasesFailed,
                releasesLoading = next.releasesLoading, releasesReloading = next.releasesReloading, releasesPagingPaused = next.releasesPagingPaused,
            )
        } else {
            _state.value = next
        }
    }

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
            repository.state.collect {
                val s = repository.state.value
                val tracked = s.repos.find { it.id == repoId }
                _state.update {
                    val shown = it.repo
                    val holdOverview = it.refreshing && tracked != null && shown != null && tracked.addedAt == shown.addedAt
                    it.copy(
                        repo = if (holdOverview && (tracked.owner != shown.owner || tracked.repo != shown.repo)) {
                            tracked.copy(owner = shown.owner, repo = shown.repo)
                        } else tracked,
                        snapshot = if (holdOverview) it.snapshot else s.snapshots[repoId],
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
        refresh(silent = true)
    }

    fun setTab(newTab: DetailTab) {
        _state.update { it.copy(tab = newTab) }
    }

    fun protectReadingRows(tab: DetailTab, ids: Set<String>, headerInView: Boolean = false) {
        val current = _state.value
        val cursor = if (tab == DetailTab.COMMITS) commitCursor else releaseCursor
        val reloading = if (tab == DetailTab.COMMITS) current.commitsReloading else current.releasesReloading
        cursor.readingIds = if (reloading && !current.manualRefresh) ids else emptySet()
        cursor.headerInView = headerInView
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

    fun loadBranches() {
        if (_state.value.branches == null) loadNextBranchPage()
    }

    fun loadNextBranchPage() {
        val current = _state.value
        val page = current.nextBranchPage ?: return
        if (current.branchesLoading) return
        _state.update { it.copy(branchesLoading = true, branchesFailed = false) }
        viewModelScope.launch {
            val result = repository.branchPage(repoId, page)
            if (result == null) {
                _state.update { it.copy(branchesLoading = false, branchesFailed = true) }
                return@launch
            }
            _state.update { state ->
                val branches = (state.branches.orEmpty() + result.names.map { BranchInfo(it, null) })
                    .distinctBy { it.name }.sortedBy { it.name != state.snapshot?.defaultBranch }
                state.copy(
                    branches = branches, branchesLoading = false,
                    nextBranchPage = if (result.hasNext) page + 1 else null,
                )
            }
            val dates = repository.branchDates(repoId, result.names, firstPage = page == 1)
            if (dates.values.any { it != null }) {
                _state.update { state ->
                    state.copy(branches = state.branches?.map { row ->
                        dates[row.name]?.let { row.copy(tipDate = it) } ?: row
                    })
                }
            }
        }
    }

    /** 切换浏览分支：落库 + 重置提交列表（状态/版本仍按仓库级，不动） */
    fun setBranch(branch: String?) {
        if (_state.value.repo?.branch == branch) return
        viewModelScope.launch {
            repository.setBranch(repoId, branch)
            commitCursor.reset()
            commitsGen++
            pendingPull = pendingPull?.forNewBranch()
            _state.update { it.forNewBranch() }
            loadCommits()
        }
    }

    /** The header and both first pages are independent. Start each once for this visit,
     * keeping previous results visible until their replacements arrive. */
    fun refresh(silent: Boolean = false) {
        if ((pendingPull ?: _state.value).syncing) {
            // A user pull can take over the requests already started on entry.
            if (!silent && pendingPull == null) updateHistory {
                val commits = commitCursor.takeHeldPage()
                val releases = releaseCursor.takeHeldPage()
                if (commits != null) commitsGen++
                if (releases != null) releasesGen++
                it.copy(
                    commits = commits?.rows ?: it.commits, commitsEnd = commits?.end ?: it.commitsEnd,
                    commitsFailed = commits == null && it.commitsFailed, commitsLoading = commits == null && it.commitsLoading,
                    commitsPagingPaused = commits == null && it.commitsPagingPaused,
                    releases = releases?.rows ?: it.releases, releasesEnd = releases?.end ?: it.releasesEnd,
                    releasesFailed = releases == null && it.releasesFailed, releasesLoading = releases == null && it.releasesLoading,
                    releasesPagingPaused = releases == null && it.releasesPagingPaused,
                ).withManualRefresh(requested = true)
            }
            return
        }
        commitCursor.startReload()
        releaseCursor.startReload()
        commitsGen++
        releasesGen++
        updateHistory {
            it.copy(
                manualRefresh = !silent,
                snapshotLoading = true,
                banner = null,
                commitsLoading = false, commitsReloading = true, commitsPagingPaused = false,
                releasesLoading = false, releasesReloading = true, releasesPagingPaused = false,
            )
        }
        loadCommits()
        loadReleases()
        viewModelScope.launch {
            val error = try {
                repository.refreshRepo(repoId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                SourceError.NetworkUnreachable
            }
            updateHistory { it.copy(snapshotLoading = false, banner = error).withManualRefresh() }
        }
    }

    fun loadCommits() {
        val current = pendingPull ?: _state.value
        if (current.commitsLoading) return
        if (!current.commitsReloading && (current.commitsEnd || current.manualRefresh || commitCursor.pagingPaused)) return
        val page = if (current.commitsReloading) 1 else commitCursor.nextPage
        val gen = commitsGen
        updateHistory { it.copy(commitsLoading = true) }
        viewModelScope.launch {
            val store = repository.state.value
            val tracked = store.repos.firstOrNull { it.id == repoId }
            val branch = tracked?.branch ?: store.snapshots[repoId]?.defaultBranch
            val cache = if (page == 1 && tracked != null) DetailHistoryCache.pages(tracked) else null
            val request = cache?.let { ++it.commitRequest }
            val data = try {
                repository.commits(repoId, page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (gen != commitsGen) return@launch
            if (cache != null && cache.commitRequest == request && data != null) {
                cache.branch = branch
                cache.commits = data.take(COMMITS_PER_PAGE)
            }
            updateHistory {
                val result = commitCursor.accept(it.commits, it.commitsEnd, page, data, it.commitsReloading && !it.manualRefresh)
                it.copy(
                    commits = result.rows,
                    commitsLoading = false, commitsReloading = false,
                    commitsPagingPaused = commitCursor.pagingPaused && !result.end,
                    commitsFailed = data == null, commitsEnd = result.end,
                ).withManualRefresh()
            }
        }
    }

    fun loadReleases() {
        val current = pendingPull ?: _state.value
        if (current.releasesLoading) return
        if (!current.releasesReloading && (current.releasesEnd || current.manualRefresh || releaseCursor.pagingPaused)) return
        val page = if (current.releasesReloading) 1 else releaseCursor.nextPage
        val gen = releasesGen
        updateHistory { it.copy(releasesLoading = true) }
        viewModelScope.launch {
            val tracked = repository.state.value.repos.firstOrNull { it.id == repoId }
            val cache = if (page == 1 && tracked != null) DetailHistoryCache.pages(tracked) else null
            val request = cache?.let { ++it.releaseRequest }
            val data = try {
                repository.releases(repoId, page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (gen != releasesGen) return@launch
            if (cache != null && cache.releaseRequest == request && data != null) {
                cache.releases = data.take(RELEASES_PER_PAGE)
            }
            updateHistory {
                val result = releaseCursor.accept(it.releases, it.releasesEnd, page, data, it.releasesReloading && !it.manualRefresh)
                it.copy(
                    releases = result.rows,
                    releasesLoading = false, releasesReloading = false,
                    releasesPagingPaused = releaseCursor.pagingPaused && !result.end,
                    releasesFailed = data == null, releasesEnd = result.end,
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
    fun installDownloaded(record: DownloadedApk) {
        if (repository.state.value.repos.find { it.id == repoId }?.downloadedApk != record) return
        val file = File(apkDir, record.fileName)
        if (!file.isFile) {
            viewModelScope.launch {
                downloadHost.deleteRecord(repoId, record)
                _events.send(Event.DownloadMissing)
            }
            return
        }
        installManager.installApk(file, repoId, record.displayAssetName)
    }

    /** Only remove the file the user saw, even if a newer transfer finishes during the tap. */
    fun deleteDownloaded(record: DownloadedApk) {
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

private data class HistoryPage<T>(val rows: List<T>, val end: Boolean)

private class HistoryCursor<T>(private val pageSize: Int, private val key: (T) -> String) {
    var readingIds: Set<String> = emptySet()
    var headerInView = false
    var nextPage = 1
        private set
    var pagingPaused = false
        private set
    private var heldFirstPage: List<T>? = null
    private var needsTailAnchor = false

    fun startReload() {
        readingIds = emptySet()
        headerInView = false
        heldFirstPage = null
        needsTailAnchor = false
        pagingPaused = false
    }

    fun reset() {
        startReload()
        nextPage = 1
    }

    fun takeHeldPage(): HistoryPage<T>? {
        val received = heldFirstPage ?: return null
        startReload()
        return accept(emptyList(), false, 1, received, false)
    }

    fun accept(
        previous: List<T>, previousEnd: Boolean, page: Int, received: List<T>?, silentReload: Boolean,
    ): HistoryPage<T> {
        if (received == null) {
            nextPage = page
            return HistoryPage(previous, false)
        }
        if (needsTailAnchor && page > 1) {
            // Page numbers can shift under a reader. Only append after the actual last row.
            val tail = previous.lastOrNull()?.let(key)
            val index = received.indexOfFirst { key(it) == tail }
            if (index < 0) {
                pagingPaused = true
                return HistoryPage(previous, previousEnd)
            }
            needsTailAnchor = false
            nextPage = page + 1
            return HistoryPage((previous + received.drop(index + 1)).distinctBy(key), received.size < pageSize)
        }

        val rows = mergePage(previous, page, received, key)
        // A header-anchored list can push a visible row off-screen even when its key survives.
        val displacesReader = silentReload && readingIds.any { id ->
            val before = previous.indexOfFirst { key(it) == id }
            val after = rows.indexOfFirst { key(it) == id }
            before >= 0 && (after < 0 || previous[before] != rows[after] || headerInView && before != after)
        }
        if (displacesReader) {
            heldFirstPage = received
            // If the refreshed list has the same tail, it also tells us exactly where to resume.
            needsTailAnchor = rows.lastOrNull()?.let(key) != previous.lastOrNull()?.let(key)
            pagingPaused = received.size < pageSize
            nextPage = if (needsTailAnchor) maxOf(2, nextPage) else 2
            return HistoryPage(previous, previousEnd)
        }
        nextPage = page + 1
        return HistoryPage(rows, received.size < pageSize)
    }
}

/** Main-thread only. Null means never loaded; an empty list is a successful empty page. */
private object DetailHistoryCache {
    private data class Key(val id: Long, val addedAt: Instant, val provider: String, val owner: String, val repo: String)

    class Pages {
        var branch: String? = null
        var commits: List<CommitRecord>? = null
        var releases: List<ReleaseRecord>? = null
        // Another detail entry (e.g. opened from a notification) can start a newer first-page request.
        var commitRequest = 0L
        var releaseRequest = 0L
    }

    private val recent = LruCache<Key, Pages>(6)

    fun pages(repo: TrackedRepo): Pages {
        val key = Key(repo.id, repo.addedAt, repo.provider, repo.owner, repo.repo)
        return recent.get(key) ?: Pages().also { recent.put(key, it) }
    }
}

/** A new first page replaces the snapshot (including an empty page). Later pages can overlap
 * when new commits/releases are published between requests. */
internal fun <T, K> mergePage(previous: List<T>, page: Int, received: List<T>?, key: (T) -> K): List<T> {
    if (received == null) return previous
    return (if (page == 1) received else previous + received).distinctBy(key)
}
