package io.candy.tick.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.ui.common.AppPickerSheet
import io.candy.tick.ui.common.ErrorBanner
import io.candy.tick.ui.common.Feedback
import io.candy.tick.ui.common.PressIconButton
import io.candy.tick.ui.common.ScrollToTopTitle
import io.candy.tick.ui.common.scrollToTop
import io.candy.tick.data.InstallManager
import io.candy.tick.data.SourceError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.RefreshState
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.common.LoadingDots
import io.candy.tick.ui.common.rememberBusyIndicator
import io.candy.tick.ui.common.rememberMotionEnabled
import kotlin.math.roundToInt

@Composable
fun DetailScreen(
    repoId: Long,
    releaseRequestId: String? = null,
    releaseTag: String? = null,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as CandyTickApp
    var browsing by rememberSaveable(repoId, releaseRequestId) { mutableStateOf(releaseRequestId == null) }
    var positioned by rememberSaveable(repoId, releaseRequestId) { mutableStateOf(false) }
    var savedTab by rememberSaveable(repoId, releaseRequestId) {
        mutableStateOf(if (releaseRequestId == null) DetailViewModel.DetailTab.COMMITS else DetailViewModel.DetailTab.RELEASES)
    }
    val vm: DetailViewModel = viewModel(key = "detail-$repoId") {
        DetailViewModel(
            repoId = repoId,
            repository = app.container.repoRepository,
            installManager = app.container.installManager,
            downloadHost = app.container.repoDownloadHost,
            context = app,
            initialTab = savedTab,
        )
    }
    // 从安装器返回（ON_RESUME）：本机版本可能已变（装完新 APK），通知 VM 重算版本对比
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.onAppResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val tab = if (browsing) state.tab else DetailViewModel.DetailTab.RELEASES
    LaunchedEffect(releaseRequestId) {
        if (!browsing) vm.setTab(DetailViewModel.DetailTab.RELEASES)
    }
    val tag = releaseTag?.takeIf { releaseRequestId != null }
    val extraRelease = state.notificationRelease?.takeIf { it.tagName == tag }
    val releases = remember(state.releases, extraRelease) {
        if (extraRelease == null) state.releases else {
            val fromPage = state.releases.firstOrNull { it.tagName == extraRelease.tagName }
            listOf(fromPage ?: extraRelease) + state.releases.filterNot { it.tagName == extraRelease.tagName }
        }
    }
    val releaseIndex = remember(releases, tag) { releases.indexOfFirst { it.tagName == tag } }
    val awaitingTag = !browsing && tag != null && releaseIndex < 0
    val pageAvailable = state.releases.isNotEmpty() || state.releasesEnd || state.releasesFailed
    var lookupError by remember(repoId, releaseRequestId) { mutableStateOf<SourceError?>(null) }
    var attempt by remember(repoId, releaseRequestId) { mutableIntStateOf(0) }
    LaunchedEffect(releaseRequestId, awaitingTag, pageAvailable, attempt) {
        if (awaitingTag && pageAvailable) {
            lookupError = null
            lookupError = vm.findNotificationRelease(tag)
        }
    }
    val entryTransition = LocalNavAnimatedContentScope.current.transition
    // Start all requests immediately. Measure fresh rows after the arriving page has settled.
    // Latch this once so returning, cancelling a back gesture or scrolling never hides existing rows.
    val rowsReady by produceState(
        initialValue = entryTransition.currentState == EnterExitState.Visible ||
            state.commits.isNotEmpty() || state.releases.isNotEmpty(),
        key1 = entryTransition,
    ) {
        if (!value) {
            snapshotFlow { entryTransition.currentState == EnterExitState.Visible }
                .first { it }
            value = true
        }
    }
    val scheme = MiuixTheme.colorScheme
    val pullState = rememberPullToRefreshState()
    val pullSettled by remember(pullState) {
        derivedStateOf { pullState.refreshState == RefreshState.Idle && pullState.dragOffset <= 0f }
    }
    val pullIdle = !state.refreshing && pullSettled
    val commitsTab = tab == DetailViewModel.DetailTab.COMMITS
    val motion = rememberMotionEnabled()
    val targetPosition = if (commitsTab) 0f else 1f
    val tabPosition = animateFloatAsState(
        targetPosition, tween(if (motion) Motion.PHASE else 0, easing = Motion.Decelerate), label = "detailTab",
    )
    val tabMoving by remember(tabPosition, targetPosition) {
        derivedStateOf { tabPosition.value != targetPosition }
    }
    // The selected plate and all incoming rows follow one clock. A fetch, scroll or page entry
    // doesn't trigger it, and idle rows need no offset layer.
    val tabContentModifier = if (motion && tabMoving) {
        Modifier.offset {
            IntOffset((12.dp.toPx() * (targetPosition - tabPosition.value)).roundToInt(), 0)
        }
    } else Modifier
    val tabLoading = if (commitsTab) state.commitsLoading else state.releasesLoading
    val tabReloading = if (commitsTab) state.commitsReloading else state.releasesReloading
    val tabEmpty = if (commitsTab) state.commits.isEmpty() else releases.isEmpty()
    val firstPagePending = tabLoading && tabEmpty
    val paging = tabLoading && !tabEmpty && !tabReloading
    // A background fetch for the other tab should not keep the current tab looking busy.
    // Drop the delay/minimum-display timer while the pull header or paging owns the feedback.
    val topIndicatorAllowed = pullIdle && !paging
    val slowLoad = topIndicatorAllowed && rememberBusyIndicator(state.snapshotLoading || tabReloading || firstPagePending)
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    var showAppPicker by remember { mutableStateOf(false) }
    var showBranchSheet by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 列表状态提升：点标题回顶部需要驱动滚动动画（真实滚动让折叠顶栏自动展开）
    val detailListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val lookupTag = tag?.takeIf { awaitingTag && pageAvailable }
    // A busy header can span tab switches. Give each tab's first page its own anti-flicker delay.
    val slowFirstPage = firstPagePending && key(repoId, tab) { rememberBusyIndicator(true) }
    val placeholderCount = if (rowsReady && slowFirstPage) {
        visiblePlaceholderCount(detailListState, tab, if (lookupTag != null) RELEASE_LOOKUP_KEY else HISTORY_TABS_KEY)
    } else 0
    val historyOpacity = key(repoId, tab) {
        rememberHistoryContentOpacity(placeholderVisible = placeholderCount > 0, motionEnabled = motion)
    }
    val historyFading by remember(historyOpacity) { derivedStateOf { historyOpacity.value < 1f } }
    val historyRowModifier = if (motion && !firstPagePending && historyFading) {
        tabContentModifier.graphicsLayer { alpha = historyOpacity.value }
    } else tabContentModifier
    val titleHeight = with(LocalDensity.current) { 32.dp.roundToPx() }
    LaunchedEffect(detailListState, releaseRequestId, browsing) {
        if (!browsing) detailListState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) browsing = true
        }
    }
    LaunchedEffect(releaseRequestId, browsing, positioned, releaseIndex, rowsReady, lifecycleOwner) {
        if (browsing || positioned || !rowsReady) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { entryTransition.currentState == EnterExitState.Visible }.first { it }
            if (releaseIndex < 0) {
                // An older notification without a tag can open the tab, but identifies no release to acknowledge.
                detailListState.scrollToItem(HISTORY_TABS_INDEX)
                if (tag == null) positioned = true
            } else {
                val key = "releases:$tag"
                val targetIndex = HISTORY_TABS_INDEX + 1 + releaseIndex
                snapshotFlow { detailListState.layoutInfo.totalItemsCount }.first { it > targetIndex }
                if (!detailListState.layoutInfo.isReleaseInFocus(key)) {
                    detailListState.scrollToItem(targetIndex)
                }
                snapshotFlow { key in detailListState.layoutInfo.visibleHistoryKeys(titleHeight) }.first { it }
                positioned = true
            }
        }
    }
    val obscured = showAppPicker || showBranchSheet || showRemoveDialog
    val activity = state.snapshot?.activity
    LaunchedEffect(lifecycleOwner, releaseRequestId, browsing, tab, rowsReady, obscured, tabMoving,
        activity?.unreadReleaseTags, activity?.unreadCommitShas) {
        if (!rowsReady || obscured || tabMoving) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow {
                if (historyFading || detailListState.isScrollInProgress || entryTransition.currentState != EnterExitState.Visible ||
                    entryTransition.targetState != EnterExitState.Visible) emptySet()
                else detailListState.layoutInfo.visibleHistoryKeys(titleHeight)
            }.collect { keys ->
                if (!browsing) {
                    if (tag != null && "releases:$tag" in keys) vm.markVisibleActivity(releaseTags = setOf(tag))
                } else if (commitsTab) {
                    vm.markVisibleActivity(commitShas = keys.historyIds("commits:"))
                } else {
                    vm.markVisibleActivity(releaseTags = keys.historyIds("releases:"))
                }
            }
        }
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // 瞬时反馈走全局 FeedbackHost
    val doneMsg = stringResource(R.string.install_done)
    val failedMsg = stringResource(R.string.settings_install_failed)
    val failedWithReason = stringResource(R.string.install_failed_format)
    val mismatchMsg = stringResource(R.string.install_signature_mismatch)
    val autoLinkedFmt = stringResource(R.string.detail_auto_linked)
    val needPermMsg = stringResource(R.string.detail_need_install_permission)
    val missingDownloadMsg = stringResource(R.string.download_missing)
    val localVersion = remember(context, state.repo, state.installEpoch) {
        state.repo?.let { io.candy.tick.util.installedVersionName(context, it) }
    }

    LaunchedEffect(Unit) { vm.refresh(silent = true) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is DetailViewModel.Event.InstallResultEvent -> when (val r = event.result) {
                    is InstallManager.InstallResult.Success -> io.candy.tick.ui.common.Feedback.show(doneMsg)
                    is InstallManager.InstallResult.Failed -> io.candy.tick.ui.common.Feedback.show(
                        text = when {
                            r.signatureMismatch -> mismatchMsg
                            r.message.isNullOrBlank() -> failedMsg
                            else -> failedWithReason.format(r.message)
                        },
                        isError = true,
                    )
                    InstallManager.InstallResult.PendingUserAction -> Unit // 系统确认界面已接管
                    InstallManager.InstallResult.Cancelled -> Unit // 用户取消≠失败（design.md:180）
                    InstallManager.InstallResult.NeedsInstallPermission ->
                        io.candy.tick.ui.common.Feedback.show(text = needPermMsg, isError = true)
                }
                is DetailViewModel.Event.AutoLinked -> io.candy.tick.ui.common.Feedback.show(autoLinkedFmt.format(event.displayName))
                DetailViewModel.Event.DownloadMissing -> io.candy.tick.ui.common.Feedback.show(missingDownloadMsg, isError = true)
            }
        }
    }

    val repo = state.repo
    val webUrl = remember(repo?.owner, repo?.repo) {
        repo?.let { "https://github.com/${Uri.encode(it.owner)}/${Uri.encode(it.repo)}" }
    }
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
                SmallTopAppBar(
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    title = "", // 标题左对齐走 navigationIcon 槽位（与首页一致；右侧三个动作图标下居中会被挤偏）
                    navigationIcon = {
                        androidx.compose.foundation.layout.BoxWithConstraints {
                            // Back button, title spacing and three 48dp actions share this bar.
                            val titleMax = (maxWidth - 200.dp).coerceIn(0.dp, 180.dp)
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                io.candy.tick.ui.common.PressIconButton(
                                    onClick = onBack,
                                    // 图标旁无文字的控件必须有可访问名称（icon-button a11y）
                                    contentDescription = stringResource(R.string.back),
                                ) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_back),
                                        contentDescription = null,
                                        tint = scheme.onBackground,
                                    )
                                }
                                // 后退钮与标题的呼吸缝：48dp 热区/40dp 染色圆之外再留 8dp
                                // （skill: touch-spacing——此前圆贴标题文案）
                                Spacer(Modifier.width(8.dp))
                                ScrollToTopTitle(
                                    text = repo?.let { it.noteName ?: it.repo } ?: stringResource(R.string.detail_title),
                                    modifier = Modifier.widthIn(max = titleMax),
                                    onClick = {
                                        browsing = true
                                        scope.launch { detailListState.scrollToTop(motion) }
                                    },
                                )
                            }
                        }
                    },
                    actions = {
                        Row(
                            modifier = Modifier.width(144.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (webUrl != null) RepositoryWebButton(webUrl)
                            // 切换浏览分支（仅提交视图有分支语义；Release tab 隐藏，避免点了无效果的控件）
                            // 宽度+透明度过渡：tab 切换时图标个数不再突跳
                            androidx.compose.animation.AnimatedVisibility(
                                visible = commitsTab,
                                enter = androidx.compose.animation.fadeIn(
                                    androidx.compose.animation.core.tween(Motion.PHASE),
                                ) + androidx.compose.animation.expandHorizontally(
                                    animationSpec = androidx.compose.animation.core.tween(Motion.PHASE),
                                ),
                                exit = androidx.compose.animation.fadeOut(
                                    androidx.compose.animation.core.tween(Motion.PHASE),
                                ) + androidx.compose.animation.shrinkHorizontally(
                                    animationSpec = androidx.compose.animation.core.tween(Motion.PHASE),
                                ),
                            ) {
                                io.candy.tick.ui.common.PressIconButton(
                                    onClick = {
                                        showBranchSheet = true
                                        vm.loadBranches()
                                    },
                                    contentDescription = stringResource(R.string.branch_picker_title),
                                ) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_git_branch),
                                        contentDescription = null,
                                        tint = scheme.onBackground,
                                    )
                                }
                            }
                            // 移除订阅（destructive → 确认对话框；底部文字按钮已移除）
                            io.candy.tick.ui.common.PressIconButton(
                                onClick = { showRemoveDialog = true },
                                contentDescription = stringResource(R.string.detail_remove_repo),
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_delete),
                                    contentDescription = null,
                                    tint = scheme.onBackground,
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
        },
    ) { padding ->
        io.candy.tick.ui.common.CandyPullToRefresh(
            requestState = vm.state,
            refreshing = { it.refreshing },
            onRefresh = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress) // 松手触发确认：与首页同款
                vm.refresh()
            },
            pullToRefreshState = pullState,
            topAppBarScrollBehavior = scrollBehavior,
            modifier = Modifier.padding(padding),
        ) {
            LazyColumn(
                state = detailListState,
                modifier = Modifier.fillMaxSize(),
                // 底部 32：全 App 滚动终止留白统一档（footer 元素上下间距见 FooterText v8 + 此 32）
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(HistoryItemSpacing),
            ) {
                item {
                    DetailSyncIndicator(visible = slowLoad, allowed = topIndicatorAllowed)
                    // 头卡组（头卡 + 错误横幅）：banner 隐藏时 0 高度收在组内——
                    // 若作独立 item，spacedBy 会在其前后各记一次，tab 上方凭空多 10dp
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HeaderCard(
                            state = state,
                            localVersion = localVersion,
                            onChangeLinked = { showAppPicker = true },
                            onUnlink = {
                                scope.launch {
                                    vm.setLinkedApp(null)
                                    // 不出胶囊：头卡关联行当场消失，视觉已确认
                                }
                            },
                            onLinkSuggestion = {
                                state.linkSuggestion?.let { (pkg, label) -> vm.setLinkedApp(pkg, newLabel = label) }
                            },
                            onDownloadUpdate = vm::downloadUpdate,
                            onInstallUpdate = { repo?.let(vm::installDownloaded) },
                            onCancelDownload = vm::cancelDownload,
                        )
                        // 错误横幅出现/消失过渡（220 表层档）：刷新失败/恢复不再硬跳
                        androidx.compose.animation.AnimatedVisibility(
                            visible = state.banner != null,
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
                            state.banner?.let { error ->
                                // 共用失败横幅（errorContainer 底 + 重试钮，与首页同构）；
                                // 用户可不刷新只关提示（dismissBanner 由此接线）
                                ErrorBanner(
                                    error = error,
                                    hasSavedDetails = state.snapshot != null,
                                    onRetry = vm::refresh,
                                    onDismiss = vm::dismissBanner,
                                )
                            }
                        }
                    }
                }
                item(key = HISTORY_TABS_KEY) {
                    DetailTabs(
                        selected = tab,
                        position = tabPosition,
                        onSelect = { selected ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            savedTab = selected
                            vm.setTab(selected)
                            browsing = true
                        },
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                if (!rowsReady) return@LazyColumn
                if (lookupTag != null) {
                    item(key = RELEASE_LOOKUP_KEY) {
                        val message = when (lookupError) {
                            null -> R.string.detail_finding_release
                            SourceError.NotFound -> R.string.detail_notified_release_missing
                            else -> R.string.detail_notified_release_failed
                        }
                        io.candy.tick.ui.common.StatusPage(
                            icon = null,
                            compact = true,
                            title = stringResource(message, lookupTag),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            actionLabel = if (lookupError != null) stringResource(R.string.retry) else null,
                            onAction = { attempt++ },
                        )
                    }
                }
                if (firstPagePending) {
                    historyPlaceholders(tab, placeholderCount, motion)
                    return@LazyColumn
                }
                // 提交跳转基址 + 本机已装版本（Screen 层算一次：列表卡不重复查 pm）
                val htmlBase = webUrl ?: return@LazyColumn
                if (commitsTab) {
                    commitList(
                        state = state,
                        contentModifier = historyRowModifier,
                        htmlBase = htmlBase,
                        showPagingIndicator = pullIdle,
                        onLoadMore = vm::loadCommits,
                    )
                } else {
                    releaseList(
                        state = state,
                        releases = releases,
                        notificationTag = if (!browsing || extraRelease != null) tag else null,
                        contentModifier = historyRowModifier,
                        localVersion = localVersion,
                        htmlBase = htmlBase,
                        showPagingIndicator = pullIdle,
                        onLoadMore = vm::loadReleases,
                        onSelectAsset = vm::selectAsset,
                        onRead = {
                            positioned = true
                            vm.markVisibleActivity(releaseTags = setOf(it))
                        },
                        onDownload = { release, asset ->
                            positioned = true
                            vm.downloadAsset(release, asset)
                        },
                        onRetry = { release, asset ->
                            positioned = true
                            vm.retryDownload(release, asset)
                        },
                        onInstallDownloaded = { repo?.let(vm::installDownloaded) },
                        onDeleteDownloaded = vm::deleteDownloaded,
                        onCancelDownload = vm::cancelDownload,
                    )
                }
            }
        }
    }

    AppPickerSheet(
        show = showAppPicker,
        onPick = { pkg, label ->
            vm.setLinkedApp(pkg, newLabel = label)
            showAppPicker = false
        },
        onDismiss = { showAppPicker = false },
    )

    // 分支选择弹层（提交视图浏览分支）：show 直通，退场有动效
    BranchPickerSheet(
        show = showBranchSheet,
        state = state,
        onSelect = {
            vm.setBranch(it)
            showBranchSheet = false
        },
        onDismiss = { showBranchSheet = false },
        onLoadBranches = vm::loadBranches,
    )

    // 移除确认（破坏性操作）：show 直通，退场有动效
    RemoveRepoDialog(
        show = showRemoveDialog && repo != null,
        repoName = repo?.let { it.noteName ?: "${it.owner}/${it.repo}" } ?: "",
        onConfirm = {
            // 破坏性确认时刻：轻触感（视觉红按钮之外的物理确认）
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showRemoveDialog = false
            vm.removeRepo(onBack)
        },
        onDismiss = { showRemoveDialog = false },
    )
}

