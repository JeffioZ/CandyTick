package io.candy.tick.ui.settings

import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.candy.tick.R
import io.candy.tick.ui.common.CandyTextField
import io.candy.tick.ui.common.InlineErrorRow
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.SecondaryButton
import io.candy.tick.ui.common.TextFieldActionButton
import io.candy.tick.ui.common.relTimeUntilString
import io.candy.tick.ui.common.rememberMotionEnabled
import io.candy.tick.ui.theme.LocalDarkTheme
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
        val scheme = MiuixTheme.colorScheme
        val dark = LocalDarkTheme.current
        val groupBg = if (dark) scheme.surfaceContainerHigh else scheme.background
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(groupBg),
            ) {
                CacheCleanupRow(
                    iconRes = R.drawable.ic_package,
                    label = stringResource(R.string.settings_cache_apk),
                    sizeBytes = state.apkBytes.takeUnless { state.scanning },
                    checked = includeApks,
                    cleaning = state.cleaning,
                    onChecked = { cleanApk = it },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 62.dp, end = 14.dp)
                        .height(0.5.dp)
                        .background(scheme.dividerLine),
                )
                CacheCleanupRow(
                    iconRes = R.drawable.ic_report,
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
    iconRes: Int,
    label: String,
    sizeBytes: Long?,
    checked: Boolean,
    cleaning: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val dark = LocalDarkTheme.current
    val motionEnabled = rememberMotionEnabled()
    val hasFiles = sizeBytes != null && sizeBytes > 0
    val enabled = hasFiles && !cleaning
    val badgeShape = RoundedCornerShape(10.dp)
    val neutralBadgeBg = if (dark) scheme.secondary else scheme.surface
    val badgeBg by androidx.compose.animation.animateColorAsState(
        targetValue = if (checked) scheme.primaryVariant.copy(alpha = 0.12f) else neutralBadgeBg,
        animationSpec = tween(if (motionEnabled) Motion.PHASE else 0),
        label = "cacheBadgeBg",
    )
    val badgeTint by androidx.compose.animation.animateColorAsState(
        targetValue = if (checked) scheme.primaryVariant else scheme.onSurfaceContainerVariant,
        animationSpec = tween(if (motionEnabled) Motion.PHASE else 0),
        label = "cacheBadgeTint",
    )
    top.yukonga.miuix.kmp.basic.BasicComponent(
        modifier = Modifier
            .fillMaxWidth()
            .switchSemantics(checked, enabled),
        title = label,
        summary = when (sizeBytes) {
            null -> "—"
            0L -> stringResource(R.string.settings_cache_none)
            else -> formatBytes(sizeBytes)
        },
        startAction = {
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(36.dp)
                    .clip(badgeShape)
                    .background(badgeBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(iconRes),
                    contentDescription = null,
                    tint = badgeTint,
                    modifier = Modifier.size(18.dp),
                )
            }
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
            start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp,
        ),
    )
}

/** 字节数格式化（util 单源，与下载进度/资产行共用） */
private fun formatBytes(bytes: Long): String = io.candy.tick.util.CountFormat.formatBytes(bytes)

