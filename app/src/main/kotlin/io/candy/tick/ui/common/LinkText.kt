package io.candy.tick.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全局文字链唯一样式（版本历史「查看完整更新说明」、设置「Token 帮助」等外链共用）：
 * footnote1 + 薄荷 primaryVariant + 下划线（色相近时下划线是「可点」的最强信号）。
 * showIcon 时带 13dp external_link 前缀（离开 App 的预期暗示）。
 * 自绘 clickable 无 ripple，padding 计入命中区。
 */
@Composable
fun LinkText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showIcon: Boolean = false,
    spaceAfter: Dp = 0.dp,
) {
    // Count the parent's trailing gap once. The visible surface stays centred between
    // its neighbours even when a large font makes the tap target taller than 48dp.
    val inset = maxOf(8.dp, spaceAfter)
    CompactTextAction(
        onClick = onClick,
        modifier = modifier,
        hitAreaPadding = PaddingValues(top = inset, bottom = inset - spaceAfter),
    ) {
        if (showIcon) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_external_link),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primaryVariant,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1.copy(
                textDecoration = TextDecoration.Underline,
            ),
            color = MiuixTheme.colorScheme.primaryVariant,
        )
    }
}

/** Keep the tap target generous while the tinted surface hugs the icon and label. */
@Composable
internal fun CompactTextAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hitAreaPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val (source, tint) = rememberRowPressTint()
    Box(
        modifier = modifier
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(hitAreaPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .background(tint, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}
