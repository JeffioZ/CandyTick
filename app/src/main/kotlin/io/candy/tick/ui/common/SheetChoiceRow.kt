package io.candy.tick.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val SheetChoiceRowShape = RoundedCornerShape(10.dp)

@Composable
internal fun SheetChoiceRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable (RowScope.() -> Unit)? = null,
) {
    BasicComponent(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(SheetChoiceRowShape)
            // 副标题到达时行高变化走 150 微档动画：标题位移从瞬跳变为平滑过渡
            // （分支选择器逐条到达的日期副标题曾「主标题跳一下」，真机反馈）
            .animateContentSize(tween(Motion.MICRO))
            .then(modifier),
        title = title,
        summary = summary,
        startAction = startAction,
        endActions = endActions,
        onClick = onClick,
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = if (summary == null) 12.dp else 8.dp),
    )
}

@Composable
internal fun OptionRow(label: String, selected: Boolean, summary: String? = null, onClick: () -> Unit) {
    SheetChoiceRow(
        title = label,
        summary = summary,
        onClick = onClick,
        modifier = Modifier.semantics { this.selected = selected },
        endActions = {
            if (selected) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_check_circle),
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primaryVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}
