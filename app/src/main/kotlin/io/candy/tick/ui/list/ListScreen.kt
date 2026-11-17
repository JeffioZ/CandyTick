package io.candy.tick.ui.list

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import io.candy.tick.data.SyncStatus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.basic.Search
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
    val syncResult = rememberSyncResult(syncStatus)
    val scheme = MiuixTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pullState = rememberPullToRefreshState()
    var showSortSheet by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val removedMsg = stringResource(R.string.removed)
    val undoLabel = stringResource(R.string.undo)

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is ListViewModel.Event.Removed -> {
                    val result = snackbar.showSnackbar(
                        message = removedMsg,
                        actionLabel = undoLabel,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        vm.restore(event.repo, event.snapshot)
                    }
                }
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
                            Icon(
                                imageVector = MiuixIcons.Basic.Search,
                                contentDescription = stringResource(R.string.action_search),
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
                refreshTexts = listOf(
                    stringResource(R.string.refresh_pull_down),
                    stringResource(R.string.refresh_release),
                    stringResource(R.string.refresh_refreshing),
                    stringResource(R.string.refresh_done),
                ),
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    state.banner?.let { error ->
                        ErrorBanner(error = error, onRetry = vm::refresh)
                    }
                    if (state.query.isNotBlank() && state.items.isEmpty() && !state.refreshing) {
                        // 搜索无结果：提示 + 快捷清除
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.search_no_results, state.query),
                                style = MiuixTheme.textStyles.body2,
                                color = scheme.onBackgroundVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            top.yukonga.miuix.kmp.basic.TextButton(
                                text = stringResource(R.string.search_clear),
                                onClick = { vm.setQuery("") },
                            )
                        }
                    } else if (state.isEmpty) {
                        EmptyState(onAdd = onAdd, modifier = Modifier.weight(1f))
                    } else {
                        LazyColumn(
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
                                modifier = Modifier.animateItem(),
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
            ResultCapsule(
                done = syncResult,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            }
        }

        // 撤销 Snackbar 覆盖层（无指针消费，不挡触摸）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            SnackbarHost(state = snackbar)
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
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
private fun rememberSyncResult(status: SyncStatus): SyncStatus.Done? {
    var result by remember { mutableStateOf<SyncStatus.Done?>(null) }
    var prev by remember { mutableStateOf<SyncStatus?>(null) }
    androidx.compose.runtime.LaunchedEffect(status) {
        val fresh = status is SyncStatus.Done &&
            System.currentTimeMillis() - status.at < 10_000
        when {
            prev is SyncStatus.Running && status is SyncStatus.Done -> result = status
            prev == null && fresh -> result = status // 冷启动竞态兜底
        }
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
        // 结果符（与胶囊同时出现/消失）
        done?.let { d ->
            val failedOnly = d.failed > 0 && d.changed == 0
            Text(
                text = if (failedOnly) "\u2715" else "\u2713",
                style = MiuixTheme.textStyles.footnote1,
                color = if (failedOnly) scheme.onErrorContainer else scheme.primaryVariant,
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = dotsVisible,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "syncDots")
            val t by infinite.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing),
                ),
                label = "syncT",
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.alpha(if (done != null) 0f else 1f),
            ) {
                repeat(3) { i ->
                    val phase = (((t - i / 3f) % 1f) + 1f) % 1f
                    // 双向正弦：围绕布局中心上下对称摆动（±2.5dp），静止态恰在中心线上
                    val wave = kotlin.math.sin(phase * 2 * Math.PI).toFloat()
                    Box(
                        modifier = Modifier
                            .offset(y = (-2.5).dp * wave)
                            .size(4.dp)
                            .alpha(0.45f + 0.55f * (0.5f + 0.5f * wave))
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(scheme.primaryVariant),
                    )
                }
            }
        }
    }
}

/** 结果胶囊：悬浮列表区顶部，不推挤内容；信息层（状态符号在顶栏） */
@Composable
private fun ResultCapsule(done: SyncStatus.Done?, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        androidx.compose.animation.AnimatedVisibility(
            visible = done != null,
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.slideInVertically(initialOffsetY = { -it / 2 }),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            done?.let { d ->
                val allFailed = d.failed > 0 && d.changed == 0
                val text = when {
                    allFailed -> stringResource(R.string.sync_result_all_failed, d.failed)
                    d.changed > 0 -> buildString {
                        append(stringResource(R.string.sync_result_updated, d.changed))
                        if (d.failed > 0) append(stringResource(R.string.sync_result_failed_suffix, d.failed))
                    }
                    else -> stringResource(R.string.sync_result_fresh)
                }
                Row(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .shadow(4.dp, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                        .background(scheme.surface)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (allFailed) "\u2715" else "\u2713",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (allFailed) scheme.onErrorContainer else scheme.primaryVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onBackground,
                    )
                }
            }
        }
    }
}
