package io.candy.tick.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextDecoration
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
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            // padding 计入命中区：8/8 使文字链命中 ≈35dp（行内链上限，再大会推开段落节奏）
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        if (showIcon) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_external_link),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primaryVariant,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
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
