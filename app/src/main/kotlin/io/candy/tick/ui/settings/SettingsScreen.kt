package io.candy.tick.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.data.model.ThemeMode
import io.candy.tick.data.Notifications
import io.candy.tick.data.SelfUpdater
import io.candy.tick.ui.common.CenteredScrollToTopBar
import io.candy.tick.ui.common.rememberExternalUriHandler
import io.candy.tick.ui.common.Feedback
import io.candy.tick.ui.common.rememberMotionEnabled
import io.candy.tick.ui.common.scrollToTop
import io.candy.tick.ui.theme.ChipAmber
import io.candy.tick.ui.theme.ChipBlue
import io.candy.tick.ui.theme.ChipOrange
import io.candy.tick.ui.theme.ChipGreen
import io.candy.tick.ui.theme.ChipPeriwinkle
import io.candy.tick.ui.theme.ChipPink
import io.candy.tick.ui.theme.ChipSand
import io.candy.tick.ui.theme.ChipSky
import io.candy.tick.ui.theme.ChipSlate
import io.candy.tick.ui.theme.ChipTeal
import io.candy.tick.ui.theme.ChipViolet
import io.candy.tick.util.VersionCompare
import io.candy.tick.util.openAppSettings
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector

private const val REPO_URL = "https://github.com/JeffioZ/CandyTick"
private const val LICENSE_URL = "https://github.com/JeffioZ/CandyTick/blob/main/LICENSE"

/** 「数据」分组在 LazyColumn 的条目序号（appearance/sync/appupdate 之后的第 4 项）；增删分组时同步 */
private const val DATA_GROUP_INDEX = 3

