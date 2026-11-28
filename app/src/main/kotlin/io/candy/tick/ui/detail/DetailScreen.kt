package io.candy.tick.ui.detail

import io.candy.tick.ui.common.topBarProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
import io.candy.tick.ui.common.rememberExternalUriHandler
import io.candy.tick.ui.common.PressIconButton
import io.candy.tick.ui.common.ScrollToTopTitle
import io.candy.tick.ui.common.TopBarActionRow
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import io.candy.tick.ui.theme.LocalDarkTheme
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.common.rememberMotionEnabled
import kotlin.math.roundToInt

@Composable
fun DetailScreen(
    repoId: Long,
    releaseRequestId: String? = null,
    releaseTag: String? = null,
    onBack: () -> Unit,
    onRemoved: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as CandyTickApp
    var interacted by rememberSaveable(repoId) { mutableStateOf(false) }
    var browsing by rememberSaveable(repoId, releaseRequestId) { mutableStateOf(releaseRequestId == null) }
    var positioned by rememberSaveable(repoId, releaseRequestId) { mutableStateOf(false) }
    // 版本更新日志的展开集合（页面级持有：滚出屏保持、跨页签保持，标题「已在顶部再点一下」可全收起）
    var expandedNotes by rememberSaveable(repoId) { mutableStateOf(setOf<String>()) }
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
    val latest by vm.state.collectAsStateWithLifecycle()
    val pullState = rememberPullToRefreshState()
    var pullFrame by remember(vm) { mutableStateOf<DetailViewModel.UiState?>(null) }
    val headerRefreshing = pullState.refreshState == RefreshState.Refreshing
    // Both the header text and the content switch on Miuix's actual completion transition.
    // Network results can arrive one composition before the header observes the finished batch.
    val state = pullFrame?.takeIf { headerRefreshing && !latest.refreshing } ?: latest
    androidx.compose.runtime.SideEffect {
        if (pullFrame != null) {
            when {
                latest.refreshing -> pullFrame = latest
                !headerRefreshing -> pullFrame = null
            }
        }
    }
    val tab = if (browsing) state.tab else DetailViewModel.DetailTab.RELEASES
    LaunchedEffect(releaseRequestId) {
        if (!browsing) vm.setTab(DetailViewModel.DetailTab.RELEASES)
    }
    var packageTag by rememberSaveable(repoId, releaseRequestId) { mutableStateOf<String?>(null) }
    val tag = packageTag ?: releaseTag?.takeIf { releaseRequestId != null }
    val extraRelease = state.requestedRelease?.takeIf { it.tagName == tag }
    val releaseTagNames = remember(state.releases, extraRelease) {
        (state.releases + listOfNotNull(extraRelease)).mapTo(HashSet()) { it.tagName }
    }
    val releases = remember(state.releases, extraRelease) {
        if (extraRelease == null) state.releases else {
            val fromPage = state.releases.firstOrNull { it.tagName == extraRelease.tagName }
            listOf(fromPage ?: extraRelease) + state.releases.filterNot { it.tagName == extraRelease.tagName }
        }
    }
    val releaseIndex = remember(releases, tag) { releases.indexOfFirst { it.tagName == tag } }
    val awaitingTag = !browsing && tag != null && releaseIndex < 0
    val pageAvailable = !state.releasesReloading &&
        (state.releases.isNotEmpty() || state.releasesEnd || state.releasesFailed)
    var lookupError by remember(repoId, releaseRequestId, tag) { mutableStateOf<SourceError?>(null) }
    var attempt by remember(repoId, releaseRequestId) { mutableIntStateOf(0) }
    LaunchedEffect(releaseRequestId, tag, awaitingTag, pageAvailable, attempt) {
        if (awaitingTag && pageAvailable) {
            lookupError = null
            lookupError = vm.findRelease(tag)
        }
    }
    val entryTransition = LocalNavAnimatedContentScope.current.transition
    // History travels with the page. Only acknowledgement, auto-scroll and refresh animations wait for entry.
    val entrySettled = entryTransition.currentState == EnterExitState.Visible &&
        entryTransition.targetState == EnterExitState.Visible
    val scheme = MiuixTheme.colorScheme
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
    val tabReloading = if (commitsTab) state.commitsReloading else state.releasesReloading
    val tabEmpty = if (commitsTab) state.commits.isEmpty() else releases.isEmpty()
    // The selected plate and all incoming rows follow one clock. A fetch, scroll or page entry
    // doesn't trigger it, idle rows need no offset layer, and centered empty/error states stay still.
    val tabContentModifier = if (motion && tabMoving && !tabEmpty) {
        Modifier.offset {
            IntOffset((12.dp.toPx() * (targetPosition - tabPosition.value)).roundToInt(), 0)
        }
    } else Modifier
    // The download notification can outlive the in-memory release page or the remote release itself.
    val localTarget = state.repo?.downloadedApk?.takeIf { packageTag == null && !commitsTab && it.version == tag && releaseIndex < 0 }
    val commitsPending = state.commitsLoading && state.commits.isEmpty() && !state.commitsEnd && !state.commitsFailed
    val releasesPending = state.releasesLoading && releases.isEmpty() && localTarget == null && !state.releasesEnd && !state.releasesFailed
    val firstPagePending = if (commitsTab) commitsPending else releasesPending
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    var showAppPicker by rememberSaveable(repoId) { mutableStateOf(false) }
    var showBranchSheet by rememberSaveable(repoId) { mutableStateOf(false) }
    var showRemoveDialog by remember(repoId) { mutableStateOf(false) }
    var showMoreSheet by remember(repoId) { mutableStateOf(false) }
    var showTranslationDisclosure by rememberSaveable(repoId) { mutableStateOf(false) }
    LaunchedEffect(showBranchSheet) { if (showBranchSheet) vm.loadBranches() }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 列表状态提升：点标题回顶部需要驱动滚动动画（真实滚动让折叠顶栏自动展开）
    val detailListState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 标题「已在顶部」判定：derivedStateOf 只在越过边界时重组标题
    val detailAtTop by remember { derivedStateOf { !detailListState.canScrollBackward && !detailListState.isScrollInProgress } }
    val lookupTag = tag?.takeIf { awaitingTag && pageAvailable && localTarget == null }
    val obscured = showAppPicker || showBranchSheet || showRemoveDialog || showMoreSheet || showTranslationDisclosure
    val accessibility = remember(context) { context.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    val touchExploration by produceState(accessibility?.isTouchExplorationEnabled == true, accessibility) {
        if (accessibility == null) return@produceState
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { value = it }
        accessibility.addTouchExplorationStateChangeListener(listener)
        value = accessibility.isTouchExplorationEnabled
        awaitDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    // Keep the item transition enabled through the frame that publishes new first-page rows.
    var animateRefreshChanges by remember(repoId, tab) { mutableStateOf(false) }
    LaunchedEffect(tabReloading, tabEmpty, tabMoving, motion) {
        if (motion && !tabEmpty && !tabMoving && tabReloading) {
            animateRefreshChanges = true
        } else if (animateRefreshChanges) {
            if (motion && !tabMoving) kotlinx.coroutines.delay(Motion.PANEL.toLong())
            animateRefreshChanges = false
        }
    }
    // Both requests start on entry, so switching tabs should not restart either grace period.
    val commitsPlaceholderReady = key(repoId, state.repo?.branch) { firstPagePlaceholderReady(commitsPending) }
    val releasesPlaceholderReady = key(repoId) { firstPagePlaceholderReady(releasesPending) }
    val showPlaceholders = if (commitsTab) commitsPlaceholderReady else releasesPlaceholderReady
    val placeholderCount = if (showPlaceholders) {
        historyPlaceholderCount(detailListState, tab, if (lookupTag != null) RELEASE_LOOKUP_KEY else HISTORY_TABS_KEY)
    } else 0
    val historyOpacity = key(repoId, tab) {
        rememberHistoryContentOpacity(detailListState, tab, showPlaceholders, motion)
    }
    val historyFading by remember(historyOpacity) { derivedStateOf { historyOpacity.value < 1f } }
    val historyRowModifier = if (motion && !firstPagePending && historyFading) {
        tabContentModifier.graphicsLayer { alpha = historyOpacity.value }
    } else tabContentModifier
    val titleHeight = with(LocalDensity.current) { 32.dp.roundToPx() }
    LaunchedEffect(detailListState) {
        snapshotFlow { detailListState.canScrollBackward }.first { it }
        interacted = true
    }
    LaunchedEffect(detailListState, releaseRequestId, browsing) {
        if (!browsing) detailListState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) browsing = true
        }
    }
    val protectBrowsing = browsing && (interacted || touchExploration)
    LaunchedEffect(vm, tab, tabReloading, state.manualRefresh, protectBrowsing, browsing, positioned, tag) {
        if (!tabReloading || state.manualRefresh) return@LaunchedEffect
        val notificationTarget = tag?.takeIf { !browsing && positioned }
        if (!protectBrowsing && notificationTarget == null) return@LaunchedEffect
        val prefix = if (commitsTab) "commits:" else "releases:"
        try {
            snapshotFlow {
                val visible = detailListState.layoutInfo.visibleItemsInfo
                val ids = visible.mapNotNullTo(mutableSetOf()) { item ->
                    val key = item.key as? String ?: return@mapNotNullTo null
                    if (!key.startsWith(prefix)) return@mapNotNullTo null
                    key.removePrefix(prefix).takeIf { notificationTarget == null || it == notificationTarget }
                }
                val anchor = visible.firstOrNull { it.index == detailListState.firstVisibleItemIndex }?.key as? String
                ids to (anchor?.startsWith(prefix) != true)
            }.collect { (ids, headerInView) -> vm.protectReadingRows(tab, ids, headerInView) }
        } finally {
            vm.protectReadingRows(tab, emptySet())
        }
    }
    val selectPackageMsg = stringResource(R.string.detail_select_asset_hint)
    LaunchedEffect(releaseRequestId, tag, browsing, positioned, releaseIndex, localTarget, entrySettled, lifecycleOwner) {
        if (browsing || positioned || !entrySettled) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (releaseIndex < 0) {
                // An older notification without a tag can open the tab, but identifies no release to acknowledge.
                detailListState.scrollToItem(if (localTarget == null) HISTORY_TABS_INDEX else HISTORY_TABS_INDEX + 1)
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
                if (packageTag == tag && releases[releaseIndex].apkAssets.isNotEmpty()) {
                    io.candy.tick.ui.common.Feedback.show(selectPackageMsg)
                }
            }
        }
    }
    val activity = state.snapshot?.activity
    LaunchedEffect(lifecycleOwner, releaseRequestId, tag, browsing, tab, entrySettled, obscured, tabMoving,
        activity?.unreadReleaseTags, activity?.unreadCommitShas) {
        if (!entrySettled || obscured || tabMoving) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow {
                if (historyFading || detailListState.isScrollInProgress) emptySet()
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

    LaunchedEffect(vm, releaseRequestId) {
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
                        errorCode = when {
                            r.signatureMismatch -> "E-APK-SIG"
                            r.statusCode != null -> "E-APK-${r.statusCode}"
                            else -> "E-APK"
                        },
                    )
                    InstallManager.InstallResult.PendingUserAction -> Unit // 系统确认界面已接管
                    InstallManager.InstallResult.Cancelled -> Unit // 用户取消≠失败（design.md:180）
                    InstallManager.InstallResult.NeedsInstallPermission ->
                        io.candy.tick.ui.common.Feedback.show(text = needPermMsg, isError = true, errorCode = "E-PERM")
                }
                is DetailViewModel.Event.AutoLinked -> io.candy.tick.ui.common.Feedback.show(autoLinkedFmt.format(event.displayName))
                DetailViewModel.Event.DownloadMissing ->
                    io.candy.tick.ui.common.Feedback.show(missingDownloadMsg, isError = true, errorCode = "E-FILE")
                is DetailViewModel.Event.ChoosePackage -> {
                    browsing = false
                    positioned = false
                    savedTab = DetailViewModel.DetailTab.RELEASES
                    packageTag = event.tag
                    attempt++
                }
            }
        }
    }

    val repo = state.repo
    val webUrl = remember(repo?.owner, repo?.repo) {
        repo?.let { "https://github.com/${Uri.encode(it.owner)}/${Uri.encode(it.repo)}" }
    }
    // ── 一键翻译（总开关，仅翻译当前可见/已展开内容，跟随应用语言）──
    val appState by app.container.appStateStore.state.collectAsStateWithLifecycle()
    val targetLang = remember(appState.settings.language) {
        app.container.translator.resolveTargetLanguage(appState.settings.language)
    }
    var translateEnabled by rememberSaveable(repoId) { mutableStateOf(false) }
    var translating by remember { mutableStateOf(false) }
    var failedBodies by remember(repoId, targetLang) { mutableStateOf<Set<String>>(emptySet()) }
    var translations by remember(repoId, targetLang) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val translateFailedMsg = stringResource(R.string.detail_translate_failed)
    val visibleItemKeys by remember(detailListState) {
        derivedStateOf { detailListState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }
    }
    LaunchedEffect(
        translateEnabled,
        appState.settings.translationDisclosureAccepted,
        targetLang,
        commitsTab,
        repo?.id,
        state.snapshot?.description,
        state.commits,
        releases,
        expandedNotes,
        visibleItemKeys,
    ) {
        if (!translateEnabled || !appState.settings.translationDisclosureAccepted) {
            translating = false
            failedBodies = emptySet()
            return@LaunchedEffect
        }
        val translator = app.container.translator
        val shortMissing = LinkedHashSet<String>()
        val markdownMissing = LinkedHashSet<String>()
        val cachedHits = LinkedHashMap<String, String>()

        fun collectShort(text: String?) {
            val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (trimmed in translations) return
            val cached = translator.getCached(trimmed, targetLang)
            if (cached != null) cachedHits[trimmed] = cached else shortMissing.add(trimmed)
        }

        // 1) 仓库简介
        collectShort(state.snapshot?.description)

        // 2) 当前页签可见内容（不展示的不翻）
        if (commitsTab) {
            val visibleIndices = state.commits.indices.filter { "commits:${state.commits[it].sha}" in visibleItemKeys }
            val start = ((visibleIndices.minOrNull() ?: 0) - 2).coerceAtLeast(0)
            val end = ((visibleIndices.maxOrNull() ?: (state.commits.size.coerceAtMost(12) - 1)) + 3)
                .coerceAtMost(state.commits.lastIndex)
            if (start <= end) {
                for (i in start..end) {
                    collectShort(commitTitlePreview(state.commits[i].message))
                }
            }
        } else {
            val visibleIndices = releases.indices.filter { "releases:${releases[it].tagName}" in visibleItemKeys }
            val start = ((visibleIndices.minOrNull() ?: 0) - 1).coerceAtLeast(0)
            val end = ((visibleIndices.maxOrNull() ?: (releases.size.coerceAtMost(8) - 1)) + 2)
                .coerceAtMost(releases.lastIndex)
            if (start <= end) {
                for (i in start..end) {
                    val rel = releases[i]
                    val customName = rel.name?.trim()?.takeIf {
                        it.isNotEmpty() && !io.candy.tick.util.VersionCompare.displayVersion(it)
                            .equals(io.candy.tick.util.VersionCompare.displayVersion(rel.tagName), ignoreCase = true)
                    }
                    collectShort(customName)
                }
            }
            for (rel in releases) {
                if (rel.tagName in expandedNotes &&
                    rel.body.isNotBlank() &&
                    rel.body !in translations &&
                    rel.body !in failedBodies
                ) {
                    val cached = translator.getCached(rel.body, targetLang)
                    if (cached != null) cachedHits[rel.body] = cached else markdownMissing.add(rel.body)
                }
            }
        }

        if (cachedHits.isNotEmpty()) {
            translations = translations + cachedHits
        }
        if (shortMissing.isEmpty() && markdownMissing.isEmpty()) {
            translating = false
            return@LaunchedEffect
        }

        // 快速滑动期间轻防抖：避免每滚过一项就反复中断/重发翻译请求
        if (detailListState.isScrollInProgress) {
            kotlinx.coroutines.delay(180)
        }

        translating = true
        try {
            var anySucceeded = false
            // 已展开的更新日志优先翻译：用户点开卡片正盯着正文等，先响应视线焦点
            for (body in markdownMissing) {
                val translated = translator.translateMarkdown(body, targetLang)
                if (translated != null) {
                    anySucceeded = true
                    failedBodies = failedBodies - body
                    translations = translations + (body to translated)
                } else {
                    failedBodies = failedBodies + body
                }
            }
            if (shortMissing.isNotEmpty()) {
                val batch = translator.translateBatch(shortMissing.toList(), targetLang)
                if (batch.isNotEmpty()) {
                    anySucceeded = true
                    translations = translations + batch
                }
            }
            if (!anySucceeded && cachedHits.isEmpty()) {
                io.candy.tick.ui.common.Feedback.show(
                    translateFailedMsg,
                    isError = true,
                    errorCode = translator.lastErrorCode ?: "E-TR",
                )
                if (translations.isEmpty()) translateEnabled = false
            }
        } finally {
            translating = false
        }
    }
    val pageBackdrop = if (top.yukonga.miuix.kmp.material.isRuntimeShaderSupported()) {
        top.yukonga.miuix.kmp.blur.rememberLayerBackdrop(backgroundColor = scheme.background)
    } else null
    Scaffold(
        modifier = Modifier
            .onPreviewKeyEvent {
                interacted = true
                false
            }
            .pointerInput(repoId) {
                awaitPointerEventScope {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.type != PointerEventType.Press && event.type != PointerEventType.Scroll)
                }
                interacted = true
            },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            val detailScrolled by remember { derivedStateOf { detailListState.canScrollBackward } }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .topBarProgressiveBlur(pageBackdrop, active = detailScrolled),
            ) {
                SmallTopAppBar(
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    title = "",
                    actions = {
                        TopBarActionRow(minimum = 48.dp * 4 + 12.dp) {
                            PressIconButton(
                                onClick = { if (showAppPicker) showAppPicker = false else onBack() },
                                contentDescription = stringResource(R.string.back),
                                containerColor = androidx.compose.ui.graphics.Color.Unspecified,
                                glass = true,
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_back),
                                    contentDescription = null,
                                    tint = scheme.onBackground,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f).padding(end = 4.dp)) {
                                ScrollToTopTitle(
                                    text = repo?.let { it.noteName ?: it.repo } ?: stringResource(R.string.detail_title),
                                    actionLabel = if (detailAtTop && !commitsTab &&
                                        expandedNotes.count { it in releaseTagNames } > 0
                                    ) R.string.collapse_all else R.string.scroll_to_top,
                                    onClick = {
                                        browsing = true
                                        // 已在顶部再点一下：收起版本页签全部展开的更新日志。
                                        // 提交页签无展开内容 → 维持无操作（不碰另一页签的展开——不可见的变更没有反馈）；
                                        // 门禁只认当前列表里确有展开项——集合可能残留已翻页/刷新掉的 tag，
                                        // 只剩残留时点了没反应，判空必须用交集
                                        val visibleNotesExpanded = expandedNotes.count { it in releaseTagNames }
                                        if (detailAtTop && !commitsTab && visibleNotesExpanded > 0) {
                                            expandedNotes = emptySet()
                                        } else {
                                            scope.launch { detailListState.scrollToTop(motion) }
                                        }
                                    },
                                )
                            }
                            // 一键翻译总开关：开启带主题浅绿圆底，翻译中图标与底圈柔和呼吸
                            val translateTint by androidx.compose.animation.animateColorAsState(
                                targetValue = if (translateEnabled) scheme.primaryVariant else scheme.onBackground,
                                animationSpec = if (motion) tween(Motion.PHASE) else snap(),
                                label = "translateTint",
                            )
                            val translateBg by androidx.compose.animation.animateColorAsState(
                                targetValue = if (translateEnabled) {
                                    scheme.primaryVariant.copy(alpha = 0.14f)
                                } else {
                                    androidx.compose.ui.graphics.Color.Transparent
                                },
                                animationSpec = if (motion) tween(Motion.PHASE) else snap(),
                                label = "translateBg",
                            )
                            val pulseAlpha = if (translating && motion) {
                                val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "translatePulse")
                                infinite.animateFloat(
                                    initialValue = 1f,
                                    targetValue = 0.28f,
                                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                        animation = tween(
                                            durationMillis = 1000,
                                            easing = androidx.compose.animation.core.EaseInOutSine,
                                        ),
                                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                                    ),
                                    label = "translatePulseAlpha",
                                ).value
                            } else if (translating) {
                                0.55f
                            } else 1f
                            PressIconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    failedBodies = emptySet()
                                    if (translateEnabled || appState.settings.translationDisclosureAccepted) {
                                        translateEnabled = !translateEnabled
                                    } else {
                                        showTranslationDisclosure = true
                                    }
                                },
                                contentDescription = stringResource(
                                    if (translateEnabled) R.string.detail_show_original else R.string.detail_translate,
                                ),
                                containerColor = if (translateEnabled) {
                                    translateBg.copy(alpha = translateBg.alpha * pulseAlpha)
                                } else {
                                    androidx.compose.ui.graphics.Color.Unspecified
                                },
                                glass = true,
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_translate),
                                    contentDescription = null,
                                    tint = translateTint.copy(alpha = translateTint.alpha * pulseAlpha),
                                )
                            }
                            // 低频动作（切分支 / 打开网页 / 复制链接 / 移除订阅）统一收进「更多」，释放顶栏标题空间
                            PressIconButton(
                                onClick = { showMoreSheet = true },
                                contentDescription = stringResource(R.string.detail_more_actions),
                                containerColor = androidx.compose.ui.graphics.Color.Unspecified,
                                glass = true,
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_more),
                                    contentDescription = null,
                                    tint = scheme.onBackground,
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        val topPad = padding.calculateTopPadding()
        io.candy.tick.ui.common.CandyPullToRefresh(
            requestState = vm.state,
            refreshing = { it.refreshing },
            onRefresh = {
                pullFrame = vm.state.value
                vm.refresh()
            },
            pullToRefreshState = pullState,
            topAppBarScrollBehavior = scrollBehavior,
            topOffset = topPad,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
                .then(if (pageBackdrop != null) Modifier.layerBackdrop(pageBackdrop) else Modifier),
        ) {
            LazyColumn(
                state = detailListState,
                modifier = Modifier.fillMaxSize(),
                // 底部 32：全 App 滚动终止留白统一档（footer 元素上下间距见 FooterText v8 + 此 32）
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPad, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(HistoryItemSpacing),
            ) {
                item {
                    // 头卡组（头卡 + 错误横幅）：banner 隐藏时 0 高度收在组内——
                    // 若作独立 item，spacedBy 会在其前后各记一次，tab 上方凭空多 10dp
                    androidx.compose.foundation.layout.Column(
                        // 顶栏自带视觉底隙：6dp 与下方 HistoryItemSpacing(10dp) 视觉对称
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        HeaderCard(
                            state = state,
                            localVersion = localVersion,
                            onChangeLinked = { showAppPicker = true },
                            onUnlink = {
                                // 不出胶囊：头卡关联行当场消失，视觉已确认
                                vm.setLinkedApp(null)
                            },
                            onLinkSuggestion = {
                                state.linkSuggestion?.let { (pkg, label) -> vm.setLinkedApp(pkg, newLabel = label) }
                            },
                            onDownloadUpdate = vm::downloadUpdate,
                            onInstallUpdate = { repo?.downloadedApk?.let { vm.installDownloaded(it, fromHeader = true) } },
                            onCancelDownload = vm::cancelDownload,
                            translatedDescription = if (translateEnabled) {
                                state.snapshot?.description?.trim()?.let { translations[it] }
                            } else null,
                        )
                        // 错误横幅出现/消失过渡：垂直展开/收起 + 淡入淡出，lastBanner 留帧避免退场硬跳
                        var lastBanner by remember { mutableStateOf(state.banner) }
                        if (state.banner != null) lastBanner = state.banner
                        androidx.compose.animation.AnimatedVisibility(
                            visible = state.banner != null,
                            enter = if (motion) {
                                androidx.compose.animation.expandVertically(
                                    animationSpec = tween(Motion.PANEL, easing = Motion.Decelerate),
                                    expandFrom = Alignment.Top,
                                ) + fadeIn(tween(Motion.PANEL))
                            } else androidx.compose.animation.EnterTransition.None,
                            exit = if (motion) {
                                androidx.compose.animation.shrinkVertically(
                                    animationSpec = tween(Motion.PANEL, easing = Motion.Decelerate),
                                    shrinkTowards = Alignment.Top,
                                ) + fadeOut(tween(Motion.MICRO))
                            } else androidx.compose.animation.ExitTransition.None,
                            label = "detailRefreshError",
                        ) {
                            lastBanner?.let { error ->
                                // 共用失败横幅（errorContainer 底 + 重试钮，与首页同构）；
                                // 用户可不刷新只关提示（dismissBanner 由此接线）
                                if (error == SourceError.BranchUnavailable) {
                                    io.candy.tick.ui.common.InlineErrorRow(
                                        message = stringResource(R.string.branch_unavailable),
                                        onRetry = { vm.setBranch(null) },
                                        actionLabel = stringResource(R.string.branch_use_default),
                                    )
                                } else ErrorBanner(
                                    error = error,
                                    modifier = Modifier.padding(top = 10.dp),
                                    hasSavedDetails = state.snapshot != null,
                                    onRetry = vm::refresh,
                                    onDismiss = vm::dismissBanner,
                                )
                            }
                        }
                    }
                }
                item(key = HISTORY_TABS_KEY) {
                    val tabsTopPadding by animateDpAsState(
                        targetValue = if (state.banner != null) 0.dp else 8.dp,
                        animationSpec = if (motion) tween(Motion.PANEL) else snap(),
                        label = "tabsTop",
                    )
                    DetailTabs(
                        selected = tab,
                        position = tabPosition,
                        onSelect = { selected ->
                            interacted = true
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            savedTab = selected
                            vm.setTab(selected)
                            browsing = true
                            packageTag = null
                        },
                        // 错误横幅在场时页签顶部留白归零：横幅内衬 6 + item 间距 10 = 16dp，
                        // 与横幅上方 10+6=16 对齐；随横幅 220ms 尺寸过渡同拍动画（与首页列表顶隙
                        // 同规则），避免两段跳动
                        modifier = Modifier.padding(
                            top = tabsTopPadding,
                            bottom = 8.dp,
                        ),
                    )
                }
                if (lookupTag != null) {
                    item(key = RELEASE_LOOKUP_KEY) {
                        val message = when (lookupError) {
                            null -> R.string.detail_finding_release
                            SourceError.NotFound -> R.string.detail_notified_release_missing
                            else -> R.string.detail_notified_release_failed
                        }
                        io.candy.tick.ui.common.StatusPage(
                            compact = true,
                            isError = lookupError != null,
                            title = stringResource(message, lookupTag),
                            subtitle = lookupError?.let { io.candy.tick.ui.common.sourceErrorText(it) },
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
                val animateChanges = entrySettled && animateRefreshChanges && !tabMoving && !historyFading &&
                    !detailListState.isScrollInProgress && releaseRequestId == null
                val activeTranslations = if (translateEnabled) translations else emptyMap()
                if (commitsTab) {
                    commitList(
                        state = state,
                        contentModifier = historyRowModifier,
                        htmlBase = htmlBase,
                        showPagingIndicator = pullIdle,
                        animateRefreshChanges = animateChanges,
                        onLoadMore = vm::loadCommits,
                        translatedTexts = activeTranslations,
                    )
                } else {
                    releaseList(
                        state = state,
                        releases = releases,
                        localTarget = localTarget,
                        notificationTag = if (packageTag == null && (!browsing || extraRelease != null)) tag else null,
                        contentModifier = historyRowModifier,
                        localVersion = localVersion,
                        htmlBase = htmlBase,
                        expandedNotes = expandedNotes,
                        onToggleNotes = { tag ->
                            expandedNotes = if (tag in expandedNotes) expandedNotes - tag else expandedNotes + tag
                        },
                        showPagingIndicator = pullIdle,
                        animateRefreshChanges = animateChanges,
                        onLoadMore = vm::loadReleases,
                        onSelectAsset = vm::selectAsset,
                        onRead = {
                            interacted = true
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
                        onInstallDownloaded = { vm.installDownloaded(it) },
                        onDeleteDownloaded = vm::deleteDownloaded,
                        onCancelDownload = vm::cancelDownload,
                        translatedTexts = activeTranslations,
                        translateEnabled = translateEnabled,
                        failedBodies = failedBodies,
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
        onLoadBranches = vm::loadNextBranchPage,
        onVisibleBranches = vm::loadVisibleBranchDates,
    )

    // 更多操作浮层（收拢低频操作：切分支 / 打开网页 / 复制链接 / 移除仓库）
    DetailMoreActionsSheet(
        show = showMoreSheet,
        showBranch = commitsTab,
        webUrl = webUrl,
        onSelectBranch = {
            showMoreSheet = false
            showBranchSheet = true
        },
        onRemove = {
            showMoreSheet = false
            showRemoveDialog = true
        },
        onDismiss = { showMoreSheet = false },
    )

    // 移除确认（破坏性操作）：show 直通，退场有动效
    DetailConfirmationSheet(
        show = showRemoveDialog && repo != null,
        title = stringResource(R.string.remove_confirm_title),
        message = stringResource(R.string.remove_confirm_body, repo?.let { it.noteName ?: "${it.owner}/${it.repo}" }.orEmpty()),
        confirmText = stringResource(R.string.action_remove),
        destructive = true,
        onConfirm = {
            // 破坏性确认时刻：轻触感（视觉红按钮之外的物理确认）
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showRemoveDialog = false
            vm.removeRepo(onRemoved)
        },
        onDismiss = { showRemoveDialog = false },
    )
    DetailConfirmationSheet(
        show = showTranslationDisclosure,
        title = stringResource(R.string.translation_disclosure_title),
        message = stringResource(R.string.translation_disclosure_body),
        confirmText = stringResource(R.string.translation_disclosure_accept),
        onConfirm = {
            vm.acceptTranslationDisclosure()
            showTranslationDisclosure = false
            translateEnabled = true
        },
        onDismiss = { showTranslationDisclosure = false },
    )
}

private const val HISTORY_TABS_INDEX = 1
private const val HISTORY_TABS_KEY = "history-tabs"
private const val RELEASE_LOOKUP_KEY = "notification-release-lookup"

@Composable
private fun DetailMoreActionsSheet(
    show: Boolean,
    showBranch: Boolean,
    webUrl: String?,
    onSelectBranch: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scheme = MiuixTheme.colorScheme
    val links = rememberExternalUriHandler()
    val copyLabel = stringResource(R.string.copy_repo_link)

    io.candy.tick.ui.common.CandySheet(
        show = show,
        title = stringResource(R.string.detail_more_actions),
        onDismissRequest = onDismiss,
    ) {
        val dark = LocalDarkTheme.current
        val cardShape = RoundedCornerShape(16.dp)
        // 统一浮层软底分组卡（玻璃态微半透 / 低版本柔和实色）+ 薄荷淡底徽章；危险项沿用同款中性卡 + 淡红徽章 + 红字
        val groupBg = io.candy.tick.ui.common.sheetCardColor()
        val actionBadgeBg = scheme.primaryVariant.copy(alpha = 0.12f)
        val dangerBadgeBg = scheme.error.copy(alpha = if (dark) 0.14f else 0.11f)
        val hasPrimaryActions = showBranch || webUrl != null
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (hasPrimaryActions) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(groupBg),
                ) {
                    if (showBranch) {
                        DetailSheetActionRow(
                            icon = R.drawable.ic_git_branch,
                            title = stringResource(R.string.branch_picker_title),
                            titleColor = scheme.onSurface,
                            iconTint = scheme.primaryVariant,
                            badgeBg = actionBadgeBg,
                            onClick = onSelectBranch,
                        )
                        if (webUrl != null) {
                            DetailSheetDivider(io.candy.tick.ui.common.sheetDividerColor())
                        }
                    }
                    if (webUrl != null) {
                        DetailSheetActionRow(
                            icon = R.drawable.ic_external_link,
                            title = stringResource(R.string.open_in_browser),
                            titleColor = scheme.onSurface,
                            iconTint = scheme.primaryVariant,
                            badgeBg = actionBadgeBg,
                            onClick = {
                                onDismiss()
                                links.openUri(webUrl)
                            },
                        )
                        DetailSheetDivider(io.candy.tick.ui.common.sheetDividerColor())
                        DetailSheetActionRow(
                            icon = R.drawable.ic_link,
                            title = copyLabel,
                            titleColor = scheme.onSurface,
                            iconTint = scheme.primaryVariant,
                            badgeBg = actionBadgeBg,
                            onClick = {
                                onDismiss()
                                runCatching {
                                    val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
                                    clipboard.setPrimaryClip(ClipData.newPlainText(copyLabel, webUrl))
                                }.onSuccess {
                                    Feedback.show(resources.getString(R.string.toast_copied))
                                }.onFailure {
                                    Feedback.show(resources.getString(R.string.copy_repo_link_failed), isError = true, errorCode = "E-CLIP")
                                }
                            },
                        )
                    }
                }
            }
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(groupBg),
            ) {
                DetailSheetActionRow(
                    icon = R.drawable.ic_delete,
                    title = stringResource(R.string.detail_remove_repo),
                    titleColor = scheme.onErrorContainer,
                    iconTint = scheme.onErrorContainer,
                    badgeBg = dangerBadgeBg,
                    onClick = onRemove,
                )
            }
        }
    }
}

@Composable
private fun DetailSheetDivider(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 60.dp, end = 16.dp)
            .height(0.5.dp)
            .background(color),
    )
}

