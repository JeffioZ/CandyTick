package io.candy.tick.ui.list

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.data.SourceError
import io.candy.tick.ui.common.CandyIcons
import io.candy.tick.ui.common.sourceErrorText
import io.candy.tick.ui.theme.DarkPrimary
import io.candy.tick.ui.theme.LightPrimary
import io.candy.tick.ui.theme.LocalDarkTheme
import top.yukonga.miuix.kmp.basic.Button
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
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

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
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = { showSortSheet = true }) {
                            Icon(
                                imageVector = MiuixIcons.Basic.ArrowUpDown,
                                contentDescription = stringResource(R.string.sort_title),
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
                modifier = Modifier.padding(padding),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
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
                            RepoCard(
                                item = item,
                                extras = state.cards[item.repo.id],
                                expanded = item.repo.id in state.expanded,
                                onToggle = { vm.toggleExpand(item.repo.id) },
                                onClick = { onOpenDetail(item.repo.id) },
                                onRemove = { vm.remove(item) },
                                modifier = Modifier.animateItem(),
                            )
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
            Button(onClick = onRetry) {
                Text(
                    text = stringResource(R.string.retry),
                    color = scheme.onError,
                    style = MiuixTheme.textStyles.button,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    val dark = LocalDarkTheme.current
    val mint = if (dark) DarkPrimary else LightPrimary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .background(scheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EchoPing(mint = mint)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.empty_title),
            style = MiuixTheme.textStyles.title4,
            color = scheme.onBackground,
        )
        Text(
            text = stringResource(R.string.empty_subtitle),
            style = MiuixTheme.textStyles.body2,
            color = scheme.onBackgroundVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAdd) {
            Text(
                text = stringResource(R.string.empty_cta),
                color = scheme.onPrimary,
                style = MiuixTheme.textStyles.button,
            )
        }
    }
}

/** 回声「ping」：糖珠 + 两圈涟漪扩散（仅空态非滚动路径，尊重系统动画时长缩放） */
@Composable
private fun EchoPing(mint: androidx.compose.ui.graphics.Color) {
    val context = LocalContext.current
    val animationsOn = remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
    val progress by if (animationsOn) {
        rememberInfiniteTransition(label = "echoPing").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing)),
            label = "echoPingProgress",
        )
    } else {
        remember { mutableStateOf(0.55f) }
    }
    Box(modifier = Modifier.size(140.dp), contentAlignment = Alignment.Center) {
        listOf(0f, 0.5f).forEach { offset ->
            val p = (progress + offset) % 1f
            Box(
                modifier = Modifier
                    .size((36 + 92 * p).dp)
                    .graphicsLayer { alpha = (1f - p) * 0.4f }
                    .border(width = 2.dp, color = mint, shape = CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(mint),
        )
    }
}

@Composable
private fun SortSheet(
    show: Boolean,
    current: SortMode,
    onSelect: (SortMode) -> Unit,
    onDismiss: () -> Unit,
) {
    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.sort_title),
        onDismissRequest = onDismiss,
    ) {
        Column {
            SortRow(
                label = stringResource(R.string.sort_last_push),
                selected = current == SortMode.LAST_PUSH,
                onClick = { onSelect(SortMode.LAST_PUSH) },
            )
            SortRow(
                label = stringResource(R.string.sort_status),
                selected = current == SortMode.STATUS,
                onClick = { onSelect(SortMode.STATUS) },
            )
            SortRow(
                label = stringResource(R.string.sort_name),
                selected = current == SortMode.NAME,
                onClick = { onSelect(SortMode.NAME) },
            )
            SortRow(
                label = stringResource(R.string.sort_updatable),
                selected = current == SortMode.UPDATABLE,
                onClick = { onSelect(SortMode.UPDATABLE) },
            )
        }
    }
}

@Composable
private fun SortRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = null,
                    tint = scheme.primaryVariant,
                )

            }
        }
        Spacer(Modifier.size(10.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = if (selected) scheme.primaryVariant else scheme.onBackground,
        )
    }
}
