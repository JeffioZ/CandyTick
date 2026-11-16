package io.candy.tick.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import io.candy.tick.data.Notifications
import io.candy.tick.data.SelfUpdater
import io.candy.tick.data.model.SyncFrequency
import io.candy.tick.ui.common.CandyIcons
import io.candy.tick.ui.common.PrimaryButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

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
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showFrequencySheet by remember { mutableStateOf(false) }

    // 权限状态：进入页面 / 从系统设置返回（ON_RESUME）时刷新
    var installPermGranted by remember { mutableStateOf(app.container.installManager.canRequestInstalls()) }
    var notifGranted by remember { mutableStateOf(Notifications.canNotify(app)) }
    var askedNotifOnce by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installPermGranted = app.container.installManager.canRequestInstalls()
                notifGranted = Notifications.canNotify(app)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notifGranted = Notifications.canNotify(app) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                android.widget.Toast.makeText(app, app.getString(R.string.import_failed), android.widget.Toast.LENGTH_SHORT).show()
            } else {
                scope.launch {
                    val added = vm.importSubscriptions(text)
                    val msg = if (added >= 0) {
                        app.getString(R.string.import_result, added)
                    } else {
                        app.getString(R.string.import_failed)
                    }
                    android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
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

    /** 1=已授权 0=未授权 2=无需授予（见文件级 permText） */

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.settings_title),
                color = androidx.compose.ui.graphics.Color.Transparent,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = CandyIcons.Back,
                            contentDescription = null,
                            tint = scheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            // ── 外观 ──
            SettingsGroup(title = stringResource(R.string.settings_appearance)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_theme_mode),
                        style = MiuixTheme.textStyles.body1,
                        color = scheme.onBackground,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    ThemeModeSegmented(
                        selected = state.settings.themeMode,
                        onSelect = vm::setThemeMode,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 16.dp),
                    )
                }
            }

            // ── 同步 ──
            SettingsGroup(title = stringResource(R.string.settings_sync)) {
                SettingRow(
                    title = stringResource(R.string.settings_sync_frequency),
                    value = frequencyLabel(state.settings.syncFrequency),
                    onClick = { showFrequencySheet = true },
                )
                RowDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_sync_wifi_only),
                    checked = state.settings.wifiOnly,
                    onChange = vm::setWifiOnly,
                )
                RowDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_sync_charging_only),
                    checked = state.settings.chargingOnly,
                    onChange = vm::setChargingOnly,
                )
            }

            // ── 通知 ──
            SettingsGroup(title = stringResource(R.string.settings_notifications)) {
                SwitchRow(
                    title = stringResource(R.string.settings_notifications),
                    checked = state.settings.notificationsEnabled,
                    onChange = vm::setNotifications,
                )
            }

            // ── 权限 ──
            SettingsGroup(title = stringResource(R.string.settings_permission_group)) {
                SettingRow(
                    title = stringResource(R.string.settings_perm_install_unknown),
                    summary = stringResource(R.string.settings_perm_install_summary),
                    value = permText(if (installPermGranted) 1 else 0),
                    onClick = { app.container.installManager.openInstallPermissionSettings() },
                )
                RowDivider()
                SettingRow(
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

            // ── GitHub Token ──
            SettingsGroup(title = stringResource(R.string.settings_token)) {
                Text(
                    text = stringResource(R.string.settings_token_hint),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                )
                TextField(
                    value = state.tokenInput,
                    onValueChange = vm::onTokenInput,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (state.tokenRejected) {
                    Text(
                        text = stringResource(R.string.settings_token_rejected),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onErrorContainer,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrimaryButton(
                        text = stringResource(R.string.settings_token_test),
                        onClick = vm::saveAndTestToken,
                        enabled = state.tokenInput.isNotBlank(), // 空 Token 禁用（薄荷底 vs 灰底，状态一眼可辨）
                    )
                    when (state.tokenTest) {
                        SettingsViewModel.TokenTest.TESTING -> Text(
                            stringResource(R.string.loading),
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onBackgroundVariant,
                        )
                        SettingsViewModel.TokenTest.VALID -> Text(
                            stringResource(R.string.settings_token_valid),
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.primaryVariant,
                        )
                        SettingsViewModel.TokenTest.INVALID -> Text(
                            stringResource(R.string.settings_token_invalid),
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onErrorContainer,
                        )
                        SettingsViewModel.TokenTest.NETWORK -> Text(
                            stringResource(R.string.settings_token_network_error),
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onErrorContainer,
                        )
                        null -> Unit
                    }
                }
            }

            // ── 关于 · 版本 ──
            SettingsGroup(title = stringResource(R.string.settings_self_update)) {
                SettingRow(
                    title = stringResource(R.string.settings_current_version_title),
                    value = state.localVersion,
                    valueMonospace = true,
                )
                RowDivider()
                SettingRow(
                    title = stringResource(R.string.settings_check_update),
                    value = checkStateValue(state.selfUpdate),
                    valueMonospace = state.selfUpdate is SelfUpdater.State.NewVersion,
                    onClick = vm::checkUpdate,
                )
                RowDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_self_update_check),
                    checked = state.settings.selfUpdateCheck,
                    onChange = vm::setSelfUpdateCheck,
                )
                RowDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_self_update_download),
                    checked = state.settings.selfUpdateAutoDownload,
                    onChange = vm::setSelfUpdateDownload,
                    summary = stringResource(R.string.settings_silent_hint),
                )
                SelfUpdateActions(state = state, vm = vm)
            }

            // ── 备份 ──
            SettingsGroup(title = stringResource(R.string.settings_backup_group)) {
                SettingRow(
                    title = stringResource(R.string.settings_export),
                    summary = stringResource(R.string.settings_export_summary),
                    onClick = {
                        val text = vm.exportSubscriptions()
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
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
                    title = stringResource(R.string.settings_import),
                    summary = stringResource(R.string.settings_import_summary),
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                )
            }

            // ── 关于 ──
            SettingsGroup(title = stringResource(R.string.settings_about_group)) {
                SettingRow(
                    title = stringResource(R.string.settings_github_repo),
                    value = "JeffioZ/CandyTick",
                    onClick = { openUrl(REPO_URL) },
                )
                RowDivider()
                SettingRow(
                    title = stringResource(R.string.settings_license),
                    value = "GPL-3.0",
                    onClick = { openUrl(LICENSE_URL) },
                )
            }

            Spacer(Modifier.size(32.dp))
        }
    }

    WindowBottomSheet(
        show = showFrequencySheet,
        title = stringResource(R.string.settings_sync_frequency),
        onDismissRequest = { showFrequencySheet = false },
    ) {
        Column {
            FrequencyRow(SyncFrequency.EVERY_6_HOURS, state.settings.syncFrequency, vm::setSyncFrequency) { showFrequencySheet = false }
            FrequencyRow(SyncFrequency.EVERY_12_HOURS, state.settings.syncFrequency, vm::setSyncFrequency) { showFrequencySheet = false }
            FrequencyRow(SyncFrequency.DAILY, state.settings.syncFrequency, vm::setSyncFrequency) { showFrequencySheet = false }
        }
    }
}

