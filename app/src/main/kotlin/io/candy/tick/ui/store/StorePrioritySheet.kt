package io.candy.tick.ui.store

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.candy.tick.R
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.ui.common.CandySheet
import io.candy.tick.ui.common.sheetCardColor
import io.candy.tick.ui.common.sheetDividerColor
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val PriorityRowHeight = 52.dp

/**
 * 轻量「渠道优先级」拖动排序浮层：
 * - 仅展示当前已开启渠道，不破坏设置主列表的固定顺序；
 * - 按住右侧把手直接上下拖动实时换位，松手回写 [StorePreferences.enabledStores]。
 */
@Composable
internal fun StorePrioritySheet(
    show: Boolean,
    enabledStores: List<String>,
    onDismiss: () -> Unit,
    onCommitOrder: (List<String>) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val rowHeightPx = with(LocalDensity.current) { PriorityRowHeight.toPx() }

    val items = remember { mutableStateListOf<String>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val latestCommitOrder by rememberUpdatedState(onCommitOrder)

    LaunchedEffect(show, enabledStores) {
        if (show && draggingId == null) {
            items.clear()
            items.addAll(enabledStores)
        }
    }

    CandySheet(
        show = show,
        title = stringResource(R.string.store_priority_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.store_priority_desc),
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(sheetCardColor())
                    .verticalScroll(rememberScrollState(), enabled = draggingId == null),
            ) {
                items.forEachIndexed { idx, storeId ->
                    // 必须以 storeId 为 key：换位时保留同一 pointerInput 实例，防止拖动手势中途被取消卡住
                    key(storeId) {
                        val isDragging = draggingId == storeId
                        val desc = StoreCatalog.descriptorOf(storeId)
                        val label = desc?.let { stringResource(it.nameRes) } ?: storeId
                        if (idx > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp)
                                    .height(1.dp)
                                    .background(sheetDividerColor()),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(PriorityRowHeight)
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    if (isDragging) {
                                        translationY = dragOffsetY
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                    }
                                }
                                .background(if (isDragging) scheme.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                                .padding(start = 14.dp, end = 4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(scheme.primary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = (idx + 1).toString(),
                                    style = MiuixTheme.textStyles.footnote1.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontFeatureSettings = "tnum",
                                    ),
                                    color = scheme.primaryVariant,
                                )
                            }
                            Text(
                                text = label,
                                style = MiuixTheme.textStyles.body2.copy(
                                    fontWeight = if (isDragging) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                color = scheme.onSurfaceContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .pointerInput(Unit) {
                                        detectVerticalDragGestures(
                                            onDragStart = {
                                                draggingId = storeId
                                                dragOffsetY = 0f
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                if (draggingId != storeId) return@detectVerticalDragGestures
                                                dragOffsetY += dragAmount
                                                var curIdx = items.indexOf(storeId)
                                                if (curIdx < 0) return@detectVerticalDragGestures
                                                while (dragOffsetY > rowHeightPx * 0.55f && curIdx < items.lastIndex) {
                                                    items.add(curIdx + 1, items.removeAt(curIdx))
                                                    curIdx++
                                                    dragOffsetY -= rowHeightPx
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                while (dragOffsetY < -rowHeightPx * 0.55f && curIdx > 0) {
                                                    items.add(curIdx - 1, items.removeAt(curIdx))
                                                    curIdx--
                                                    dragOffsetY += rowHeightPx
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            },
                                            onDragEnd = {
                                                draggingId = null
                                                dragOffsetY = 0f
                                                latestCommitOrder(items.toList())
                                            },
                                            onDragCancel = {
                                                draggingId = null
                                                dragOffsetY = 0f
                                                latestCommitOrder(items.toList())
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                DragHandleGlyph(
                                    color = if (isDragging) scheme.primaryVariant else scheme.onSurfaceContainerVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DragHandleGlyph(color: androidx.compose.ui.graphics.Color) {
    Canvas(modifier = Modifier.size(width = 18.dp, height = 14.dp)) {
        val stroke = 1.8.dp.toPx()
        val ys = listOf(stroke / 2f, size.height / 2f, size.height - stroke / 2f)
        for (y in ys) {
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
