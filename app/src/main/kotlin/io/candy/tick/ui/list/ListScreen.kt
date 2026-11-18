package io.candy.tick.ui.list

import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import io.candy.tick.data.SourceError
import io.candy.tick.ui.common.SecondaryButton
import io.candy.tick.ui.common.sourceErrorText
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ListScreen(
    onAdd: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as CandyTickApp
    val vm: ListViewModel = viewModel { ListViewModel(app.container.repoRepository, app) }
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
            val allFailed = d.failed > 0 && d.changed == 0
            when {
                allFailed -> io.candy.tick.ui.common.Feedback.show(
                    text = app.getString(R.string.sync_result_all_failed, d.failed),
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
    val searchFocus = androidx.compose.ui.focus.FocusRequester()
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
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
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
                        IconButton(onClick = {
                            if (showSearch) vm.setQuery("")
                            showSearch = !showSearch
                        }) {
                            // 激活态换关闭图标：点一下能收起是可发现的
                            Icon(
                                imageVector = if (showSearch) {
                                    MiuixIcons.Close
                                } else {
                                    MiuixIcons.Basic.Search
                                },
                                contentDescription = stringResource(
                                    if (showSearch) R.string.close_search else R.string.action_search,
                                ),
                                tint = scheme.onBackground,
                            )
                        }
                        IconButton(onClick = { showSortSheet = true }) {
                            Icon(
                                imageVector = MiuixIcons.Basic.ArrowUpDown,
                                contentDescription = stringResource(R.string.sort_title),
                                tint = scheme.onBackground,
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = MiuixIcons.Settings,
                                contentDescription = stringResource(R.string.settings_title),
                                tint = scheme.onBackground,
                            )
                        }
                        // 主操作：添加仓库（顶栏最右，替代 FAB）
                        IconButton(onClick = onAdd) {
                            Icon(
                                imageVector = MiuixIcons.Add,
                                contentDescription = stringResource(R.string.add_repo),
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
                onRefresh = vm::refresh,
                pullToRefreshState = pullState,
                topAppBarScrollBehavior = scrollBehavior,
                color = scheme.primaryVariant, // 薄荷主色：与全应用活动色统一
                // 只在刷新中显示两个字：36dp 文字区是库的固定结构，用它让间距有目的
                refreshTexts = listOf("", "", stringResource(R.string.refresh_refreshing), ""),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSearch,
                        enter = androidx.compose.animation.expandVertically() +
                            androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.shrinkVertically() +
                            androidx.compose.animation.fadeOut(),
                    ) {
                        top.yukonga.miuix.kmp.basic.TextField(
                            value = state.query,
                            onValueChange = vm::setQuery,
                            label = stringResource(R.string.search_hint),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                // 实时过滤已出结果：回车只收键盘
                                onSearch = { searchFocus.freeFocus() },
                            ),
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
                        ErrorBanner(error = error, onRetry = vm::refresh)
                    }
                    if (state.query.isNotBlank() && state.items.isEmpty() && !state.refreshing) {
                        // 搜索无结果：统一空态组件
                        io.candy.tick.ui.common.StatusPage(
                            icon = MiuixIcons.Basic.Search,
                            title = stringResource(R.string.search_no_results, state.query),
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
                                    onToggle = { vm.toggleExpand(item.repo.id) },
                                    onClick = {
                                        vm.clearChanged(item.repo.id)
                                        onOpenDetail(item.repo.id)
                                    },
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

/** 刷新失败横幅：旧数据保留 + 重试（限流显示恢复时间） */
@Composable
private fun ErrorBanner(error: SourceError, onRetry: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    // 错误容器底（浅粉/深红棕）：错误语义即时可读，深浅两模式下边界都清晰
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(scheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sourceErrorText(error),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.banner_old_data_kept),
                    style = MiuixTheme.textStyles.footnote2,
                    color = scheme.onBackgroundVariant,
                )
            }
            SecondaryButton(
                text = stringResource(R.string.retry),
                onClick = onRetry,
            )
        }
    }
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
                androidx.compose.animation.core.tween(200),
            ) + androidx.compose.animation.scaleOut(
                targetScale = 0.6f,
                animationSpec = androidx.compose.animation.core.tween(200),
            ),
        ) {
        lastDone?.let { d ->
            val failedOnly = d.failed > 0 && d.changed == 0
            Text(
                text = if (failedOnly) "\u2715" else "\u2713",
                style = MiuixTheme.textStyles.footnote1,
                color = if (failedOnly) scheme.onErrorContainer else scheme.primaryVariant,
            )
        }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = dotsVisible,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
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
