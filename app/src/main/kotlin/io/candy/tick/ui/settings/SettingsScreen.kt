package io.candy.tick.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.data.Notifications
import io.candy.tick.data.SelfUpdater
import io.candy.tick.data.model.SyncFrequency
import io.candy.tick.data.model.ThemeMode
import io.candy.tick.ui.common.CandyIcons
import io.candy.tick.ui.theme.MonoTokenStyle
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

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
    var showFrequencySheet by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 结果以横幅常驻提醒呈现，不弹窗追问 */ }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.settings_title),
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(stringResource(R.string.settings_appearance))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_theme_mode),
                        style = MiuixTheme.textStyles.subtitle,
                        color = scheme.onBackground,
                    )
                    ThemeModeSegmented(
                        selected = state.settings.themeMode,
                        onSelect = vm::setThemeMode,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SectionLabel(stringResource(R.string.settings_sync))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SettingRow(
                        title = stringResource(R.string.settings_sync_frequency),
                        value = frequencyLabel(state.settings.syncFrequency),
                        onClick = { showFrequencySheet = true },
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_sync_wifi_only),
                        checked = state.settings.wifiOnly,
                        onChange = vm::setWifiOnly,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_sync_charging_only),
                        checked = state.settings.chargingOnly,
                        onChange = vm::setChargingOnly,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_notifications),
                        checked = state.settings.notificationsEnabled,
                        onChange = vm::setNotifications,
                    )
                    if (state.settings.notificationsEnabled && !Notifications.canNotify(app)) {
                        Text(
                            text = stringResource(R.string.settings_notif_permission_banner),
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onErrorContainer,
                        )
                        TextButton(
                            text = stringResource(R.string.settings_notif_grant),
                            onClick = {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_token),
                        style = MiuixTheme.textStyles.subtitle,
                        color = scheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.settings_token_hint),
                        style = MiuixTheme.textStyles.footnote2,
                        color = scheme.onBackgroundVariant,
                    )
                    TextField(
                        value = state.tokenInput,
                        onValueChange = vm::onTokenInput,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = vm::saveAndTestToken) {
                            Text(
                                text = stringResource(R.string.settings_token_test),
                                color = scheme.onPrimary,
                                style = MiuixTheme.textStyles.button,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
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
            }

            SectionLabel(stringResource(R.string.settings_self_update))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_current_version, state.localVersion),
                        style = MonoTokenStyle,
                        color = scheme.onBackground,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_self_update_check),
                        checked = state.settings.selfUpdateCheck,
                        onChange = vm::setSelfUpdateCheck,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_self_update_download),
                        checked = state.settings.selfUpdateAutoDownload,
                        onChange = vm::setSelfUpdateDownload,
                    )
                    Text(
                        text = stringResource(R.string.settings_silent_hint),
                        style = MiuixTheme.textStyles.footnote2,
                        color = scheme.onBackgroundVariant,
                    )
                    SelfUpdateSection(state = state, vm = vm)
                }
            }

            Spacer(Modifier.size(24.dp))
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
private fun SelfUpdateSection(state: SettingsViewModel.UiState, vm: SettingsViewModel) {
    val scheme = MiuixTheme.colorScheme
    when (val update = state.selfUpdate) {
        SelfUpdater.State.Idle, SelfUpdater.State.Checking -> {
            Button(onClick = vm::checkUpdate) {
                Text(
                    text = if (update == SelfUpdater.State.Checking) {
                        stringResource(R.string.loading)
                    } else {
                        stringResource(R.string.settings_check_update)
                    },
                    color = scheme.onPrimary,
                    style = MiuixTheme.textStyles.button,
                )
            }
        }
        SelfUpdater.State.UpToDate -> Text(
            text = stringResource(R.string.settings_up_to_date),
            style = MiuixTheme.textStyles.body2,
            color = scheme.primaryVariant,
        )
        is SelfUpdater.State.NewVersion -> {
            Text(
                text = if (update.incomparable) {
                    stringResource(R.string.settings_new_version_incomparable, update.tag)
                } else {
                    stringResource(R.string.settings_new_version, update.tag)
                },
                style = MiuixTheme.textStyles.body2,
                color = if (update.incomparable) scheme.onBackgroundVariant else scheme.primaryVariant,
            )
            update.notes?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                )
            }
            Button(
                onClick = vm::downloadUpdate,
                enabled = update.asset != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.settings_download_update),
                    color = scheme.onPrimary,
                    style = MiuixTheme.textStyles.button,
                )
            }
        }
        is SelfUpdater.State.Downloading -> {
            LinearProgressIndicator(progress = update.progress, modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.download_progress_percent, (update.progress * 100).toInt()),
                style = MiuixTheme.textStyles.footnote2,
                color = scheme.onBackgroundVariant,
            )
        }
        is SelfUpdater.State.Downloaded -> {
            Button(onClick = { vm.installUpdate(update.file) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_install_update),
                    color = scheme.onPrimary,
                    style = MiuixTheme.textStyles.button,
                )
            }
        }
        is SelfUpdater.State.Failed -> Text(
            text = stringResource(R.string.settings_update_failed),
            style = MiuixTheme.textStyles.footnote1,
            color = scheme.onErrorContainer,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            color = scheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = scheme.onBackgroundVariant,
        )
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun frequencyLabel(frequency: SyncFrequency): String = stringResource(
    when (frequency) {
        SyncFrequency.EVERY_6_HOURS -> R.string.frequency_6h
        SyncFrequency.EVERY_12_HOURS -> R.string.frequency_12h
        SyncFrequency.DAILY -> R.string.frequency_daily
    },
)

@Composable
private fun FrequencyRow(
    option: SyncFrequency,
    current: SyncFrequency,
    onSelect: (SyncFrequency) -> Unit,
    onDone: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                onSelect(option)
                onDone()
            }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (option == current) {
                Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = null,
                    tint = scheme.primaryVariant,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = frequencyLabel(option),
            style = MiuixTheme.textStyles.body1,
            color = if (option == current) scheme.primaryVariant else scheme.onBackground,
        )
    }
}
