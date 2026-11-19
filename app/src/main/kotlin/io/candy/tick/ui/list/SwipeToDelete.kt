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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import io.candy.tick.R

/**
 * 卡片双向滑动操作：右滑删除（红容器 + 垃圾桶，过阈值删除 + Snackbar 撤销）、
 * 左滑置顶 toggle（薄荷容器 + 图钉；已置顶项为中性容器 + 斜置图钉 = 解除）。
 *
 * 手势工程（accompanist SwipeToDismiss 同款模式）：
 * - 判定期：累计位移 > 24dp 且横向占优（|dx|>|dy|）才接管并消费事件——
 *   此前零消费，垂直滚动 / 下拉刷新正常接管，斜划不误触（系统 8dp slop 太灵敏）
 * - 触发阈值 42% 宽度；越线那一拍单次震动确认（单手势防重入）
 * - 底层为单层方向驱动背景（offset 正负决定删除/置顶样式，图标随露出侧），
 *   16dp 圆角与卡片同款（原 CircleShape 内切圆只覆盖卡片中部，两侧图标被裁不可见）
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
    val haptic = LocalHapticFeedback.current
    // 减弱动态（系统动画=0）：删除消散瞬时完成（reduced-motion 尊重）
    val motionEnabled by rememberUpdatedState(io.candy.tick.ui.common.rememberMotionEnabled())
    // 手势块持有创建时的闭包（key 不变不重启）：不桥接最新值的话，
    // togglePin 会拿到置顶前的旧条目快照，导致「无法取消置顶」
    val currentOnDelete by rememberUpdatedState(onDelete)
    val currentOnPin by rememberUpdatedState(onPin)
    // 手势判定状态：死区门槛内不动卡；单手势震动一次
    var dragClaimed by remember { mutableStateOf(false) }
    var dragTotal by remember { mutableStateOf(0f) }
    var hapticFired by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier) {
        // 单层方向驱动背景：右滑露出删除（图标在左侧露出区），左滑露出置顶（右侧）
        val movingRight = offsetX.value > 0f
        val container = if (movingRight) {
            scheme.errorContainer
        } else {
            if (pinned) scheme.secondaryVariant else scheme.primaryContainer
        }
        val onContainer = if (movingRight) {
            scheme.onErrorContainer
        } else {
            if (pinned) scheme.onBackgroundVariant else scheme.onPrimaryContainer
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(16.dp))
                .background(container),
            contentAlignment = if (movingRight) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Icon(
                imageVector = if (movingRight) ImageVector.vectorResource(R.drawable.ic_delete) else ImageVector.vectorResource(R.drawable.ic_pin),
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier
                    .padding(start = if (movingRight) 24.dp else 0.dp, end = if (movingRight) 0.dp else 24.dp)
                    .size(22.dp)
                    .rotate(if (!movingRight && pinned) 45f else 0f),
            )
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
                    // 死区门槛：系统 slop（~8dp）后再累计 16dp 才开始动卡——
                    // 斜划/轻蹭不误触；框架的垂直取消（先竖向移动则交还列表滚动）内置处理。
                    // 不自研手势循环：卡片 offset 移动布局节点后，手算 position 差值
                    // 在移动坐标系里双重计数会颤抖（框架 dragAmount 已正确处理）
                    val claimPx = 16.dp.toPx()
                    val commitThreshold = size.width * 0.42f
                    detectHorizontalDragGestures(
                        onDragStart = { dragClaimed = false; dragTotal = 0f; hapticFired = false },
                        onDragEnd = {
                            scope.launch {
                                when {
                                    offsetX.value > commitThreshold -> {
                                        if (motionEnabled) {
                                            kotlinx.coroutines.coroutineScope {
                                                launch { offsetX.animateTo(0f, tween(220)) }
                                                launch { dissolve.animateTo(0f, tween(300)) }
                                            }
                                        } else {
                                            offsetX.snapTo(0f)
                                            dissolve.snapTo(0f)
                                        }
                                        currentOnDelete()
                                        dissolve.snapTo(1f)
                                    }
                                    offsetX.value < -commitThreshold -> {
                                        currentOnPin?.invoke()
                                        offsetX.snapTo(0f)
                                    }
                                    else -> offsetX.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offsetX.animateTo(0f) } },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!dragClaimed) {
                                dragTotal += dragAmount
                                if (abs(dragTotal) < claimPx) return@detectHorizontalDragGestures
                                dragClaimed = true
                            }
                            change.consume()
                            scope.launch {
                                val min = if (onPin != null) -size.width * 1.2f else 0f
                                offsetX.snapTo(
                                    (offsetX.value + dragAmount).coerceIn(min, size.width * 1.2f),
                                )
                                if (!hapticFired && abs(offsetX.value) > commitThreshold) {
                                    hapticFired = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
