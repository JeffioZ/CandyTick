package io.candy.tick.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.SelfUpdater
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.SecondaryButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ── 设置页弹窗与自身更新操作块（自 SettingsScreen 拆出）──

/** 导入来源选择弹窗（文件 / 剪贴板）：主按钮 = 文件，次按钮 = 剪贴板 */
@Composable
internal fun ImportSourceDialog(
    onFromFile: () -> Unit,
    onFromClipboard: () -> Unit,
    onDismiss: () -> Unit,
) {
    top.yukonga.miuix.kmp.window.WindowDialog(
        show = true,
        title = stringResource(R.string.settings_import),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrimaryButton(
                text = stringResource(R.string.import_from_file),
                onClick = {
                    onFromFile()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                text = stringResource(R.string.import_from_clipboard),
                onClick = {
                    onFromClipboard()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Token 弹窗：标题/说明走 Dialog 内置槽位；关闭 = 底部「取消」+ scrim 点击 + 返回键。
 * 输入框单行密码式，眼睛切换在原生 trailingIcon 槽位；帮助链接为下划线超链接样式。
 */
@Composable
internal fun TokenDialog(
    state: SettingsViewModel.UiState,
    tokenVisible: Boolean,
    onToggleVisible: () -> Unit,
    onTokenInput: (String) -> Unit,
    onSaveAndTest: () -> Unit,
    onOpenHelp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    top.yukonga.miuix.kmp.window.WindowDialog(
        show = true,
        title = stringResource(R.string.settings_token_row),
        summary = stringResource(R.string.settings_token_hint),
        onDismissRequest = { onDismiss() },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 输入框：单行密码式，眼睛切换在原生 trailingIcon 槽位（48dp 触控）
            TextField(
                value = state.tokenInput,
                onValueChange = onTokenInput,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (tokenVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                ),
                trailingIcon = {
                    top.yukonga.miuix.kmp.basic.IconButton(onClick = onToggleVisible) {
                        Icon(
                            imageVector = if (tokenVisible) MiuixIcons.Hide else MiuixIcons.Show,
                            contentDescription = stringResource(
                                if (tokenVisible) R.string.token_hide else R.string.token_show,
                            ),
                            tint = scheme.onBackgroundVariant,
                        )
                    }
                },
            )

            // 帮助链接（超链接样式：链接图标 + 下划线强调色文字，点按有涟漪）
            Row(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenHelp)
                    .padding(top = 10.dp, bottom = 10.dp, start = 2.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MiuixIcons.Link,
                    contentDescription = null,
                    tint = scheme.primaryVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.token_help_link),
                    style = MiuixTheme.textStyles.footnote1.copy(
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    ),
                    color = scheme.primaryVariant,
                )
            }

            // 验证结果 / 被拒提示（就近展示在输入框下方）
            when (state.tokenTest) {
                SettingsViewModel.TokenTest.TESTING -> Text(
                    text = stringResource(R.string.loading),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                )
                SettingsViewModel.TokenTest.VALID -> Text(
                    text = stringResource(R.string.token_verified),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.primaryVariant,
                )
                SettingsViewModel.TokenTest.INVALID -> Text(
                    text = stringResource(R.string.token_invalid_short),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onErrorContainer,
                )
                SettingsViewModel.TokenTest.NETWORK -> Text(
                    text = stringResource(R.string.token_network_error),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onErrorContainer,
                )
                null -> Unit
            }
            if (state.tokenRejected) {
                Text(
                    text = stringResource(R.string.settings_token_rejected),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onErrorContainer,
                )
            }

            // 底部按钮行：取消（显式关闭）+ 保存并验证（主操作）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { onDismiss() },
                )
                Spacer(Modifier.width(12.dp))
                PrimaryButton(
                    text = stringResource(R.string.settings_token_test),
                    onClick = onSaveAndTest,
                    enabled = state.tokenInput.isNotBlank(),
                )
            }
        }
    }
}

@Composable
internal fun SelfUpdateActions(state: SettingsViewModel.UiState, vm: SettingsViewModel) {
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
