package io.candy.tick.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.candy.tick.ui.common.CandyIcons
import io.candy.tick.ui.common.SecondaryButton
import io.candy.tick.ui.common.sourceErrorText
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
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
import top.yukonga.miuix.kmp.icon.basic.Search
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
            topBar = {
                SmallTopAppBar(
                    title = stringResource(R.string.app_name),
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        // 当前排序名直接可见（安卓规范：状态外显 + 点击更改）
                        top.yukonga.miuix.kmp.basic.TextButton(
                            text = sortLabel(state.sortMode),
                            onClick = { showSortSheet = true },
                        )
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(
                                imageVector = MiuixIcons.Basic.Search,
                                contentDescription = stringResource(R.string.action_search),
                                tint = scheme.onBackground,
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = CandyIcons.Settings,
                                contentDescription = stringResource(R.string.settings_title),
                                tint = scheme.onBackground,
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = onAdd) {
                    Icon(
                        imageVector = CandyIcons.Add,
                        contentDescription = stringResource(R.string.add_repo),
                        tint = scheme.onPrimary,
                    )
                }
            },
        ) { padding ->
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
                modifier = Modifier.padding(padding),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (showSearch) {
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
                    if (state.isEmpty) {
                        EmptyState(onAdd = onAdd, modifier = Modifier.weight(1f))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 4.dp,
                                bottom = 96.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                        items(state.items, key = { it.repo.id }) { item ->
                            SwipeToDelete(
                                onDelete = { vm.remove(item) },
                                modifier = Modifier.animateItem(),
                            ) {
                                RepoCard(
                                    item = item,
                                    extras = state.cards[item.repo.id],
                                    expanded = item.repo.id in state.expanded,
                                    onToggle = { vm.toggleExpand(item.repo.id) },
                                    onClick = { onOpenDetail(item.repo.id) },
                                    onRemove = { vm.remove(item) },
                                )
                            }
                        }
                        }
                    }
                }
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

@Composable
private fun sortLabel(mode: SortMode): String = stringResource(
    when (mode) {
        SortMode.LAST_PUSH -> R.string.sort_last_push
        SortMode.STATUS -> R.string.sort_status
        SortMode.NAME -> R.string.sort_name
        SortMode.UPDATABLE -> R.string.sort_updatable
    },
)

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
