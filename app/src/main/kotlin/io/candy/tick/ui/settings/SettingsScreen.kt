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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.data.Notifications
import io.candy.tick.data.SelfUpdater
import io.candy.tick.data.model.SyncFrequency
import io.candy.tick.ui.common.CandyIcons
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.SecondaryButton
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
    var showFrequencySheet by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 结果以行内提醒呈现，不弹窗追问 */ }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

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
                if (state.settings.notificationsEnabled && !Notifications.canNotify(app)) {
                    RowDivider()
                    SettingRow(
                        title = stringResource(R.string.settings_notif_permission_banner),
                        trailing = {
                            TextButton(
                                text = stringResource(R.string.settings_notif_grant),
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                            )
                        },
                    )
                }
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
                    SecondaryButton(
                        text = stringResource(R.string.settings_token_test),
                        onClick = vm::saveAndTestToken,
                        enabled = state.tokenInput.isNotBlank(), // 空 Token 禁用
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
