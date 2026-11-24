package io.candy.tick.ui.list

import io.candy.tick.data.RepoDownloadHost
import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.candy.tick.CandyTickApp
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.SourceError
import io.candy.tick.data.SyncStatus
import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.RepoSnapshot
import io.candy.tick.data.model.RepoStatus
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.data.model.statusAt
import io.candy.tick.util.VersionCompare
import io.candy.tick.util.installedVersionName
import java.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** 同一轮同步可以跨页面继续工作，标题只在首次看到它的那次停留中播放。 */
internal class HomeTitleSync {
    var interruptedRun by mutableStateOf<SyncStatus.Running?>(null)
        private set
    var pullOwnedRun by mutableStateOf<SyncStatus.Running?>(null)

    fun leave(status: SyncStatus) {
        when (status) {
            is SyncStatus.Running -> interruptedRun = status
            is SyncStatus.Done -> interruptedRun = status.run
            SyncStatus.Idle -> Unit
        }
    }

    fun shouldAnimate(status: SyncStatus): Boolean =
        status is SyncStatus.Running && status !== interruptedRun && status !== pullOwnedRun
}

class ListViewModel(
    private val repository: RepoRepository,
    private val downloadHost: RepoDownloadHost,
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
        val installedVersion: String? = null,
    ) {
        private val releaseComparison = snapshot?.currentReleaseTag?.let { tag ->
            installedVersion?.let { VersionCompare.compare(tag, it) }
        }
        val updateAvailable get() = releaseComparison != null && releaseComparison > 0
        fun comparisonFor(tag: String): Int? = if (tag == snapshot?.currentReleaseTag) releaseComparison
            else installedVersion?.let { VersionCompare.compare(tag, it) }
        val status: RepoStatus get() = snapshot?.statusAt() ?: RepoStatus.UNKNOWN
        private val commitDate = snapshot?.lastCommitDate
        val releaseIsLatest = snapshot?.latestApk?.publishedAt?.let { commitDate == null || it >= commitDate } == true
        val activityAt: Instant? = if (releaseIsLatest) snapshot?.latestApk?.publishedAt else commitDate ?: snapshot?.pushedAt
    }

    enum class CardLoad { IDLE, INITIAL, USER_REFRESH, BACKGROUND }

    @Immutable
    data class CardExtras(
        val addedAt: Instant,
        val commitBranch: String?,
        val commits: List<CommitRecord>? = null,
        val latestRelease: ReleaseRecord? = null,
        val load: CardLoad = CardLoad.IDLE,
        val failed: Boolean = false,
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
        val cards: Map<Long, CardExtras> = emptyMap(),
    )

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
    private val cardJobs = mutableMapOf<Long, Job>()

    // 展开时 stale 重拉的会话级节流：拉过一次不再因「有新版」重复拉（配额敏感）；
    // 新活动（changed）或新一轮刷新会重新解锁
    private val silentLoaded = MutableStateFlow<Set<Long>>(emptySet())

    // ON_RESUME 递增：安装完成后重算 updatableIds/版本行（本机版本读 PM 非响应式）
    private val resumeTick = MutableStateFlow(0)

    private val installedVersions = combine(
        repository.state.map { state -> state.repos.mapNotNull { it.linkedPackage }.toSet() }.distinctUntilChanged(),
        resumeTick,
    ) { packages, _ ->
        packages.associateWith { pkg -> installedVersionName(context, pkg) }
    }.flowOn(Dispatchers.IO)


    // If the remote head changes, refresh any cached preview the next time it is shown.
    private var lastSnapshots: Map<Long, RepoSnapshot> = emptyMap()

    /** 自动同步信号（打开即同步 / 定时）：首页细线 + 结果胶囊消费 */
    private val workerSync = (context.applicationContext as CandyTickApp).container.syncStatus
    private val homeSync = MutableStateFlow(workerSync.value)
    val syncStatus: StateFlow<SyncStatus> = homeSync
    internal val titleSync = HomeTitleSync()
    // Main-thread presentation state, retained for the same back-stack entry across STOP/START.
    internal var displayedHomeOrder: HomeListOrder? = null

    init {
        viewModelScope.launch {
            workerSync.collectLatest { status ->
                // The worker owns repository metadata. An expanded card may still be fetching the
                // messages for that SHA. Keep this page's indicator until those rows settle too.
                // 后台整轮同步全成功时收起旧的失败横幅（横幅只在手动刷新路径赋值）。
                // 必须在 awaitExpandedPreviews 之前：collectLatest 会被下一状态取消，挂起之后清理可能被跳过
                if (status is SyncStatus.Done && status.failed == 0) banner.value = null
                if (status is SyncStatus.Done) awaitExpandedPreviews()
                if (status is SyncStatus.Running && refreshing.value) titleSync.pullOwnedRun = status
                homeSync.value = status
            }
        }
        viewModelScope.launch {
            (context.applicationContext as CandyTickApp).container.installManager.packageInstalled.collect { pkg ->
                if (repository.state.value.repos.any { it.linkedPackage == pkg }) onAppResume()
            }
        }
        viewModelScope.launch {
            repository.state.collect { newState ->
                val repos = newState.repos.associateBy { it.id }
                val cached = cards.value
                val kept = cached.filter { (id, card) ->
                    val repo = repos[id]
                    repo != null && card.addedAt == repo.addedAt && card.commitBranch == (repo.branch ?: newState.snapshots[id]?.defaultBranch)
                }
                cards.value = kept
                expanded.value = expanded.value.intersect(repos.keys)
                silentLoaded.value = silentLoaded.value.intersect(kept.keys)
                // 从详情页退订不经 remove()：同 id 仓库之后重新订阅时不得继承旧的失败角标
                if (repoErrors.value.keys.any { it !in repos }) repoErrors.value = repoErrors.value.filterKeys { it in repos }
                for (id in expanded.value) {
                    if (id in cached && id !in kept) loadCard(id)
                }
                for ((id, snapshot) in newState.snapshots) {
                    val head = snapshot.lastCommitSha ?: continue
                    if (head == lastSnapshots[id]?.lastCommitSha) continue
                    val card = cards.value[id] ?: continue
                    if (card.commits != null && card.commits.firstOrNull()?.sha.orEmpty() == head) continue
                    silentLoaded.value -= id
                    if (id in expanded.value) loadCard(id, background = true, knownHead = head)
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
        val installed = versions[repo.linkedPackage]?.let { VersionCompare.installedName(it, repo) }
        repo.id to RepoListItem(repo, snapshot, error = null, installedVersion = installed)
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
    val state: StateFlow<UiState> = combine(ordered, feedback, expanded, cards) { list, (loading, error), expandedIds, previews ->
        list.copy(
            refreshing = loading,
            banner = error,
            expanded = expandedIds,
            cards = previews,
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

    private fun sortedItems(
        rows: OrderRows, query: String, errors: Map<Long, SourceError>,
    ): List<RepoListItem> {
        val q = query.trim()
        val matching = if (q.isEmpty()) rows.live.values else rows.live.values.filter { (repo) ->
            "${repo.owner}/${repo.repo}".contains(q, ignoreCase = true) ||
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
                val activeSync = workerSync.value as? SyncStatus.Running ?: syncStatus.value as? SyncStatus.Running
                if (activeSync != null) {
                    titleSync.pullOwnedRun = activeSync
                    expanded.value.filter { cards.value[it]?.failed == true }
                        .mapNotNull { loadCard(it, background = true) }
                        .joinAll()
                } else {
                    refreshRepositories()
                }
                // A worker can start or finish while this pull is waiting for a card.
                do {
                    val status = workerSync.value
                    if (status is SyncStatus.Running) {
                        titleSync.pullOwnedRun = status
                        workerSync.first { it !== status }
                    } else {
                        awaitExpandedPreviews()
                    }
                } while (workerSync.value !== status)
            } finally {
                refreshing.value = false
            }
        }
    }

    private suspend fun refreshRepositories() {
        // 不在刷新开头清横幅：重试期间横幅先消失再回来 = 闪烁。
        // 手动刷新成功由本函数收尾赋值清空；后台整轮成功在 worker 完成处清（见 init）
        silentLoaded.value = emptySet()
        // A pre-existing card request may only be reading commits. Delegate the release
        // check solely to the full previews started by this pull.
        val newPreviews = expanded.value - cardJobs.keys
        val previews = expanded.value.mapNotNull { id ->
            loadCard(id, background = true)?.let { id to it }
        }.toMap()
        val errors = try {
            repository.refreshAll(previews.keys.intersect(newPreviews))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (errors != null) {
            repoErrors.value = errors
            val total = repository.state.value.repos.size
            banner.value = if (errors.isNotEmpty() && errors.size >= total) {
                errors.values.firstOrNull()
            } else {
                errors.values.firstOrNull { it is SourceError.RateLimited }
            }
        } else {
            // 整轮管线异常：保留旧错误表、按网络类横幅提示——不能清空错误表伪装成刷新成功
            banner.value = SourceError.NetworkUnreachable
        }
        previews.values.joinAll()
    }

    fun markReleaseSeen(id: Long, tag: String) {
        viewModelScope.launch {
            // 展开面板的版本行呈现的是「当前版本状态」：看到它即视为看过更早的全部未读
            // release——此前只清最新一个，积压的未读必须进详情页才能清掉
            val unread = repository.state.value.snapshots[id]?.activity?.unreadReleaseTags.orEmpty()
            val tags = if (tag in unread) unread else setOf(tag)
            repository.markActivityRead(id, releaseTags = tags, visibleReleaseHead = tag)
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
    fun retryCard(id: Long) = loadCard(id)

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
            if (cards.value[id]?.commits == null || id !in silentLoaded.value) loadCard(id)
        }
    }

    private suspend fun awaitExpandedPreviews() {
        val stored = repository.state.value
        expanded.value.mapNotNull { id ->
            val repo = stored.repos.firstOrNull { it.id == id } ?: return@mapNotNull null
            val snapshot = stored.snapshots[id]
            val head = snapshot?.lastCommitSha
            val card = cards.value[id]?.takeIf {
                it.addedAt == repo.addedAt && it.commitBranch == (repo.branch ?: snapshot?.defaultBranch)
            }
            when {
                card == null -> loadCard(id, background = true, knownHead = head)
                id in cardJobs -> cardJobs[id]
                head != null && (!card.failed || head != lastSnapshots[id]?.lastCommitSha) &&
                    (card.commits == null || card.commits.firstOrNull()?.sha.orEmpty() != head) ->
                    loadCard(id, background = true, knownHead = head)
                else -> null
            }
        }.joinAll()
        // A branch switch can replace a job while the original requests are settling.
        while (true) {
            val pending = expanded.value.mapNotNull { cardJobs[it] }
            if (pending.isEmpty()) return
            pending.joinAll()
        }
    }

    private fun loadCard(id: Long, background: Boolean = false, knownHead: String? = null): Job? {
        val current = repository.state.value
        val repo = current.repos.firstOrNull { it.id == id } ?: return null
        val branch = repo.branch ?: current.snapshots[id]?.defaultBranch
        val old = cards.value[id]?.takeIf { it.addedAt == repo.addedAt && it.commitBranch == branch }
            ?: CardExtras(addedAt = repo.addedAt, commitBranch = branch)
        if (old.load != CardLoad.IDLE) return cardJobs[id]
        val load = when {
            old.commits == null -> CardLoad.INITIAL
            background -> CardLoad.BACKGROUND
            else -> CardLoad.USER_REFRESH
        }
        val pending = old.copy(load = load, failed = false)
        cards.value = cards.value + (id to pending)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                var requestedHead = knownHead ?: current.snapshots[id]?.lastCommitSha
                val commitsJob = async {
                    fetchPreview {
                        if (knownHead == null) repository.commits(id, 1) else repository.previewCommitsAt(id, knownHead)
                    }
                }
                // A SHA-only change needs no release query: the version row observes the snapshot.
                val releasesJob = if (knownHead == null) async { fetchPreview { repository.releases(id, 1) } } else null
                var commits = commitsJob.await()
                val releases = releasesJob?.await()
                while (cards.value[id] === pending) {
                    val latest = repository.state.value
                    val tracked = latest.repos.firstOrNull { it.id == id } ?: return@launch
                    val snapshot = latest.snapshots[id]
                    if (tracked.addedAt != repo.addedAt || (tracked.branch ?: snapshot?.defaultBranch) != branch) return@launch
                    val head = snapshot?.lastCommitSha
                    val stale = head != null && head != requestedHead &&
                        (commits == null || commits.firstOrNull()?.sha.orEmpty() != head)
                    if (stale && id in expanded.value) {
                        // Only a new target can cause another read. Don't let a late preview move
                        // the repository's head backwards.
                        requestedHead = head
                        commits = fetchPreview { repository.previewCommitsAt(id, head) }
                        continue
                    }
                    val failed = commits == null || (releasesJob != null && releases == null)
                    if (!failed && !stale) silentLoaded.value += id
                    cards.value += id to CardExtras(
                        addedAt = repo.addedAt,
                        commitBranch = branch,
                        commits = commits ?: old.commits,
                        latestRelease = if (releases == null) old.latestRelease else releases.firstOrNull { it.apkAssets.isNotEmpty() },
                        failed = failed,
                    )
                    break
                }
            } finally {
                if (cardJobs[id] === coroutineContext.job) cardJobs.remove(id)
            }
        }
        cardJobs[id] = job
        job.start()
        return job
    }

    private suspend fun <T> fetchPreview(fetch: suspend () -> T): T? = try {
        fetch()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** 乐观删除 + 撤销事件（Snackbar）；撤销 restore 不需要恢复下载会话 */
    fun remove(item: RepoListItem) {
        downloadHost.clear(item.repo.id) // 退订清场：撤掉在跑的下载任务（与详情页删除同口径）
        // 撤销恢复带回同 id 仓库时不该继承旧错误角标
        repoErrors.value = repoErrors.value - item.repo.id
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
