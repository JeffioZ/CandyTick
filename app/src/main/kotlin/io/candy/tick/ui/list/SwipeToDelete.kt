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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Pin

/**
 * 卡片双向滑动操作：右滑删除（红底 + 垃圾桶，过阈值删除 + Snackbar 撤销）、
 * 左滑置顶 toggle（薄荷底 + 图钉；已置顶项为中性底 + 斜置图钉 = 解除）。
 * 滑动露出的底色与图标即 affordance（swipe-clarity）；置顶为可逆轻操作，
 * 触发后回弹归位（条目不消失，位置跳变即反馈）。
 */
@Composable
fun SwipeToDelete(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onPin: (() -> Unit)? = null,
    pinned: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val scheme = MiuixTheme.colorScheme
    // 手势块持有创建时的闭包（key 不变不重启）：不桥接最新值的话，
    // togglePin 会拿到置顶前的旧条目快照，导致「无法取消置顶」
    val currentOnDelete by androidx.compose.runtime.rememberUpdatedState(onDelete)
    val currentOnPin by androidx.compose.runtime.rememberUpdatedState(onPin)

    BoxWithConstraints(modifier = modifier) {
        // 底层左：删除（内容右滑时露出）
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(scheme.errorContainer),
            contentAlignment = Alignment.CenterStart,
        ) {
            Icon(
                imageVector = MiuixIcons.Delete,
                contentDescription = null,
                tint = scheme.onErrorContainer,
                modifier = Modifier
                    .padding(start = 24.dp)
                    .size(22.dp),
            )
        }
        // 底层右：置顶 toggle（内容左滑时露出）
        if (onPin != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(if (pinned) scheme.secondaryVariant else scheme.primaryContainer),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = MiuixIcons.Pin,
                    contentDescription = null,
                    tint = if (pinned) scheme.onBackgroundVariant else scheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(end = 24.dp)
                        .size(22.dp)
                        .rotate(if (pinned) 45f else 0f),
                )
            }
        }
        // 上层：内容随手势横移（负向 = 置顶方向）
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(onPin != null) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                val min = if (onPin != null) -size.width * 1.2f else 0f
                                offsetX.snapTo((offsetX.value + dragAmount).coerceIn(min, size.width * 1.2f))
                            }
                        },
                        onDragEnd = {
                            val threshold = size.width * 0.42f
                            scope.launch {
                                when {
                                    offsetX.value > threshold -> {
                                        currentOnDelete()
                                        offsetX.snapTo(0f) // 条目即被列表移除，复位供撤销回归
                                    }
                                    offsetX.value < -threshold -> {
                                        currentOnPin?.invoke()
                                        offsetX.animateTo(0f) // 置顶不移除条目：回弹，位置跳变即反馈
                                    }
                                    else -> offsetX.animateTo(0f)
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