private const val HISTORY_TABS_INDEX = 1
private const val HISTORY_TABS_KEY = "history-tabs"
private const val RELEASE_LOOKUP_KEY = "notification-release-lookup"

@Composable
private fun RepositoryWebButton(url: String) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val copyLabel = stringResource(R.string.copy_repo_link)
    PressIconButton(
        contentDescription = stringResource(R.string.open_in_browser),
        onClick = {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.onFailure { Feedback.show(resources.getString(R.string.open_failed), isError = true) }
        },
        onLongClickLabel = copyLabel,
        onLongClick = {
            runCatching {
                val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
                clipboard.setPrimaryClip(ClipData.newPlainText(copyLabel, url))
            }.onSuccess {
                Feedback.show(resources.getString(R.string.toast_copied))
            }.onFailure { Feedback.show(resources.getString(R.string.copy_repo_link_failed), isError = true) }
        },
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_globe),
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

private fun LazyListLayoutInfo.isReleaseInFocus(key: String): Boolean {
    val item = visibleItemsInfo.firstOrNull { it.key == key } ?: return false
    val viewportHeight = viewportEndOffset - viewportStartOffset
    val needed = minOf(item.size, viewportHeight * 2 / 3)
    return item.offset >= viewportStartOffset && item.offset + needed <= viewportEndOffset
}

private fun LazyListLayoutInfo.visibleHistoryKeys(titleHeight: Int): Set<String> =
    visibleItemsInfo.mapNotNullTo(mutableSetOf()) { item ->
        val key = item.key as? String ?: return@mapNotNullTo null
        val titleEnd = item.offset + minOf(titleHeight, item.size)
        key.takeIf {
            (item.contentType == "release" || item.contentType == "commit") &&
                item.offset >= viewportStartOffset && titleEnd <= viewportEndOffset
        }
    }

private fun Set<String>.historyIds(prefix: String): Set<String> =
    mapNotNullTo(mutableSetOf()) { if (it.startsWith(prefix)) it.removePrefix(prefix) else null }

/** 静默同步使用首卡上方既有留白，随卡片滚动。 */
@Composable
private fun DetailSyncIndicator(visible: Boolean, allowed: Boolean) {
    Box(
        // The toolbar already has space below its painted buttons. Balance that space
        // with 8dp below the dots’ 9dp wave envelope, in both idle and loading states.
        modifier = Modifier.fillMaxWidth().height(17.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (allowed) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(Motion.MICRO)),
                exit = fadeOut(tween(Motion.MICRO)),
            ) {
                LoadingDots(dotSize = 4.dp)
            }
        }
    }
}
