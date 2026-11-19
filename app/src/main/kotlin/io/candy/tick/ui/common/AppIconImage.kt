package io.candy.tick.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 本机应用图标统一渲染：白底图标加 0.5dp 半透明描边（应用商店惯例——白图标在浅色
 * 卡片上有轮廓，彩图标无感）。所有展示应用图标的地方共用，避免各页面自行处理漂移。
 */
@Composable
fun AppIconImage(
    bitmap: ImageBitmap,
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
) {
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.6f),
                RoundedCornerShape(corner),
            ),
    )
}
