package io.candy.tick.ui.settings

import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.CandyTextField
import io.candy.tick.ui.common.InlineErrorRow
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.SecondaryButton
import io.candy.tick.ui.common.SheetChoiceRowShape
import io.candy.tick.ui.common.TextFieldActionButton
import io.candy.tick.ui.common.relTimeUntilString
import io.candy.tick.ui.common.rememberMotionEnabled
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ── 设置页弹窗与自身更新操作块（自 SettingsScreen 拆出）──

/** 缓存清理浮层：实时扫描 → 逐类勾选 → 清理（loading 阻断 + 释放量反馈） */
@Composable
internal fun CacheCleanupSheet(
    show: Boolean,
    state: SettingsViewModel.CacheState,
    onScan: () -> Unit,
    onClean: suspend (Boolean, Boolean) -> Long,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val visible by rememberUpdatedState(show)
    var cleanApk by remember { mutableStateOf(true) }
    var cleanCrash by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(show) {
        if (show) {
            cleanApk = true
            cleanCrash = false
            onScan()
        }
    }

    io.candy.tick.ui.common.CandySheet(
        show = show,
        title = stringResource(R.string.settings_cache_cleanup),
        onDismissRequest = { if (!state.cleaning) onDismiss() },
        onDismissFinished = { if (!visible) onDismissFinished() },
    ) {
        val includeApks = cleanApk && !state.scanning && state.apkBytes > 0
        val includeCrashLog = cleanCrash && !state.scanning && state.crashLogBytes > 0
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CacheCleanupRow(
                    label = stringResource(R.string.settings_cache_apk),
                    sizeBytes = state.apkBytes.takeUnless { state.scanning },
                    checked = includeApks,
                    cleaning = state.cleaning,
                    onChecked = { cleanApk = it },
                )
                CacheCleanupRow(
                    label = stringResource(R.string.settings_cache_crash_log),
                    sizeBytes = state.crashLogBytes.takeUnless { state.scanning },
                    checked = includeCrashLog,
                    cleaning = state.cleaning,
                    onChecked = { cleanCrash = it },
                )
            }
            val selectedBytes = (if (includeApks) state.apkBytes else 0L) +
                (if (includeCrashLog) state.crashLogBytes else 0L)
            val action = stringResource(R.string.settings_cache_clean_action)
            val label = when {
                state.scanning -> action
                selectedBytes > 0 -> "$action · ${formatBytes(selectedBytes)}"
                state.apkBytes > 0 || state.crashLogBytes > 0 -> stringResource(R.string.settings_cache_select_hint)
                else -> stringResource(R.string.settings_cache_empty)
            }
            val freedTemplate = stringResource(R.string.settings_cache_freed)
            PrimaryButton(
                text = label,
                busyText = when {
                    state.scanning -> stringResource(R.string.settings_cache_scanning)
                    state.cleaning -> stringResource(R.string.settings_cache_cleaning)
                    else -> null
                },
                enabled = selectedBytes > 0,
                onClick = {
                    scope.launch {
                        val freed = onClean(includeApks, includeCrashLog)
                        io.candy.tick.ui.common.Feedback.show(
                            text = String.format(java.util.Locale.US, freedTemplate, formatBytes(freed)),
                        )
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CacheCleanupRow(
    label: String,
    sizeBytes: Long?,
    checked: Boolean,
    cleaning: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val hasFiles = sizeBytes != null && sizeBytes > 0
    val enabled = hasFiles && !cleaning
    // 色块比文字左右各宽 8dp（与选项弹层的行同款）：按下染色不贴文字和开关
    top.yukonga.miuix.kmp.basic.BasicComponent(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(SheetChoiceRowShape).switchSemantics(checked, enabled),
        title = label,
        summary = when (sizeBytes) {
            null -> "—"
            0L -> stringResource(R.string.settings_cache_none)
            else -> formatBytes(sizeBytes)
        },
        onClick = if (enabled) { { onChecked(!checked) } } else null,
        endActions = {
            // 大小为 0 也保留 Switch（灰显）：行结构稳定不闪隐
            top.yukonga.miuix.kmp.basic.Switch(
                modifier = Modifier.clearAndSetSemantics { },
                checked = checked,
                onCheckedChange = onChecked,
                enabled = enabled,
            )
        },
        insideMargin = androidx.compose.foundation.layout.PaddingValues(
            start = 8.dp, end = 8.dp, top = 10.dp, bottom = 10.dp,
        ),
    )
}

/** 字节数格式化（util 单源，与下载进度/资产行共用） */
private fun formatBytes(bytes: Long): String = io.candy.tick.util.CountFormat.formatBytes(bytes)

/** 导入来源选择弹窗（文件 / 剪贴板）：主按钮 = 文件，次按钮 = 剪贴板 */
@Composable
internal fun ImportSourceDialog(
    show: Boolean,
    onFromFile: () -> Unit,
    onFromClipboard: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 底部浮层容器：与选项浮层同款滑入滑出动效
    io.candy.tick.ui.common.CandySheet(
        show = show,
        title = stringResource(R.string.settings_import),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
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
    onRefreshRateLimit: () -> Unit,
    onOpenHelp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    // 弹窗打开即聚焦输入（唯一目的就是贴 Token）并免费刷新一次当前额度；show=false 不请求
    val tokenFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(show) {
        if (show) {
            onRefreshRateLimit()
            kotlinx.coroutines.delay(150)
            runCatching { tokenFocus.requestFocus() }
        }
    }
    // show 直通（勿用 if 包裹）：退场动画依赖组件持续组合；底部浮层与选项浮层动效统一
    io.candy.tick.ui.common.CandySheet(
        show = show,
        title = stringResource(R.string.settings_token_row),
        onDismissRequest = { onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 账号与 API 额度卡：未填展示匿名额度，已填展示 @用户名 + 认证额度，免费接口实时刷新
            TokenRateLimitCard(
                username = state.settings.githubUsername,
                hasToken = !state.settings.githubToken.isNullOrBlank(),
                tokenRejected = state.tokenRejected,
                rateLimit = state.rateLimit,
                loading = state.rateLimitLoading,
                onRefresh = onRefreshRateLimit,
            )
            // 说明（左对齐：sheet 内容语言；居中是对话框时代遗产）
            Text(
                text = stringResource(R.string.settings_token_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onBackgroundVariant,
            )
            // 输入框：单行密码式，眼睛切换在原生 trailingIcon 槽位（48dp 触控）
            CandyTextField(
                value = state.tokenInput,
                // Token 不设前端长度上限：官方无长度文档，正确性由 Save & verify 的
                // 服务端验证兜底——前端瞎猜的上限反而可能废掉未来的有效格式
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
                    TextFieldActionButton(
                        onClick = onToggleVisible,
                        icon = if (tokenVisible) R.drawable.ic_hide else R.drawable.ic_show,
                        actionLabel = if (tokenVisible) R.string.token_hide else R.string.token_show,
                    )
                },
            )
            // 验证结果 / 被拒提示（平滑展开与淡入，消除弹窗高度与文案突跳）
            val motionEnabled = rememberMotionEnabled()
            val tokenError = when {
                state.tokenTest == SettingsViewModel.TokenTest.INVALID -> R.string.token_invalid_short
                state.tokenTest == SettingsViewModel.TokenTest.NETWORK -> R.string.token_network_error
                state.tokenTest == SettingsViewModel.TokenTest.SAVE_FAILED -> R.string.token_save_failed
                state.tokenRejected -> R.string.settings_token_rejected
                else -> null
            }
            val tokenStatus: Pair<Boolean, Int>? = when {
                state.tokenTest == SettingsViewModel.TokenTest.VALID -> true to R.string.token_verified
                tokenError != null -> false to tokenError
                else -> null
            }
            // 满宽主按钮（退出=放弃由 scrim/下拉/返回键承担，不再设取消按钮）
            val removingToken = state.tokenInput.isBlank() && !state.settings.githubToken.isNullOrBlank()
            Column {
                androidx.compose.animation.AnimatedVisibility(
                    visible = tokenStatus != null,
                    enter = androidx.compose.animation.expandVertically(
                        animationSpec = tween(if (motionEnabled) Motion.PANEL else 0, easing = Motion.Decelerate),
                    ) + fadeIn(tween(if (motionEnabled) Motion.PANEL else 0)),
                    exit = androidx.compose.animation.shrinkVertically(
                        animationSpec = tween(if (motionEnabled) Motion.PHASE else 0),
                    ) + fadeOut(tween(if (motionEnabled) Motion.PHASE else 0)),
                ) {
                    var lastStatus by remember { mutableStateOf(tokenStatus) }
                    if (tokenStatus != null) lastStatus = tokenStatus
                    lastStatus?.let { (isValid, resId) ->
                        Column(modifier = Modifier.padding(bottom = 10.dp)) {
                            if (isValid) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_check_circle),
                                        contentDescription = null,
                                        tint = scheme.primaryVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = stringResource(resId),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = scheme.primaryVariant,
                                    )
                                }
                            } else {
                                InlineErrorRow(message = stringResource(resId), onRetry = null)
                            }
                        }
                    }
                }
                PrimaryButton(
                    text = stringResource(if (removingToken) R.string.remove_token else R.string.settings_token_test),
                    onClick = onSaveAndTest,
                    enabled = state.tokenInput.isNotBlank() || removingToken,
                    busyText = if (state.tokenTest == SettingsViewModel.TokenTest.TESTING) {
                        stringResource(R.string.settings_token_verifying)
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                io.candy.tick.ui.common.LinkText(
                    text = stringResource(R.string.token_help_link),
                    onClick = onOpenHelp,
                    showIcon = true,
                    // 图标与弹层内的说明、输入框、按钮左缘对齐
                    alignInk = true,
                )
            }
        }
    }
}

@Composable
private fun TokenRateLimitCard(
    username: String?,
    hasToken: Boolean,
    tokenRejected: Boolean,
    rateLimit: io.candy.tick.data.GitHubApi.RateLimitInfo?,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val motionEnabled = rememberMotionEnabled()
    val shape = RoundedCornerShape(12.dp)
    val identityLabel = when {
        tokenRejected -> stringResource(R.string.token_quota_rejected_mode)
        hasToken && !username.isNullOrBlank() -> "@$username"
        hasToken -> stringResource(R.string.token_quota_auth_mode)
        else -> stringResource(R.string.token_quota_anon_mode)
    }
    val identityColor = if (tokenRejected) scheme.onErrorContainer else scheme.onSurfaceContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = identityLabel,
                style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium),
                color = identityColor,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rateLimit?.resetAt?.let { reset ->
                    if (reset.isAfter(java.time.Instant.now())) {
                        Text(
                            text = stringResource(R.string.token_quota_reset, relTimeUntilString(reset)),
                            style = MiuixTheme.textStyles.footnote2.copy(fontFeatureSettings = "tnum"),
                            color = scheme.onBackgroundVariant,
                        )
                    }
                }
                val (refreshPress, refreshBg) = io.candy.tick.ui.common.rememberRowPressTint(
                    surface = scheme.surfaceContainerHigh,
                )
                val spinAngle = if (loading && motionEnabled) {
                    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "quotaRefreshSpin")
                    infinite.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = tween(
                                durationMillis = 850,
                                easing = androidx.compose.animation.core.LinearEasing,
                            ),
                        ),
                        label = "quotaRefreshAngle",
                    ).value
                } else 0f
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(refreshBg)
                        .clickable(
                            enabled = !loading,
                            interactionSource = refreshPress,
                            indication = null,
                            role = Role.Button,
                            onClick = onRefresh,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.retry),
                        tint = scheme.primaryVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = spinAngle },
                    )
                }
            }
        }

        // 仅在「无数据 ↔ 有数据」之间做相变，点刷新时不重建进度条，消除闪烁
        AnimatedContent(
            targetState = rateLimit != null,
            transitionSpec = {
                val spec = tween<Float>(if (motionEnabled) Motion.PHASE else 0)
                (fadeIn(spec) togetherWith fadeOut(spec)).using(SizeTransform(clip = false))
            },
            label = "tokenRateLimitBody",
        ) { hasData ->
            val info = rateLimit
            if (hasData && info != null) {
                val targetFraction = if (info.limit > 0) {
                    (info.remaining.toFloat() / info.limit.toFloat()).coerceIn(0f, 1f)
                } else 0f
                val animatedFraction by animateFloatAsState(
                    targetValue = targetFraction,
                    animationSpec = tween(if (motionEnabled) Motion.PROGRESS else 0),
                    label = "rateLimitBar",
                )
                val barColor by androidx.compose.animation.animateColorAsState(
                    targetValue = if (tokenRejected || targetFraction < 0.2f) {
                        scheme.onErrorContainer
                    } else {
                        scheme.primaryVariant
                    },
                    animationSpec = tween(if (motionEnabled) Motion.PHASE else 0),
                    label = "rateLimitBarColor",
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 纯明度对比轨道（不依赖色相，色弱友好）+ 6dp 清晰高度
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(scheme.onSurfaceContainer.copy(alpha = 0.16f)),
                    ) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedFraction)
                                .fillMaxHeight()
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(barColor),
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.token_quota_detail,
                            info.used,
                            info.remaining,
                            info.limit,
                        ),
                        style = MiuixTheme.textStyles.footnote2.copy(fontFeatureSettings = "tnum"),
                        color = scheme.onBackgroundVariant,
                    )
                }
            } else {
                Text(
                    text = stringResource(
                        if (loading) R.string.token_quota_loading else R.string.token_quota_unavailable,
                    ),
                    style = MiuixTheme.textStyles.footnote2,
                    color = scheme.onBackgroundVariant,
                )
            }
        }
    }
}

