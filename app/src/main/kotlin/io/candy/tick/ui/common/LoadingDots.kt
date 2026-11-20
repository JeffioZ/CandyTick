package io.candy.tick.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.sin

/**
 * 轻量三点波浪加载指示（与首页同步活体同一视觉语言：点 = 活动中）。
 * 纯静态文案「加载中…」的统一替代；带 busy 语义描述（无障碍）。
 * 波浪 ±2.5dp 围绕中心对称摆动，占高约 10dp，行内等高替换文字。
 */
@Composable
fun LoadingDots(
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.primaryVariant,
    dotSize: Dp = 4.dp, // 行内伴随 4dp；独立等待位点用 5dp（用户正盯着它等）
) {
    val busyLabel = stringResource(R.string.loading)
    // 减弱动态（系统动画时长=0）：三点静态化（持续运动是前庭不适主因）
    if (!rememberMotionEnabled()) {
        Row(
            // 与动态分支同 padding（等高）：两分支切换不跳行高
            modifier = modifier
                .semantics { contentDescription = busyLabel }
                .padding(vertical = dotSize * 0.625f),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(dotSize)
                        .alpha(0.8f)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
        return
    }
    val t by rememberInfiniteTransition(label = "loadingDots").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "loadingT",
    )
    Row(
        // 垂直余量 = 波动幅度（±0.625×dotSize）：offset 不参与测量，Row 实际高度若只有
        // 点直径，浮动越界部分会被带 clip 的祖先（卡片圆角）裁掉——首帧点在最大偏移位，最明显
        modifier = modifier
            .semantics { contentDescription = busyLabel }
            .padding(vertical = dotSize * 0.625f),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val phase = (((t - i / 3f) % 1f) + 1f) % 1f
            val wave = sin(phase * 2 * Math.PI).toFloat()
            Box(
                modifier = Modifier
                    .offset(y = dotSize * -0.625f * wave)
                    .size(dotSize)
                    .alpha(0.45f + 0.55f * (0.5f + 0.5f * wave))
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