@Composable
private fun DetailSheetActionRow(
    icon: Int,
    title: String,
    titleColor: androidx.compose.ui.graphics.Color,
    iconTint: androidx.compose.ui.graphics.Color,
    badgeBg: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val badgeShape = RoundedCornerShape(10.dp)
    top.yukonga.miuix.kmp.basic.BasicComponent(
        modifier = Modifier.fillMaxWidth(),
        title = title,
        titleColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(color = titleColor),
        startAction = {
            Box(
                modifier = Modifier
                    // end=4dp 配合 BasicComponent 默认 8dp 凑齐 12dp 图文间距，与 DetailSheetDivider(start=60dp) 对齐
                    .padding(end = 4.dp)
                    .size(34.dp)
                    .clip(badgeShape)
                    .background(badgeBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(icon),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        onClick = onClick,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    )
}

private fun LazyListLayoutInfo.isReleaseInFocus(key: String): Boolean {
    val item = visibleItemsInfo.firstOrNull { it.key == key } ?: return false
    val viewportHeight = viewportEndOffset - viewportStartOffset
    val needed = minOf(item.size, viewportHeight * 2 / 3)
    return item.offset >= viewportStartOffset && item.offset + needed <= viewportEndOffset
}

private fun LazyListLayoutInfo.visibleHistoryKeys(titleHeight: Int): Set<String> =
    // asReversed：从旧到新收集——已读记忆容量从旧端淘汰，可见集合的最新行必须排在最后
    visibleItemsInfo.asReversed().mapNotNullTo(mutableSetOf()) { item ->
        val key = item.key as? String ?: return@mapNotNullTo null
        val titleEnd = item.offset + minOf(titleHeight, item.size)
        key.takeIf {
            (item.contentType == "release" || item.contentType == "commit") &&
                item.offset >= viewportStartOffset && titleEnd <= viewportEndOffset
        }
    }

private fun Set<String>.historyIds(prefix: String): Set<String> =
    mapNotNullTo(mutableSetOf()) { if (it.startsWith(prefix)) it.removePrefix(prefix) else null }
