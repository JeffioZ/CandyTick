package io.candy.tick.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.theme.DarkFieldActionTint
import io.candy.tick.ui.theme.LightFieldActionTint
import io.candy.tick.ui.theme.LocalDarkTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 统一按钮封装：Miuix Button 默认是次要样式（secondaryVariant 容器），
 * 主按钮必须显式 buttonColorsPrimary()（薄荷底 + onPrimary 字），文字色交组件自管。
 */

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busyText: String? = null,
) {
    val shownBusyText = rememberBusyValue(busyText)
    val scheme = MiuixTheme.colorScheme
    val motion = rememberMotionEnabled()
    val interactive = enabled && busyText == null && shownBusyText == null
    val busy = busyText != null || shownBusyText != null
    // 忙碌态保持主题色柔化而非硬跳浅灰禁用色，状态切换平滑过渡不突兀
    val targetBg = when {
        interactive -> scheme.primary
        busy -> scheme.primary.copy(alpha = 0.80f)
        else -> scheme.disabledPrimaryButton
    }
    val targetTextColor = when {
        interactive || busy -> scheme.onPrimary
        else -> scheme.disabledOnPrimaryButton
    }
    val bg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(if (motion) Motion.PHASE else 0),
        label = "primaryBtnBg",
    )
    val contentColor by animateColorAsState(
        targetValue = targetTextColor,
        animationSpec = tween(if (motion) Motion.PHASE else 0),
        label = "primaryBtnFg",
    )
    // 深色主按钮本身是亮薄荷底（深色字），按压应走黑色压暗而非全局白色提亮
    val buttonIndication = if (LocalDarkTheme.current) {
        remember { CandyIndication(color = Color.Black, maxAlpha = 0.14f) }
    } else {
        LocalIndication.current
    }
    CompositionLocalProvider(LocalIndication provides buttonIndication) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = interactive,
            colors = ButtonDefaults.buttonColors(
                color = bg,
                disabledColor = bg,
                contentColor = contentColor,
                disabledContentColor = contentColor,
            ),
        ) {
            AnimatedContent(
                targetState = shownBusyText,
                transitionSpec = {
                    val spec = tween<Float>(if (motion) Motion.MICRO else 0)
                    fadeIn(spec) togetherWith fadeOut(spec)
                },
                label = "primaryBtnContent",
            ) { currentBusy ->
                if (currentBusy != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LoadingDots(color = LocalContentColor.current, contentDescription = null)
                        Text(text = currentBusy, style = MiuixTheme.textStyles.button)
                    }
                } else {
                    Text(text = text, style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MiuixTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            color = scheme.secondary,
            disabledColor = scheme.disabledSecondary,
            contentColor = scheme.onSecondary,
            disabledContentColor = scheme.disabledOnSecondary,
        ),
    ) {
        Text(text = text, style = MiuixTheme.textStyles.button)
    }
}

/** 危险操作按钮：error 容器 + onError 文字，用于移除仓库这类不可撤销的动作。 */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val buttonIndication = if (LocalDarkTheme.current) {
        remember { CandyIndication(color = Color.Black, maxAlpha = 0.14f) }
    } else {
        LocalIndication.current
    }
    CompositionLocalProvider(LocalIndication provides buttonIndication) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(color = scheme.error, contentColor = scheme.onError),
        ) {
            Text(text = text, style = MiuixTheme.textStyles.button)
        }
    }
}

/**
 * 图标钮统一按压反馈版（全 App 图标钮唯一来源，2026-11-21 起）：
 * miuix IconButton 的最小尺寸是 40dp，按下反馈是库默认的 10% spring 叠加；本组件改为
 * 48dp 圆形热区 + App 标准按压染色（rememberRowPressTint，与 PressableCard/
 * 信息行同一反馈语言）+ Role.Button 语义；可访问名称挂容器（[contentDescription]），
 * 槽内 Icon 一律 contentDescription = null。
 *
 * 染色圆直径 = 48dp − 2×[tintInset]（默认 4dp）：热区大于视觉圆——顶栏相邻 48dp
 * 槽位相接时染色圆之间仍有 8dp 视觉间距。输入框用 6dp 内缩保留 36dp 可见圆。
 */
@Composable
fun PressIconButton(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tintInset: Dp = Dp.Unspecified,
    containerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
    border: BorderStroke? = null,
    glass: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val resolvedInset = if (tintInset != Dp.Unspecified) tintInset else if (glass) 3.dp else 4.dp
    val glassEnabled = glass && LocalAppBackdrop.current != null
    val resolvedSurface = when {
        !glass -> containerColor
        glassEnabled -> {
            val glassBase = if (dark) Color.White.copy(alpha = 0.11f) else Color.White.copy(alpha = 0.72f)
            if (containerColor == Color.Unspecified) glassBase else containerColor.compositeOver(glassBase)
        }
        containerColor != Color.Unspecified -> containerColor
        else -> MiuixTheme.colorScheme.surfaceContainer
    }
    val resolvedBorder = when {
        border != null -> border
        !glass -> null
        else -> BorderStroke(
            width = 0.5.dp,
            color = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.07f),
        )
    }
    val (pressSrc, pressColor) = rememberRowPressTint(surface = resolvedSurface)
    val gestures = if (onLongClick == null) {
        Modifier.clickable(enabled = enabled, interactionSource = pressSrc, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier.combinedClickable(
            enabled = enabled,
            interactionSource = pressSrc,
            indication = null,
            role = Role.Button,
            onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }
    Box(
        modifier = modifier
            .size(48.dp)
            .then(gestures)
            .semantics { contentDescription?.let { this.contentDescription = it } }
            .then(if (enabled) Modifier else Modifier.graphicsLayer { alpha = 0.38f })
            // padding 在 clickable 之后：染色圆缩进、热区不缩
            .padding(resolvedInset)
            .clip(CircleShape)
            .background(pressColor, CircleShape)
            .then(if (resolvedBorder == null) Modifier else Modifier.border(resolvedBorder, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** The field can still be focused after Back or a preview dismissed the keyboard. */
@Composable
internal fun clearTextFieldAction(focus: FocusRequester, onClear: () -> Unit): () -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    return {
        onClear()
        focus.requestFocus()
        keyboard?.show()
    }
}

/** 输入框尾部图标按钮。48dp 热区 + end=4dp 使 24dp 图标到右缘 16dp，与 TextField 左侧 16dp 及上下留白对称。 */
@Composable
internal fun TextFieldActionButton(
    onClick: () -> Unit,
    @DrawableRes icon: Int = R.drawable.ic_close,
    @StringRes actionLabel: Int = R.string.clear_input,
) {
    val dark = LocalDarkTheme.current
    PressIconButton(
        onClick = onClick,
        contentDescription = stringResource(actionLabel),
        tintInset = 6.dp,
        modifier = Modifier.padding(end = 4.dp),
        containerColor = Color.Transparent,
    ) {
        // 图标交叉淡入（令牌显隐等条件换图）：瞬切会与按压色块的 ~100ms 保持期打架
        androidx.compose.animation.Crossfade(
            targetState = icon,
            animationSpec = if (rememberMotionEnabled()) {
                tween(Motion.MICRO)
            } else {
                androidx.compose.animation.core.snap()
            },
            label = "fieldActionIcon",
        ) { res ->
            Icon(
                imageVector = ImageVector.vectorResource(res),
                contentDescription = null,
                tint = if (dark) DarkFieldActionTint else LightFieldActionTint,
            )
        }
    }
}