/** 导出去向选择浮层（本地文件 / 剪贴板 / 分享）：三列极简卡片 + 底部格式说明 */
@Composable
internal fun ExportDestinationDialog(
    show: Boolean,
    onToFile: () -> Unit,
    onToClipboard: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    io.candy.tick.ui.common.CandySheet(
        show = show,
        title = stringResource(R.string.settings_export),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionSourceCard(
                    iconRes = R.drawable.ic_file,
                    title = stringResource(R.string.export_to_file),
                    onClick = onToFile,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                ActionSourceCard(
                    iconRes = R.drawable.ic_clipboard,
                    title = stringResource(R.string.export_to_clipboard),
                    onClick = onToClipboard,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                ActionSourceCard(
                    iconRes = R.drawable.ic_share,
                    title = stringResource(R.string.export_to_share),
                    onClick = onShare,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Text(
                text = stringResource(R.string.export_sheet_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        }
    }
}

/** 导入来源选择浮层（本地文件 / 剪贴板）：两列极简卡片 + 底部格式说明，与导出浮层同源 */
@Composable
internal fun ImportSourceDialog(
    show: Boolean,
    onFromFile: () -> Unit,
    onFromClipboard: () -> Unit,
    onDismiss: () -> Unit,
) {
    io.candy.tick.ui.common.CandySheet(
        show = show,
        title = stringResource(R.string.settings_import),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionSourceCard(
                    iconRes = R.drawable.ic_file,
                    title = stringResource(R.string.import_from_file),
                    onClick = onFromFile,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                ActionSourceCard(
                    iconRes = R.drawable.ic_clipboard,
                    title = stringResource(R.string.import_from_clipboard),
                    onClick = onFromClipboard,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Text(
                text = stringResource(R.string.import_sheet_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ActionSourceCard(
    iconRes: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val dark = LocalDarkTheme.current
    val cardShape = RoundedCornerShape(16.dp)
    val badgeShape = RoundedCornerShape(12.dp)
    val cardBg = if (dark) scheme.surfaceContainerHigh else scheme.background
    val (press, pressColor) = io.candy.tick.ui.common.rememberRowPressTint(surface = cardBg)
    Column(
        modifier = modifier
            .clip(cardShape)
            .background(pressColor)
            .clickable(
                interactionSource = press,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(badgeShape)
                .background(scheme.primaryVariant.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(iconRes),
                contentDescription = null,
                tint = scheme.primaryVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = title,
            style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium),
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
                .padding(top = 8.dp),
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
    val dark = LocalDarkTheme.current
    val motionEnabled = rememberMotionEnabled()
    val shape = RoundedCornerShape(14.dp)
    val cardBg = if (dark) scheme.surfaceContainerHigh else scheme.background
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
            .background(cardBg)
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val dotColor by androidx.compose.animation.animateColorAsState(
                targetValue = when {
                    tokenRejected -> scheme.onErrorContainer
                    hasToken -> scheme.primaryVariant
                    else -> scheme.onBackgroundVariant.copy(alpha = 0.45f)
                },
                animationSpec = tween(if (motionEnabled) Motion.PHASE else 0),
                label = "tokenStatusDot",
            )
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(dotColor),
                )
                Text(
                    text = identityLabel,
                    style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium),
                    color = identityColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
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
                // 外层 48dp 触控热区包住 32dp 视觉圆钮，兼顾单手易点与卡片紧凑度
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            enabled = !loading,
                            interactionSource = refreshPress,
                            indication = null,
                            role = Role.Button,
                            onClick = onRefresh,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(refreshBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.action_refresh),
                            tint = scheme.primaryVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .graphicsLayer { rotationZ = spinAngle },
                        )
                    }
                }
            }
        }

        // 仅在「无数据 ↔ 有数据」之间做相变，点刷新时不重建进度条，消除闪烁；右补 8dp 与外层 6dp 凑齐左右 14dp 对称
        AnimatedContent(
            targetState = rateLimit != null,
            modifier = Modifier.padding(end = 8.dp),
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

/** 桌面图标三档可视化选择弹层：3 枚通透大图标 + 外呼吸选中环（跟随系统垂直正中对称双拼） */
@Composable
internal fun AppIconPickerSheet(
    show: Boolean,
    selected: io.candy.tick.data.model.AppIconMode,
    onSelect: (io.candy.tick.data.model.AppIconMode) -> Unit,
    onDismiss: () -> Unit,
) {
    io.candy.tick.ui.common.CandySheet(
        show = show,
        title = stringResource(R.string.settings_app_icon),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max)
                    .selectableGroup()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VisualPickerOption(
                    label = stringResource(R.string.app_icon_system),
                    selected = selected == io.candy.tick.data.model.AppIconMode.SYSTEM,
                    onClick = { onSelect(io.candy.tick.data.model.AppIconMode.SYSTEM) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { AppIconThumbnail(io.candy.tick.data.model.AppIconMode.SYSTEM) }
                VisualPickerOption(
                    label = stringResource(R.string.app_icon_light),
                    selected = selected == io.candy.tick.data.model.AppIconMode.LIGHT,
                    onClick = { onSelect(io.candy.tick.data.model.AppIconMode.LIGHT) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { AppIconThumbnail(io.candy.tick.data.model.AppIconMode.LIGHT) }
                VisualPickerOption(
                    label = stringResource(R.string.app_icon_dark),
                    selected = selected == io.candy.tick.data.model.AppIconMode.DARK,
                    onClick = { onSelect(io.candy.tick.data.model.AppIconMode.DARK) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { AppIconThumbnail(io.candy.tick.data.model.AppIconMode.DARK) }
            }
        }
    }
}

/** 深浅模式三档可视化选择弹层：与「桌面图标」同款三枚通透预览 + 外呼吸选中环 */
@Composable
internal fun ThemeModePickerSheet(
    show: Boolean,
    selected: io.candy.tick.data.model.ThemeMode,
    onSelect: (io.candy.tick.data.model.ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    io.candy.tick.ui.common.CandySheet(
        show = show,
        title = stringResource(R.string.settings_theme_mode),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max)
                    .selectableGroup()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VisualPickerOption(
                    label = stringResource(R.string.theme_mode_system),
                    selected = selected == io.candy.tick.data.model.ThemeMode.SYSTEM,
                    onClick = { onSelect(io.candy.tick.data.model.ThemeMode.SYSTEM) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { ThemeModeThumbnail(io.candy.tick.data.model.ThemeMode.SYSTEM) }
                VisualPickerOption(
                    label = stringResource(R.string.theme_mode_light),
                    selected = selected == io.candy.tick.data.model.ThemeMode.LIGHT,
                    onClick = { onSelect(io.candy.tick.data.model.ThemeMode.LIGHT) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { ThemeModeThumbnail(io.candy.tick.data.model.ThemeMode.LIGHT) }
                VisualPickerOption(
                    label = stringResource(R.string.theme_mode_dark),
                    selected = selected == io.candy.tick.data.model.ThemeMode.DARK,
                    onClick = { onSelect(io.candy.tick.data.model.ThemeMode.DARK) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { ThemeModeThumbnail(io.candy.tick.data.model.ThemeMode.DARK) }
            }
        }
    }
}

/** 同步频率四档可视化选择弹层：单行 4 列横排（与深浅模式 / 桌面图标 / 应用语言同款外呼吸选中环） */
@Composable
internal fun SyncFrequencyPickerSheet(
    show: Boolean,
    selected: io.candy.tick.data.model.SyncFrequency,
    onSelect: (io.candy.tick.data.model.SyncFrequency) -> Unit,
    onDismiss: () -> Unit,
) {
    io.candy.tick.ui.common.CandySheet(
        show = show,
        title = stringResource(R.string.settings_sync_frequency),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max)
                    .selectableGroup()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    io.candy.tick.data.model.SyncFrequency.ON_OPEN,
                    io.candy.tick.data.model.SyncFrequency.EVERY_6_HOURS,
                    io.candy.tick.data.model.SyncFrequency.EVERY_12_HOURS,
                    io.candy.tick.data.model.SyncFrequency.DAILY,
                ).forEach { freq ->
                    val isSelected = selected == freq
                    VisualPickerOption(
                        label = frequencyLabel(freq),
                        selected = isSelected,
                        onClick = { onSelect(freq) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        SyncFrequencyThumbnail(frequency = freq, selected = isSelected)
                    }
                }
            }
        }
    }
}

/** 同步频率 52dp 缩略徽章：刷新图标 / 6h / 12h / 24h，与语言缩略图同材质并锁定 fontScale=1f */
@Composable
private fun SyncFrequencyThumbnail(
    frequency: io.candy.tick.data.model.SyncFrequency,
    selected: Boolean,
) {
    val scheme = MiuixTheme.colorScheme
    val dark = LocalDarkTheme.current
    val thumbShape = RoundedCornerShape(14.dp)
    val baseSurface = if (dark) scheme.surfaceContainerHigh else scheme.background
    val tileBg = if (selected) {
        scheme.primaryVariant.copy(alpha = 0.08f).compositeOver(baseSurface)
    } else {
        baseSurface
    }
    val fgColor = if (selected) scheme.primaryVariant else scheme.onSurface
    val baseDensity = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 1f)) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(thumbShape)
                .background(tileBg)
                .border(0.5.dp, scheme.outline.copy(alpha = 0.45f), thumbShape)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            if (frequency == io.candy.tick.data.model.SyncFrequency.ON_OPEN) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_refresh),
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                val code = when (frequency) {
                    io.candy.tick.data.model.SyncFrequency.EVERY_6_HOURS -> "6h"
                    io.candy.tick.data.model.SyncFrequency.EVERY_12_HOURS -> "12h"
                    else -> "24h"
                }
                Text(
                    text = code,
                    fontSize = 16.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fgColor,
                )
            }
        }
    }
}

/** 语言三档可视化选择弹层：与「桌面图标 / 深浅模式」同款三枚字标缩略图 + 外呼吸选中环 */
@Composable
internal fun LanguagePickerSheet(
    show: Boolean,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    io.candy.tick.ui.common.CandySheet(
        show = show,
        title = stringResource(R.string.settings_language),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max)
                    .selectableGroup()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VisualPickerOption(
                    label = stringResource(R.string.lang_system),
                    selected = selected.isEmpty(),
                    onClick = { onSelect("") },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { LanguageThumbnail(language = "", selected = selected.isEmpty()) }
                VisualPickerOption(
                    label = stringResource(R.string.lang_chinese),
                    selected = selected == "zh",
                    onClick = { onSelect("zh") },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { LanguageThumbnail(language = "zh", selected = selected == "zh") }
                VisualPickerOption(
                    label = stringResource(R.string.lang_english),
                    selected = selected == "en",
                    onClick = { onSelect("en") },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { LanguageThumbnail(language = "en", selected = selected == "en") }
            }
        }
    }
}

/** 语言 56dp 缩略预览（BCP 47 标准代码）：跟随系统用地球图标，语种统一 14.5sp 居中（zh-CN / en） */
@Composable
private fun LanguageThumbnail(language: String, selected: Boolean) {
    val scheme = MiuixTheme.colorScheme
    val dark = LocalDarkTheme.current
    val thumbShape = RoundedCornerShape(14.dp)
    val fgColor = if (selected) scheme.primaryVariant else scheme.onSurface
    val baseSurface = if (dark) scheme.surfaceContainerHigh else scheme.background
    val tileBg = if (selected) {
        scheme.primaryVariant.copy(alpha = 0.08f).compositeOver(baseSurface)
    } else {
        baseSurface
    }
    val baseDensity = LocalDensity.current
    // 缩略图内为图形化徽章（下方已有随系统缩放的可读标题），锁定 fontScale=1f 防大字号下挤碰裁切
    CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 1f)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(thumbShape)
                .background(tileBg)
                .border(0.5.dp, scheme.outline.copy(alpha = 0.45f), thumbShape)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            if (language.isEmpty()) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_globe),
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                val code = when (language) {
                    "zh" -> "zh-CN"
                    "en" -> "en"
                    else -> language
                }
                Text(
                    text = code,
                    fontSize = 14.5.sp,
                    lineHeight = 16.sp,
                    letterSpacing = (-0.2).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fgColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun VisualPickerOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: @Composable () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val motionEnabled = rememberMotionEnabled()
    val hitShape = RoundedCornerShape(16.dp)
    val ringShape = RoundedCornerShape(19.dp)
    val ringColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) scheme.primaryVariant else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = tween(if (motionEnabled) Motion.PHASE else 0),
        label = "visualPickerRing",
    )
    val labelColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) scheme.primaryVariant else scheme.onSurfaceContainer,
        animationSpec = tween(if (motionEnabled) Motion.PHASE else 0),
        label = "visualPickerLabel",
    )
    val (press, pressColor) = io.candy.tick.ui.common.rememberRowPressTint(surface = scheme.surfaceContainer)
    Column(
        modifier = modifier
            .clip(hitShape)
            .background(pressColor)
            .selectable(
                selected = selected,
                interactionSource = press,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 外圈 2dp 主题绿选中环 + 3dp 呼吸缝（与桌面图标选择器共用）
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .border(2.dp, ringColor, ringShape)
                .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            thumbnail()
        }
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1.copy(
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                lineBreak = LineBreak.Heading,
            ),
            color = labelColor,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 迷你深浅界面 56dp 缩略预览（100% 取自 Color.kt 真实双色板，柔和薄荷双卡 + 跟随系统垂直正中双拼） */
@Composable
private fun ThemeModeThumbnail(mode: io.candy.tick.data.model.ThemeMode) {
    val scheme = MiuixTheme.colorScheme
    val thumbShape = RoundedCornerShape(14.dp)

    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .size(56.dp)
            .clip(thumbShape)
            .border(0.5.dp, scheme.outline.copy(alpha = 0.45f), thumbShape),
    ) {
        fun drawMiniUi(dark: Boolean) {
            val bg = if (dark) io.candy.tick.ui.theme.DarkBackground else io.candy.tick.ui.theme.LightBackground
            val card = if (dark) io.candy.tick.ui.theme.DarkSurface else io.candy.tick.ui.theme.LightSurface
            val accent = if (dark) io.candy.tick.ui.theme.DarkPrimary else io.candy.tick.ui.theme.LightPrimary
            val pillBg = if (dark) io.candy.tick.ui.theme.DarkPrimaryContainer else io.candy.tick.ui.theme.LightPrimaryContainer
            val line1 = if (dark) io.candy.tick.ui.theme.DarkTextPrimary.copy(alpha = 0.58f)
            else io.candy.tick.ui.theme.LightTextPrimary.copy(alpha = 0.48f)
            val line2 = if (dark) io.candy.tick.ui.theme.DarkTextSecondary.copy(alpha = 0.36f)
            else io.candy.tick.ui.theme.LightTextSecondary.copy(alpha = 0.30f)

            drawRect(color = bg)
            val w = size.width
            val h = size.height
            val pad = w * 0.125f
            val cardW = w - pad * 2
            val r = androidx.compose.ui.geometry.CornerRadius(w * 0.10f, w * 0.10f)

            // 上方仓库卡片：淡薄荷圆角方徽章 + 小绿点 + 两行骨架
            val c1Top = h * 0.143f
            val c1H = h * 0.34f
            drawRoundRect(
                color = card,
                topLeft = androidx.compose.ui.geometry.Offset(pad, c1Top),
                size = androidx.compose.ui.geometry.Size(cardW, c1H),
                cornerRadius = r,
            )
            val badgeSize = w * 0.17f
            val badgeX = pad + w * 0.072f
            val badgeY = c1Top + (c1H - badgeSize) / 2f
            drawRoundRect(
                color = pillBg,
                topLeft = androidx.compose.ui.geometry.Offset(badgeX, badgeY),
                size = androidx.compose.ui.geometry.Size(badgeSize, badgeSize),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.054f, w * 0.054f),
            )
            drawCircle(
                color = accent,
                radius = w * 0.04f,
                center = androidx.compose.ui.geometry.Offset(badgeX + badgeSize / 2f, badgeY + badgeSize / 2f),
            )
            val textX = badgeX + badgeSize + w * 0.054f
            drawRoundRect(
                color = line1,
                topLeft = androidx.compose.ui.geometry.Offset(textX, c1Top + c1H * 0.30f),
                size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.05f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )
            drawRoundRect(
                color = line2,
                topLeft = androidx.compose.ui.geometry.Offset(textX, c1Top + c1H * 0.56f),
                size = androidx.compose.ui.geometry.Size(w * 0.215f, h * 0.043f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )

            // 下方次卡片：PrimaryContainer 柔和薄荷胶囊 + 小绿点 + 细强调条
            val c2Top = c1Top + c1H + h * 0.062f
            val c2H = h * 0.312f
            drawRoundRect(
                color = card,
                topLeft = androidx.compose.ui.geometry.Offset(pad, c2Top),
                size = androidx.compose.ui.geometry.Size(cardW, c2H),
                cornerRadius = r,
            )
            val pillH = h * 0.134f
            val pillX = pad + w * 0.08f
            val pillW = cardW - w * 0.16f
            val pillY = c2Top + (c2H - pillH) / 2f
            drawRoundRect(
                color = pillBg,
                topLeft = androidx.compose.ui.geometry.Offset(pillX, pillY),
                size = androidx.compose.ui.geometry.Size(pillW, pillH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(pillH / 2f, pillH / 2f),
            )
            drawCircle(
                color = accent,
                radius = w * 0.036f,
                center = androidx.compose.ui.geometry.Offset(pillX + w * 0.09f, pillY + pillH / 2f),
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.85f),
                topLeft = androidx.compose.ui.geometry.Offset(pillX + w * 0.17f, pillY + (pillH - h * 0.045f) / 2f),
                size = androidx.compose.ui.geometry.Size(w * 0.30f, h * 0.045f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )
        }

        when (mode) {
            io.candy.tick.data.model.ThemeMode.LIGHT -> drawMiniUi(dark = false)
            io.candy.tick.data.model.ThemeMode.DARK -> drawMiniUi(dark = true)
            io.candy.tick.data.model.ThemeMode.SYSTEM -> {
                drawMiniUi(dark = false)
                val darkHalf = androidx.compose.ui.graphics.Path().apply {
                    addRect(
                        androidx.compose.ui.geometry.Rect(
                            left = size.width / 2f,
                            top = 0f,
                            right = size.width,
                            bottom = size.height,
                        ),
                    )
                }
                clipPath(darkHalf) {
                    drawMiniUi(dark = true)
                }
            }
        }
    }
}

/** 按桌面自适应图标真实几何比例绘制 56dp 缩略图（同步 B2 · 1.10x，跟随系统垂直正中对称双拼） */
@Composable
private fun AppIconThumbnail(mode: io.candy.tick.data.model.AppIconMode) {
    val scheme = MiuixTheme.colorScheme
    val iconShape = RoundedCornerShape(14.dp)

    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .size(56.dp)
            .clip(iconShape)
            .border(0.5.dp, scheme.outline.copy(alpha = 0.45f), iconShape),
    ) {
        fun drawEcho(dark: Boolean) {
            val bgBrush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = if (dark) {
                    listOf(androidx.compose.ui.graphics.Color(0xFF1F4B38), androidx.compose.ui.graphics.Color(0xFF0F251B))
                } else {
                    listOf(androidx.compose.ui.graphics.Color(0xFFFAF7EE), androidx.compose.ui.graphics.Color(0xFFECE6D5))
                },
                center = center,
                radius = size.minDimension * 0.72f,
            )
            drawRect(brush = bgBrush)
            val unit = size.minDimension / 72f
            val glowBrush = androidx.compose.ui.graphics.Brush.radialGradient(
                colorStops = if (dark) {
                    arrayOf(
                        0f to androidx.compose.ui.graphics.Color(0x525BD6A4),
                        0.56f to androidx.compose.ui.graphics.Color(0x175BD6A4),
                        1f to androidx.compose.ui.graphics.Color.Transparent,
                    )
                } else {
                    arrayOf(
                        0f to androidx.compose.ui.graphics.Color(0x3318936C),
                        0.56f to androidx.compose.ui.graphics.Color(0x0D18936C),
                        1f to androidx.compose.ui.graphics.Color.Transparent,
                    )
                },
                center = center,
                radius = 36.3f * unit,
            )
            drawCircle(brush = glowBrush, radius = 36.3f * unit)
            drawCircle(
                color = if (dark) androidx.compose.ui.graphics.Color(0x668BDEBB) else androidx.compose.ui.graphics.Color(0x8512825C),
                radius = 24.2f * unit,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.95f * unit),
            )
            val innerBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = if (dark) {
                    listOf(androidx.compose.ui.graphics.Color(0xFF6EE0B4), androidx.compose.ui.graphics.Color(0xFF49C495))
                } else {
                    listOf(androidx.compose.ui.graphics.Color(0xFF179269), androidx.compose.ui.graphics.Color(0xFF0F7654))
                },
            )
            drawCircle(
                brush = innerBrush,
                radius = 17.6f * unit,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 7.15f * unit),
            )
            val coreBrush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = if (dark) {
                    listOf(androidx.compose.ui.graphics.Color(0xFF64D8AC), androidx.compose.ui.graphics.Color(0xFF4CC798))
                } else {
                    listOf(androidx.compose.ui.graphics.Color(0xFF168E66), androidx.compose.ui.graphics.Color(0xFF0F7856))
                },
                center = center,
                radius = 10.2f * unit,
            )
            drawCircle(brush = coreBrush, radius = 9.35f * unit)
        }

        when (mode) {
            io.candy.tick.data.model.AppIconMode.LIGHT -> drawEcho(dark = false)
            io.candy.tick.data.model.AppIconMode.DARK -> drawEcho(dark = true)
            io.candy.tick.data.model.AppIconMode.SYSTEM -> {
                drawEcho(dark = false)
                val darkHalf = androidx.compose.ui.graphics.Path().apply {
                    addRect(
                        androidx.compose.ui.geometry.Rect(
                            left = size.width / 2f,
                            top = 0f,
                            right = size.width,
                            bottom = size.height,
                        ),
                    )
                }
                clipPath(darkHalf) {
                    drawEcho(dark = true)
                }
            }
        }
    }
}

