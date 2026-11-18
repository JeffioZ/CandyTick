package io.candy.tick.ui.list

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SortMode { LAST_PUSH, ADDED, STATUS, NAME, NAME_DESC, UPDATABLE }

class ListViewModel(
    private val repository: RepoRepository,
    private val context: Context,
) : ViewModel() {

    data class RepoListItem(
        val repo: TrackedRepo,
        val snapshot: RepoSnapshot?,
        val error: SourceError?,
    ) {
        val status: RepoStatus get() = snapshot?.statusAt() ?: RepoStatus.UNKNOWN
    }

    data class CardExtras(
        val commits: List<CommitRecord>? = null,
        val latestRelease: ReleaseRecord? = null,
        val loading: Boolean = false,
    )

    data class UiState(
        val items: List<RepoListItem> = emptyList(),
        val refreshing: Boolean = false,
        val sortMode: SortMode = SortMode.LAST_PUSH,
        val query: String = "",
        val banner: SourceError? = null,
        val expanded: Set<Long> = emptySet(),
        val changedIds: Set<Long> = emptySet(), // 有新活动的仓库（刷新后对比）
        val cards: Map<Long, CardExtras> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = items.isEmpty() && !refreshing && query.isBlank()
    }

    sealed interface Event {
        data class Removed(val repo: TrackedRepo, val snapshot: RepoSnapshot?) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val sortMode = MutableStateFlow(
        runCatching { SortMode.valueOf(repository.state.value.settings.sortMode) }
            .getOrDefault(SortMode.LAST_PUSH)
    )
    private val query = MutableStateFlow("")
    private val refreshing = MutableStateFlow(false)
    private val banner = MutableStateFlow<SourceError?>(null)
    private val repoErrors = MutableStateFlow<Map<Long, SourceError>>(emptyMap())
    private val expanded = MutableStateFlow<Set<Long>>(emptySet())
    private val changedRepos = MutableStateFlow<Set<Long>>(emptySet())
    private val cards = MutableStateFlow<Map<Long, CardExtras>>(emptyMap())

    private val part1 = combine(repository.state, sortMode, refreshing, banner, query) { s, sort, refr, ban, q ->
        Part1(s.repos, s.snapshots, sort, refr, ban, q)
    }
    private val part2 = combine(repoErrors, expanded, cards, changedRepos) { errs, exp, crds, chg ->
        Part2(errs, exp, crds, chg)
    }

    // 快照变更监听：无论来源（下拉/SyncWorker/打开即同步），有新提交或新 release 就标
    private var lastSnapshots: Map<Long, RepoSnapshot> = emptyMap()

    /** 自动同步信号（打开即同步 / 定时）：首页细线 + 结果胶囊消费 */
    val syncStatus: kotlinx.coroutines.flow.StateFlow<SyncStatus> =
        (context.applicationContext as CandyTickApp).container.syncStatus

    /** 已展示过的同步结果时间戳上限：导航往返组件重建后旧结果不复活（VM 跨导航存活） */
    var syncResultConsumedAt by androidx.compose.runtime.mutableStateOf(0L)

    /**
     * 重排抑制：数据刷新引起的排序变化不做位移动画（长距离滑动=飞行感）；
     * 仅用户操作（置顶/撤销）保留动画。手动刷新与自动同步两条来源都会置起。
     */
    var suppressItemPlacement by androidx.compose.runtime.mutableStateOf(false)
        private set

    private fun suppressPlacementTemporarily() {
        suppressItemPlacement = true
        viewModelScope.launch {
            delay(600) // 覆盖刷新尾部与收尾组合的数帧
            suppressItemPlacement = false
        }
    }


    init {
        // 自动同步期间同样抑制重排动画（Running 置起，Done 后短暂释放）
        viewModelScope.launch {
            syncStatus.collect { st ->
                when (st) {
                    is SyncStatus.Running -> suppressItemPlacement = true
                    is SyncStatus.Done -> suppressPlacementTemporarily()
                    SyncStatus.Idle -> Unit
                }
            }
        }
        viewModelScope.launch {
            repository.state.collect { newState ->
                if (lastSnapshots.isNotEmpty()) {
                    val importAt = newState.lastImportAt
                    val newChanged = newState.snapshots.filterKeys { id ->
                        val old = lastSnapshots[id] ?: return@filterKeys false
                        val newSnap = newState.snapshots[id] ?: return@filterKeys false
                        // 导入基线：旧快照属备份数据（同步时间早于导入时刻）→ 首次本地同步只立基线不标记
                        if (old.lastSyncAt != null && importAt != null && old.lastSyncAt.isBefore(importAt)) {
                            return@filterKeys false
                        }
                        // 与 SyncWorker 结果统计同口径（首次记录到 tag 不算，规则内置）
                        SnapshotDiff.hasNewActivity(old, newSnap)
                    }.keys
                    if (newChanged.isNotEmpty()) {
                        changedRepos.value = changedRepos.value + newChanged
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
                        repository.setNoteName(tracked.id, label, auto = true)
                    // 收编无标记旧数据：备注为空（旧版手动关联从不写备注）或值恰等于当前应用名
                    !tracked.autoNote && (tracked.noteName == null || tracked.noteName == label) ->
                        repository.setNoteName(tracked.id, label, auto = true)
                    // 其余 = 异机导入的旧自动值或标签不可得：不动
                }
            }
        }
    }

    val state: StateFlow<UiState> = combine(part1, part2) { p1, p2 ->
        UiState(
            items = buildItems(p1.repos, p1.snapshots, p2.errors, p1.sort, p1.query),
            refreshing = p1.refreshing,
            sortMode = p1.sort,
            query = p1.query,
            banner = p1.banner,
            expanded = p2.expanded,
            changedIds = p2.changed,
            cards = p2.cards,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialUiState())

    /**
     * 冷启动首帧状态：Store 在构造时已同步加载 JSON，直接算出真实列表，
     * 避免 stateIn 占位空值导致「空态闪一帧再出列表」。
     */
    private fun initialUiState(): UiState {
        val loaded = repository.state.value
        // 排序与真实首发射一致（sortMode 初始化自持久化设置）：避免首帧按默认排序、
        // 下一帧重排造成的「列表突然变一下」
        return UiState(
            items = buildItems(loaded.repos, loaded.snapshots, emptyMap(), sortMode.value, ""),
        )
    }

    private data class Part1(
        val repos: List<TrackedRepo>,
        val snapshots: Map<Long, RepoSnapshot>,
        val sort: SortMode,
        val refreshing: Boolean,
        val banner: SourceError?,
        val query: String,
    )

    private data class Part2(
        val errors: Map<Long, SourceError>,
        val expanded: Set<Long>,
        val cards: Map<Long, CardExtras>,
        val changed: Set<Long>,
    )

    private fun buildItems(
        repos: List<TrackedRepo>,
        snapshots: Map<Long, RepoSnapshot>,
        errors: Map<Long, SourceError>,
        sort: SortMode,
        query: String,
    ): List<RepoListItem> {
        val q = query.trim().lowercase()
        val source = if (q.isBlank()) repos else repos.filter { tracked ->
            tracked.repo.lowercase().contains(q) ||
                tracked.owner.lowercase().contains(q) ||
                tracked.noteName?.lowercase()?.contains(q) == true
        }
        val base = when (sort) {
                SortMode.LAST_PUSH -> compareByDescending<RepoListItem> { it.snapshot?.pushedAt?.toEpochMilli() ?: Long.MIN_VALUE }
                SortMode.STATUS -> compareBy { it.status }
                SortMode.ADDED -> compareByDescending<RepoListItem> { it.repo.addedAt.toEpochMilli() }
                SortMode.NAME -> compareBy { it.repo.repo.lowercase() }
                SortMode.NAME_DESC -> compareByDescending<RepoListItem> { it.repo.repo.lowercase() }
                // 「可更新」优先：仅版本可比且严格更新（宁可不比）
                SortMode.UPDATABLE -> compareByDescending<RepoListItem> { it.hasUpdate(context) }
                    .thenByDescending { it.snapshot?.pushedAt?.toEpochMilli() ?: Long.MIN_VALUE }
            }
        // 置顶组永远最前（组内沿用当前排序 + 最近置顶在前）；搜索时按纯排序避免困惑
        return if (q.isBlank()) {
            source.map { RepoListItem(it, snapshots[it.id], errors[it.id]) }
                .sortedWith(
                    compareByDescending<RepoListItem> { it.repo.pinnedAt != null }
                        .thenByDescending { it.repo.pinnedAt?.toEpochMilli() ?: 0L }
                        .then(base),
                )
        } else {
            source.map { RepoListItem(it, snapshots[it.id], errors[it.id]) }.sortedWith(base)
        }
    }

    /** 关联本机应用 + latestReleaseTag 严格更新（宁可不比） */
    private fun RepoListItem.hasUpdate(context: Context): Boolean {
        val tag = snapshot?.latestReleaseTag ?: return false
        val pkg = repo.linkedPackage ?: return false
        val local = runCatching {
            context.packageManager.getPackageInfo(pkg, 0).versionName
        }.getOrNull() ?: return false
        return VersionCompare.compare(tag, local)?.let { it > 0 } == true
    }

    /** 下拉刷新：单仓库失败只做角标，全部失败才出横幅（旧数据保留） */
    fun refresh() {
        if (refreshing.value) return
        // 自动同步进行中则让位：结果同样经胶囊/圆点交付，避免同一仓库并发双请求烧配额
        if (syncStatus.value is SyncStatus.Running) return
        viewModelScope.launch {
            refreshing.value = true
            banner.value = null
            suppressPlacementTemporarily() // 刷新重排瞬时落位
            val errors = repository.refreshAll()
            repoErrors.value = errors
            val total = state.value.items.size
            banner.value = if (errors.isNotEmpty() && errors.size >= total) {
                errors.values.firstOrNull()
            } else {
                errors.values.firstOrNull { it is SourceError.RateLimited } // 限流永远值得横幅提示
            }
            refreshing.value = false
            suppressPlacementTemporarily() // 弱网下刷新可超过首个抑制窗：落位点再压一次
        }
    }

    /** 用户点击该仓库（进入详情）后清除「有更新」标记 */
    fun clearChanged(id: Long) {
        changedRepos.value = changedRepos.value - id
    }

    fun setSort(mode: SortMode) {
        sortMode.value = mode
        viewModelScope.launch { repository.updateSettings { it.copy(sortMode = mode.name) } }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun toggleExpand(id: Long) {
        val next = expanded.value.toMutableSet()
        if (!next.add(id)) next.remove(id)
        expanded.value = next
        if (id in next) {
            // 展开查看即视为已读：清除「有更新」标记
            changedRepos.value = changedRepos.value - id
            val cached = cards.value[id]
            if (cached?.commits == null && cached?.loading != true) loadCard(id)
        }
    }

    private fun loadCard(id: Long) {
        viewModelScope.launch {
            cards.value = cards.value + (id to CardExtras(loading = true))
            val commits = runCatching { repository.commits(id, 1) }.getOrNull()
            // 与 SyncWorker tag 选取同口径：跳过无安装包资产的 release（桌面专用 nightly 等）
            val release = runCatching { repository.releases(id, 1) }.getOrNull()
                ?.firstOrNull { it.apkAssets.isNotEmpty() }
            cards.value = cards.value + (id to CardExtras(commits, release, loading = false))
        }
    }

    /** 乐观删除 + 撤销事件（Snackbar） */
    fun remove(item: RepoListItem) {
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
        viewModelScope.launch {
            repository.setPinned(
                item.repo.id,
                if (item.repo.pinnedAt == null) Instant.now() else null,
            )
        }
    }
}
