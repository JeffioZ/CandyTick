package io.candy.tick.ui.list

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.candy.tick.CandyTickApp
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.SnapshotDiff
import io.candy.tick.data.SourceError
import io.candy.tick.data.SyncStatus
import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.RepoSnapshot
import io.candy.tick.data.model.RepoStatus
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.data.model.statusAt
import io.candy.tick.util.VersionCompare
import java.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** 同一轮同步可以跨页面继续工作，标题只在首次看到它的那次停留中播放。 */
internal class HomeTitleSync {
    var interruptedRun by mutableStateOf<SyncStatus.Running?>(null)
        private set

    fun leave(status: SyncStatus) {
        when (status) {
            is SyncStatus.Running -> interruptedRun = status
            is SyncStatus.Done -> interruptedRun = status.run
            SyncStatus.Idle -> Unit
        }
    }

    fun shouldAnimate(status: SyncStatus): Boolean =
        status is SyncStatus.Running && status !== interruptedRun
}

class ListViewModel(
    private val repository: RepoRepository,
    private val downloadHost: io.candy.tick.ui.detail.RepoDownloadHost,
    private val context: Application,
) : ViewModel() {

    // @Immutable（含 List 字段的值对象承诺只读）：RepoListItem/CardExtras 稳定后，
    // 下载进度 tick / 搜索输入 / 展开切换等任意 state 变化下，自身参数未变的卡片
    // 可被 Compose equals 跳过重组——列表主路径的重组收敛点
    @Immutable
    data class RepoListItem(
        val repo: TrackedRepo,
        val snapshot: RepoSnapshot?,
        val error: SourceError?,
        val updateAvailable: Boolean = false,
    ) {
        val status: RepoStatus get() = snapshot?.statusAt() ?: RepoStatus.UNKNOWN
        private val commitDate = snapshot?.lastCommitDate
        val releaseIsLatest = snapshot?.latestApk?.publishedAt?.let { commitDate == null || it >= commitDate } == true
        val activityAt: Instant? = if (releaseIsLatest) snapshot?.latestApk?.publishedAt else commitDate ?: snapshot?.pushedAt
    }

    @Immutable
    data class CardExtras(
        val commitBranch: String?,
        val commits: List<CommitRecord>? = null,
        val latestRelease: ReleaseRecord? = null,
        val loading: Boolean = false,
        val failed: Boolean = false,
        val refreshing: Boolean = false, // 静默重拉中（旧数据照常显示，展开面板给轻提示）
    )

    @Immutable
    data class UiState(
        val items: List<RepoListItem> = emptyList(),
        val refreshing: Boolean = false,
        val sortOrder: SortOrder = SortOrder(),
        val query: String = "",
        val banner: SourceError? = null,
        val expanded: Set<Long> = emptySet(),
        val updatableIds: Set<Long> = emptySet(), // 有可比新版的仓库（折叠卡直显标识）
        val versionEpoch: Int = 0, // ON_RESUME 递增：破除版本行/对比的重组跳过
        val cards: Map<Long, CardExtras> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = items.isEmpty() && !refreshing && query.isBlank()
    }

    sealed interface Event {
        data class Removed(val repo: TrackedRepo, val snapshot: RepoSnapshot?) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val query = MutableStateFlow("")
    private val refreshing = MutableStateFlow(false)
    internal val manualRefresh: StateFlow<Boolean> = refreshing
    private val banner = MutableStateFlow<SourceError?>(null)
    private val repoErrors = MutableStateFlow<Map<Long, SourceError>>(emptyMap())
    private val expanded = MutableStateFlow<Set<Long>>(emptySet())
    private val cards = MutableStateFlow<Map<Long, CardExtras>>(emptyMap())

    // 展开时 stale 重拉的会话级节流：拉过一次不再因「有新版」重复拉（配额敏感）；
    // 新活动（changed）或新一轮刷新会重新解锁
    private val silentLoaded = MutableStateFlow<Set<Long>>(emptySet())

    // ON_RESUME 递增：安装完成后重算 updatableIds/版本行（本机版本读 PM 非响应式）
    private val resumeTick = MutableStateFlow(0)

    private val installedVersions = combine(
        repository.state.map { state -> state.repos.mapNotNull { it.linkedPackage }.toSet() }.distinctUntilChanged(),
        resumeTick,
    ) { packages, _ ->
        packages.associateWith { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()
        }
    }.flowOn(Dispatchers.IO)


    // If the remote head changes, refresh any cached preview the next time it is shown.
    private var lastSnapshots: Map<Long, RepoSnapshot> = emptyMap()

    /** 自动同步信号（打开即同步 / 定时）：首页细线 + 结果胶囊消费 */
    val syncStatus: kotlinx.coroutines.flow.StateFlow<SyncStatus> =
        (context.applicationContext as CandyTickApp).container.syncStatus
    internal val titleSync = HomeTitleSync()
    // Main-thread presentation state, retained for the same back-stack entry across STOP/START.
    internal var displayedHomeOrder: HomeListOrder? = null

    init {
        viewModelScope.launch {
            (context.applicationContext as CandyTickApp).container.installManager.packageInstalled.collect { pkg ->
                if (repository.state.value.repos.any { it.linkedPackage == pkg }) onAppResume()
            }
        }
        viewModelScope.launch {
            repository.state.collect { newState ->
                val branches = newState.repos.associate { it.id to (it.branch ?: newState.snapshots[it.id]?.defaultBranch) }
                val cached = cards.value
                val kept = cached.filter { (id, card) -> id in branches && card.commitBranch == branches[id] }
                cards.value = kept
                expanded.value = expanded.value.intersect(branches.keys)
                silentLoaded.value = silentLoaded.value.intersect(kept.keys)
                for (id in expanded.value) {
                    if (id in cached && id !in kept) loadCard(id)
                }
                if (lastSnapshots.isNotEmpty()) {
                    val newChanged = newState.snapshots.filterKeys { id ->
                        val old = lastSnapshots[id] ?: return@filterKeys false
                        val newSnap = newState.snapshots[id] ?: return@filterKeys false
                        // 发布回退也应刷新展开面板的版本，未读由数据层跟踪。
                        old.currentReleaseTag != newSnap.currentReleaseTag || SnapshotDiff.commitChanged(old, newSnap.lastCommitSha)
                    }.keys
                    if (newChanged.isNotEmpty()) {
                        silentLoaded.value = silentLoaded.value - newChanged
                        newChanged.filter { it in expanded.value }
                            .filter { cards.value[it]?.commits != null } // 首次加载都没完成的跳过（走正常路径）
                            .forEach { loadCard(it, silent = true) }
                    }
                }
                lastSnapshots = newState.snapshots
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.Default) {
            // 自动备注刷新：应用改名后标题跟随（自定义备注 autoNote=false 永不动）；
            // 旧数据无标记时，值恰等于当前应用名的收编为自动值
            repository.state.value.repos.forEach { tracked ->
                val pkg = tracked.linkedPackage ?: return@forEach
                val label = io.candy.tick.util.labelOf(context, pkg) ?: return@forEach
                when {
                    tracked.autoNote && label != tracked.noteName ->
                        repository.refreshAppLabel(tracked, label)
                    // 收编无标记旧数据：备注为空（旧版手动关联从不写备注）或值恰等于当前应用名
                    !tracked.autoNote && (tracked.noteName == null || tracked.noteName == label) ->
                        repository.refreshAppLabel(tracked, label)
                    // 其余 = 异机导入的旧自动值或标签不可得：不动
                }
            }
        }
    }

    private data class OrderRows(
        val live: Map<Long, RepoListItem>,
        val forSorting: Map<Long, RepoListItem>,
        val order: SortOrder,
    )

    private fun rows(
        repos: List<TrackedRepo>,
        snapshots: Map<Long, RepoSnapshot>,
        versions: Map<String, String?>,
    ): Map<Long, RepoListItem> = repos.associate { repo ->
        val snapshot = snapshots[repo.id]
        val tag = snapshot?.currentReleaseTag
        val installed = versions[repo.linkedPackage]?.let { VersionCompare.installedName(it, repo) }
        val update = tag != null && installed != null && (VersionCompare.compare(tag, installed) ?: 0) > 0
        repo.id to RepoListItem(repo, snapshot, error = null, updateAvailable = update)
    }

    /** A network batch writes each repo separately. Display each result, but rank existing rows
     * against the last settled batch until both manual refresh and automatic sync have finished. */
    private var settledRows: Map<Long, RepoListItem>? = null
    private val batchRunning get() = refreshing.value || syncStatus.value is SyncStatus.Running
    private val orderRows = combine(repository.state, installedVersions, refreshing, syncStatus) { _, versions, _, _ ->
        // A batch may start or end during the read. Only promote a snapshot bracketed by idle states.
        val alreadyRunning = batchRunning
        val stored = repository.state.value
        val live = rows(stored.repos, stored.snapshots, versions)
        val order = SortOrder.fromStorage(stored.settings.sortMode)
        // Keep the data used for ranking across sort choices and WhileSubscribed restarts.
        // If the first observation is already mid-sync, use that one baseline until completion.
        val baseline = if (alreadyRunning || batchRunning) settledRows ?: live else live
        settledRows = baseline
        OrderRows(live, if (order.mode.usesSyncData) baseline else live, order)
    }

    private val ordered = combine(orderRows, query, repoErrors) { rows, text, errors ->
        val items = sortedItems(rows, text, errors)
        UiState(
            items = items,
            sortOrder = rows.order,
            query = text,
            updatableIds = items.filter { it.updateAvailable }.mapTo(mutableSetOf()) { it.repo.id },
        )
    }
    private val feedback = combine(refreshing, banner) { loading, error -> loading to error }
    private val extras = combine(expanded, cards, resumeTick) { exp, crds, tick ->
        CardState(exp, crds, tick)
    }

    val state: StateFlow<UiState> = combine(ordered, feedback, extras) { list, (loading, error), info ->
        list.copy(
            refreshing = loading,
            banner = error,
            expanded = info.expanded,
            versionEpoch = info.versionEpoch,
            cards = info.cards,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialUiState())

    private fun initialUiState(): UiState {
        val stored = repository.state.value
        val live = rows(stored.repos, stored.snapshots, emptyMap())
        val order = SortOrder.fromStorage(stored.settings.sortMode)
        return UiState(
            items = sortedItems(OrderRows(live, live, order), "", emptyMap()),
            sortOrder = order,
        )
    }

    private data class CardState(
        val expanded: Set<Long>,
        val cards: Map<Long, CardExtras>,
        val versionEpoch: Int,
    )

    private fun sortedItems(
        rows: OrderRows, query: String, errors: Map<Long, SourceError>,
    ): List<RepoListItem> {
        val q = query.trim()
        val matching = if (q.isEmpty()) rows.live.values else rows.live.values.filter { (repo) ->
            repo.repo.contains(q, ignoreCase = true) || repo.owner.contains(q, ignoreCase = true) ||
                repo.noteName?.contains(q, ignoreCase = true) == true
        }
        val base = rows.order.comparator()
        val byRank = if (rows.live === rows.forSorting) base else Comparator { a, b ->
            base.compare(rows.forSorting[a.repo.id] ?: a, rows.forSorting[b.repo.id] ?: b)
        }
        // Pins follow the current store even during refresh. Search intentionally ignores pins.
        val order = if (q.isEmpty()) compareByDescending<RepoListItem> { it.repo.pinnedAt }.then(byRank) else byRank
        return matching.sortedWith(order).map { item ->
            errors[item.repo.id]?.let { item.copy(error = it) } ?: item
        }
    }

    /** 下拉刷新：单仓库失败只做角标，全部失败才出横幅（旧数据保留） */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                if (syncStatus.value is SyncStatus.Running) {
                    // An in-flight sync is already refreshing the same repositories.
                    kotlinx.coroutines.withTimeoutOrNull(30_000) {
                        syncStatus.first { it !is SyncStatus.Running }
                    }
                    return@launch
                }
                banner.value = null
                silentLoaded.value = emptySet()
                val cardJobs = expanded.value.mapNotNull { id ->
                    if (cards.value[id]?.commits == null) null else loadCard(id, silent = true)?.let { id to it }
                }.toMap()
                val errors = try {
                    repository.refreshAll(cardJobs.keys)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyMap()
                }
                repoErrors.value = errors
                val total = repository.state.value.repos.size
                banner.value = if (errors.isNotEmpty() && errors.size >= total) {
                    errors.values.firstOrNull()
                } else {
                    errors.values.firstOrNull { it is SourceError.RateLimited }
                }
                cardJobs.values.joinAll()
            } finally {
                refreshing.value = false
            }
        }
    }

    fun markReleaseSeen(id: Long, tag: String) {
        viewModelScope.launch {
            repository.markActivityRead(id, releaseTags = setOf(tag), visibleReleaseHead = tag)
        }
    }

    fun markCommitSeen(id: Long, sha: String, isHead: Boolean) {
        viewModelScope.launch {
            repository.markActivityRead(id, commitShas = setOf(sha), visibleCommitHead = sha.takeIf { isHead })
        }
    }

    /** 手动关闭刷新失败横幅（此前只能等下次刷新清掉） */
    fun clearBanner() {
        banner.value = null
    }

    /** 展开卡数据拉取失败后的显式重试（此前恢复路径=收起再展开，不可发现） */
    fun retryCard(id: Long) = loadCard(id, silent = cards.value[id]?.commits != null)

    /** 通知开关开着但权限未授：首页上下文请求一次的依据 */
    val shouldRequestNotificationPermission: Boolean
        get() = repository.state.value.settings.notificationsEnabled &&
            !io.candy.tick.data.Notifications.canNotify(context)

    internal val storedSortOrder: SortOrder get() = SortOrder.fromStorage(repository.state.value.settings.sortMode)

    internal fun setSortOrder(order: SortOrder) {
        // Publish each tap to the store on the main thread before the disk write suspends.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.updateSettings { it.copy(sortMode = order.storageKey) }
        }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    /** 从安装器返回（ON_RESUME）：递增 epoch 重算「有新版」标识与版本行 */
    fun onAppResume() {
        resumeTick.value++
    }

    /** 一键收起全部展开卡（批量收起各自播放收起动画） */
    fun collapseAll() {
        expanded.value = emptySet()
    }

    fun toggleExpand(id: Long) {
        val next = expanded.value.toMutableSet()
        if (!next.add(id)) next.remove(id)
        expanded.value = next
        if (id in next) {
            val cached = cards.value[id]
            when {
                cached?.commits == null && cached?.loading != true -> loadCard(id)
                // 缓存过期：快照层提示过新活动/新版（如 SyncWorker 后台刷完回前台），
                // 展开必须重拉——否则提示与展开内容不符（展开的还是旧提交/旧版本）
                id !in silentLoaded.value -> loadCard(id, silent = true)
            }
        }
    }

    private fun loadCard(id: Long, silent: Boolean = false): Job? {
        val current = repository.state.value
        val repo = current.repos.firstOrNull { it.id == id } ?: return null
        val branch = repo.branch ?: current.snapshots[id]?.defaultBranch
        val old = cards.value[id]?.takeIf { it.commitBranch == branch } ?: CardExtras(commitBranch = branch)
        if (old.loading || old.refreshing) return null
        val pending = old.copy(loading = !silent, refreshing = silent, failed = false)
        cards.value = cards.value + (id to pending)
        return viewModelScope.launch {
            val commitsJob = async { runCatching { repository.commits(id, 1) }.getOrNull() }
            val releasesJob = async { runCatching { repository.releases(id, 1) }.getOrNull() }
            val commits = commitsJob.await()
            val releases = releasesJob.await()
            if (cards.value[id] !== pending) return@launch
            val failed = commits == null || releases == null
            if (!failed) {
                silentLoaded.value = silentLoaded.value + id
            }
            cards.value = cards.value + (id to CardExtras(
                commitBranch = branch,
                commits = commits ?: old.commits,
                latestRelease = if (releases == null) old.latestRelease else releases.firstOrNull { it.apkAssets.isNotEmpty() },
                failed = failed,
            ))
        }
    }

    /** 乐观删除 + 撤销事件（Snackbar）；撤销 restore 不需要恢复下载会话 */
    fun remove(item: RepoListItem) {
        downloadHost.clear(item.repo.id) // 退订清场：撤掉在跑的下载任务（与详情页删除同口径）
        viewModelScope.launch {
            repository.removeRepo(item.repo.id)
            _events.send(Event.Removed(item.repo, item.snapshot))
        }
    }

    fun restore(repo: TrackedRepo, snapshot: RepoSnapshot?) {
        viewModelScope.launch { repository.restoreRepo(repo, snapshot) }
    }

    /** 置顶 / 取消置顶（卡片左滑 toggle；位置跳变 + 图钉出现即反馈） */
    fun togglePin(item: RepoListItem) {
        viewModelScope.launch { repository.togglePinned(item.repo.id) }
    }
}
