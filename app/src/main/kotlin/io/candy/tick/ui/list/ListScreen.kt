package io.candy.tick.ui.list

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.alpha
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.layoutBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.common.rememberMotionEnabled

/** A short home list keeps its end marker at the bottom of the available viewport. */
private object HomeListArrangement : Arrangement.Vertical {
    override val spacing = 8.dp

    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val gap = spacing.roundToPx()
        var top = 0
        sizes.forEachIndexed { index, height ->
            outPositions[index] = if (index == sizes.lastIndex) maxOf(top, totalSize - height) else top
            top += height + gap
        }
    }
}

private class SortSheetSession(val initial: SortOrder) {
    var open by mutableStateOf(true)
    var selected by mutableStateOf(initial)
}

@Composable
fun ListScreen(
    onAdd: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenSettings: () -> Unit,
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
    // 同步结果使用页面选定的语言，和设置中的语言切换即时一致。
    // 胶囊只在有信息量时出（有更新计数/有失败）；无变化不出——顶栏 ✓ 已确认成功，
    // 卡片圆点缺席已说明无更新，静默即无变化
    androidx.compose.runtime.LaunchedEffect(syncResult) {
        syncResult?.let { d ->
            // 三分位：全挂（failed>=total）才叫「同步失败」；部分失败无更新如实报数——
            // 此前 failed>0 && changed==0 即判 allFailed，5 中 1 失败也弹红色全挂文案
            when {
                d.allFailed -> io.candy.tick.ui.common.Feedback.show(
                    text = resources.getQuantityString(R.plurals.sync_result_all_failed, d.failed, d.failed),
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
    }
    val scheme = MiuixTheme.colorScheme
    // 瞬时反馈走全局 FeedbackHost（撤销动作由胶囊承载）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val emptyScroll = rememberScrollState()
    val showingEmpty = state.items.isEmpty() && !state.refreshing
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val motionEnabled = rememberMotionEnabled()
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
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pullState = rememberPullToRefreshState()
    var showSearch by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val clearSearch = clearTextFieldAction(searchFocus) { vm.setQuery("") }
    fun closeSearch() {
        keyboard?.hide()
        vm.setQuery("")
        showSearch = false
    }
    androidx.activity.compose.BackHandler(enabled = showSearch, onBack = ::closeSearch)
    val removedMsg = stringResource(R.string.removed)
    val undoLabel = stringResource(R.string.undo)

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is ListViewModel.Event.Removed -> io.candy.tick.ui.common.Feedback.show(
                    text = removedMsg,
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
                                    onClick = {
                                        keepReadingOrder = false
                                        scope.launch {
                                            if (showingEmpty) emptyScroll.scrollToTop(motionEnabled)
                                            else listState.scrollToTop(motionEnabled)
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
                            io.candy.tick.ui.common.PressIconButton(
                                onClick = {
                                    if (showSearch) closeSearch() else showSearch = true
                                },
                                contentDescription = stringResource(
                                    if (showSearch) R.string.close_search else R.string.action_search,
                                ),
                            ) {
                                // 激活态换返回箭头（= 退出搜索模式，与系统返回同语义）：
                                // 此前用 X 与输入框「清空」钮同形（skill: X=modal close 语义撞车）
                                Icon(
                                    imageVector = if (showSearch) {
                                        ImageVector.vectorResource(R.drawable.ic_back)
                                    } else {
                                        ImageVector.vectorResource(R.drawable.ic_search)
                                    },
                                    contentDescription = null,
                                    tint = if (showSearch) scheme.primaryVariant else scheme.onBackground,
                                    modifier = Modifier.size(24.dp), // 顶栏动作图标统一 24dp
                                )
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
                Column(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSearch,
                        // 表层档 220：展开/收起高度与透明度同拍（默认 spring 不可控且不在四档体系）
                        enter = androidx.compose.animation.expandVertically(
                            animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                        ) + androidx.compose.animation.fadeIn(
                            animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                        ),
                        exit = androidx.compose.animation.shrinkVertically(
                            animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                        ) + androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                        ),
                    ) {
                        top.yukonga.miuix.kmp.basic.TextField(
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
                        // 展开即聚焦（等一帧入场布局稳定，防未挂载请求崩溃）
                        androidx.compose.runtime.LaunchedEffect(showSearch) {
                            if (showSearch) {
                                kotlinx.coroutines.delay(100)
                                runCatching { searchFocus.requestFocus() }
                            }
                        }
                    }
                    state.banner?.let { error ->
                        ErrorBanner(
                            error = error,
                            hasSavedDetails = state.items.any { it.error != null && it.snapshot != null },
                            onRetry = ::refresh,
                            onDismiss = vm::clearBanner,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    if (showingEmpty) {
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
                                EmptyState(onAdd = { leaveHome(onAdd) }, modifier = viewport)
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().layoutBounds(listBounds),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                // top 13 = 4 基线 + 9：补偿 PullToRefresh contentPadding——
                                // 刷新指示器（环+文字）的上下隙对称（实测上 20/下 10.7 → 均衡）
                                top = 13.dp,
                                bottom = 32.dp,
                            ),
                            verticalArrangement = HomeListArrangement,
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
                        if (state.items.isNotEmpty()) {
                            item(key = "list-end") {
                                io.candy.tick.ui.common.FooterText(
                                    countLabel = R.plurals.footer_repositories,
                                    count = state.items.size,
                                    topPadding = 8.dp,
                                )
                            }
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

