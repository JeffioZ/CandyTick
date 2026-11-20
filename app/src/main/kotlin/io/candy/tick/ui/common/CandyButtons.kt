package io.candy.tick.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
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
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        Text(text = text, style = MiuixTheme.textStyles.button)
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
 * 多态），与「所有可点击控件必须有多态」红线冲突。本组件：48dp 圆形热区 +
 * 按压 10% 前景染色（rememberRowPressTint，与 PressableCard/信息行同一反馈语言）+
 * Role.Button 语义；可访问名称挂容器（[contentDescription]），槽内 Icon 一律
 * contentDescription = null。行内紧凑位传 size = 44.dp 与行高协调。
 */
@Composable
fun PressIconButton(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    content: @Composable () -> Unit,
) {
    val (pressSrc, pressColor) = rememberRowPressTint()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(pressColor, CircleShape)
            .clickable(
                interactionSource = pressSrc,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription?.let { this.contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
