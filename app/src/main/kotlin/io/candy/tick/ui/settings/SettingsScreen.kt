package io.candy.tick.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.data.model.ThemeMode
import io.candy.tick.data.Notifications
import io.candy.tick.data.SelfUpdater
import io.candy.tick.data.model.SyncFrequency
import io.candy.tick.ui.theme.ChipAmber
import io.candy.tick.ui.theme.ChipBlue
import io.candy.tick.ui.theme.ChipOrange
import io.candy.tick.ui.theme.ChipGreen
import io.candy.tick.ui.theme.ChipMint
import io.candy.tick.ui.theme.ChipPeriwinkle
import io.candy.tick.ui.theme.ChipPink
import io.candy.tick.ui.theme.ChipSand
import io.candy.tick.ui.theme.ChipSky
import io.candy.tick.ui.theme.ChipSlate
import io.candy.tick.ui.theme.ChipTeal
import io.candy.tick.ui.theme.ChipViolet
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector

private const val REPO_URL = "https://github.com/JeffioZ/CandyTick"
private const val LICENSE_URL = "https://github.com/JeffioZ/CandyTick/blob/main/LICENSE"

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as CandyTickApp
    val vm: SettingsViewModel = viewModel {
        SettingsViewModel(
            repository = app.container.repoRepository,
            githubApi = app.container.githubApi,
            selfUpdater = app.container.selfUpdater,
            syncScheduler = app.container.syncScheduler,
            cacheManager = app.container.cacheManager,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val cacheState by vm.cacheState.collectAsStateWithLifecycle()
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showFrequencySheet by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
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
    var askedNotifOnce by rememberSaveable { mutableStateOf(false) }
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notifGranted = Notifications.canNotify(app) }

    var showImportDialog by remember { mutableStateOf(false) }
    var showCacheCleanup by remember { mutableStateOf(false) }

    fun importFromText(text: String?) {
        if (text.isNullOrBlank()) {
            io.candy.tick.ui.common.Feedback.show(text = app.getString(R.string.import_failed), isError = true)
            return
        }
        scope.launch {
            val added = vm.importSubscriptions(text)
            val msg = when {
                added < 0 -> app.getString(R.string.import_failed)
                added == 0 -> app.getString(R.string.import_no_new)
                else -> app.getString(R.string.import_result, added)
            }
            io.candy.tick.ui.common.Feedback.show(text = msg, isError = added < 0)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // 文件读取在 IO 线程（大备份的 pretty JSON 可达数百 KB，不占主线程）
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                importFromText(text)
            }
        }
    }

    fun shareCrashLog() {
        if (!crashLogExists()) {
            io.candy.tick.ui.common.Feedback.show(text = app.getString(R.string.crash_log_empty))
            return
        }
        val content = runCatching { crashLogFile.readText() }.getOrDefault("")
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, content)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openNotificationSettings() {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun languageTag(): String = state.settings.language

    fun applyLanguage(tag: String) {
        scope.launch { vm.setLanguage(tag) }
        // 不 recreate：ContextWrapper 包裹会响应 language 变化原地切换文字
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.settings_title),
                color = androidx.compose.ui.graphics.Color.Transparent,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_back),
                            contentDescription = null,
                            tint = scheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { padding ->
        // LazyColumn：只组合可见分组，冷启动首进不再一次性构建整页
        androidx.compose.foundation.lazy.LazyColumn(
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
                    value = languageLabel(languageTag()),
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
                    checked = state.settings.wifiOnly,
                    icon = ImageVector.vectorResource(R.drawable.ic_cloud),
                    iconTint = ChipSky,
                    onChange = vm::setWifiOnly,
                )

                RowDivider()
                SettingRow(
                    title = stringResource(R.string.settings_token_row),
                    summary = stringResource(R.string.settings_token_hint_short),
                    value = if (state.tokenInput.isNotBlank()) {
                        stringResource(R.string.token_set)
                    } else {
                        stringResource(R.string.token_unset)
                    },
                    icon = ImageVector.vectorResource(R.drawable.ic_lock),
                    iconTint = ChipOrange,
                    onClick = { showTokenDialog = true },
                )
                RowDivider()
                // 通知 = 同步的结果输出，并入同步组（原孤组收敛）
                SwitchRow(
                    title = stringResource(R.string.settings_notifications),
                    checked = state.settings.notificationsEnabled,
                    icon = ImageVector.vectorResource(R.drawable.ic_messages),
                    iconTint = ChipPink,
                    onChange = vm::setNotifications,
                )
                RowDivider()
                // 通知权限 = 通知开关的先决条件：组尾附加（开关主体在前，不打断节奏）
                SettingRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_email),
                    iconTint = ChipViolet,
                    title = stringResource(R.string.settings_perm_notifications),
                    value = when {
                        Build.VERSION.SDK_INT < 33 -> permText(2)
                        notifGranted -> permText(1)
                        else -> permText(0)
                    },
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && !askedNotifOnce) {
                            askedNotifOnce = true
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            // 二次点击 / 永久拒绝兜底：直达应用通知设置
                            openNotificationSettings()
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
                    checked = state.settings.selfUpdateAutoDownload,
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
                    icon = ImageVector.vectorResource(R.drawable.ic_file_down),
                    iconTint = ChipGreen,
                )
                RowDivider()
                // 安装权限 = 安装功能的先决条件：与自动安装成对呈现
                SettingRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_unlock),
                    iconTint = ChipGreen,
                    title = stringResource(R.string.settings_perm_install_unknown),
                    summary = stringResource(R.string.settings_perm_install_summary),
                    value = permText(if (installPermGranted) 1 else 0),
                    onClick = { app.container.installManager.openInstallPermissionSettings() },
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
                    onClick = {
                        val text = vm.exportSubscriptions()
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                        io.candy.tick.ui.common.Feedback.show(text = app.getString(R.string.toast_copied))
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, text)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
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

                SettingRow(
                    // 版本行 = 检查更新入口（微信/Telegram/Shizuku 同款惯例）：
                    // value 随检查状态变化，发现新版由全局 UpdateSheet 弹出
                    title = stringResource(R.string.settings_current_version_title),
                    value = versionRowValue(state.selfUpdate, state.localVersion),
                    icon = ImageVector.vectorResource(R.drawable.ic_info),
                    iconTint = ChipSlate,
                    valueMonospace = state.selfUpdate is SelfUpdater.State.NewVersion ||
                        state.selfUpdate is SelfUpdater.State.Downloaded,
                    onClick = {
                        // 已下载/下载中：直接重开弹窗（重复检查会覆盖已下载文件）
                        when (state.selfUpdate) {
                            is SelfUpdater.State.Downloaded, is SelfUpdater.State.Downloading -> Unit
                            // 检查中：无操作（防重复请求烧限额），「正在检查…」已是反馈
                            is SelfUpdater.State.Checking -> return@SettingRow
                            else -> vm.checkUpdate()
                        }
                        io.candy.tick.ui.common.UpdateSheetController.manualRequest = true
                    },
                )
                RowDivider()
                SettingRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_external_link),
                    iconTint = ChipPeriwinkle,
                    title = stringResource(R.string.settings_github_repo),
                    value = "JeffioZ/CandyTick",
                    onClick = { openUrl(REPO_URL) },
                )
                RowDivider()
                SettingRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_file),
                    iconTint = ChipSlate,
                    title = stringResource(R.string.settings_license),
                    value = "GPL-3.0",
                    onClick = { openUrl(LICENSE_URL) },
                )
                RowDivider()
                SettingRow(
                    // Report（错误报告）：与「当前版本」的 Info 区分，崩溃日志语义更准
                    icon = ImageVector.vectorResource(R.drawable.ic_report),
                    iconTint = ChipOrange,
                    title = stringResource(R.string.settings_crash_log),
                    summary = stringResource(R.string.settings_crash_log_summary),
                    // 存在性标记 Lucide 化（与全 App 勾符单源；此前是 Unicode 文字"✓"）
                    trailing = if (crashLogPresent) {
                        {
                            top.yukonga.miuix.kmp.basic.Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primaryVariant,
                                modifier = Modifier.size(14.dp),
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

    WindowBottomSheet(
        show = showFrequencySheet,
        title = stringResource(R.string.settings_sync_frequency),
        onDismissRequest = { showFrequencySheet = false },
    ) {
        Column {
            FrequencyRow(SyncFrequency.ON_OPEN, state.settings.syncFrequency, vm::setSyncFrequency) { showFrequencySheet = false }
            FrequencyRow(SyncFrequency.EVERY_6_HOURS, state.settings.syncFrequency, vm::setSyncFrequency) { showFrequencySheet = false }
            FrequencyRow(SyncFrequency.EVERY_12_HOURS, state.settings.syncFrequency, vm::setSyncFrequency) { showFrequencySheet = false }
            FrequencyRow(SyncFrequency.DAILY, state.settings.syncFrequency, vm::setSyncFrequency) { showFrequencySheet = false }
        }
    }

    WindowBottomSheet(
        show = showThemeSheet,
        title = stringResource(R.string.settings_theme_mode),
        onDismissRequest = { showThemeSheet = false },
    ) {
        Column {
            OptionRow(stringResource(R.string.theme_mode_system), state.settings.themeMode == ThemeMode.SYSTEM) {
                vm.setThemeMode(ThemeMode.SYSTEM); showThemeSheet = false
            }
            OptionRow(stringResource(R.string.theme_mode_light), state.settings.themeMode == ThemeMode.LIGHT) {
                vm.setThemeMode(ThemeMode.LIGHT); showThemeSheet = false
            }
            OptionRow(stringResource(R.string.theme_mode_dark), state.settings.themeMode == ThemeMode.DARK) {
                vm.setThemeMode(ThemeMode.DARK); showThemeSheet = false
            }
        }
    }

    WindowBottomSheet(
        show = showLanguageSheet,
        title = stringResource(R.string.settings_language),
        onDismissRequest = { showLanguageSheet = false },
    ) {
        Column {
            OptionRow(stringResource(R.string.lang_system), languageTag().isEmpty()) {
                applyLanguage(""); showLanguageSheet = false
            }
            OptionRow(stringResource(R.string.lang_chinese), languageTag() == "zh") {
                applyLanguage("zh"); showLanguageSheet = false
            }
            OptionRow(stringResource(R.string.lang_english), languageTag() == "en") {
                applyLanguage("en"); showLanguageSheet = false
            }
        }
    }

    // 导入来源选择弹窗（文件 / 剪贴板）：show 直通，退场有动效
    ImportSourceDialog(
        show = showImportDialog,
        onFromFile = {
            showImportDialog = false
            // mime 放宽：第三方渠道下发的 .json 常被标成 text/plain 或 octet-stream，
            // 单 application/json 会让人在文件选择器里选不到自己的备份
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        },
        onFromClipboard = {
            showImportDialog = false
            importFromText(clipboard.getText()?.text)
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
        onOpenHelp = {
            runCatching {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
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
        onDismiss = {
            showCacheCleanup = false
            vm.resetCacheState()
        },
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

/** 版本行 value：版本号常驻（用户始终可见），检查状态作前/后缀 */
@Composable
private fun versionRowValue(update: SelfUpdater.State, localVersion: String): String {
    val upToDate = stringResource(R.string.settings_up_to_date)
    val failed = stringResource(R.string.settings_update_failed)
    return when (update) {
        is SelfUpdater.State.Checking -> stringResource(R.string.settings_checking)
        is SelfUpdater.State.UpToDate -> "$upToDate · $localVersion"
        is SelfUpdater.State.NewVersion -> update.tag
        is SelfUpdater.State.Downloaded -> update.tag
        is SelfUpdater.State.Failed -> "$failed · $localVersion"
        else -> localVersion
    }
}

/** 权限状态文案：1=已授权 0=未授权 2=无需授予 */
@Composable
private fun permText(status: Int): String = stringResource(
    when (status) {
        1 -> R.string.perm_granted
        2 -> R.string.perm_not_required
        else -> R.string.perm_not_granted
    },
)