@Composable
private fun checkStateValue(update: SelfUpdater.State): String = when (update) {
    is SelfUpdater.State.Checking -> stringResource(R.string.loading)
    is SelfUpdater.State.UpToDate -> stringResource(R.string.settings_up_to_date)
    is SelfUpdater.State.NewVersion -> update.tag
    is SelfUpdater.State.Failed -> stringResource(R.string.settings_update_failed)
    else -> ""
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

/** 自身更新的可操作块：新版本（说明 + 下载）/ 下载进度 / 已下载（安装） */
@Composable
private fun SelfUpdateActions(state: SettingsViewModel.UiState, vm: SettingsViewModel) {
    val scheme = MiuixTheme.colorScheme
    when (val update = state.selfUpdate) {
        is SelfUpdater.State.NewVersion -> Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            update.notes?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            PrimaryButton(
                text = stringResource(R.string.settings_download_update),
                onClick = vm::downloadUpdate,
                modifier = Modifier.fillMaxWidth(),
                enabled = update.asset != null,
            )
        }
        is SelfUpdater.State.Downloading -> Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        ) {
            LinearProgressIndicator(progress = update.progress, modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.download_progress_percent, (update.progress * 100).toInt()),
                style = MiuixTheme.textStyles.footnote2,
                color = scheme.onBackgroundVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        is SelfUpdater.State.Downloaded -> PrimaryButton(
            text = stringResource(R.string.settings_install_update),
            onClick = { vm.installUpdate(update.file) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
        else -> Unit
    }
}
