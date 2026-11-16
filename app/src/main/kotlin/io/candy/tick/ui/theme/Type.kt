package io.candy.tick.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/*
 * 字体走系统默认 + Miuix 内置 TextStyles（HyperOS 字阶）；
 * 仅补等宽样式：版本号 / commit sha 专用（design.md「布局细节」）。
 */
val MonoTokenStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)
