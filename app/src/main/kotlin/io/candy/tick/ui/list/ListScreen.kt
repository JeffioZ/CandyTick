package io.candy.tick.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.ui.common.ErrorBanner
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun ListScreen(
    onAdd: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as CandyTickApp
    val vm: ListViewModel = viewModel { ListViewModel(app.container.repoRepository, app.container.repoDownloadHost, app) }
    // 从安装器返回（ON_RESUME）：本机版本可能已变（装完新 APK），通知 VM 重算版本对比
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.onAppResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    // 结果转变检测（Running→Done，或冷启动 10s 内的新鲜 Done）——顶栏完成符与胶囊共用
    val syncResult = rememberSyncResult(
        status = syncStatus,
        consumedAt = vm.syncResultConsumedAt,
        onConsumed = { vm.syncResultConsumedAt = it },
    )
    // 同步结果也走统一反馈（顶栏 ✓ 符保留作状态指示）；app.getString 可在协程内带参取串
    // 胶囊只在有信息量时出（有更新计数/有失败）；无变化不出——顶栏 ✓ 已确认成功，
    // 卡片圆点缺席已说明无更新，静默即无变化
    androidx.compose.runtime.LaunchedEffect(syncResult) {
        syncResult?.let { d ->
            // 三分位：全挂（failed>=total）才叫「同步失败」；部分失败无更新如实报数——
            // 此前 failed>0 && changed==0 即判 allFailed，5 中 1 失败也弹红色全挂文案
            val allFailed = d.total > 0 && d.failed >= d.total
            when {
                allFailed -> io.candy.tick.ui.common.Feedback.show(
                    text = app.getString(R.string.sync_result_all_failed, d.failed),
                    isError = true,
                )
                d.failed > 0 && d.changed == 0 -> io.candy.tick.ui.common.Feedback.show(
                    text = app.getString(R.string.sync_result_partial_failed, d.failed),
                    isError = true,
                )
                d.changed > 0 -> io.candy.tick.ui.common.Feedback.show(
                    text = app.getString(R.string.sync_result_updated, d.changed) +
                        if (d.failed > 0) app.getString(R.string.sync_result_failed_suffix, d.failed) else "",
                )
            }
        }
    }
    val scheme = MiuixTheme.colorScheme
    // 瞬时反馈走全局 FeedbackHost（撤销动作由胶囊承载）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // 重排锚点：抑制期内保持「用户原来看哪还看哪」——修复 LazyColumn 锚定取整误差
    // （重排项插到首见项之上时露出 1px 边 + 跨视口闪烁）
    var scrollAnchor by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    androidx.compose.runtime.LaunchedEffect(vm.suppressItemPlacement) {
        if (vm.suppressItemPlacement) {
            if (scrollAnchor == null && !listState.isScrollInProgress) {
                state.items.getOrNull(listState.firstVisibleItemIndex)?.let {
                    scrollAnchor = it.repo.id to listState.firstVisibleItemScrollOffset
                }
            }
        } else {
            scrollAnchor = null
        }
    }
    androidx.compose.runtime.LaunchedEffect(state.items, vm.suppressItemPlacement, scrollAnchor) {
        val anchor = scrollAnchor ?: return@LaunchedEffect
        if (!vm.suppressItemPlacement) return@LaunchedEffect
        when {
            listState.isScrollInProgress -> scrollAnchor = null // 用户接管滚动：放弃锚点
            else -> {
                val idx = state.items.indexOfFirst { it.repo.id == anchor.first }
                if (idx < 0) {
                    scrollAnchor = null // 锚点条目已不在列表
                } else if (listState.firstVisibleItemIndex != idx ||
                    listState.firstVisibleItemScrollOffset != anchor.second
                ) {
                    listState.requestScrollToItem(idx, anchor.second)
                }
            }
        }
    }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pullState = rememberPullToRefreshState()
    var showSortSheet by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // 返回键先收起搜索（而非退出应用）——搜索态的标准返回语义
    androidx.activity.compose.BackHandler(enabled = showSearch) {
        vm.setQuery("")
        showSearch = false
    }
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
                    title = "", // 标题走 navigationIcon 槽位实现左对齐（内置 title 居中）
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MiuixTheme.textStyles.title4,
                                color = scheme.onBackground,
                                // 点标题回顶部（与详情页同款交互）：无 ripple，滚动动画即反馈；
                                // 前置 padding 扩热区至 ~48dp
                                modifier = Modifier
                                    .padding(vertical = 12.dp)
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                    ) { scope.launch { listState.animateScrollToItem(0) } },
                            )
                            Spacer(Modifier.width(6.dp))
                            // 自动同步活体：标题右侧三点轻跳 → 完成符（信息由悬浮胶囊承担）
                            SyncDots(
                                running = syncStatus is SyncStatus.Running,
                                done = syncResult,
                            )
                        }
                    },
                    actions = {
                        // 一键收起：展开 ≥2 张时出现（150 淡入淡出；点按后批量收起动画即反馈）
                        androidx.compose.animation.AnimatedVisibility(
                            visible = state.expanded.size >= 2,
                            enter = androidx.compose.animation.fadeIn(
                                androidx.compose.animation.core.tween(150),
                            ),
                            exit = androidx.compose.animation.fadeOut(
                                androidx.compose.animation.core.tween(150),
                            ),
                        ) {
                            io.candy.tick.ui.common.PressIconButton(
                                onClick = vm::collapseAll,
                                contentDescription = stringResource(R.string.collapse_all),
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
                                if (showSearch) vm.setQuery("")
                                showSearch = !showSearch
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
                            onClick = { showSortSheet = true },
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
                            onClick = onOpenSettings,
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
                            onClick = onAdd,
                            contentDescription = stringResource(R.string.add_repo),
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_add),
                                contentDescription = null,
                                tint = scheme.primaryVariant,
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
            PullToRefresh(
                isRefreshing = state.refreshing,
                onRefresh = {
                    // 松手触发刷新：轻触感确认（HyperOS 下拉刷新惯例）
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.refresh()
                },
                pullToRefreshState = pullState,
                topAppBarScrollBehavior = scrollBehavior,
                // 刷新指示器四态常驻文字（下拉/释放/正在/完成）：文字从头就在 = 头部
                // 高度全相恒定，「拉时 OK、字一出上空就多」的间距跳变从根消失
                // （经典下拉刷新形态；36dp 区固定，contentPadding top 让圆环不顶顶栏）
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 9.dp),
                color = scheme.primaryVariant, // 薄荷主色：与全应用活动色统一
                refreshTexts = listOf(
                    stringResource(R.string.refresh_pull),
                    stringResource(R.string.refresh_release),
                    stringResource(R.string.refresh_refreshing),
                    stringResource(R.string.refresh_done),
                ),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSearch,
                        // 表层档 220：展开/收起高度与透明度同拍（默认 spring 不可控且不在四档体系）
                        enter = androidx.compose.animation.expandVertically(
                            animationSpec = androidx.compose.animation.core.tween(220),
                        ) + androidx.compose.animation.fadeIn(
                            animationSpec = androidx.compose.animation.core.tween(220),
                        ),
                        exit = androidx.compose.animation.shrinkVertically(
                            animationSpec = androidx.compose.animation.core.tween(220),
                        ) + androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(220),
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
                                onSearch = { searchFocus.freeFocus() },
                            ),
                            // 非空清空钮（与添加页/分支/应用选择器同款：同组件同待遇）
                            trailingIcon = if (state.query.isNotEmpty()) {
                                {
                                    io.candy.tick.ui.common.PressIconButton(
                                        onClick = { vm.setQuery("") },
                                        contentDescription = stringResource(R.string.clear_input),
                                        // 44dp 行内档 + 右让 4dp：染色圆四向 8dp 均衡
                                        size = 44.dp,
                                        modifier = Modifier.padding(end = 4.dp),
                                    ) {
                                        Icon(
                                            imageVector = ImageVector.vectorResource(R.drawable.ic_close),
                                            contentDescription = null,
                                            tint = scheme.onBackgroundVariant,
                                        )
                                    }
                                }
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
                            onRetry = vm::refresh,
                            onDismiss = vm::clearBanner,
                        )
                    }
                    if (state.query.isNotBlank() && state.items.isEmpty() && !state.refreshing) {
                        // 搜索无结果：统一空态组件
                        io.candy.tick.ui.common.StatusPage(
                            icon = ImageVector.vectorResource(R.drawable.ic_search),
                            title = stringResource(R.string.search_no_results, state.query),
                            subtitle = stringResource(R.string.search_no_results_hint),
                            actionLabel = stringResource(R.string.search_clear),
                            onAction = { vm.setQuery("") },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    } else if (state.isEmpty) {
                        EmptyState(onAdd = onAdd, modifier = Modifier.weight(1f))
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 4.dp,
                                bottom = 32.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                        items(state.items, key = { it.repo.id }) { item ->
                            SwipeToDelete(
                                onDelete = { vm.remove(item) },
                                onPin = { vm.togglePin(item) },
                                pinned = item.repo.pinnedAt != null,
                                modifier = Modifier.animateItem(
                                    // 数据刷新重排=瞬时落位（suppress 由 VM 在刷新/同步期置起）；
                                    // 用户操作（置顶/撤销）=中等刚度略回弹，长距离不飘；增删=短促淡入淡出
                                    placementSpec = if (vm.suppressItemPlacement) {
                                        androidx.compose.animation.core.snap()
                                    } else {
                                        androidx.compose.animation.core.spring(
                                            dampingRatio = 0.875f,
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                                        )
                                    },
                                    fadeInSpec = androidx.compose.animation.core.tween(150),
                                    fadeOutSpec = androidx.compose.animation.core.tween(150),
                                ),
                            ) {
                                RepoCard(
                                    item = item,
                                    extras = state.cards[item.repo.id],
                                    expanded = item.repo.id in state.expanded,
                                    isChanged = item.repo.id in state.changedIds,
                                    updatable = item.repo.id in state.updatableIds,
                                    versionEpoch = state.versionEpoch,
                                    onToggle = { vm.toggleExpand(item.repo.id) },
                                    onClick = {
                                        vm.clearChanged(item.repo.id)
                                        onOpenDetail(item.repo.id)
                                    },
                                    onRetryExtras = { vm.retryCard(item.repo.id) },
                                )
                            }
                        }
                        // 到底提示：有内容就显示（含搜索过滤态）——闪现顾虑已被
                        // footer 的 animateItem 淡入淡出兜住；过滤后滚到底同样需要终点确认
                        if (state.items.isNotEmpty()) {
                            item(key = "list-end") {
                                io.candy.tick.ui.common.FooterText(
                                    text = stringResource(R.string.list_end),
                                    count = state.items.size,
                                    // 与卡片同款淡入淡出：搜索→浏览切换时终点标记不跳现
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = androidx.compose.animation.core.tween(150),
                                        fadeOutSpec = androidx.compose.animation.core.tween(150),
                                    ),
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

    SortSheet(
        show = showSortSheet,
        current = state.sortMode,
        onSelect = {
            vm.setSort(it)
            showSortSheet = false
        },
        onDismiss = { showSortSheet = false },
    )
}

/** 结果转变检测：Running→Done，或冷启动 10s 内的新鲜 Done；到位后按内容时长自动清空 */
@Composable
private fun rememberSyncResult(
    status: SyncStatus,
    consumedAt: Long,
    onConsumed: (Long) -> Unit,
): SyncStatus.Done? {
    var result by remember { mutableStateOf<SyncStatus.Done?>(null) }
    var prev by remember { mutableStateOf<SyncStatus?>(null) }
    androidx.compose.runtime.LaunchedEffect(status, consumedAt) {
        val fresh = status is SyncStatus.Done &&
            System.currentTimeMillis() - status.at < 10_000
        // 未消费闸门：at > consumedAt 才允许展示——导航往返重建后旧结果不复活
        val unseen = (status as? SyncStatus.Done)?.at?.let { it > consumedAt } == true
        when {
            prev is SyncStatus.Running && status is SyncStatus.Done && unseen -> result = status
            prev == null && fresh && unseen -> result = status // 冷启动竞态兜底
        }
        // 消费被展示的那个 result（而非强转当前 status——展示中若来新 Running 会崩）
        result?.let { onConsumed(it.at) }
        prev = status
    }
    androidx.compose.runtime.LaunchedEffect(result) {
        val r = result ?: return@LaunchedEffect
        kotlinx.coroutines.delay(if (r.changed > 0 || r.failed > 0) 4_000L else 2_000L)
        result = null
    }
    return result
}

/** 顶栏同步活体：标题右侧三点轻跳（进行中）→ 完成/失败符；固定占位零布局跳动 */
@Composable
private fun SyncDots(running: Boolean, done: SyncStatus.Done?, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    var dotsVisible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(running) {
        if (running) {
            kotlinx.coroutines.delay(300) // 近即时防闪
            dotsVisible = true
        } else {
            if (dotsVisible) kotlinx.coroutines.delay(350) // 停一拍再收
            dotsVisible = false
        }
    }
    Box(modifier = modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
        // 结果符：捕获最后一个非空结果——退出期间继续渲染，
        // 否则 done 置空的瞬间内容塌空，退出动画淡出的是空气（= 瞬间消失）
        var lastDone by remember { mutableStateOf<SyncStatus.Done?>(null) }
        if (done != null) lastDone = done
        androidx.compose.animation.AnimatedVisibility(
            visible = done != null,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(150),
            ) + androidx.compose.animation.scaleIn(
                initialScale = 0.6f,
                animationSpec = androidx.compose.animation.core.tween(150),
            ),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(150),
            ) + androidx.compose.animation.scaleOut(
                targetScale = 0.6f,
                animationSpec = androidx.compose.animation.core.tween(150),
            ),
        ) {
        lastDone?.let { d ->
            val failedOnly = d.failed > 0 && d.changed == 0
            // 结果符 Lucide 单源（ic_check/ic_close）——此前是 Unicode 文字字符，
            // 随设备字体变形且笔画与图标库不一致（与 Feedback 胶囊状态符同批替换）
            top.yukonga.miuix.kmp.basic.Icon(
                imageVector = ImageVector.vectorResource(
                    if (failedOnly) R.drawable.ic_close else R.drawable.ic_check,
                ),
                contentDescription = null,
                tint = if (failedOnly) scheme.onErrorContainer else scheme.primaryVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = dotsVisible,
            // 150 微反馈档显式 spec：默认参数是 spring(StiffnessMediumLow)——
            // 与同组件结果符（tween 150）一处两制；对齐后波点与结果符同节奏
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(150),
            ),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(150),
            ),
        ) {
            // 复用公共组件（同一波浪语义）；结果出现时淡出
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.alpha(if (done != null) 0f else 1f),
            ) {
                io.candy.tick.ui.common.LoadingDots(color = scheme.primaryVariant)
            }
        }
    }
}
