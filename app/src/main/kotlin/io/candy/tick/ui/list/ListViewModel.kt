package io.candy.tick.ui.list

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

enum class SortMode { LAST_PUSH, ADDED, STATUS, NAME, NAME_DESC, UPDATABLE }

class ListViewModel(
    private val repository: RepoRepository,
    private val downloadHost: io.candy.tick.ui.detail.RepoDownloadHost,
    private val context: Context,
) : ViewModel() {

    // @Immutable（含 List 字段的值对象承诺只读）：RepoListItem/CardExtras 稳定后，
    // 下载进度 tick / 搜索输入 / 展开切换等任意 state 变化下，自身参数未变的卡片
    // 可被 Compose equals 跳过重组——列表主路径的重组收敛点
    @Immutable
    data class RepoListItem(
        val repo: TrackedRepo,
        val snapshot: RepoSnapshot?,
        val error: SourceError?,
    ) {
        val status: RepoStatus get() = snapshot?.statusAt() ?: RepoStatus.UNKNOWN
    }

    @Immutable
    data class CardExtras(
        val commits: List<CommitRecord>? = null,
        val latestRelease: ReleaseRecord? = null,
        val loading: Boolean = false,
        val refreshing: Boolean = false, // 静默重拉中（旧数据照常显示，展开面板给轻提示）
    )

    @Immutable
    data class UiState(
        val items: List<RepoListItem> = emptyList(),
        val refreshing: Boolean = false,
        val sortMode: SortMode = SortMode.LAST_PUSH,
        val query: String = "",
        val banner: SourceError? = null,
        val expanded: Set<Long> = emptySet(),
        val changedIds: Set<Long> = emptySet(), // 有新活动的仓库（刷新后对比）
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

    // 展开时 stale 重拉的会话级节流：拉过一次不再因「有新版」重复拉（配额敏感）；
    // 新活动（changed）或新一轮刷新会重新解锁
    private val silentLoaded = MutableStateFlow<Set<Long>>(emptySet())

    private val part1 = combine(repository.state, sortMode, refreshing, banner, query) { s, sort, refr, ban, q ->
        Part1(s.repos, s.snapshots, sort, refr, ban, q)
    }
    // ON_RESUME 递增：安装完成后重算 updatableIds/版本行（本机版本读 PM 非响应式）
    private val resumeTick = MutableStateFlow(0)

    private val part2 = combine(repoErrors, expanded, cards, changedRepos, resumeTick) { errs, exp, crds, chg, tick ->
        Part2(errs, exp, crds, changed = chg, versionEpoch = tick)
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
                        // 展开中的卡当场跟进新数据（此前只有收起再展开才触发，
                        // 展开时出现「有新版」标记但内容停留旧值的割裂）
                        newChanged.filter { it in expanded.value }
                            .filter { cards.value[it]?.commits != null } // 首次加载都没完成的跳过（走正常路径）
                            .forEach {
                                // 展开中=正在看：视为已读（数据已自动跟进），changed 标记
                                // 即刻清——否则要收起再展开才消失（卡在展开态无人清除）
                                changedRepos.value = changedRepos.value - it
                                loadCard(it, silent = true)
                            }
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
        val (items, upd) = buildItems(p1.repos, p1.snapshots, p2.errors, p1.sort, p1.query)
        UiState(
            items = items,
            refreshing = p1.refreshing,
            sortMode = p1.sort,
            query = p1.query,
            banner = p1.banner,
            expanded = p2.expanded,
            versionEpoch = p2.versionEpoch,
            changedIds = p2.changed,
            // 有可比新版的仓库集（与排序比较器共用同一份 PM 查询结果，零重复 IPC）
            updatableIds = upd.filterValues { it }.keys,
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
            items = buildItems(loaded.repos, loaded.snapshots, emptyMap(), sortMode.value, "").first,
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
        val versionEpoch: Int = 0,
        val changed: Set<Long>,
    )

    private fun buildItems(
        repos: List<TrackedRepo>,
        snapshots: Map<Long, RepoSnapshot>,
        errors: Map<Long, SourceError>,
        sort: SortMode,
        query: String,
    ): Pair<List<RepoListItem>, Map<Long, Boolean>> {
        val q = query.trim().lowercase()
        val source = if (q.isBlank()) repos else repos.filter { tracked ->
            tracked.repo.lowercase().contains(q) ||
                tracked.owner.lowercase().contains(q) ||
                tracked.noteName?.lowercase()?.contains(q) == true
        }
        val items = source.map { RepoListItem(it, snapshots[it.id], errors[it.id]) }
        // 「可更新」预计算：排序比较器与折叠卡标识共用（O(n) 次 PM 查询，Map 直查）
        val upd = items.associate { it.repo.id to it.hasUpdate(context) }
        val base = when (sort) {
                SortMode.LAST_PUSH -> compareByDescending<RepoListItem> { it.snapshot?.pushedAt?.toEpochMilli() ?: Long.MIN_VALUE }
                SortMode.STATUS -> compareBy { it.status }
                SortMode.ADDED -> compareByDescending<RepoListItem> { it.repo.addedAt.toEpochMilli() }
                // 按显示名排（备注优先）：列表显示什么就按什么排，改名后排序跟随
                SortMode.NAME -> compareBy { (it.repo.noteName ?: it.repo.repo).lowercase() }
                SortMode.NAME_DESC -> compareByDescending<RepoListItem> { (it.repo.noteName ?: it.repo.repo).lowercase() }
                // 「可更新」优先：仅版本可比且严格更新（宁可不比）
                SortMode.UPDATABLE -> compareByDescending<RepoListItem> { upd[it.repo.id] == true }
                    .thenByDescending { it.snapshot?.pushedAt?.toEpochMilli() ?: Long.MIN_VALUE }
            }
        // 置顶组永远最前（组内沿用当前排序 + 最近置顶在前）；搜索时按纯排序避免困惑
        val sorted = if (q.isBlank()) {
            items.sortedWith(
                    compareByDescending<RepoListItem> { it.repo.pinnedAt != null }
                        .thenByDescending { it.repo.pinnedAt?.toEpochMilli() ?: 0L }
                        .then(base),
                )
        } else {
            items.sortedWith(base)
        }
        return sorted to upd
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
        // 自动同步进行中则让位：结果同样经胶囊/圆点交付，避免同一仓库并发双请求烧配额。
        // 但松手瞬间「刷新中」文字已亮——静默 return 会让它闪现即逝（进页同步窗口内下拉必现），
        // 接管 refreshing 直到同步结束，指示器节奏才连贯
        if (syncStatus.value is SyncStatus.Running) {
            viewModelScope.launch {
                refreshing.value = true
                // 看门狗之外的双保险：让位等待限时 30s（仅 Wi-Fi 无 Wi-Fi 时哨兵曾永久卡 Running）
                kotlinx.coroutines.withTimeoutOrNull(30_000) {
                    syncStatus.first { it !is SyncStatus.Running }
                }
                refreshing.value = false
            }
            return
        }
        viewModelScope.launch {
            refreshing.value = true
            banner.value = null
            silentLoaded.value = emptySet() // 新一轮刷新：解锁展开时的 stale 重拉节流
            suppressPlacementTemporarily() // 刷新重排瞬时落位
            // 网络层已把非 HTTP 异常收敛为 SourceException；此处再兜一层：任何意外抛出
            // 不得把下拉刷新协程带崩（空 Map = 单仓无错误，横幅不出）
            val errors = runCatching { repository.refreshAll() }.getOrDefault(emptyMap())
            repoErrors.value = errors
            val total = state.value.items.size
            banner.value = if (errors.isNotEmpty() && errors.size >= total) {
                errors.values.firstOrNull()
            } else {
                errors.values.firstOrNull { it is SourceError.RateLimited } // 限流永远值得横幅提示
            }
            refreshing.value = false
            suppressPlacementTemporarily() // 弱网下刷新可超过首个抑制窗：落位点再压一次
            // 刷新后已展开的卡原地静默重拉：展开内容与快照同步（缓存只拉一次的旧值不再滞留）
            expanded.value.forEach { id ->
                if (cards.value[id]?.commits != null) loadCard(id, silent = true)
            }
        }
    }

    /** 用户点击该仓库（进入详情）后清除「有更新」标记 */
    fun clearChanged(id: Long) {
        changedRepos.value = changedRepos.value - id
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

    fun setSort(mode: SortMode) {
        sortMode.value = mode
        viewModelScope.launch { repository.updateSettings { it.copy(sortMode = mode.name) } }
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
        // 批量收起 = N 张卡逐帧缩 + 下方 M 张卡位移——suppress 让位移改 snap，
        // 省掉每帧 N×M 的 spring 写入（与详情列表 placementSpec=snap 同哲学）
        suppressPlacementTemporarily()
        expanded.value = emptySet()
    }

    fun toggleExpand(id: Long) {
        // 展开/收起 220ms 期间下方卡片 snap 贴合生长边缘：spring 位移在此期间
        // 每帧多跑 N 个且滞后于生长中的卡（详情列表同款结论），suppress 窗口内顺带
        // 锚定逻辑保持「用户原来看哪还看哪」
        suppressPlacementTemporarily()
        val next = expanded.value.toMutableSet()
        if (!next.add(id)) next.remove(id)
        expanded.value = next
        if (id in next) {
            // 展开查看即视为已读：清除「有更新」标记（先记后清，供缓存过期判断）
            val hadChanges = id in changedRepos.value
            changedRepos.value = changedRepos.value - id
            val cached = cards.value[id]
            when {
                cached?.commits == null && cached?.loading != true -> loadCard(id)
                // 缓存过期：快照层提示过新活动/新版（如 SyncWorker 后台刷完回前台），
                // 展开必须重拉——否则提示与展开内容不符（展开的还是旧提交/旧版本）
                hadChanges ||
                    (id in state.value.updatableIds && id !in silentLoaded.value) ->
                    loadCard(id, silent = true)
            }
        }
    }

    private fun loadCard(id: Long, silent: Boolean = false) {
        viewModelScope.launch {
            if (silent) {
                // 静默重拉：旧数据保留，只竖 refreshing 旗（展开面板显示轻提示，
                // 不再"没反应→数据突然变了"的暗更）
                cards.value = cards.value + (id to (cards.value[id]?.copy(refreshing = true) ?: CardExtras(refreshing = true)))
            } else {
                cards.value = cards.value + (id to CardExtras(loading = true))
            }
            // 两请求并行（此前串行 2×RTT，展开等待翻倍——比详情页并行加载还慢的根源）；
            // 请求数不变（依旧 commits+releases 各一次），配额零增量
            val commitsDeferred = async {
                runCatching { repository.commits(id, 1) }.getOrNull()
            }
            val releaseDeferred = async {
                // 与 SyncWorker tag 选取同口径：跳过无安装包资产的 release（桌面专用 nightly 等）
                runCatching { repository.releases(id, 1) }.getOrNull()
                    ?.firstOrNull { it.apkAssets.isNotEmpty() }
            }
            val commits = commitsDeferred.await()
            val release = releaseDeferred.await()
            cards.value = cards.value + (id to if (silent) {
                // 静默重拉：失败逐字段保留旧值（不闪 loading、不丢已展示内容）
                val old = cards.value[id] ?: CardExtras()
                silentLoaded.value = silentLoaded.value + id
                CardExtras(commits ?: old.commits, release ?: old.latestRelease, loading = false, refreshing = false)
            } else {
                CardExtras(commits, release, loading = false)
            })
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
        viewModelScope.launch {
            repository.setPinned(
                item.repo.id,
                if (item.repo.pinnedAt == null) Instant.now() else null,
            )
        }
    }
}
