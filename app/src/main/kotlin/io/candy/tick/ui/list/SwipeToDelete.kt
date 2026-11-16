package io.candy.tick.ui.list

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.extended.Delete

/**
 * 安卓规范「滑动删除」：左右滑超阈值触发 onDelete（配合上层 Snackbar 撤销），
 * 未过阈值回弹。design.md 动效清单「删除滑出 + 撤销滑入」的实现。
 */
@Composable
fun SwipeToDelete(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val scheme = MiuixTheme.colorScheme

    BoxWithConstraints(modifier = modifier) {
        // 底层：删除底色 + 图标（随滑动露出）
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(scheme.errorContainer),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(
                imageVector = top.yukonga.miuix.kmp.icon.MiuixIcons.Delete,
                contentDescription = null,
                tint = scheme.onErrorContainer,
                modifier = Modifier
                    .padding(end = 24.dp)
                    .size(22.dp),
            )
        }
        // 上层：内容随手势横移
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-size.width * 1.2f, size.width * 1.2f))
                            }
                        },
                        onDragEnd = {
                            val threshold = size.width * 0.42f
                            scope.launch {
                                if (offsetX.value > threshold || offsetX.value < -threshold) {
                                    onDelete()
                                    offsetX.snapTo(0f) // 条目即被列表移除，复位供撤销回归
                                } else {
                                    offsetX.animateTo(0f)
                                }
                            }
                        },
                    )
                },
        ) {
            content()
        }
    }
}
