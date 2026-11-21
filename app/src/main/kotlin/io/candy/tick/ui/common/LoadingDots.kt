package io.candy.tick.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.SyncStatus
import top.yukonga.miuix.kmp.basic.Icon
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

/**
 * 顶栏同步活体（首页/详情共用）：标题右侧三点轻跳（进行中）→ 完成/失败符。
 * 36dp 固定占位槽——槽常在，标题再长 dots 也不会被挤出/挤进 actions 区；
 * 300ms 延迟防闪（短抖动不点亮），结束后停一拍再收。
 * done 传 null = 只做波点（详情静默刷新没有结果事件）。
 */
@Composable
fun SyncDots(
    running: Boolean,
    done: SyncStatus.Done?,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    var dotsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (running) {
            kotlinx.coroutines.delay(300) // 近即时防闪
            dotsVisible = true
        } else {
            if (dotsVisible) kotlinx.coroutines.delay(350) // 停一拍再收
            dotsVisible = false
        }
    }
    Box(modifier = modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
        // 结果符：捕获最后一个非空结果——退出期间继续渲染，
        // 否则 done 置空的瞬间内容塌空，退出动画淡出的是空气（= 瞬间消失）
        var lastDone by remember { mutableStateOf<SyncStatus.Done?>(null) }
        if (done != null) lastDone = done
        AnimatedVisibility(
            visible = done != null,
            enter = fadeIn(tween(Motion.MICRO)) + scaleIn(
                initialScale = 0.6f,
                animationSpec = tween(Motion.MICRO),
            ),
            exit = fadeOut(tween(Motion.MICRO)) + scaleOut(
                targetScale = 0.6f,
                animationSpec = tween(Motion.MICRO),
            ),
        ) {
            lastDone?.let { d ->
                val failedOnly = d.failed > 0 && d.changed == 0
                // 结果符 Lucide 单源（ic_check/ic_close）——此前是 Unicode 文字字符，
                // 随设备字体变形且笔画与图标库不一致（与 Feedback 胶囊状态符同批替换）
                Icon(
                    imageVector = ImageVector.vectorResource(
                        if (failedOnly) R.drawable.ic_close else R.drawable.ic_check,
                    ),
                    contentDescription = null,
                    tint = if (failedOnly) scheme.onErrorContainer else scheme.primaryVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = dotsVisible,
            enter = fadeIn(tween(Motion.MICRO)),
            exit = fadeOut(tween(Motion.MICRO)),
        ) {
            // 复用公共组件（同一波浪语义）；结果出现时淡出
            Box(
                modifier = Modifier.alpha(if (done != null) 0f else 1f),
            ) {
                LoadingDots(color = scheme.primaryVariant)
            }
        }
    }
}
