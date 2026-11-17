package io.candy.tick.ui.list

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
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
    // 删除消散进度：1=常态；删除时动画到 0（卡片缩回 + 粒子迸散），LibChecker 式 hero 特效
    val dissolve = remember { Animatable(1f) }
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
        // 上层：内容随手势横移（负向 = 置顶方向）；删除时缩放淡出
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .graphicsLayer {
                    val d = dissolve.value
                    alpha = d
                    scaleX = 0.6f + 0.4f * d
                    scaleY = 0.6f + 0.4f * d
                }
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
                                        // hero 特效：卡片滑回原位的同时缩小消散 + 粒子迸散，320ms 后移除条目
                                        kotlinx.coroutines.coroutineScope {
                                            launch {
                                                offsetX.animateTo(0f, tween(200))
                                            }
                                            launch {
                                                dissolve.animateTo(0f, tween(320))
                                            }
                                        }
                                        currentOnDelete()
                                        dissolve.snapTo(1f) // 复位供撤销回归重绘
                                    }
                                    offsetX.value < -threshold -> {
                                        currentOnPin?.invoke()
                                        // 横向瞬间归位：置顶重排只走纵向动画，避免横竖叠加的斜向飞行
                                        offsetX.snapTo(0f)
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
        // 粒子层：删除消散时从卡片中心迸散（红/薄荷双色系，重力下坠），不随卡片缩放
        if (dissolve.value < 0.999f) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) },
            ) {
                val p = 1f - dissolve.value
                val accent = scheme.onErrorContainer
                val main = scheme.primaryVariant
                repeat(22) { i ->
                    val angle = (i / 22f) * 2f * Math.PI + i * 2.399963f
                    val speed = 0.5f + ((i * 37) % 100) / 100f
                    val dist = size.minDimension * 0.62f * p * speed
                    val cx = center.x + kotlin.math.cos(angle).toFloat() * dist
                    val cy = center.y + kotlin.math.sin(angle).toFloat() * dist +
                        size.height * 0.3f * p * p // 重力
                    drawCircle(
                        color = if (i % 3 == 0) accent else main,
                        radius = 3.dp.toPx() * (1f - p * 0.6f),
                        center = androidx.compose.ui.geometry.Offset(cx, cy),
                        alpha = (1f - p).coerceIn(0f, 1f),
                    )
                }
            }
        }
    }
}
