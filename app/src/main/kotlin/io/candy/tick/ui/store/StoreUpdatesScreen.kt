package io.candy.tick.ui.store

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import io.candy.tick.ui.common.rememberAppIcon
import io.candy.tick.ui.common.PickerSearchField
import io.candy.tick.data.store.StoreCheckSummary
import io.candy.tick.data.store.SingleStoreCheckResult
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDownloadFailure
import io.candy.tick.data.store.StoreTaskState
import io.candy.tick.ui.common.CandyPullToRefresh
import io.candy.tick.ui.common.CandySheet
import io.candy.tick.ui.common.CenteredScrollToTopBar
import io.candy.tick.ui.common.Feedback
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.common.PressIconButton
import io.candy.tick.ui.common.PressableCard
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.SecondaryButton
import io.candy.tick.ui.common.StatusPage
import io.candy.tick.ui.common.relTimeString
import io.candy.tick.ui.common.rememberMotionEnabled
import io.candy.tick.ui.common.scrollToTop
import io.candy.tick.ui.common.topBarProgressiveBlur
import io.candy.tick.ui.theme.MonoTokenStyle
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun StoreUpdatesScreen(
    onBack: () -> Unit,
    onOpenStoreSettings: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.applicationContext as CandyTickApp
    val vm: StoreUpdatesViewModel = viewModel {
        StoreUpdatesViewModel(
            app = app,
            stateStore = app.container.appStateStore,
            installedCache = app.container.installedAppCache,
            checker = app.container.storeUpdateChecker,
            downloadHost = app.container.storeDownloadHost,
        )
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(state.enabledStores, state.lastCheckComplete) {
        if (state.enabledStores.isNotEmpty() && state.lastCheckComplete == null) vm.refresh()
    }
    var activeTab by rememberSaveable { mutableStateOf(StoreFilterTab.UPDATABLE) }
    var sheetPackage by remember { mutableStateOf<String?>(null) }
    var installedActionPkg by remember { mutableStateOf<String?>(null) }
    var pendingBatch by remember { mutableStateOf<StoreBatchPlan?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val installer = app.container.installManager
    var canInstall by remember(installer) { mutableStateOf(installer.canRequestInstalls()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(installer) {
        canInstall = installer.canRequestInstalls()
        onPauseOrDispose { }
    }
    val batch = remember(state.updatable, state.autoInstall, canInstall) { state.batchPlan(canInstall) }
    fun startBatch(plan: StoreBatchPlan, meteredConfirmed: Boolean) {
        val started = vm.updateAll(plan, meteredConfirmed, installer.canRequestInstalls())
        if (started > 0) Feedback.show(resources.getString(R.string.store_batch_started))
    }

    val scheme = MiuixTheme.colorScheme
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val motionEnabled = rememberMotionEnabled()
    val pageBackdrop = if (isRuntimeShaderSupported()) rememberLayerBackdrop(backgroundColor = scheme.background) else null
    val scrolled by remember(listState) {
        derivedStateOf { listState.canScrollBackward }
    }
    val pullState = rememberPullToRefreshState()

    val shownItems = when (activeTab) {
        StoreFilterTab.UPDATABLE -> state.updatable
        StoreFilterTab.ALL_INSTALLED -> remember(state.allInstalled, query) {
            val term = query.trim()
            state.allInstalled.filter { it.local.displayName.contains(term, true) || it.local.packageName.contains(term, true) }
        }
        StoreFilterTab.SKIPPED -> state.skipped
    }
    // 与 DetailScreen 同款共享时钟：页签滑块与下方列表行同拍横向落位
    val targetTabPosition = StoreFilterTab.entries.indexOf(activeTab).coerceAtLeast(0).toFloat()
    val tabPosition = animateFloatAsState(
        targetValue = targetTabPosition,
        animationSpec = tween(if (motionEnabled) Motion.PHASE else 0, easing = Motion.Decelerate),
        label = "storeTab",
    )
    val tabMoving by remember(tabPosition, targetTabPosition) {
        derivedStateOf { tabPosition.value != targetTabPosition }
    }
    val tabContentModifier = if (motionEnabled && tabMoving && shownItems.isNotEmpty()) {
        Modifier.offset {
            IntOffset((12.dp.toPx() * (targetTabPosition - tabPosition.value)).roundToInt(), 0)
        }
    } else Modifier

    val sheetItem = sheetPackage?.let { pkg -> state.allInstalled.firstOrNull { it.local.packageName == pkg } }
    val installedActionItem = installedActionPkg?.let { pkg -> state.allInstalled.firstOrNull { it.local.packageName == pkg } }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .topBarProgressiveBlur(pageBackdrop, active = scrolled),
            ) {
                CenteredScrollToTopBar(
                    title = stringResource(R.string.store_updates_title),
                    onBack = onBack,
                    onTitleClick = { scope.launch { listState.scrollToTop(motionEnabled) } },
                    endAction = {
                        PressIconButton(
                            onClick = onOpenStoreSettings,
                            contentDescription = stringResource(R.string.store_settings_title),
                            containerColor = Color.Unspecified,
                            glass = true,
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_settings),
                                contentDescription = null,
                                tint = scheme.onBackground,
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (state.enabledStores.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                StatusPage(
                    icon = ImageVector.vectorResource(R.drawable.ic_package),
                    title = stringResource(R.string.store_empty_no_source_title),
                    subtitle = stringResource(R.string.store_empty_no_source_desc),
                    actionLabel = stringResource(R.string.store_choose_stores),
                    onAction = onOpenStoreSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            val topPad = padding.calculateTopPadding()
            val incompleteHint = if (state.failedStores.isEmpty()) stringResource(R.string.store_check_retry_hint) else {
                val names = state.failedStores.mapNotNull { StoreCatalog.descriptorOf(it)?.nameRes }
                    .joinToString(resources.getString(R.string.list_separator)) { resources.getString(it) }
                val reason = state.failedStoreReason
                when {
                    reason == "E-TIME" -> stringResource(R.string.store_sources_timed_out, names)
                    reason == "E-NET" -> stringResource(R.string.store_sources_unreachable, names)
                    reason == "E-DATA" -> stringResource(R.string.store_sources_unreadable, names)
                    reason?.startsWith("HTTP ") == true || reason?.startsWith("API ") == true ->
                        Feedback.withErrorCode(stringResource(R.string.store_failed_sources, names), reason)
                    else -> stringResource(R.string.store_failed_sources, names)
                }
            }
            val showCheckDone: (StoreCheckSummary) -> Unit = { result ->
                if (!result.complete) {
                    Feedback.show(resources.getString(R.string.store_check_incomplete), isError = true)
                } else if (result.updatableCount == 0) {
                    Feedback.show(resources.getString(R.string.store_check_done_up_to_date))
                }
            }
            CandyPullToRefresh(
                requestState = vm.pullRefreshing,
                refreshing = { it },
                onRefresh = { vm.onPullRefresh(showCheckDone) },
                pullToRefreshState = pullState,
                topAppBarScrollBehavior = null,
                topOffset = topPad,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding())
                    .then(if (pageBackdrop != null) Modifier.layerBackdrop(pageBackdrop) else Modifier),
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        // 顶栏自身底部自带约 6dp 视觉留白：+4dp 与下方 spacedBy(10dp) 严格对称
                        top = topPad + 4.dp,
                        bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "filter_tabs") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            StoreTabs(
                                selected = activeTab,
                                position = tabPosition,
                                labels = listOf(
                                    chipLabel(R.string.store_tab_updatable_name, state.updatable.size),
                                    chipLabel(R.string.store_tab_all_name, state.allInstalled.size),
                                    chipLabel(R.string.store_tab_skipped_name, state.skipped.size),
                                ),
                                onSelect = { activeTab = it },
                            )
                            StoreCheckProgressStrip(progressFlow = vm.checkProgress)
                        }
                    }

                    if (activeTab == StoreFilterTab.ALL_INSTALLED && (state.allInstalled.isNotEmpty() || query.isNotEmpty())) {
                        item(key = "installed_search") {
                            PickerSearchField(query, stringResource(R.string.picker_search_app)) { query = it }
                        }
                    }
                    if (activeTab == StoreFilterTab.UPDATABLE && state.updatable.isNotEmpty()) {
                        item(key = "header_action") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(
                                        fadeInSpec = if (motionEnabled) tween(Motion.PHASE, easing = Motion.Decelerate) else null,
                                        fadeOutSpec = if (motionEnabled) tween(Motion.MICRO) else null,
                                        placementSpec = if (motionEnabled) tween(Motion.PHASE, easing = Motion.Decelerate) else null,
                                    )
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            ) {
                                val checkedLabel = if (state.lastCheckComplete == false) incompleteHint else state.lastCheckedAt?.let {
                                    stringResource(R.string.store_last_checked, relTimeString(it))
                                } ?: stringResource(R.string.store_pull_to_check)
                                Text(
                                    text = checkedLabel,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.onBackgroundVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                // Keep the same measured label when hidden, including at large font scales.
                                val action = if (batch.count < 2) Modifier.clearAndSetSemantics { }.alpha(0f) else {
                                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                                        if (batch.downloads.isNotEmpty() && vm.shouldPromptMetered(batch.estimatedBytes ?: 0L)) {
                                            pendingBatch = batch
                                        } else {
                                            startBatch(batch, meteredConfirmed = false)
                                        }
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.store_update_all),
                                    style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
                                    color = scheme.primaryVariant,
                                    modifier = action.padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }

                    if (shownItems.isEmpty()) {
                        item(key = "empty_tab") {
                            AnimatedContent(
                                targetState = activeTab to (activeTab == StoreFilterTab.UPDATABLE && state.refreshing),
                                transitionSpec = {
                                    val spec = if (motionEnabled) tween<Float>(Motion.MICRO) else snap()
                                    fadeIn(spec) togetherWith fadeOut(spec)
                                },
                                label = "storeEmptyTab",
                                modifier = Modifier.animateItem(
                                    fadeInSpec = if (motionEnabled) tween(Motion.PHASE, easing = Motion.Decelerate) else null,
                                    fadeOutSpec = if (motionEnabled) tween(Motion.MICRO) else null,
                                    placementSpec = if (motionEnabled) tween(Motion.PHASE, easing = Motion.Decelerate) else null,
                                ),
                            ) { (tab, isCheckingUpdatable) ->
                                when {
                                    tab == StoreFilterTab.UPDATABLE && isCheckingUpdatable -> StatusPage(
                                        icon = ImageVector.vectorResource(R.drawable.ic_package),
                                        title = stringResource(R.string.store_checking_empty_title),
                                        subtitle = stringResource(R.string.store_checking_empty_desc),
                                        modifier = Modifier.fillParentMaxHeight(0.60f).fillMaxWidth(),
                                    )
                                    tab == StoreFilterTab.UPDATABLE && state.lastCheckComplete != true -> StatusPage(
                                        icon = ImageVector.vectorResource(R.drawable.ic_package),
                                        title = stringResource(if (state.lastCheckComplete == false) R.string.store_check_incomplete else R.string.store_not_checked_yet),
                                        subtitle = if (state.lastCheckComplete == false) incompleteHint else null,
                                        actionLabel = stringResource(R.string.store_recheck_now),
                                        onAction = { vm.refresh(showCheckDone) },
                                        modifier = Modifier.fillParentMaxHeight(0.60f).fillMaxWidth(),
                                    )
                                    tab == StoreFilterTab.UPDATABLE -> StatusPage(
                                        icon = ImageVector.vectorResource(R.drawable.ic_check_circle),
                                        title = stringResource(R.string.store_empty_updatable_title),
                                        subtitle = stringResource(R.string.store_empty_updatable_desc),
                                        actionLabel = stringResource(R.string.store_recheck_now),
                                        onAction = { vm.refresh(showCheckDone) },
                                        modifier = Modifier.fillParentMaxHeight(0.60f).fillMaxWidth(),
                                    )
                                    tab == StoreFilterTab.ALL_INSTALLED -> StatusPage(
                                        icon = ImageVector.vectorResource(R.drawable.ic_package),
                                        title = stringResource(if (query.isBlank()) R.string.store_empty_all else R.string.picker_no_match),
                                        modifier = Modifier.fillParentMaxHeight(0.60f).fillMaxWidth(),
                                    )
                                    else -> StatusPage(
                                        icon = ImageVector.vectorResource(R.drawable.ic_hide),
                                        title = stringResource(R.string.store_empty_skipped),
                                        subtitle = stringResource(R.string.store_empty_skipped_desc),
                                        modifier = Modifier.fillParentMaxHeight(0.60f).fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    } else {
                        items(shownItems, key = { "${activeTab.name}:${it.local.packageName}" }) { row ->
                            val task = if (row.task is StoreTaskState.Downloading) {
                                val progress = remember(row.local.packageName, vm) {
                                    vm.tasks.map { it[row.local.packageName] }.distinctUntilChanged()
                                }
                                progress.collectAsStateWithLifecycle(initialValue = row.task).value
                            } else row.task
                            StoreAppCard(
                                row = if (task == row.task) row else row.copy(task = task),
                                onCardClick = {
                                    if (row.best != null || row.update != null) {
                                        sheetPackage = row.local.packageName
                                    } else {
                                        installedActionPkg = row.local.packageName
                                    }
                                },
                                onInstallReady = { expected -> vm.installReady(row.local.packageName, expected) },
                                onCancel = { vm.cancelDownload(row.local.packageName) },
                                onDismissError = { vm.clearFailure(row.local.packageName) },
                                onUnskip = { vm.setSkippedVersion(row.local.packageName, null) },
                                modifier = tabContentModifier.animateItem(
                                    fadeInSpec = if (motionEnabled) tween(Motion.PHASE, easing = Motion.Decelerate) else null,
                                    fadeOutSpec = if (motionEnabled) tween(Motion.MICRO) else null,
                                    placementSpec = if (motionEnabled) tween(Motion.PHASE, easing = Motion.Decelerate) else null,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // 弹层始终保持在组合树中（show 直通 CandySheet），确保关闭时播放完整液态玻璃退场动画
    StoreUpdateSheet(
        show = sheetItem != null,
        item = sheetItem,
        autoInstall = state.autoInstall,
        pinnedStoreId = state.pinnedSources[sheetPackage],
        signatures = state.signatures,
        shouldPromptMetered = vm::shouldPromptMetered,
        onDismiss = { sheetPackage = null },
        onDownload = vm::startUpdate,
        onInstallReady = vm::installReady,
        onPinSource = { sid -> sheetItem?.let { vm.pinSource(it.local.packageName, sid) } },
        onSkipVersion = { code ->
            val item = sheetItem ?: return@StoreUpdateSheet
            val pkg = item.local.packageName
            vm.setSkippedVersion(pkg, code)
            Feedback.show(
                text = resources.getString(R.string.store_skipped_toast),
                actionLabel = resources.getString(R.string.undo),
                onAction = { vm.setSkippedVersion(pkg, item.skippedThrough, expected = code) },
            )
        },
        onUnskipVersion = {
            val pkg = sheetItem?.local?.packageName ?: return@StoreUpdateSheet
            vm.setSkippedVersion(pkg, null)
        },
        onExcludeApp = {
            val pkg = sheetItem?.local?.packageName ?: return@StoreUpdateSheet
            vm.excludePackage(pkg)
            Feedback.show(
                text = resources.getString(R.string.store_excluded_toast),
                actionLabel = resources.getString(R.string.undo),
                onAction = { vm.unexcludePackage(pkg) },
            )
        },
    )

    InstalledAppActionSheet(
        show = installedActionItem != null,
        item = installedActionItem,
        onDismiss = { installedActionPkg = null },
        onCheckSingle = {
            val local = installedActionItem?.local ?: return@InstalledAppActionSheet
            installedActionPkg = null
            Feedback.show(resources.getString(R.string.store_checking_single, local.displayName))
            vm.checkSingleApp(local) { result ->
                when (result) {
                    SingleStoreCheckResult.FOUND -> {
                        Feedback.show(resources.getString(R.string.store_single_found, local.displayName))
                        sheetPackage = local.packageName
                    }
                    SingleStoreCheckResult.UP_TO_DATE -> Feedback.show(resources.getString(R.string.store_single_up_to_date, local.displayName))
                    SingleStoreCheckResult.INCOMPLETE -> Feedback.show(resources.getString(R.string.store_check_incomplete), isError = true)
                    SingleStoreCheckResult.NO_ELIGIBLE_STORE -> Feedback.show(resources.getString(R.string.store_no_eligible_source))
                }
            }
        },
        onExclude = {
            val pkg = installedActionItem?.local?.packageName ?: return@InstalledAppActionSheet
            installedActionPkg = null
            vm.excludePackage(pkg)
            Feedback.show(
                text = resources.getString(R.string.store_excluded_toast),
                actionLabel = resources.getString(R.string.undo),
                onAction = { vm.unexcludePackage(pkg) },
            )
        },
        onOpenSystemInfo = {
            val pkg = installedActionItem?.local?.packageName ?: return@InstalledAppActionSheet
            installedActionPkg = null
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )

    CandySheet(
        show = pendingBatch != null,
        title = stringResource(R.string.store_metered_title),
        onDismissRequest = { pendingBatch = null },
    ) {
        val bytes = pendingBatch?.estimatedBytes ?: 0L
        val msg = if (bytes > 0L) {
            stringResource(R.string.store_metered_confirm_all, Formatter.formatShortFileSize(context, bytes))
        } else {
            stringResource(R.string.store_metered_confirm_unknown)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = msg,
                style = MiuixTheme.textStyles.body2,
                color = scheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            PrimaryButton(
                text = stringResource(R.string.store_metered_continue),
                onClick = {
                    pendingBatch?.let { startBatch(it, meteredConfirmed = true) }
                    pendingBatch = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                text = stringResource(R.string.cancel),
                onClick = { pendingBatch = null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InstalledAppActionSheet(
    show: Boolean,
    item: StoreUpdateRowItem?,
    onDismiss: () -> Unit,
    onCheckSingle: () -> Unit,
    onExclude: () -> Unit,
    onOpenSystemInfo: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    var cachedItem by remember { mutableStateOf(item) }
    if (show && item != null) cachedItem = item
    val current = if (show && item != null) item else cachedItem
    val pkg = current?.local?.packageName
    val icon = rememberAppIcon(pkg)
    CandySheet(show = show && current != null, onDismissRequest = onDismiss) {
        if (current == null) return@CandySheet
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StoreSheetAppHeader(
                icon = icon,
                displayName = current.local.displayName,
                packageName = current.local.packageName,
            )
            StoreSheetInfoBar {
                Text(
                    text = stringResource(R.string.store_current_version_label),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                )
                Text(
                    text = current.local.versionName,
                    style = MonoTokenStyle.copy(fontWeight = FontWeight.Medium),
                    color = scheme.onSurfaceContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            PrimaryButton(
                text = stringResource(R.string.store_action_check_single),
                onClick = onCheckSingle,
                modifier = Modifier.fillMaxWidth(),
            )
            StoreSheetLinkActions(
                leftText = stringResource(R.string.store_open_app_info),
                onLeftClick = onOpenSystemInfo,
                rightText = stringResource(R.string.store_exclude_app),
                onRightClick = onExclude,
            )
        }
    }
}

@Composable
private fun StoreCheckProgressStrip(
    progressFlow: kotlinx.coroutines.flow.StateFlow<io.candy.tick.data.store.StoreCheckProgress?>,
) {
    val progress by progressFlow.collectAsStateWithLifecycle()
    var lastProgress by remember { mutableStateOf(progress) }
    if (progress != null) lastProgress = progress
    val scheme = MiuixTheme.colorScheme
    val motion = rememberMotionEnabled()
    val sep = stringResource(R.string.list_separator)
    AnimatedVisibility(
        visible = progress != null,
        enter = if (motion) expandVertically(tween(Motion.PHASE)) + fadeIn(tween(Motion.MICRO)) else fadeIn(snap()),
        exit = if (motion) shrinkVertically(tween(Motion.PHASE)) + fadeOut(tween(Motion.MICRO)) else fadeOut(snap()),
    ) {
        val cur = progress ?: lastProgress ?: return@AnimatedVisibility
        val runningNames = cur.runningStoreIds
            .take(2)
            .mapNotNull { id -> StoreCatalog.descriptorOf(id)?.let { stringResource(it.nameRes) } }
            .joinToString(sep)
        val targetRatio = if (cur.total > 0) ((cur.completed + 0.15f) / cur.total).coerceIn(0.08f, 1f) else 0.1f
        val animatedRatio by animateFloatAsState(
            targetValue = targetRatio,
            animationSpec = if (motion) tween(Motion.PROGRESS, easing = Motion.Decelerate) else snap(),
            label = "storeCheckBar",
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (runningNames.isNotBlank()) {
                    stringResource(R.string.store_checking_progress_names, cur.completed, cur.total, runningNames)
                } else {
                    stringResource(R.string.store_checking_progress, cur.completed, cur.total)
                },
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceContainerVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(progress = animatedRatio, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun chipLabel(nameRes: Int, count: Int): String {
    val name = stringResource(nameRes)
    return if (count > 0) "$name $count" else name
}

@Composable
private fun StoreAppCard(
    row: StoreUpdateRowItem,
    onCardClick: () -> Unit,
    onInstallReady: (StoreTaskState.ReadyToInstall) -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    onUnskip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val pkg = row.local.packageName
    val icon = rememberAppIcon(pkg)
    PressableCard(
        onClick = onCardClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StoreAppIconSlot(icon = icon, size = 44.dp, corner = 12.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.local.displayName,
                        style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurfaceContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val task = row.recommendedTask
                    val release = row.displayedRelease
                    val sourceId = task?.storeId ?: release?.storeId
                    val version = task?.versionName ?: release?.versionName
                    if (sourceId != null && version != null) {
                        val storeName = StoreCatalog.descriptorOf(sourceId)?.let { stringResource(it.nameRes) } ?: sourceId
                        val sourceCount = row.update?.candidates?.size ?: 1
                        val sourceLabel = if (task == null && sourceCount > 1) {
                            stringResource(R.string.store_sources_multi, storeName, sourceCount)
                        } else storeName
                        Text(
                            text = "${row.local.versionName} → $version",
                            style = MonoTokenStyle,
                            color = scheme.primaryVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val bytes = (task as? StoreTaskState.Downloading)?.totalBytes?.takeIf { it > 0 } ?: release?.sizeBytes ?: 0L
                        val sizePart = if (bytes > 0L) " · ${Formatter.formatShortFileSize(context, bytes)}" else ""
                        Text(
                            text = sourceLabel + sizePart,
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceContainerVariant,
                            maxLines = 1,
                        )
                    } else {
                        Text(
                            text = row.local.versionName,
                            style = MonoTokenStyle,
                            color = scheme.onSurfaceContainerVariant,
                            maxLines = 1,
                        )
                    }
                }
                when (val task = row.recommendedTask) {
                    is StoreTaskState.Downloading -> SecondaryButton(
                        text = stringResource(R.string.cancel),
                        onClick = onCancel,
                    )
                    is StoreTaskState.Verifying -> Text(
                        text = stringResource(R.string.store_verifying),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceContainerVariant,
                    )
                    is StoreTaskState.Installing -> Text(
                        text = stringResource(R.string.store_installing),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.primaryVariant,
                    )
                    is StoreTaskState.ReadyToInstall -> PrimaryButton(
                        text = stringResource(R.string.store_install_now),
                        onClick = { onInstallReady(task) },
                    )
                    is StoreTaskState.Failed -> {
                        val canSwitchSource = task.reason == StoreDownloadFailure.SIGNATURE_MISMATCH && row.best != null
                        SecondaryButton(
                            text = when {
                                canSwitchSource -> stringResource(R.string.store_switch_source)
                                task.reason == StoreDownloadFailure.SIGNATURE_MISMATCH -> stringResource(R.string.store_dismiss_error)
                                else -> stringResource(R.string.retry)
                            },
                            onClick = if (task.reason == StoreDownloadFailure.SIGNATURE_MISMATCH && !canSwitchSource) {
                                onDismissError
                            } else {
                                onCardClick
                            },
                        )
                    }
                    null -> when {
                        row.isSkipped -> SecondaryButton(
                            text = stringResource(R.string.store_unskip),
                            onClick = onUnskip,
                        )
                        row.best != null -> SecondaryButton(
                            text = stringResource(R.string.store_action_update),
                            onClick = onCardClick,
                        )
                        else -> Unit
                    }
                }
            }

            when (val task = row.recommendedTask) {
                is StoreTaskState.Downloading -> {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = task.fraction, modifier = Modifier.fillMaxWidth())
                    if (task.totalBytes > 0L) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${Formatter.formatShortFileSize(context, task.bytesRead)} / ${Formatter.formatShortFileSize(context, task.totalBytes)}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceContainerVariant,
                        )
                    }
                }
                is StoreTaskState.ReadyToInstall -> if (task.is32BitWarning) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.store_32bit_warning),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.error,
                    )
                }
                is StoreTaskState.Failed -> {
                    Spacer(Modifier.height(8.dp))
                    val storeName = StoreCatalog.descriptorOf(task.storeId)?.let { stringResource(it.nameRes) } ?: task.storeId
                    val msg = when (task.reason) {
                        StoreDownloadFailure.SIGNATURE_MISMATCH -> stringResource(R.string.store_err_signature_mismatch, storeName)
                        StoreDownloadFailure.VERIFY_HASH -> stringResource(R.string.store_err_verify_hash)
                        StoreDownloadFailure.VERIFY_ARCHIVE -> stringResource(R.string.store_err_verify_archive)
                        StoreDownloadFailure.INSTALL_FAILED -> stringResource(R.string.store_err_install_failed)
                        StoreDownloadFailure.METERED_CONFIRMATION -> stringResource(R.string.store_confirm_final_size)
                        StoreDownloadFailure.NETWORK -> stringResource(if (task.error == io.candy.tick.data.SourceError.StorageFull) R.string.err_storage_full else R.string.store_err_download_failed)
                    }
                    Text(
                        text = msg,
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.error,
                    )
                }
                else -> Unit
            }
        }
    }
}