@Composable
fun SettingsScreen(onBack: () -> Unit, autoOpenImport: Boolean = false) {
    val app = LocalContext.current.applicationContext as CandyTickApp
    val vm: SettingsViewModel = viewModel {
        SettingsViewModel(
            repository = app.container.repoRepository,
            githubApi = app.container.githubApi,
            selfUpdater = app.container.selfUpdater,
            cacheManager = app.container.cacheManager,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val cacheState by vm.cacheState.collectAsStateWithLifecycle()

    // 检查更新的结果走全局 toast（与全 App 反馈同通道）：瞬时结论用胶囊；需要行动的
    // （发现新版 → UpdateSheet、下载/安装失败 → 各自重试入口）仍走既有路径
    val upToDateMsg = stringResource(R.string.version_local_current)
    val checkFailedMsg = stringResource(R.string.settings_update_failed)
    val rateLimitedMsg = stringResource(R.string.err_rate_limited_plain)
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val listState = rememberLazyListState()
    val motionEnabled = rememberMotionEnabled()
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var showFrequencySheet by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showIconSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showTokenDialog by rememberSaveable { mutableStateOf(false) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }

    val crashLogFile = remember {
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        java.io.File(dir, "crash.log")
    }
    fun crashLogExists() = crashLogFile.exists() && crashLogFile.length() > 0

    // 权限状态：进入页面 / 从系统设置返回（ON_RESUME）时刷新
    var installPermGranted by remember { mutableStateOf(app.container.installManager.canRequestInstalls()) }
    var notifGranted by remember { mutableStateOf(Notifications.canNotify(app)) }
    // 崩溃日志存在性走状态（ON_RESUME 刷新），避免每次重组都在主线程查磁盘
    var crashLogPresent by remember { mutableStateOf(false) } // ON_RESUME 注册即刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installPermGranted = app.container.installManager.canRequestInstalls()
                notifGranted = Notifications.canNotify(app)
                crashLogPresent = crashLogExists()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        vm.refreshRateLimit()
    }
    // rememberSaveable：空态「导入直达」拉起后旋转屏幕浮层不丢（此前 remember 旋转即关）
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var showCacheCleanup by remember { mutableStateOf(false) }
    // 仅用户手动点击「检查更新」时行尾显示「正在检查…」；后台静默检查不打扰版本号显示
    var manualChecking by remember { mutableStateOf(false) }
    // 空态「导入已有订阅」直达：先定位到数据分组（弹窗关闭时导入/导出行已在屏上），
    // 再自动拉起导入浮层。一次性消费（rememberSaveable）：旋转/返回重进不重复拉起；
    // scrollToItem 挂起等待首次布局，无需 totalItemsCount 门禁
    var importAutoOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoOpenImport) {
        if (!autoOpenImport || importAutoOpened) return@LaunchedEffect
        listState.scrollToItem(DATA_GROUP_INDEX)
        showImportDialog = true
        // 消费标记最后落：先置位时若此刻进程被杀/组合销毁，恢复后标记已 true 而浮层未开——直达静默失效
        importAutoOpened = true
    }

    fun showImportResult(added: Int) {
        val msg = when {
            added < 0 -> resources.getString(R.string.import_failed)
            added == 0 -> resources.getString(R.string.import_no_new)
            else -> resources.getQuantityString(R.plurals.import_result, added, added)
        }
        Feedback.show(msg, isError = added < 0, errorCode = if (added < 0) "E-IMPORT" else null)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val added = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { vm.importSubscriptions(it) } ?: -1
                } catch (_: java.io.IOException) { -1 }
                catch (_: SecurityException) { -1 }
            }
            showImportResult(added)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val text = vm.exportSubscriptions()
            if (text == null) {
                Feedback.show(resources.getString(R.string.export_unavailable), isError = true, errorCode = "E-EXPORT")
                return@launch
            }
            val saved = withContext(Dispatchers.IO) {
                try {
                    // 覆盖已有文件时优先用 "wt" 截断，避免旧文件尾部字节残留导致 JSON 损坏
                    val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: context.contentResolver.openOutputStream(uri)
                    stream?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } != null
                } catch (_: java.io.IOException) { false }
                catch (_: SecurityException) { false }
            }
            if (saved) Feedback.show(resources.getString(R.string.export_saved))
            else Feedback.show(resources.getString(R.string.export_save_failed), isError = true, errorCode = "E-EXPORT")
        }
    }

    suspend fun share(text: String, backup: Boolean) {
        try {
            shareSettingsText(context, text, backup)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Feedback.show(resources.getString(R.string.share_unavailable), isError = true, errorCode = "E-SHARE")
        }
    }

    fun shareCrashLog() {
        scope.launch {
            val content = withContext(Dispatchers.IO) { runCatching { crashLogFile.readText() }.getOrNull() }
            if (content.isNullOrEmpty()) Feedback.show(resources.getString(R.string.crash_log_empty))
            else share(content, backup = false)
        }
    }

    val links = rememberExternalUriHandler()

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            CenteredScrollToTopBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack,
                onTitleClick = { scope.launch { listState.scrollToTop(motionEnabled) } },
            )
        },
    ) { padding ->
        // LazyColumn：只组合可见分组，冷启动首进不再一次性构建整页
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            // ── 外观 ──
            item(key = "appearance") {
            SettingsGroup(title = stringResource(R.string.settings_appearance)) {
                SettingRow(
                    title = stringResource(R.string.settings_language),
                    value = languageLabel(state.settings.language),
                    icon = ImageVector.vectorResource(R.drawable.ic_globe),
                    iconTint = ChipBlue,
                    onClick = { showLanguageSheet = true },
                )
                RowDivider()
                SettingRow(
                    title = stringResource(R.string.settings_theme_mode),
                    value = themeModeLabel(state.settings.themeMode),
                    icon = ImageVector.vectorResource(R.drawable.ic_theme),
                    iconTint = ChipPeriwinkle,
                    onClick = { showThemeSheet = true },
                )
                RowDivider()
                SettingRow(
                    title = stringResource(R.string.settings_app_icon),
                    value = appIconModeLabel(state.settings.appIconMode),
                    icon = ImageVector.vectorResource(R.drawable.ic_app_icon),
                    iconTint = ChipTeal,
                    onClick = { showIconSheet = true },
                )
            }
            }

            // ── 同步与通知（通知 = 同步的结果输出）──
            item(key = "sync") {
            SettingsGroup(title = stringResource(R.string.settings_sync_and_notify)) {
                SettingRow(
                    title = stringResource(R.string.settings_sync_frequency),
                    value = frequencyLabel(state.settings.syncFrequency),
                    icon = ImageVector.vectorResource(R.drawable.ic_timer),
                    iconTint = ChipTeal,
                    onClick = { showFrequencySheet = true },
                )
                RowDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_sync_wifi_only),
                    summary = stringResource(R.string.settings_sync_wifi_only_summary),
                    checked = state.settings.wifiOnly,
                    icon = ImageVector.vectorResource(R.drawable.ic_wifi),
                    iconTint = ChipSky,
                    onChange = vm::setWifiOnly,
                )

                RowDivider()
                val rateLimit = state.rateLimit
                val tokenSummary = when {
                    state.tokenRejected -> stringResource(R.string.token_quota_rejected_short)
                    rateLimit != null -> stringResource(
                        R.string.token_quota_summary,
                        rateLimit.used,
                        rateLimit.remaining,
                        rateLimit.limit,
                    )
                    else -> stringResource(R.string.settings_token_hint_short)
                }
                val tokenValue = when {
                    !state.settings.githubToken.isNullOrBlank() && !state.settings.githubUsername.isNullOrBlank() ->
                        "@${state.settings.githubUsername}"
                    !state.settings.githubToken.isNullOrBlank() -> stringResource(R.string.token_set)
                    else -> stringResource(R.string.token_unset)
                }
                SettingRow(
                    title = stringResource(R.string.settings_token_row),
                    summary = tokenSummary,
                    value = tokenValue,
                    icon = ImageVector.vectorResource(R.drawable.ic_lock),
                    iconTint = ChipOrange,
                    onClick = {
                        vm.resetTokenDraft()
                        tokenVisible = false
                        showTokenDialog = true
                    },
                )
                RowDivider()
                // 通知 = 同步的结果输出，并入同步组（原孤组收敛）
                SwitchRow(
                    title = stringResource(R.string.settings_notifications),
                    checked = state.settings.notificationsEnabled,
                    icon = ImageVector.vectorResource(R.drawable.ic_bell),
                    iconTint = ChipPink,
                    onChange = vm::setNotifications,
                )
                RowDivider()
                // 通知权限 = 通知开关的先决条件：组尾附加（开关主体在前，不打断节奏）
                SettingRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_shield),
                    iconTint = ChipViolet,
                    title = stringResource(R.string.settings_perm_notifications),
                    value = permText(notifGranted),
                    onClick = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        if (!context.openAppSettings(intent)) {
                            Feedback.show(resources.getString(R.string.system_settings_unavailable), isError = true, errorCode = "E-SYS")
                        }
                    },
                )
            }
            }

            // ── 自动更新：纯偏好开关（检查行动已并入「关于」的版本行）──
            item(key = "appupdate") {
            SettingsGroup(title = stringResource(R.string.settings_app_updates)) {
                SwitchRow(
                    title = stringResource(R.string.settings_self_update_check),
                    checked = state.settings.selfUpdateCheck,
                    icon = ImageVector.vectorResource(R.drawable.ic_refresh),
                    iconTint = ChipTeal,
                    onChange = vm::setSelfUpdateCheck,
                )
                RowDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_self_update_download),
                    checked = state.settings.canAutoDownloadUpdate,
                    enabled = state.settings.selfUpdateCheck,
                    summary = if (state.settings.selfUpdateCheck) null else
                        stringResource(R.string.settings_self_update_download_requires_check),
                    onChange = vm::setSelfUpdateDownload,
                    icon = ImageVector.vectorResource(R.drawable.ic_download),
                    iconTint = ChipSky,
                )
                RowDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_auto_install),
                    checked = state.settings.autoInstallDownload,
                    onChange = vm::setAutoInstallDownload,
                    summary = stringResource(R.string.settings_auto_install_summary),
                    icon = ImageVector.vectorResource(R.drawable.ic_package),
                    iconTint = ChipGreen,
                )
                RowDivider()
                // 安装权限 = 安装功能的先决条件：与自动安装成对呈现
                SettingRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_unlock),
                    iconTint = ChipGreen,
                    title = stringResource(R.string.settings_perm_install_unknown),
                    summary = stringResource(R.string.settings_perm_install_summary),
                    value = permText(installPermGranted),
                    onClick = {
                        if (!app.container.installManager.openInstallPermissionSettings()) {
                            Feedback.show(resources.getString(R.string.system_settings_unavailable), isError = true, errorCode = "E-SYS")
                        }
                    },
                )
            }
            }

            // ── 数据 ──
            item(key = "data") {
            SettingsGroup(title = stringResource(R.string.settings_backup_group)) {
                SettingRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_share),
                    iconTint = ChipAmber,
                    title = stringResource(R.string.settings_export),
                    summary = stringResource(R.string.settings_export_summary),
                    onClick = { showExportDialog = true },
                )
                RowDivider()
                SettingRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_import),
                    iconTint = ChipSand,
                    title = stringResource(R.string.settings_import),
                    summary = stringResource(R.string.settings_import_summary),
                    onClick = { showImportDialog = true },
                )
                RowDivider()
                SettingRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_delete),
                    iconTint = ChipOrange,
                    title = stringResource(R.string.settings_cache_cleanup),
                    summary = stringResource(R.string.settings_cache_summary),
                    onClick = { showCacheCleanup = true },
                )
            }
            }

            // ── 关于：纯信息（版本 + 链接），更新行为偏好独立成组 ──
            item(key = "about") {
            SettingsGroup(title = stringResource(R.string.settings_about_group)) {

                val (versionValue, versionMono) = versionRowDisplay(
                    update = state.selfUpdate,
                    installed = state.localVersion,
                    manualChecking = manualChecking,
                )
                SettingRow(
                    // 版本行 = 检查更新入口（微信/Telegram/Shizuku 同款惯例）：
                    // 常态只展示版本号，仅手动检查时短暂显示「正在检查…」，有新版本/下载失败等可操作状态才切换文案
                    title = stringResource(R.string.settings_current_version_title),
                    value = versionValue,
                    icon = ImageVector.vectorResource(R.drawable.ic_info),
                    iconTint = ChipSlate,
                    valueMonospace = versionMono,
                    onClick = {
                        // 已下载/下载中：直接重开弹窗（重复检查会覆盖已下载文件）
                        when (state.selfUpdate) {
                            is SelfUpdater.State.Downloaded, is SelfUpdater.State.Downloading ->
                                io.candy.tick.ui.common.UpdateSheetController.manualRequest = true
                            else -> {
                                if (manualChecking) return@SettingRow
                                manualChecking = true
                                val alreadyChecking = state.selfUpdate is SelfUpdater.State.Checking
                                scope.launch {
                                    val result = try {
                                        if (alreadyChecking) vm.awaitCheckResult() else vm.checkUpdate()
                                    } finally {
                                        manualChecking = false
                                    }
                                    when (result) {
                                        SelfUpdater.State.UpToDate ->
                                            io.candy.tick.ui.common.Feedback.show(upToDateMsg)
                                        is SelfUpdater.State.Failed -> when (result.reason) {
                                            SelfUpdater.Reason.NETWORK ->
                                                io.candy.tick.ui.common.Feedback.show(checkFailedMsg, isError = true, errorCode = "E-NET")
                                            // 限流：Failed 无 tag，弹窗打不开（contentTag=null 被静默消费）——胶囊直接给原因
                                            SelfUpdater.Reason.RATE_LIMITED ->
                                                io.candy.tick.ui.common.Feedback.show(rateLimitedMsg, isError = true, errorCode = "HTTP 403")
                                            else -> io.candy.tick.ui.common.UpdateSheetController.manualRequest = true
                                        }
                                        is SelfUpdater.State.NewVersion, is SelfUpdater.State.Downloaded ->
                                            io.candy.tick.ui.common.UpdateSheetController.manualRequest = true
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    },
                )
                RowDivider()
                SettingRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_external_link),
                    iconTint = ChipPeriwinkle,
                    title = stringResource(R.string.settings_github_repo),
                    value = "JeffioZ/CandyTick",
                    onClick = { links.openUri(REPO_URL) },
                )
                RowDivider()
                SettingRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_scale),
                    iconTint = ChipSlate,
                    title = stringResource(R.string.settings_license),
                    value = "GPL-3.0",
                    onClick = { links.openUri(LICENSE_URL) },
                )
                RowDivider()
                SettingRow(
                    // Report（错误报告）：与「当前版本」的 Info 区分，崩溃日志语义更准
                    icon = ImageVector.vectorResource(R.drawable.ic_report),
                    iconTint = ChipOrange,
                    title = stringResource(R.string.settings_crash_log),
                    summary = stringResource(R.string.settings_crash_log_summary),
                    // 存在性标记 Lucide 带圈勾（与全 App 结果符单源；此前是 Unicode 文字"✓"）
                    trailing = if (crashLogPresent) {
                        {
                            top.yukonga.miuix.kmp.basic.Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_check_circle),
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primaryVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else null,
                    onClick = { shareCrashLog() },
                )
            }
            }

            item(key = "footer") { Spacer(Modifier.size(32.dp)) }
        }
    }

    SyncFrequencyPickerSheet(
        show = showFrequencySheet,
        selected = state.settings.syncFrequency,
        onSelect = { freq ->
            vm.setSyncFrequency(freq)
            showFrequencySheet = false
        },
        onDismiss = { showFrequencySheet = false },
    )

    ThemeModePickerSheet(
        show = showThemeSheet,
        selected = state.settings.themeMode,
        onSelect = { mode ->
            vm.setThemeMode(mode)
            showThemeSheet = false
        },
        onDismiss = { showThemeSheet = false },
    )

    AppIconPickerSheet(
        show = showIconSheet,
        selected = state.settings.appIconMode,
        onSelect = { mode ->
            showIconSheet = false
            if (mode == state.settings.appIconMode) return@AppIconPickerSheet
            // 仅落盘偏好并提示，实际组件切换延迟到退回后台时执行（避免前台禁用 alias 触发系统杀进程）
            vm.setAppIconMode(mode)
            Feedback.show(resources.getString(R.string.app_icon_switched))
        },
        onDismiss = { showIconSheet = false },
    )

    LanguagePickerSheet(
        show = showLanguageSheet,
        selected = state.settings.language,
        onSelect = { lang ->
            vm.setLanguage(lang)
            showLanguageSheet = false
        },
        onDismiss = { showLanguageSheet = false },
    )

    // 导出去向选择浮层（本地文件 / 剪贴板 / 分享给其他应用）：与导入浮层对称
    ExportDestinationDialog(
        show = showExportDialog,
        onToFile = {
            showExportDialog = false
            scope.launch {
                if (vm.exportSubscriptions() == null) {
                    Feedback.show(resources.getString(R.string.export_unavailable), isError = true, errorCode = "E-EXPORT")
                    return@launch
                }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                runCatching {
                    exportLauncher.launch("candytick-backup-$stamp.json")
                }.onFailure {
                    Feedback.show(resources.getString(R.string.file_picker_unavailable), isError = true, errorCode = "E-PICK")
                }
            }
        },
        onToClipboard = {
            showExportDialog = false
            scope.launch {
                val text = vm.exportSubscriptions()
                if (text == null) {
                    Feedback.show(resources.getString(R.string.export_unavailable), isError = true, errorCode = "E-EXPORT")
                    return@launch
                }
                if (text.length > MAX_SHARED_TEXT_CHARS) {
                    Feedback.show(resources.getString(R.string.export_too_large_for_clipboard), isError = true, errorCode = "E-EXPORT")
                    return@launch
                }
                if (runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(null, text)) }.isSuccess) {
                    Feedback.show(resources.getString(R.string.toast_copied))
                } else {
                    Feedback.show(resources.getString(R.string.export_save_failed), isError = true, errorCode = "E-EXPORT")
                }
            }
        },
        onShare = {
            showExportDialog = false
            scope.launch {
                val text = vm.exportSubscriptions()
                if (text == null) {
                    Feedback.show(resources.getString(R.string.export_unavailable), isError = true, errorCode = "E-EXPORT")
                    return@launch
                }
                share(text, backup = true)
            }
        },
        onDismiss = { showExportDialog = false },
    )

    // 导入来源选择弹窗（文件 / 剪贴板）：show 直通，退场有动效
    ImportSourceDialog(
        show = showImportDialog,
        onFromFile = {
            showImportDialog = false
            // mime 放宽：第三方渠道下发的 .json 常被标成 text/plain 或 octet-stream，
            // 单 application/json 会让人在文件选择器里选不到自己的备份
            runCatching {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }.onFailure {
                Feedback.show(resources.getString(R.string.file_picker_unavailable), isError = true, errorCode = "E-PICK")
            }
        },
        onFromClipboard = {
            showImportDialog = false
            scope.launch {
                val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
                showImportResult(if (text.isNullOrBlank()) -1 else vm.importSubscriptions(text))
            }
        },
        onDismiss = { showImportDialog = false },
    )

    // Token 弹窗：关闭 = 底部「取消」+ scrim 点击 + 返回键；show 直通保退场动效
    TokenDialog(
        show = showTokenDialog,
        state = state,
        tokenVisible = tokenVisible,
        onToggleVisible = { tokenVisible = !tokenVisible },
        onTokenInput = vm::onTokenInput,
        onSaveAndTest = vm::saveAndTestToken,
        onRefreshRateLimit = vm::refreshRateLimit,
        onOpenHelp = { links.openUri("https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens") },
        onDismiss = {
            showTokenDialog = false
            vm.resetTokenDraft() // 退出=放弃未保存编辑：重置为已保存值，消灭幽灵草稿
        },
    )

    // 清理缓存浮层
    CacheCleanupSheet(
        show = showCacheCleanup,
        state = cacheState,
        onScan = vm::scanCache,
        onClean = { apks, crashLog ->
            val freed = vm.cleanCache(apks, crashLog)
            // 崩溃日志被清后立即刷新 ✓ 标记（不等 ON_RESUME）
            crashLogPresent = crashLogExists()
            freed
        },
        onDismiss = { showCacheCleanup = false },
        onDismissFinished = vm::resetCacheState,
    )
}

