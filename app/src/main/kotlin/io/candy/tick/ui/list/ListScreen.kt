package io.candy.tick.ui.list

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import io.candy.tick.data.SyncStatus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewmodel.compose.viewModel
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.ui.common.ErrorBanner
import io.candy.tick.ui.common.ScrollToTopTitle
import io.candy.tick.ui.common.TextFieldActionButton
import io.candy.tick.ui.common.clearTextFieldAction
import io.candy.tick.ui.common.scrollToTop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import io.candy.tick.ui.common.TopBarActionRow
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.common.rememberMotionEnabled


private class SortSheetSession(val initial: SortOrder) {
    var open by mutableStateOf(true)
    var selected by mutableStateOf(initial)
}

@Composable
fun ListScreen(
    onAdd: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImport: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as CandyTickApp
    val resources = LocalResources.current
    val vm: ListViewModel = viewModel { ListViewModel(app.container.repoRepository, app.container.repoDownloadHost, app) }
    fun leaveHome(navigate: () -> Unit) {
        vm.titleSync.leave(vm.syncStatus.value)
        navigate()
    }
    // 从安装器返回（ON_RESUME）：本机版本可能已变（装完新 APK），通知 VM 重算版本对比
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val listBounds = remember { LayoutBoundsHolder() }
    var sortSheet by remember(vm) { mutableStateOf<SortSheetSession?>(null) }
    var keepReadingOrder by remember(vm) { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, vm) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> vm.onAppResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> vm.titleSync.leave(vm.syncStatus.value)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> keepReadingOrder = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.titleSync.leave(vm.syncStatus.value)
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val syncResult = rememberSyncResult(vm, lifecycleOwner.lifecycle)
    val scheme = MiuixTheme.colorScheme
    // 瞬时反馈走全局 FeedbackHost（撤销动作由胶囊承载）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val emptyScroll = rememberScrollState()
    // 标题「已在顶部」判定：derivedStateOf 只在越过边界时重组标题（canScrollBackward 逐帧变）
    val homeAtTop by remember { androidx.compose.runtime.derivedStateOf { !listState.canScrollBackward && !listState.isScrollInProgress } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val motionEnabled = rememberMotionEnabled()
    // 列表顶隙随横幅在场 13↔7：与横幅出入场（220 表层档）同拍动画，内容不再跳变
    val listTopGap by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (state.banner != null) 7.dp else 13.dp,
        animationSpec = if (motionEnabled) tween(Motion.PANEL) else snap(),
        label = "listTopGap",
    )
    // A foreground sync can start after the sheet closes. Keep the just-shown ranking
    // through that update, until the user scrolls, refreshes or leaves this visit.
    // The sort's own requestScrollToItem(0) is not a user drag.
    LaunchedEffect(listState, keepReadingOrder) {
        if (keepReadingOrder) listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Stop) keepReadingOrder = false
        }
    }
    fun refresh() {
        keepReadingOrder = false
        vm.refresh()
    }
    // Ranking runs off the main thread. During dismissal only hand over rows for the final choice.
    val holdOrder = sortSheet?.open == true || state.sortOrder != vm.storedSortOrder
    val rows = rememberHomeRows(
        vm, state, listState, lifecycleState == Lifecycle.State.RESUMED, motionEnabled, holdOrder = holdOrder,
        keepReadingOrder = keepReadingOrder,
    )
    // A sort may bring distant keys into the lazy layout's tracking range. They are existing cards.
    val fadeMembership = motionEnabled && sortSheet == null && !holdOrder && !rows.revealingSort

    // 同步结果使用页面选定的语言，和设置中的语言切换即时一致。
    // 胶囊只在有信息量时出（有更新计数/有失败）；无变化不出——顶栏 ✓ 已确认成功，
    // 卡片圆点缺席已说明无更新，静默即无变化。
    // 时机：等首页把这一轮的结果落实完再报数。最典型的是「打开 App 时后台同步在跑、用户又下拉刷新」：
    // 这一轮归下拉接管，同步一结束提示就到，而下拉头还在收、名次还没切（名次在刷新期间保持旧的，
    // 刷新结束才切到最新，经后台线程回到界面要几帧）。先等下拉刷新收尾、行位移动画走完，
    // 再留出下拉头收起的时间，最后才弹
    val currentRows by rememberUpdatedState(rows)
    LaunchedEffect(syncResult) {
        val d = syncResult ?: return@LaunchedEffect
        // 下拉刷新进行中：这一轮归下拉接管，等它收尾（名次要到这时才切到最新）
        var afterPull = vm.manualRefresh.value
        while (true) {
            snapshotFlow { vm.manualRefresh.value || currentRows.motion?.active == true }.first { !it }
            delay(if (afterPull) SYNC_RESULT_SETTLE_AFTER_PULL_MS else SYNC_RESULT_SETTLE_MS)
            // 等待期间用户又开始下拉：接着等这一轮收尾再报，免得胶囊盖在刚出现的下拉头上
            if (!vm.manualRefresh.value) break
            afterPull = true
        }
        when {
            d.allFailed -> io.candy.tick.ui.common.Feedback.show(
                text = if (d.rateLimited) {
                    // 限流是唯一「可行动」的后台失败（等恢复时间/去配令牌）：报因不报数。
                    // 胶囊是瞬时的，直接给绝对时刻（relTimeUntilString 是组合函数，此处不可用）
                    d.rateLimitRetryAt?.let {
                        // 同一天只给时刻；跨午夜补日期（否则 00:32 读起来像已经过去的时间）
                        val zone = java.time.ZoneId.systemDefault()
                        val pattern = if (it.atZone(zone).toLocalDate() == java.time.LocalDate.now(zone)) "HH:mm" else "MM-dd HH:mm"
                        val clock = java.time.format.DateTimeFormatter.ofPattern(pattern).withZone(zone).format(it)
                        resources.getString(R.string.sync_rate_limited_until, clock)
                    } ?: resources.getString(R.string.err_rate_limited_plain)
                } else {
                    resources.getQuantityString(R.plurals.sync_result_all_failed, d.failed, d.failed)
                },
                isError = true,
            )
            d.failed > 0 && d.changed == 0 -> io.candy.tick.ui.common.Feedback.show(
                text = resources.getQuantityString(R.plurals.sync_result_partial_failed, d.failed, d.failed),
                isError = true,
            )
            d.changed > 0 -> {
                val updated = resources.getQuantityString(R.plurals.sync_result_updated, d.changed, d.changed)
                val message = if (d.failed > 0) {
                    resources.getString(R.string.sync_result_with_failures, updated, d.failed)
                } else updated
                io.candy.tick.ui.common.Feedback.show(text = message)
            }
        }
    }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pullState = rememberPullToRefreshState()
    var showSearch by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val searchVisibility = remember { MutableTransitionState(showSearch) }.apply { targetState = showSearch }
    // Capture the populated viewport once, before the field starts taking space.
    val openingListHeight = remember(showSearch, listState) {
        if (showSearch && listState.canScrollForward) listState.layoutInfo.viewportSize.height else 0
    }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val clearSearch = clearTextFieldAction(searchFocus) { vm.setQuery("") }
    fun closeSearch() {
        keyboard?.hide()
        vm.setQuery("")
        showSearch = false
    }
    androidx.activity.compose.BackHandler(enabled = showSearch, onBack = ::closeSearch)
    val removedNamedMsg = stringResource(R.string.removed_named)
    val undoLabel = stringResource(R.string.undo)

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is ListViewModel.Event.Removed -> io.candy.tick.ui.common.Feedback.show(
                    // 带仓库名：连续删除时每次替换肉眼可见——通用文案在连删场景
                    // 看起来像同一条胶囊卡住没走（真机反馈）
                    text = removedNamedMsg.format(event.repo.noteName ?: event.repo.repo),
                    actionLabel = undoLabel,
                    onAction = { vm.restore(event.repo, event.snapshot) },
                    // 滑删撤销是唯一救回手段：默认 4.5s 偏短，破坏性撤销放宽到 7s
                    durationMs = 7_000,
                )
            }
        }
    }

    // 通知权限上下文请求：开关默认开但权限从不主动请求——用户以为会收到更新提醒，
    // 实际被系统静默丢弃（此前唯一入口在设置页某行）。有订阅才问（空态首启没有
    // 「需要通知」的语境，此刻弹权限 = 过早打扰）；进程内问一次，被拒不纠缠
    val notifPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    var notifPermAsked by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    LaunchedEffect(state.items.isEmpty()) {
        if (state.items.isEmpty()) return@LaunchedEffect
        if (!notifPermAsked && android.os.Build.VERSION.SDK_INT >= 33 &&
            vm.shouldRequestNotificationPermission
        ) {
            notifPermAsked = true
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            // 键盘弹出（搜索态）时整页收缩到 IME 上方：空态/结果在可见区居中不被遮挡
            modifier = Modifier.imePadding(),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                SmallTopAppBar(
                    title = "",
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        val canCollapse = state.expanded.size >= 2 &&
                            state.items.count { it.repo.id in state.expanded } >= 2
                        TopBarActionRow(minimum = 48.dp * (if (canCollapse) 6 else 5)) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ScrollToTopTitle(
                                    text = stringResource(R.string.app_name),
                                    modifier = Modifier.weight(1f, fill = false),
                                    actionLabel = if (homeAtTop &&
                                        state.items.count { it.repo.id in state.expanded } > 0
                                    ) R.string.collapse_all else R.string.scroll_to_top,
                                    onClick = {
                                        keepReadingOrder = false
                                        // 已在顶部再点一下：收起全部展开卡片（此前是空操作）。
                                        // 门禁只认「当前可见列表里」确有展开项——搜索过滤掉的展开卡
                                        // 不算数（收起不可见内容没有反馈），无可见展开时维持无操作。
                                        // 判空与内容分支同源（items.isEmpty()，不含 refreshing）——
                                        // 空列表刷新期间屏幕是空态，滚的必须是空态自己的 scroll
                                        val empty = state.items.isEmpty()
                                        val atTop = if (empty) {
                                            emptyScroll.value == 0 && !emptyScroll.isScrollInProgress
                                        } else homeAtTop
                                        val visibleExpanded = if (empty) 0 else state.items.count { it.repo.id in state.expanded }
                                        when {
                                            atTop && visibleExpanded > 0 -> vm.collapseAll()
                                            empty -> scope.launch { emptyScroll.scrollToTop(motionEnabled) }
                                            else -> scope.launch { listState.scrollToTop(motionEnabled) }
                                        }
                                    },
                                )
                                Spacer(Modifier.width(6.dp))
                                // 离开时连同最短显示时长和退出动画一并结束，快速返回也不带回上一帧。
                                androidx.compose.runtime.key(vm.titleSync.interruptedRun, vm.titleSync.pullOwnedRun, state.refreshing) {
                                    io.candy.tick.ui.common.SyncDots(
                                        modifier = Modifier.width(18.dp),
                                        running = (syncStatus as? SyncStatus.Running)?.takeIf {
                                            !state.refreshing && vm.titleSync.shouldAnimate(it)
                                        },
                                        done = syncResult?.takeIf { !state.refreshing && vm.titleSync.shouldAnimate(it.run) },
                                    )
                                }
                            }
                            // Reserve the slot before fading in; release it only after the pressed circle has faded.
                            androidx.compose.animation.AnimatedVisibility(
                                visible = canCollapse,
                                enter = if (motionEnabled) {
                                    expandHorizontally(tween(Motion.PRESS), expandFrom = Alignment.End, clip = false) +
                                        fadeIn(tween(Motion.MICRO, delayMillis = Motion.PRESS))
                                } else EnterTransition.None,
                                exit = if (motionEnabled) {
                                    fadeOut(tween(Motion.MICRO)) +
                                        shrinkHorizontally(
                                            tween(Motion.PRESS, delayMillis = Motion.MICRO),
                                            shrinkTowards = Alignment.End,
                                            clip = false,
                                        )
                                } else ExitTransition.None,
                            ) {
                                io.candy.tick.ui.common.PressIconButton(
                                    onClick = { if (canCollapse) vm.collapseAll() },
                                    contentDescription = stringResource(R.string.collapse_all),
                                    modifier = if (canCollapse) Modifier else Modifier
                                        .clearAndSetSemantics { }
                                        .focusProperties { canFocus = false },
                                ) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_collapse_all),
                                        contentDescription = null,
                                        tint = scheme.onBackground,
                                        modifier = Modifier.size(24.dp), // 顶栏动作图标统一 24dp
                                    )
                                }
                            }
                            // 空态（非搜索会话）隐藏搜索/排序：零订阅时两者都无对象可作用，
                            // 顶栏聚焦「设置 + 添加」引导；首个订阅加入后淡入回归。
                            // 判空不看 refreshing——空列表下拉刷新不该让按钮冒头再缩回
                            androidx.compose.animation.AnimatedVisibility(
                                visible = state.items.isNotEmpty() || showSearch,
                                // 时序与「全部收起」钮同款：入场先展开后渐显（delay PRESS），
                                // 退场先渐显完（MICRO）再收宽——收快显慢会让图标盖到相邻按钮上
                                enter = if (motionEnabled) {
                                    androidx.compose.animation.expandHorizontally(
                                        tween(Motion.PRESS), expandFrom = Alignment.Start, clip = false,
                                    ) + androidx.compose.animation.fadeIn(tween(Motion.MICRO, delayMillis = Motion.PRESS))
                                } else EnterTransition.None,
                                exit = if (motionEnabled) {
                                    androidx.compose.animation.fadeOut(tween(Motion.MICRO)) +
                                        androidx.compose.animation.shrinkHorizontally(
                                            tween(Motion.PRESS, delayMillis = Motion.MICRO),
                                            shrinkTowards = Alignment.Start, clip = false,
                                        )
                                } else ExitTransition.None,
                            ) {
                                Row {
                                    io.candy.tick.ui.common.PressIconButton(
                                        onClick = {
                                            if (showSearch) closeSearch() else showSearch = true
                                        },
                                        contentDescription = stringResource(
                                            if (showSearch) R.string.close_search else R.string.action_search,
                                        ),
                                    ) {
                                        // 激活态换返回箭头（= 退出搜索模式，与系统返回同语义）：
                                        // 此前用 X 与输入框「清空」钮同形（skill: X=modal close 语义撞车）。
                                        // 换脸与按压染色同拍（collectPressTint：松手保持 80ms → 150ms 淡出）：
                                        // 保持期内什么都不变（按压读作完成），之后「暗圈退、旧脸退、新脸进」
                                        // 三位一体落在同一 80→230ms 窗口——不会出现「还按着就换脸」。24dp 钉死无尺寸跳
                                        AnimatedContent(
                                            targetState = showSearch,
                                            transitionSpec = {
                                                if (motionEnabled) {
                                                    (
                                                        fadeIn(tween(Motion.MICRO, delayMillis = Motion.PRESS)) togetherWith
                                                            fadeOut(tween(Motion.MICRO, delayMillis = Motion.PRESS))
                                                        ).using(SizeTransform(clip = false))
                                                } else {
                                                    fadeIn(snap()) togetherWith fadeOut(snap())
                                                }
                                            },
                                            label = "searchToggle",
                                        ) { active ->
                                            Icon(
                                                imageVector = ImageVector.vectorResource(
                                                    if (active) R.drawable.ic_back else R.drawable.ic_search,
                                                ),
                                                contentDescription = null,
                                                tint = if (active) scheme.primaryVariant else scheme.onBackground,
                                                modifier = Modifier.size(24.dp), // 顶栏动作图标统一 24dp
                                            )
                                        }
                                    }
                                    io.candy.tick.ui.common.PressIconButton(
                                        onClick = { sortSheet = SortSheetSession(vm.storedSortOrder) },
                                        contentDescription = stringResource(R.string.sort_title),
                                    ) {
                                        Icon(
                                            imageVector = ImageVector.vectorResource(R.drawable.ic_sort),
                                            contentDescription = null,
                                            tint = scheme.onBackground,
                                            modifier = Modifier.size(24.dp), // 顶栏动作图标统一 24dp
                                        )
                                    }
                                }
                            }
                            io.candy.tick.ui.common.PressIconButton(
                                onClick = { leaveHome(onOpenSettings) },
                                contentDescription = stringResource(R.string.settings_title),
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_settings),
                                    contentDescription = null,
                                    tint = scheme.onBackground,
                                )
                            }
                            // 主操作：添加仓库（顶栏最右，替代 FAB）
                            io.candy.tick.ui.common.PressIconButton(
                                onClick = { leaveHome(onAdd) },
                                contentDescription = stringResource(R.string.add_repo),
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_add),
                                    contentDescription = null,
                                    tint = scheme.primaryVariant,
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
            // 松手触发刷新：轻触感确认（HyperOS 下拉刷新惯例）
            // 下拉刷新只包列表分支：空态没有任何订阅可刷，接下拉是死手势
            // （拉了只看到空刷新动画）——指示器原点随之下移到列表区，语义也更准
                Column(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.animation.AnimatedVisibility(
                        visibleState = searchVisibility,
                        enter = if (motionEnabled) androidx.compose.animation.expandVertically(
                            animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                        ) + androidx.compose.animation.fadeIn(
                            animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                        ) else EnterTransition.None,
                        exit = if (motionEnabled) androidx.compose.animation.shrinkVertically(
                            animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                        ) + androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                        ) else ExitTransition.None,
                    ) {
                        io.candy.tick.ui.common.CandyTextField(
                            value = state.query,
                            onValueChange = { if (it.length <= io.candy.tick.ui.common.InputLimits.SEARCH) vm.setQuery(it) },
                            // singleLine 与全 App 输入框统一（此前列表过滤框会折行）
                            singleLine = true,
                            label = stringResource(R.string.search_hint),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                // 实时过滤已出结果：回车只收键盘
                                onSearch = { keyboard?.hide() },
                            ),
                            // 非空清空钮（与添加页/分支/应用选择器同款：同组件同待遇）
                            trailingIcon = if (state.query.isNotEmpty()) {
                                { TextFieldActionButton(onClick = clearSearch) }
                            } else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocus)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        LaunchedEffect(showSearch) {
                            if (showSearch) {
                                snapshotFlow { searchVisibility.isIdle }.first { it }
                                if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED) {
                                    searchFocus.requestFocus()
                                }
                            }
                        }
                    }
                    // 横幅出入场：fade + 高度渐变（与详情页同款 220 表层档）——
                    // 此前硬切 + 列表顶隙瞬变，出现/消失时下方内容跳变突兀
                    AnimatedContent(
                        targetState = state.banner,
                        transitionSpec = {
                            val duration = if (motionEnabled) Motion.PANEL else 0
                            (
                                fadeIn(tween(duration)) togetherWith
                                    fadeOut(tween(duration))
                                ).using(
                                    SizeTransform { _, _ -> tween(duration) },
                                )
                        },
                        label = "homeBanner",
                    ) { error ->
                        if (error != null) {
                            ErrorBanner(
                                error = error,
                                hasSavedDetails = state.items.any { it.error != null && it.snapshot != null },
                                onRetry = ::refresh,
                                onDismiss = vm::clearBanner,
                                // 上 7 + 内衬 6 = 13：与下方（内衬 6 + 列表顶隙 7）对称，
                                // 列表顶隙由 banner 在场时 13→7 联动（见 LazyColumn contentPadding，同拍动画）
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
                            )
                        }
                    }
                    // 内容切换（空态 ⇄ 列表）：直接切换——Crossfade 包裹与行动画/布局系统
                    // 交互后真机出现「首仓库卡片沉到屏底」（回归），润色不值得这个风险。
                    // 语义修复保留：判空只看 items（此前含 !refreshing，空列表下拉刷新会闪切）
                    if (state.items.isEmpty()) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val viewport = Modifier.fillMaxWidth()
                                .verticalScroll(emptyScroll)
                                .heightIn(min = maxHeight)
                            if (state.query.isNotBlank()) {
                                io.candy.tick.ui.common.StatusPage(
                                    icon = ImageVector.vectorResource(R.drawable.ic_search),
                                    title = stringResource(R.string.search_no_results),
                                    subtitle = stringResource(R.string.search_no_results_hint),
                                    actionLabel = stringResource(R.string.search_clear),
                                    onAction = clearSearch.takeIf { showSearch },
                                    modifier = viewport,
                                )
                            } else {
                                EmptyState(
                                    onAdd = { leaveHome(onAdd) },
                                    onImport = { leaveHome(onOpenImport) },
                                    modifier = viewport,
                                )
                            }
                        }
                    } else {
                        io.candy.tick.ui.common.CandyPullToRefresh(
                            requestState = vm.manualRefresh,
                            refreshing = { it },
                            onRefresh = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                refresh()
                            },
                            pullToRefreshState = pullState,
                            topAppBarScrollBehavior = scrollBehavior,
                        ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().layoutBounds(listBounds).then(
                                if (openingListHeight > 0 && !searchVisibility.isIdle) {
                                    Modifier.holdOpeningViewport(openingListHeight)
                                } else Modifier,
                            ),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                // top 13 = 4 基线 + 9：补偿 PullToRefresh contentPadding——
                                // 刷新指示器（环+文字）的上下隙对称（实测上 20/下 10.7 → 均衡）；
                                // 横幅在场时 13→7（listTopGap 同拍动画）：横幅下衬 6 + 7 = 13，与横幅上方对称
                                top = listTopGap,
                                bottom = 32.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                        items(rows.items, key = { it.repo.id }) { item ->
                            SwipeToDelete(
                                onDelete = { vm.remove(item) },
                                onPin = { vm.togglePin(item) },
                                pinned = item.repo.pinnedAt != null,
                                modifier = (rows.motion?.modifier(item.repo.id) ?: Modifier).animateItem(
                                    placementSpec = null,
                                    fadeInSpec = if (fadeMembership) tween(Motion.MICRO) else null,
                                    fadeOutSpec = if (fadeMembership) tween(Motion.MICRO) else null,
                                ),
                            ) { swipeActions ->
                                RepoCard(
                                    interactionModifier = swipeActions,
                                    item = item,
                                    extras = state.cards[item.repo.id]?.takeIf {
                                        it.addedAt == item.repo.addedAt && it.commitBranch == (item.repo.branch ?: item.snapshot?.defaultBranch)
                                    },
                                    expanded = item.repo.id in state.expanded,
                                    pageRefreshing = state.refreshing || vm.titleSync.shouldAnimate(syncStatus),
                                    isChanged = item.snapshot?.activity?.hasUnread == true,
                                    updatable = item.repo.id in state.updatableIds,
                                    onToggle = { vm.toggleExpand(item.repo.id) },
                                    onClick = { leaveHome { onOpenDetail(item.repo.id) } },
                                    readViewport = listBounds.takeIf {
                                        lifecycleState == Lifecycle.State.RESUMED && sortSheet == null && !holdOrder
                                    },
                                    onReleaseSeen = { vm.markReleaseSeen(item.repo.id, it) },
                                    onCommitSeen = { sha, isHead -> vm.markCommitSeen(item.repo.id, sha, isHead) },
                                    onRetryExtras = { vm.retryCard(item.repo.id) },
                                )
                            }
                        }
                        // 列表末尾标记：始终渲染、紧跟最后一张卡（用户定案——不做按屏判定，
                        // 颜色在 FooterText 内整体弱化以降噪）。FooterText 自带顶部留白 24：8 间距 + 24
                        // = 32，与底部 contentPadding 32 上下对称（原版节奏）
                        item(key = "list-end") {
                            io.candy.tick.ui.common.FooterText(
                                countLabel = R.plurals.footer_repositories,
                                count = state.items.size,
                            )
                        }
                        }
                    }
                }
            }

            }
        }

    }

    sortSheet?.let { sheet ->
        key(sheet) {
            SortSheet(
                show = sheet.open,
                current = sheet.selected,
                updatableCount = state.updatableIds.size,
                onSelect = { mode ->
                    if (sheet.open) {
                        sheet.selected = sheet.selected.select(mode)
                        vm.setSortOrder(sheet.selected)
                    }
                },
                onDismiss = {
                    if (sheet.open && sheet.selected != sheet.initial) {
                        keepReadingOrder = sheet.selected.mode.usesSyncData
                    }
                    sheet.open = false
                },
                onDismissFinished = { if (!sheet.open && sortSheet === sheet) sortSheet = null },
            )
        }
    }
}

