package io.candy.tick.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.collectPressTint
import io.candy.tick.ui.common.rememberMotionEnabled
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val TabInset = 3.dp
private val TabTrackShape = RoundedCornerShape(16.dp)
private val TabThumbRadius = 16.dp - TabInset
private val TabThumbShape = RoundedCornerShape(TabThumbRadius)

@Composable
internal fun DetailTabs(
    selected: DetailViewModel.DetailTab,
    position: State<Float>,
    onSelect: (DetailViewModel.DetailTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).height(IntrinsicSize.Min)
            .background(scheme.secondaryVariant, TabTrackShape)
            .padding(horizontal = TabInset)
            .drawBehind {
                // Match the two equally weighted Row children, including an odd remaining pixel.
                val firstWidth = (size.width.toInt() / 2).toFloat()
                val secondWidth = size.width - firstWidth
                val fraction = position.value
                val start = firstWidth * fraction
                val width = firstWidth + (secondWidth - firstWidth) * fraction
                val left = if (layoutDirection == LayoutDirection.Ltr) start else size.width - start - width
                val inset = TabInset.toPx()
                drawRoundRect(
                    color = scheme.surface,
                    topLeft = Offset(left, inset),
                    size = Size(width, size.height - 2 * inset),
                    cornerRadius = CornerRadius(TabThumbRadius.toPx()),
                )
            }
            .selectableGroup(),
    ) {
        DetailTab(
            isSelected = selected == DetailViewModel.DetailTab.COMMITS,
            icon = R.drawable.ic_git_commit,
            label = stringResource(R.string.detail_commits),
            // The thin commit line carries less visual weight than its label.
            contentOffsetX = (-3).dp,
            onClick = { onSelect(DetailViewModel.DetailTab.COMMITS) },
            modifier = Modifier.weight(1f),
        )
        DetailTab(
            isSelected = selected == DetailViewModel.DetailTab.RELEASES,
            icon = R.drawable.ic_tag,
            label = stringResource(R.string.detail_releases),
            onClick = { onSelect(DetailViewModel.DetailTab.RELEASES) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DetailTab(
    isSelected: Boolean,
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    contentOffsetX: Dp = 0.dp,
) {
    val scheme = MiuixTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val press = remember { Animatable(0f) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        collectPressTint(source, press) { focused = it }
    }
    val motion = rememberMotionEnabled()
    val foreground = if (isSelected) scheme.onSurface else scheme.onBackgroundVariant
    Box(
        modifier.fillMaxHeight()
            .selectable(selected = isSelected, interactionSource = source, indication = null, role = Role.Tab) {
                if (!isSelected) onClick()
            }
            .padding(vertical = TabInset)
            .then(if (focused) Modifier.border(1.dp, scheme.primaryVariant, TabThumbShape) else Modifier)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.offset(x = contentOffsetX).graphicsLayer {
                val amount = press.value
                scaleX = if (motion) 1f - 0.035f * amount else 1f
                scaleY = scaleX
                alpha = if (motion) 1f else 1f - 0.12f * amount
            },
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Exclude the vectors' transparent side bearings from the centered icon/label group.
            Box(Modifier.size(width = 14.dp, height = 16.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = ImageVector.vectorResource(icon),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(16.dp),
                    tint = foreground,
                )
            }
            Text(label, color = foreground, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center)
        }
    }
}
