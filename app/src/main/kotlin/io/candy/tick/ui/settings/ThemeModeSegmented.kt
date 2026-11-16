package io.candy.tick.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.model.ThemeMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全工程唯一自搭组件：主题三档分段控件（design.md「体验五原则」定案）。
 * HyperOS 样式：灰底胶囊轨道 + 滑动选中浮层。
 */
@Composable
fun ThemeModeSegmented(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.theme_mode_system),
        ThemeMode.LIGHT to stringResource(R.string.theme_mode_light),
        ThemeMode.DARK to stringResource(R.string.theme_mode_dark),
    )
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.secondaryVariant)
            .padding(3.dp),
    ) {
        BoxWithConstraints {
            val segmentWidth = maxWidth / entries.size
            val index = entries.indexOfFirst { it.first == selected }.coerceAtLeast(0)
            val offset by animateDpAsState(
                targetValue = segmentWidth * index,
                animationSpec = tween(durationMillis = 250),
                label = "themeSegmentOffset",
            )
            Box(
                modifier = Modifier
                    .offset(x = offset)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surface),
            )
            Row(modifier = Modifier.fillMaxSize()) {
                entries.forEach { (mode, label) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(mode) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (mode == selected) {
                                MiuixTheme.colorScheme.onSurface
                            } else {
                                MiuixTheme.colorScheme.onSurfaceContainerVariant
                            },
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
        }
    }
}
