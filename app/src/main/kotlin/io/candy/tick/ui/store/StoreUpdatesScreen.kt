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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
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
    var activeTab by rememberSaveable { mutableStateOf(StoreFilterTab.UPDATABLE) }
    var sheetPackage by remember { mutableStateOf<String?>(null) }
    var installedActionPkg by remember { mutableStateOf<String?>(null) }
    var updateAllMeteredBytes by remember { mutableStateOf<Long?>(null) }

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
        StoreFilterTab.ALL_INSTALLED -> state.allInstalled
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
            val showCheckDone: (Int) -> Unit = { count ->
                if (count == 0) {
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
                                val checkedLabel = state.lastCheckedAt?.let {
                                    stringResource(R.string.store_last_checked, relTimeString(it))
                                } ?: stringResource(R.string.store_pull_to_check)
                                Text(
                                    text = checkedLabel,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.onBackgroundVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                if (state.updatable.count { it.best != null } >= 2) {
                                    Text(
                                        text = stringResource(R.string.store_update_all),
                                        style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
                                        color = scheme.primaryVariant,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(role = Role.Button) {
                                                val totalBytes = state.updatable.sumOf { row ->
                                                    if (row.task is StoreTaskState.Downloading || row.task is StoreTaskState.Installing) 0L
                                                    else (row.best?.sizeBytes ?: 0L)
                                                }
                                                if (vm.shouldPromptMetered(totalBytes)) {
                                                    updateAllMeteredBytes = totalBytes
                                                } else {
                                                    vm.updateAll()
                                                    Feedback.show(resources.getString(R.string.store_update_all_started))
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                    )
                                }
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
                                        title = stringResource(R.string.store_empty_all),
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
                            StoreAppCard(
                                row = row,
                                onCardClick = {
                                    if (row.best != null || row.update != null) {
                                        sheetPackage = row.local.packageName
                                    } else {
                                        installedActionPkg = row.local.packageName
                                    }
                                },
                                onInstallReady = { vm.installReady(row.local.packageName) },
                                onCancel = { vm.cancelDownload(row.local.packageName) },
                                onDismissError = { vm.clearFailure(row.local.packageName) },
                                onUnskip = { vm.unskipVersion(row.local.packageName) },
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
    val sheetIcon by produceState(sheetPackage?.let { StoreIconCache.peek(it) }, sheetPackage) {
        val pkg = sheetPackage ?: return@produceState
        if (value == null) value = StoreIconCache.load(context, pkg)
    }
    StoreUpdateSheet(
        show = sheetItem != null,
        item = sheetItem,
        icon = sheetIcon,
        signatures = state.signatures,
        shouldPromptMetered = vm::shouldPromptMetered,
        onDismiss = { sheetPackage = null },
        onDownload = { rel -> sheetItem?.let { vm.startUpdate(it.local, rel) } },
        onPinSource = { sid -> sheetItem?.let { vm.pinSource(it.local.packageName, sid) } },
        onSkipVersion = { code ->
            val pkg = sheetItem?.local?.packageName ?: return@StoreUpdateSheet
            vm.skipVersion(pkg, code)
            Feedback.show(
                text = resources.getString(R.string.store_skipped_toast),
                actionLabel = resources.getString(R.string.undo),
                onAction = { vm.unskipVersion(pkg) },
            )
        },
        onUnskipVersion = {
            val pkg = sheetItem?.local?.packageName ?: return@StoreUpdateSheet
            vm.unskipVersion(pkg)
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
            vm.checkSingleApp(local) { found ->
                if (found) {
                    Feedback.show(resources.getString(R.string.store_single_found, local.displayName))
                    sheetPackage = local.packageName
                } else {
                    Feedback.show(resources.getString(R.string.store_single_up_to_date, local.displayName))
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
        show = updateAllMeteredBytes != null,
        title = stringResource(R.string.store_metered_title),
        onDismissRequest = { updateAllMeteredBytes = null },
    ) {
        val bytes = updateAllMeteredBytes ?: 0L
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
                    updateAllMeteredBytes = null
                    vm.updateAll()
                    Feedback.show(resources.getString(R.string.store_update_all_started))
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                text = stringResource(R.string.cancel),
                onClick = { updateAllMeteredBytes = null },
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
    val icon by produceState(pkg?.let { StoreIconCache.peek(it) }, pkg) {
        val target = pkg ?: return@produceState
        if (value == null) value = StoreIconCache.load(context, target)
    }
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
    onInstallReady: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    onUnskip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val pkg = row.local.packageName
    val icon by produceState(StoreIconCache.peek(pkg), pkg) {
        if (value == null) value = StoreIconCache.load(context, pkg)
    }
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
                    val best = row.best
                    if (best != null) {
                        val storeName = StoreCatalog.descriptorOf(best.storeId)?.let { stringResource(it.nameRes) } ?: best.storeId
                        val sourceCount = row.update?.candidates?.size ?: 1
                        val sourceLabel = if (sourceCount > 1) {
                            stringResource(R.string.store_sources_multi, storeName, sourceCount)
                        } else storeName
                        Text(
                            text = "${row.local.versionName} → ${best.versionName}",
                            style = MonoTokenStyle,
                            color = scheme.primaryVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sizePart = if (best.sizeBytes > 0L) " · ${Formatter.formatShortFileSize(context, best.sizeBytes)}" else ""
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
                when (val task = row.task) {
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
                        onClick = onInstallReady,
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

            when (val task = row.task) {
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
                        StoreDownloadFailure.NETWORK -> stringResource(R.string.store_err_download_failed)
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