@Composable
private fun languageLabel(tag: String): String = when (tag) {
    "zh" -> stringResource(R.string.lang_chinese)
    "en" -> stringResource(R.string.lang_english)
    else -> stringResource(R.string.lang_system)
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_mode_system
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.DARK -> R.string.theme_mode_dark
    },
)

@Composable
private fun appIconModeLabel(mode: io.candy.tick.data.model.AppIconMode): String = stringResource(
    when (mode) {
        io.candy.tick.data.model.AppIconMode.SYSTEM -> R.string.app_icon_system
        io.candy.tick.data.model.AppIconMode.LIGHT -> R.string.app_icon_light
        io.candy.tick.data.model.AppIconMode.DARK -> R.string.app_icon_dark
    },
)

/** 版本行 value 与等宽标记：常态与瞬时结果统一显示干净版本号，仅手动检查中或可操作状态切换文案 */
@Composable
private fun versionRowDisplay(
    update: SelfUpdater.State,
    installed: String?,
    manualChecking: Boolean,
): Pair<String, Boolean> {
    if (manualChecking) return stringResource(R.string.settings_checking) to false
    val localVersion = installed?.let(VersionCompare::displayVersion) ?: stringResource(R.string.status_unknown)
    val localMono = installed != null
    return when (update) {
        is SelfUpdater.State.NewVersion ->
            // 无法比较版本：明说「无法比较」，不宣称「有更新」（design.md:183）
            if (update.incomparable) {
                stringResource(R.string.settings_new_release_incomparable, VersionCompare.displayVersion(update.tag)) to false
            } else {
                VersionCompare.displayVersion(update.tag) to true
            }
        is SelfUpdater.State.Downloaded -> VersionCompare.displayVersion(update.tag) to true
        is SelfUpdater.State.Failed -> when (update.reason) {
            SelfUpdater.Reason.DOWNLOAD -> stringResource(R.string.settings_download_failed) to false
            SelfUpdater.Reason.INSTALL -> stringResource(R.string.settings_install_failed) to false
            // 网络/限流失败已有手动点击 Toast，后台失败不污染版本号行
            else -> localVersion to localMono
        }
        else -> localVersion to localMono
    }
}

@Composable
private fun permText(granted: Boolean): String =
    stringResource(if (granted) R.string.perm_granted else R.string.perm_not_granted)
