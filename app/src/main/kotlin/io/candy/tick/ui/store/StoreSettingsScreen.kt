package io.candy.tick.ui.store

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.candy.tick.data.store.needsInstalledAppsPermission
import io.candy.tick.data.store.PERM_GET_INSTALLED_APPS
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.candy.tick.util.openAppSettings
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.data.model.AppState
import io.candy.tick.data.model.FdroidMirror
import io.candy.tick.data.model.MeteredDownloadPolicy
import io.candy.tick.data.model.withStorePreferenceFor
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDescriptor
import io.candy.tick.data.store.StoreQuerySpeed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import io.candy.tick.ui.common.CandySheet
import io.candy.tick.ui.common.CenteredScrollToTopBar
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.common.OptionRow
import io.candy.tick.ui.common.SecondaryButton
import io.candy.tick.ui.common.rememberMotionEnabled
import io.candy.tick.ui.common.scrollToTop
import io.candy.tick.ui.common.sheetCardColor
import io.candy.tick.ui.common.sheetDividerColor
import io.candy.tick.ui.common.topBarProgressiveBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「设置 › 本机应用更新」子页：
 * - 直接观察内存 AppState（零 PackageManager 扫描，进出流畅）；
 * - 商店来源统一在一张卡片内按固定顺序展示，纯白/深色卡片底色不叠灰，优先级在独立拖动浮层中调整；
 * - F-Droid 镜像源收进「检查与下载」卡片，点击弹出纯文字单选浮层。
 */
