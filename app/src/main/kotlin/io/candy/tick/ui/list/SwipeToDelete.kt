package io.candy.tick.ui.list

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import io.candy.tick.R
import io.candy.tick.ui.common.Motion

/** 向末端滑动删除，向起始侧滑动置顶或取消置顶；松手时以卡片宽度的 42% 为阈值。 */
@Composable
fun SwipeToDelete(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onPin: (() -> Unit)? = null,
    pinned: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    // 删除消散进度：1=常态；删除时动画到 0（卡片缩回 + 粒子迸散），LibChecker 式 hero 特效
    val dissolve = remember { Animatable(1f) }
    val scheme = MiuixTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val direction = LocalLayoutDirection.current
    // 减弱动态（系统动画=0）：删除消散瞬时完成（reduced-motion 尊重）
    val motionEnabled by rememberUpdatedState(io.candy.tick.ui.common.rememberMotionEnabled())
    // 手势块持有创建时的闭包（key 不变不重启）：不桥接最新值的话，
    // togglePin 会拿到置顶前的旧条目快照，导致「无法取消置顶」
    val currentOnDelete by rememberUpdatedState(onDelete)
    val currentOnPin by rememberUpdatedState(onPin)
    var committing by remember { mutableStateOf(false) }
    // 读屏动作标签（pinned 变化触发重组，标签随状态切换）
    val pinActionLabel = stringResource(if (pinned) R.string.action_unpin else R.string.action_pin)
    val removeActionLabel = stringResource(R.string.action_remove)

    // 手势挂在固定的槽位，只有前景卡片移动。拖动直接写位移，松手后才交给回位动画。
    val swipe = Modifier.pointerInput(onPin != null, direction) {
        val claimPx = 16.dp.toPx()
        val toLogical = if (direction == LayoutDirection.Ltr) 1f else -1f
        try {
            coroutineScope {
                var returnJob: Job? = null
                var ignoreGesture = false
                var dragClaimed = false
                var dragTotal = 0f
                var hapticFired = false

                fun settle(deleting: Boolean = false, commit: (() -> Unit)? = null) {
                    if (ignoreGesture || committing || offsetX == 0f) return
                    committing = commit != null
                    returnJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            if (motionEnabled) {
                                if (deleting) {
                                    // 删除：卡停在松手位置原地消散——不回位。回位动画方向与
                                    // 手势相反（左滑松手后卡向右飞回再消失），且 300ms 消散对
                                    // 220ms 回位的时长差会多出一段「位置停了还在淡」的两段感
                                    dissolve.animateTo(0f, tween(Motion.PROGRESS))
                                } else {
                                    animate(
                                        initialValue = offsetX,
                                        targetValue = 0f,
                                        animationSpec = tween(Motion.PHASE, easing = Motion.Decelerate),
                                    ) { value, _ -> offsetX = value }
                                }
                            } else if (deleting) {
                                dissolve.snapTo(0f)
                            }
                            offsetX = 0f
                        } finally {
                            // 即使回位中条目离屏，已松手确认的动作也只提交一次。
                            if (commit != null) {
                                offsetX = 0f
                                commit()
                                committing = deleting
                            }
                        }
                    }
                }

                detectHorizontalDragGestures(
                    onDragStart = {
                        ignoreGesture = committing
                        if (!ignoreGesture) returnJob?.cancel()
                        dragClaimed = false
                        dragTotal = 0f
                        hapticFired = false
                    },
                    onHorizontalDrag = { change, physicalDelta ->
                        if (ignoreGesture) return@detectHorizontalDragGestures
                        val delta = physicalDelta * toLogical
                        if (!dragClaimed) {
                            dragTotal += delta
                            if (abs(dragTotal) < claimPx) return@detectHorizontalDragGestures
                            dragClaimed = true
                        }
                        change.consume()
                        val limit = size.width * 1.2f
                        val min = if (currentOnPin != null) -limit else 0f
                        offsetX = (offsetX + delta).coerceIn(min, limit)
                        if (!hapticFired && abs(offsetX) > size.width * 0.42f) {
                            hapticFired = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDragEnd = {
                        val threshold = size.width * 0.42f
                        when {
                            dragClaimed && offsetX > threshold -> settle(deleting = true, commit = currentOnDelete)
                            dragClaimed && offsetX < -threshold -> settle(commit = currentOnPin)
                            else -> settle()
                        }
                    },
                    onDragCancel = { settle() },
                )
            }
        } finally {
            offsetX = 0f
        }
    }
    Box(modifier = modifier.then(swipe)) {
        // 单层方向驱动背景：右滑露出删除（图标在左侧露出区），左滑露出置顶（右侧）
        val revealedSide by remember { derivedStateOf { offsetX.roundToInt().compareTo(0) } }
        if (revealedSide != 0) {
            val movingRight = revealedSide > 0
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .squircleSurface(
                        color = when {
                            movingRight -> scheme.errorContainer
                            pinned -> scheme.secondaryVariant
                            else -> scheme.primaryContainer
                        },
                        cornerRadius = CardDefaults.CornerRadius,
                    ),
                contentAlignment = if (movingRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(if (movingRight) R.drawable.ic_delete else R.drawable.ic_pin),
                    contentDescription = null,
                    tint = when {
                        movingRight -> scheme.onErrorContainer
                        pinned -> scheme.onBackgroundVariant
                        else -> scheme.onPrimaryContainer
                    },
                    modifier = Modifier
                        .padding(start = if (movingRight) 24.dp else 0.dp, end = if (movingRight) 0.dp else 24.dp)
                        .size(22.dp)
                        .rotate(if (!movingRight && pinned) 45f else 0f),
                )
            }
        }
        // 上层：内容随手势横移（负向 = 置顶方向）；删除时缩放淡出
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .graphicsLayer {
                    val d = dissolve.value
                    alpha = d
                    scaleX = 0.6f + 0.4f * d
                    scaleY = 0.6f + 0.4f * d
                },
        ) {
            content(Modifier.semantics {
                customActions = buildList {
                    currentOnPin?.let { pin ->
                        add(CustomAccessibilityAction(pinActionLabel) {
                            if (committing) false else { pin(); true }
                        })
                    }
                    add(CustomAccessibilityAction(removeActionLabel) {
                        if (committing) false else { currentOnDelete(); true }
                    })
                }
            })
        }
        // 粒子层：删除消散时从卡片中心迸散（红/薄荷双色系，重力下坠），不随卡片缩放。
        // dissolve 读取放在 draw 阶段——组合期的条件读取会让整个内容子树随动画每帧重组
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .matchParentSize()
                .offset { IntOffset(offsetX.roundToInt(), 0) },
        ) {
            val value = dissolve.value
            if (value >= 0.999f) return@Canvas
            val p = 1f - value
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
