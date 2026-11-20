package io.candy.tick.ui.settings

import androidx.compose.animation.togetherWith
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
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
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector

// ── 设置页弹窗与自身更新操作块（自 SettingsScreen 拆出）──

/** 缓存清理浮层：实时扫描 → 逐类勾选 → 清理（loading 阻断 + 释放量反馈） */
@Composable
internal fun CacheCleanupSheet(
    show: Boolean,
    state: SettingsViewModel.CacheState,
    onScan: () -> Unit,
    onClean: suspend (Boolean, Boolean) -> Long,
    onDismiss: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var cleanApk by remember { mutableStateOf(true) }
    var cleanCrash by remember { mutableStateOf(true) }

    androidx.compose.runtime.LaunchedEffect(show) {
        if (show) {
            cleanApk = true; cleanCrash = true
            onScan()
        }
    }

    top.yukonga.miuix.kmp.window.WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_cache_cleanup),
        onDismissRequest = { if (!state.cleaning) onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.scanning) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    io.candy.tick.ui.common.LoadingDots(dotSize = 5.dp)
                }
            } else {
                // 逐类列出
                CacheCleanupRow(
                    label = stringResource(R.string.settings_cache_apk),
                    sizeBytes = state.apkBytes,
                    checked = cleanApk,
                    onChecked = { cleanApk = it },
                )
                CacheCleanupRow(
                    label = stringResource(R.string.settings_cache_crash_log),
                    sizeBytes = state.crashLogBytes,
                    checked = cleanCrash,
                    onChecked = { cleanCrash = it },
                )

                Spacer(Modifier.height(8.dp))
                if (state.cleaning) {
                    // 清理中：轻量阻断（转圈 + 文案，防误触返回）
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            io.candy.tick.ui.common.LoadingDots()
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_cache_cleaning),
                                style = MiuixTheme.textStyles.footnote1,
                                color = scheme.onBackgroundVariant,
                            )
                        }
                    }
                } else {
                    val selectedBytes = (if (cleanApk) state.apkBytes else 0L) +
                        (if (cleanCrash) state.crashLogBytes else 0L)
                    val hasSelection = selectedBytes > 0
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    PrimaryButton(
                        // 带选中总量：行动前量级一目了然
                        text = stringResource(R.string.settings_cache_clean_action) +
                            if (selectedBytes > 0) {
                                " · " + formatBytes(selectedBytes)
                            } else {
                                ""
                            },
                        enabled = hasSelection,
                        onClick = {
                            scope.launch {
                                val freed = onClean(cleanApk, cleanCrash)
                                io.candy.tick.ui.common.Feedback.show(
                                    text = ctx.getString(R.string.settings_cache_freed, formatBytes(freed)),
                                )
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!hasSelection) {
                        Text(
                            text = stringResource(R.string.settings_cache_empty),
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onBackgroundVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CacheCleanupRow(
    label: String,
    sizeBytes: Long,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val enabled = sizeBytes > 0
    top.yukonga.miuix.kmp.basic.BasicComponent(
        modifier = Modifier.fillMaxWidth(),
        title = label,
        summary = if (enabled) formatBytes(sizeBytes) else stringResource(R.string.settings_cache_none),
        onClick = if (enabled) { { onChecked(!checked) } } else null,
        endActions = {
            // 大小为 0 也保留 Switch（灰显）：行结构稳定不闪隐
            top.yukonga.miuix.kmp.basic.Switch(
                checked = checked && enabled,
                onCheckedChange = onChecked,
                enabled = enabled,
            )
        },
        insideMargin = androidx.compose.foundation.layout.PaddingValues(
            start = 0.dp, end = 0.dp, top = 10.dp, bottom = 10.dp,
        ),
    )
}

/** 字节数格式化：1.2 MB / 345 KB / 0 B */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** 导入来源选择弹窗（文件 / 剪贴板）：主按钮 = 文件，次按钮 = 剪贴板 */
@Composable
internal fun ImportSourceDialog(
    show: Boolean,
    onFromFile: () -> Unit,
    onFromClipboard: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 底部浮层容器：与选项浮层同款滑入滑出动效
    top.yukonga.miuix.kmp.window.WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_import),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
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
    show: Boolean,
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
    // 弹窗打开即聚焦输入（唯一目的就是贴 Token）；show=false 不请求
    val tokenFocus = androidx.compose.ui.focus.FocusRequester()
    androidx.compose.runtime.LaunchedEffect(show) {
        if (show) {
            kotlinx.coroutines.delay(150)
            runCatching { tokenFocus.requestFocus() }
        }
    }
    // show 直通（勿用 if 包裹）：退场动画依赖组件持续组合；底部浮层与选项浮层动效统一
    top.yukonga.miuix.kmp.window.WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_token_row),
        onDismissRequest = { onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .imePadding()
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 说明（左对齐：sheet 内容语言；居中是对话框时代遗产）
            Text(
                text = stringResource(R.string.settings_token_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onBackgroundVariant,
            )
            // 输入框：单行密码式，眼睛切换在原生 trailingIcon 槽位（48dp 触控）
            TextField(
                value = state.tokenInput,
                onValueChange = onTokenInput,
                label = stringResource(R.string.token_input_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(tokenFocus),
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
                    io.candy.tick.ui.common.PressIconButton(
                        onClick = onToggleVisible,
                        contentDescription = stringResource(
                            if (tokenVisible) R.string.token_hide else R.string.token_show,
                        ),
                    ) {
                        Icon(
                            imageVector = if (tokenVisible) ImageVector.vectorResource(R.drawable.ic_hide) else ImageVector.vectorResource(R.drawable.ic_show),
                            contentDescription = null,
                            tint = scheme.onBackgroundVariant,
                        )
                    }
                },
            )
            // 验证结果 / 被拒提示（紧贴输入框；状态机见 VM 注释）
            when (state.tokenTest) {
                SettingsViewModel.TokenTest.TESTING -> androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    io.candy.tick.ui.common.LoadingDots()
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.loading),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onBackgroundVariant,
                    )
                }
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
            // 满宽主按钮（退出=放弃由 scrim/下拉/返回键承担，不再设取消按钮）
            PrimaryButton(
                text = stringResource(R.string.settings_token_test),
                onClick = onSaveAndTest,
                enabled = state.tokenInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            // 帮助链接（底部辅助位：不插主操作流；全局文字链组件）
            io.candy.tick.ui.common.LinkText(
                text = stringResource(R.string.token_help_link),
                onClick = onOpenHelp,
                showIcon = true,
            )
        }
    }
}