@Composable
fun StoreSettingsScreen(
    onBack: () -> Unit,
    onOpenStoreUpdates: () -> Unit = {},
) {
    val context = LocalContext.current
    val appContainer = remember(context) { (context.applicationContext as CandyTickApp).container }
    val stateStore = appContainer.appStateStore
    val installedCache = appContainer.installedAppCache
    val appState by stateStore.state.collectAsStateWithLifecycle()
    val prefs = appState.settings.storePrefs
    val health = appState.storeState.health
    val scope = rememberCoroutineScope()
    val motionEnabled = rememberMotionEnabled()

    var permissionDenied by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(prefs.enabledStores.isNotEmpty()) {
        permissionDenied = prefs.enabledStores.isNotEmpty() && needsInstalledAppsPermission(context)
        onPauseOrDispose { }
    }
    val appListPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionDenied = !granted
        if (granted) {
            scope.launch {
                runCatching { installedCache.refresh(stateStore.state.value.settings.storePrefs.includeSystemApps) }
            }
        }
    }
    fun requestAppListPermissionIfNeeded() {
        if (needsInstalledAppsPermission(context)) {
            appListPermLauncher.launch(PERM_GET_INSTALLED_APPS)
        }
    }

    var showExcludedSheet by remember { mutableStateOf(false) }
    var showMirrorSheet by remember { mutableStateOf(false) }
    var showMeteredSheet by remember { mutableStateOf(false) }
    var showPrioritySheet by remember { mutableStateOf(false) }

    val scheme = MiuixTheme.colorScheme
    val listState = rememberLazyListState()
    val pageBackdrop = if (isRuntimeShaderSupported()) rememberLayerBackdrop(backgroundColor = scheme.background) else null
    val scrolled by remember(listState) { derivedStateOf { listState.canScrollBackward } }

    val enabledSet = remember(prefs.enabledStores) { prefs.enabledStores.toSet() }
    val fdroidEnabled = StoreCatalog.ID_FDROID in enabledSet
    val orderedStores = StoreCatalog.all

    fun updateCheckScope(transform: (AppState) -> AppState) {
        scope.launch {
            stateStore.update { old ->
                val next = transform(old)
                if (next == old) old else next.copy(storeState = next.storeState.copy(
                    lastAttemptAt = null, lastCheckedAt = null, lastCheckComplete = null, lastFailedStoreIds = emptyList(),
                ))
            }
        }
    }

    fun setIncludeSystemApps(enabled: Boolean) {
        if (enabled) requestAppListPermissionIfNeeded()
        updateCheckScope { st ->
            st.copy(settings = st.settings.copy(storePrefs = st.settings.storePrefs.copy(includeSystemApps = enabled)))
        }
    }

    fun toggleStore(storeId: String, enabled: Boolean) {
        if (enabled) requestAppListPermissionIfNeeded()
        updateCheckScope { st ->
            val p = st.settings.storePrefs
            val next = if (enabled) {
                val combined = (p.enabledStores + storeId).distinct()
                if (p.priorityCustomized) combined else StoreCatalog.sortByDefaultPriority(combined)
            } else {
                p.enabledStores.filterNot { it == storeId }
            }
            val nextHealth = if (enabled) st.storeState.health - storeId else st.storeState.health
            val nextCustomized = if (next.isEmpty()) false else p.priorityCustomized
            st.copy(
                settings = st.settings.copy(
                    storePrefs = p.copy(enabledStores = next, priorityCustomized = nextCustomized),
                ),
                storeState = st.storeState.copy(health = nextHealth),
            )
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .topBarProgressiveBlur(pageBackdrop, active = scrolled),
            ) {
                CenteredScrollToTopBar(
                    title = stringResource(R.string.store_settings_title),
                    onBack = onBack,
                    onTitleClick = { scope.launch { listState.scrollToTop(motionEnabled) } },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            modifier = Modifier
                .fillMaxSize()
                .then(if (pageBackdrop != null) Modifier.layerBackdrop(pageBackdrop) else Modifier),
        ) {
            item(key = "stores_unified") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.store_settings_stores_section),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onBackgroundVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (prefs.enabledStores.size >= 2) {
                        Text(
                            text = stringResource(R.string.store_priority_action),
                            style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium),
                            color = scheme.primaryVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                    showPrioritySheet = true
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.store_settings_privacy_note),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                )
                if (permissionDenied && enabledSet.isNotEmpty() && needsInstalledAppsPermission(context)) {
                    BasicComponent(
                        title = stringResource(R.string.store_permission_needed),
                        summary = stringResource(R.string.store_permission_settings),
                        onClick = { context.openAppSettings(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))) },
                    )
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        orderedStores.forEachIndexed { idx, desc ->
                            val isOn = desc.id in enabledSet
                            if (idx > 0) StoreRowDivider()
                            StoreToggleRow(
                                desc = desc,
                                checked = isOn,
                                autoDisabled = !isOn && health[desc.id]?.autoDisabled == true,
                                onCheckedChange = { toggleStore(desc.id, it) },
                            )
                        }
                    }
                }
            }

            item(key = "check_download_group") {
                SectionLabel(stringResource(R.string.store_settings_behavior_section))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = if (motionEnabled) {
                            Modifier.animateContentSize(tween(Motion.PHASE, easing = Motion.Decelerate))
                        } else Modifier,
                    ) {
                        AnimatedVisibility(visible = enabledSet.isNotEmpty()) {
                            Column {
                                BasicComponent(
                                    title = stringResource(R.string.store_settings_open_updates),
                                    summary = stringResource(R.string.store_settings_open_updates_desc),
                                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    onClick = onOpenStoreUpdates,
                                )
                                StoreRowDivider()
                            }
                        }
                        BasicComponent(
                            title = stringResource(R.string.store_settings_hide_home_card),
                            summary = stringResource(R.string.store_settings_hide_home_card_desc),
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            onClick = {
                                val next = !prefs.hideHomeCardWhenUpToDate
                                scope.launch {
                                    stateStore.update { st ->
                                        st.copy(settings = st.settings.copy(storePrefs = st.settings.storePrefs.copy(hideHomeCardWhenUpToDate = next)))
                                    }
                                }
                            },
                            endActions = {
                                Switch(
                                    checked = prefs.hideHomeCardWhenUpToDate,
                                    onCheckedChange = { checked ->
                                        scope.launch {
                                            stateStore.update { st ->
                                                st.copy(settings = st.settings.copy(storePrefs = st.settings.storePrefs.copy(hideHomeCardWhenUpToDate = checked)))
                                            }
                                        }
                                    },
                                )
                            },
                        )
                        StoreRowDivider()
                        BasicComponent(
                            title = stringResource(R.string.store_settings_system_apps),
                            summary = stringResource(R.string.store_settings_system_apps_desc),
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            onClick = { setIncludeSystemApps(!prefs.includeSystemApps) },
                            endActions = {
                                Switch(checked = prefs.includeSystemApps, onCheckedChange = ::setIncludeSystemApps)
                            },
                        )
                        StoreRowDivider()
                        ValueSettingRow(
                            title = stringResource(R.string.store_metered_title),
                            value = meteredPolicyLabel(prefs.meteredDownloadPolicy),
                            onClick = { showMeteredSheet = true },
                        )
                        AnimatedVisibility(visible = fdroidEnabled) {
                            Column {
                                StoreRowDivider()
                                ValueSettingRow(
                                    title = stringResource(R.string.store_fdroid_mirror_title),
                                    value = if (prefs.fdroidMirror == FdroidMirror.TUNA) {
                                        stringResource(R.string.store_fdroid_mirror_tuna_short)
                                    } else {
                                        stringResource(R.string.store_fdroid_mirror_official)
                                    },
                                    onClick = { showMirrorSheet = true },
                                )
                            }
                        }
                        StoreRowDivider()
                        ValueSettingRow(
                            title = stringResource(R.string.store_settings_excluded_apps),
                            value = if (prefs.excludedPackages.isEmpty()) {
                                stringResource(R.string.store_settings_none)
                            } else {
                                stringResource(R.string.store_settings_excluded_short, prefs.excludedPackages.size)
                            },
                            onClick = { showExcludedSheet = true },
                        )
                    }
                }
            }
        }
    }

    StorePrioritySheet(
        show = showPrioritySheet,
        enabledStores = prefs.enabledStores,
        onDismiss = { showPrioritySheet = false },
        onCommitOrder = { nextOrder ->
            scope.launch {
                stateStore.update { st ->
                    st.copy(
                        settings = st.settings.copy(
                            storePrefs = st.settings.storePrefs.copy(
                                enabledStores = nextOrder,
                                priorityCustomized = true,
                            ),
                        ),
                    )
                }
            }
        },
    )

    CandySheet(
        show = showMeteredSheet,
        title = stringResource(R.string.store_metered_title),
        onDismissRequest = { showMeteredSheet = false },
    ) {
        val options = listOf(
            Triple(MeteredDownloadPolicy.ALWAYS_ALLOW, R.string.store_metered_always_allow, R.string.store_metered_always_allow_desc),
            Triple(MeteredDownloadPolicy.OVER_50MB, R.string.store_metered_over_50, R.string.store_metered_over_50_desc),
            Triple(MeteredDownloadPolicy.OVER_200MB, R.string.store_metered_over_200, R.string.store_metered_over_200_desc),
            Triple(MeteredDownloadPolicy.ALWAYS_ASK, R.string.store_metered_always_ask, R.string.store_metered_always_ask_desc),
        )
        for ((policy, labelRes, descRes) in options) {
            OptionRow(
                label = stringResource(labelRes),
                summary = stringResource(descRes),
                selected = prefs.meteredDownloadPolicy == policy,
                onClick = {
                    showMeteredSheet = false
                    scope.launch {
                        stateStore.update { st ->
                            st.copy(
                                settings = st.settings.copy(
                                    storePrefs = st.settings.storePrefs.copy(
                                        meteredDownloadPolicy = policy,
                                    ),
                                ),
                            )
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    CandySheet(
        show = showMirrorSheet,
        title = stringResource(R.string.store_fdroid_mirror_title),
        onDismissRequest = { showMirrorSheet = false },
    ) {
        OptionRow(
            label = stringResource(R.string.store_fdroid_mirror_tuna_short),
            summary = stringResource(R.string.store_fdroid_mirror_tuna_desc),
            selected = prefs.fdroidMirror == FdroidMirror.TUNA,
            onClick = {
                showMirrorSheet = false
                updateCheckScope { st ->
                    st.copy(settings = st.settings.copy(storePrefs = st.settings.storePrefs.copy(fdroidMirror = FdroidMirror.TUNA)))
                }
            },
        )
        OptionRow(
            label = stringResource(R.string.store_fdroid_mirror_official),
            summary = stringResource(R.string.store_fdroid_mirror_official_desc),
            selected = prefs.fdroidMirror == FdroidMirror.OFFICIAL,
            onClick = {
                showMirrorSheet = false
                updateCheckScope { st ->
                    st.copy(settings = st.settings.copy(storePrefs = st.settings.storePrefs.copy(fdroidMirror = FdroidMirror.OFFICIAL)))
                }
            },
        )
        Spacer(Modifier.height(8.dp))
    }

    LaunchedEffect(showExcludedSheet) {
        if (showExcludedSheet) appContainer.storeUpdateChecker.reconcileTrackedPackages()
    }
    var excludedLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(prefs.excludedPackages, showExcludedSheet) {
        if (prefs.excludedPackages.isEmpty()) {
            excludedLabels = emptyMap()
            return@LaunchedEffect
        }
        if (!showExcludedSheet) {
            return@LaunchedEffect
        }
        excludedLabels = withContext(Dispatchers.IO) {
            prefs.excludedPackages.associateWith { pkg -> io.candy.tick.util.labelOf(context, pkg) ?: pkg }
        }
    }

    CandySheet(
        show = showExcludedSheet,
        title = stringResource(R.string.store_settings_excluded_apps),
        onDismissRequest = { showExcludedSheet = false },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
        ) {
            if (prefs.excludedPackages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(sheetCardColor())
                        .padding(vertical = 24.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.store_settings_excluded_empty),
                        style = MiuixTheme.textStyles.body2,
                        color = scheme.onSurfaceContainerVariant,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(sheetCardColor())
                        .verticalScroll(rememberScrollState()),
                ) {
                    val sortedExcluded = prefs.excludedPackages.sortedBy { excludedLabels[it] ?: it }
                    sortedExcluded.forEachIndexed { idx, pkg ->
                        if (idx > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp)
                                    .height(1.dp)
                                    .background(sheetDividerColor()),
                            )
                        }
                        ExcludedAppRow(
                            packageName = pkg,
                            displayName = excludedLabels[pkg] ?: pkg,
                            onRestore = {
                                scope.launch {
                                    stateStore.update { st ->
                                        st.withStorePreferenceFor(pkg) { p -> p.copy(excludedPackages = p.excludedPackages - pkg) }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExcludedAppRow(
    packageName: String,
    displayName: String,
    onRestore: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val icon = io.candy.tick.ui.common.rememberAppIcon(packageName)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        StoreAppIconSlot(icon = icon, size = 36.dp, corner = 10.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium),
                color = scheme.onSurfaceContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (displayName != packageName) {
                Text(
                    text = packageName,
                    style = MiuixTheme.textStyles.footnote2,
                    color = scheme.onSurfaceContainerVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SecondaryButton(
            text = stringResource(R.string.store_settings_restore),
            onClick = onRestore,
        )
    }
}

@Composable
private fun meteredPolicyLabel(policy: MeteredDownloadPolicy): String = when (policy) {
    MeteredDownloadPolicy.ALWAYS_ALLOW -> stringResource(R.string.store_metered_always_allow)
    MeteredDownloadPolicy.OVER_50MB -> stringResource(R.string.store_metered_over_50)
    MeteredDownloadPolicy.OVER_200MB -> stringResource(R.string.store_metered_over_200)
    MeteredDownloadPolicy.ALWAYS_ASK -> stringResource(R.string.store_metered_always_ask)
}

@Composable
private fun ValueSettingRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onClick,
        endActions = {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun StoreRowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

@Composable
private fun StoreToggleRow(
    desc: StoreDescriptor,
    checked: Boolean,
    autoDisabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val summary = when {
        !desc.implemented -> stringResource(R.string.store_tag_coming_soon)
        autoDisabled -> stringResource(R.string.store_tag_auto_paused)
        desc.speed == StoreQuerySpeed.FAST_BATCH -> stringResource(R.string.store_tag_fast)
        desc.speed == StoreQuerySpeed.LOCAL_INDEX -> stringResource(R.string.store_tag_local_index)
        else -> stringResource(R.string.store_tag_per_app)
    }
    BasicComponent(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (desc.implemented) 1f else 0.45f),
        title = stringResource(desc.nameRes),
        summary = summary,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        enabled = desc.implemented,
        onClick = if (desc.implemented) ({ onCheckedChange(!checked) }) else null,
        endActions = {
            if (desc.implemented) {
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
        },
    )
}