/**
 * 同步结果胶囊在首页落实完之后还要等的一拍：名次切回最新界面只要几帧，再让人先看到列表落位
 */
private const val SYNC_RESULT_SETTLE_MS = 300L

/**
 * 下拉刷新刚收尾时的等待，按最坏情况取：下拉头收起 = 最短驻留最多 300ms（CandyPullToRefresh）
 * + 库的完成动画 200ms（PullToRefresh）。最坏情况（下拉刚开始同步就结束）胶囊仍在下拉头收起之后；
 * 常见情况下拉头收起后约 300ms 胶囊才出现
 */
private const val SYNC_RESULT_SETTLE_AFTER_PULL_MS = 500L

/** Slide a scrollable list with the field; clip its lower edge until the final viewport is known. */
private fun Modifier.holdOpeningViewport(height: Int): Modifier = clipToBounds().layout { measurable, constraints ->
    val listHeight = maxOf(height, constraints.maxHeight)
    val list = measurable.measure(constraints.copy(minHeight = listHeight, maxHeight = listHeight))
    layout(list.width, constraints.maxHeight) { list.placeRelative(0, 0) }
}

/** 完成反馈属于用户在首页目睹的这一轮同步。每次回到首页都从空状态开始。 */
@Composable
private fun rememberSyncResult(vm: ListViewModel, lifecycle: Lifecycle): SyncStatus.Done? {
    val result by produceState<SyncStatus.Done?>(null, vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var observed: SyncStatus.Running? = null
            try {
                vm.syncStatus.collectLatest { status ->
                    val completed = (status as? SyncStatus.Done)?.takeIf { it.run === observed }
                    observed = status as? SyncStatus.Running
                    value = completed
                    if (completed != null) {
                        delay(if (completed.changed > 0 || completed.failed > 0) 4_000L else 2_000L)
                        value = null
                    }
                }
            } finally {
                value = null
            }
        }
    }
    // A pull can own the visual result while update counts and errors still reach the user.
    return result?.takeUnless { it.run === vm.titleSync.interruptedRun }
}

