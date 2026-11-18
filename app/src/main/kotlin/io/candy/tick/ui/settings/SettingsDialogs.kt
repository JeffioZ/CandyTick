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
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ── 设置页弹窗与自身更新操作块（自 SettingsScreen 拆出）──

/** 缓存清理浮层：实时扫描 → 逐类勾选 → 清理（loading 阻断 + 释放量反馈） */
@Composable
internal fun CacheCleanupSheet(
    show: Boolean,
    state: SettingsViewModel.CacheState,
    onScan: () -> Unit,
    onClean: suspend (Boolean, Boolean, Boolean) -> Long,
    onDismiss: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var cleanRepo by remember { mutableStateOf(true) }
    var cleanUpdate by remember { mutableStateOf(true) }
    var cleanCrash by remember { mutableStateOf(true) }

    androidx.compose.runtime.LaunchedEffect(show) {
        if (show) {
            cleanRepo = true; cleanUpdate = true; cleanCrash = true
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
                .padding(horizontal = 20.dp)
                .imePadding()
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    label = stringResource(R.string.settings_cache_repo_apk),
                    sizeBytes = state.repoApkBytes,
                    checked = cleanRepo,
                    onChecked = { cleanRepo = it },
                )
                CacheCleanupRow(
                    label = stringResource(R.string.settings_cache_update_apk),
                    sizeBytes = state.updateApkBytes,
                    checked = cleanUpdate,
                    onChecked = { cleanUpdate = it },
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
                    val hasSelection = (cleanRepo && state.repoApkBytes > 0) ||
                        (cleanUpdate && state.updateApkBytes > 0) ||
                        (cleanCrash && state.crashLogBytes > 0)
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    PrimaryButton(
                        text = stringResource(R.string.settings_cache_clean_action),
                        enabled = hasSelection,
                        onClick = {
                            scope.launch {
                                val freed = onClean(cleanRepo, cleanUpdate, cleanCrash)
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
                .padding(horizontal = 20.dp)
                .imePadding()
                .padding(top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            // 帮助链接（底部辅助位：不插主操作流）
            Row(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenHelp)
                    .padding(top = 2.dp, bottom = 6.dp, start = 2.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = io.candy.tick.ui.common.ExternalLinkIcon,
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
        }
    }
}

@Composable
internal fun SelfUpdateActions(state: SettingsViewModel.UiState, vm: SettingsViewModel) {
    val scheme = MiuixTheme.colorScheme
    // 按分支作 key 的柔切换（检查出新版 → 下载进度 → 安装）；进度条 300ms 补间
    val phase = when (state.selfUpdate) {
        is SelfUpdater.State.NewVersion -> "new"
        is SelfUpdater.State.Downloading -> "downloading"
        is SelfUpdater.State.Downloaded -> "downloaded"
        else -> "idle"
    }
    androidx.compose.animation.AnimatedContent(
        targetState = phase,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.slideInVertically(
                    animationSpec = androidx.compose.animation.core.tween(180),
                ) { it / 6 }) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))
        },
        label = "selfUpdatePhase",
    ) { p ->
        when (p) {
            "new" -> {
                val update = state.selfUpdate as? SelfUpdater.State.NewVersion
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    // 醒目版本 tag：一眼看到"升到什么"
                    update?.tag?.let {
                        Text(
                            text = it,
                            style = io.candy.tick.ui.theme.MonoTokenStyle.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            ),
                            color = scheme.primaryVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    update?.notes?.let {
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onBackgroundVariant,
                            maxLines = 4,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    PrimaryButton(
                        text = stringResource(R.string.settings_download_update),
                        onClick = vm::downloadUpdate,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = update?.asset != null,
                    )
                }
            }
            "downloading" -> Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            ) {
                val prog by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = (state.selfUpdate as? SelfUpdater.State.Downloading)?.progress ?: 0f,
                    animationSpec = androidx.compose.animation.core.tween(300),
                    label = "selfDl",
                )
                LinearProgressIndicator(progress = prog, modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.download_progress_percent, (prog * 100).toInt()),
                    style = MiuixTheme.textStyles.footnote2,
                    color = scheme.onBackgroundVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            "downloaded" -> {
                val update = state.selfUpdate as? SelfUpdater.State.Downloaded
                update?.let {
                    PrimaryButton(
                        text = stringResource(R.string.settings_install_update),
                        onClick = { vm.installUpdate(it.file) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                }
            }
            else -> Unit
        }
    }
}
