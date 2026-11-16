package io.candy.tick.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.SelfUpdater
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 设置页「关于 · 版本」区的状态卡（检查 / 新版本 / 下载 / 安装） */
@Composable
internal fun SelfUpdateSection(state: SettingsViewModel.UiState, vm: SettingsViewModel) {
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
