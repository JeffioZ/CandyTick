package io.candy.tick.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
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
import io.candy.tick.ui.theme.DarkFieldActionContainer
import io.candy.tick.ui.theme.DarkFieldActionOutline
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
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && busyText == null && shownBusyText == null,
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        if (shownBusyText != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LoadingDots(color = LocalContentColor.current, contentDescription = null)
                Text(text = shownBusyText, style = MiuixTheme.textStyles.button)
            }
        } else {
            Text(text = text, style = MiuixTheme.textStyles.button)
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
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        Text(text = text, style = MiuixTheme.textStyles.button)
    }
}

/**
 * 图标钮统一按压反馈版（全 App 图标钮唯一来源，2026-11-21 起）：
 * Miuix IconButton 的 clickable 不接 indication（0.9.4 字节码核实——库层面无按压
 * 多态），与「所有可点击控件必须有多态」红线冲突。本组件：[size] 圆形热区 +
 * 按压前景染色（rememberRowPressTint，与 PressableCard/信息行同一反馈语言）+
 * Role.Button 语义；可访问名称挂容器（[contentDescription]），槽内 Icon 一律
 * contentDescription = null。
 *
 * 染色圆直径 = size − 2×[tintInset]（默认 4dp）：热区大于视觉圆——顶栏相邻 48dp
 * 槽位相接时染色圆之间仍有 8dp 视觉间距。输入框用 6dp 内缩保留 36dp 可见圆。
 */
@Composable
fun PressIconButton(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tintInset: Dp = 4.dp,
    containerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
    border: BorderStroke? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val (pressSrc, pressColor) = rememberRowPressTint(surface = containerColor)
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
            .size(size)
            .then(gestures)
            .semantics { contentDescription?.let { this.contentDescription = it } }
            // padding 在 clickable 之后：染色圆缩进、热区不缩
            .padding(tintInset)
            .background(pressColor, CircleShape)
            .then(if (border == null) Modifier else Modifier.border(border, CircleShape)),
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

/** 输入框尾部的圆钮。36dp 可见圆与输入框边缘之间留出 8dp。 */
@Composable
internal fun TextFieldActionButton(
    onClick: () -> Unit,
    @DrawableRes icon: Int = R.drawable.ic_close,
    @StringRes actionLabel: Int = R.string.clear_input,
) {
    val colors = MiuixTheme.colorScheme
    val dark = LocalDarkTheme.current
    PressIconButton(
        onClick = onClick,
        contentDescription = stringResource(actionLabel),
        tintInset = 6.dp,
        modifier = Modifier.padding(end = 2.dp),
        containerColor = if (dark) DarkFieldActionContainer else colors.surfaceContainer,
        border = if (dark) BorderStroke(1.dp, DarkFieldActionOutline) else null,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(icon),
            contentDescription = null,
            tint = if (dark) colors.onSurfaceSecondary else colors.onBackgroundVariant,
        )
    }
}
