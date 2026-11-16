package io.candy.tick.ui.list

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.SourceError
import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.RepoSnapshot
import io.candy.tick.data.model.RepoStatus
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.data.model.statusAt
import io.candy.tick.util.VersionCompare
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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

    private val sortMode = MutableStateFlow(SortMode.LAST_PUSH)
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
        return UiState(
            items = buildItems(loaded.repos, loaded.snapshots, emptyMap(), SortMode.LAST_PUSH, ""),
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
        return source.map { RepoListItem(it, snapshots[it.id], errors[it.id]) }
            .sortedWith(
            when (sort) {
                SortMode.LAST_PUSH -> compareByDescending<RepoListItem> { it.snapshot?.pushedAt?.toEpochMilli() ?: Long.MIN_VALUE }
                SortMode.STATUS -> compareBy { it.status }
                SortMode.ADDED -> compareByDescending<RepoListItem> { it.repo.addedAt.toEpochMilli() }
                SortMode.NAME -> compareBy { it.repo.repo.lowercase() }
                SortMode.NAME_DESC -> compareByDescending<RepoListItem> { it.repo.repo.lowercase() }
                // 「可更新」优先：仅版本可比且严格更新（宁可不比）
                SortMode.UPDATABLE -> compareByDescending<RepoListItem> { it.hasUpdate(context) }
                    .thenByDescending { it.snapshot?.pushedAt?.toEpochMilli() ?: Long.MIN_VALUE }
            },
        )
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
        viewModelScope.launch {
            refreshing.value = true
            banner.value = null
            // 刷新前快照：记录每个仓库的 pushedAt 和 latestReleaseTag
            val before = repository.state.value.snapshots.mapValues {
                Triple(it.value.pushedAt, it.value.lastSyncAt, it.value.latestReleaseTag)
            }
            val errors = repository.refreshAll()
            repoErrors.value = errors
            // 刷新后对比：pushedAt 或 latestReleaseTag 有变化 → 标记
            val after = repository.state.value.snapshots
            val changed = after.filterKeys { id ->
                val old = before[id] ?: return@filterKeys false
                val newSnap = after[id] ?: return@filterKeys false
                newSnap.pushedAt != old.first || newSnap.latestReleaseTag != old.third
            }.keys
            changedRepos.value = changed
            val total = state.value.items.size
            banner.value = if (errors.isNotEmpty() && errors.size >= total) {
                errors.values.firstOrNull()
            } else {
                errors.values.firstOrNull { it is SourceError.RateLimited } // 限流永远值得横幅提示
            }
            refreshing.value = false
        }
    }

    /** 用户点击该仓库（进入详情）后清除「有更新」标记 */
    fun clearChanged(id: Long) {
        changedRepos.value = changedRepos.value - id
    }

    fun setSort(mode: SortMode) {
        sortMode.value = mode
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun toggleExpand(id: Long) {
        val next = expanded.value.toMutableSet()
        if (!next.add(id)) next.remove(id)
        expanded.value = next
        if (id in next) {
            val cached = cards.value[id]
            if (cached?.commits == null && cached?.loading != true) loadCard(id)
        }
    }

    private fun loadCard(id: Long) {
        viewModelScope.launch {
            cards.value = cards.value + (id to CardExtras(loading = true))
            val commits = runCatching { repository.commits(id, 1) }.getOrNull()
            val release = runCatching { repository.releases(id, 1) }.getOrNull()?.firstOrNull()
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
}
