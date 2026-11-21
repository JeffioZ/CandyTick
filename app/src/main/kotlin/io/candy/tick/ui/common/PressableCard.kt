package io.candy.tick.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.candy.tick.ui.common.Motion

/**
 * 可点击卡片统一按压反馈（全 App 唯一来源）：
 * - 按压 = 卡面染 16% 前景色（M3 pressed 区间偏上：10% 在白卡相邻对比下不可辨，
 *   用户实测；暗色主题同公式自动变亮）
 * - 按下 snap 即时显色、松手 150 渐隐：渐入在快跳点击（按下 ~40ms 即释放）下根本
 *   走不完 =「松手快就没反馈」的根因；纯色变零位移零露底——Sink 缩放会在
 *   surface/background 色差下露出深色环（Miuix Card 无阴影可关）
 * - 圆角 16 / insideMargin 0（内容自管 padding，与其他卡片同构）；
 *   与 Miuix 默认卡色同源：surfaceContainer / onSurfaceContainer
 */
@Composable
fun PressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val pressSrc = remember { MutableInteractionSource() }
    val pressed = pressSrc.collectIsPressedAsState().value
    // 染色保持窗（快跳反馈的根治）：按下 ~40ms 即释放时，pressed 真假可能在同一帧内
    // 合并——snap 的染色一帧都没渲染，animateColorAsState 直接从 0% 走松手渐隐 =
    // 「松手快就没反馈」的残余根因。松手后按住染色 ≥100ms 再渐隐：快跳必现反馈，
    // 且覆盖式转场 260ms 里旧页全程可见，反馈必然落入可见窗口。
    // 动作零延迟——延迟的是效果的消失，不是「等动画播完再跳转」
    var tintHold by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            tintHold = true
        } else {
            delay(100)
            tintHold = false
        }
    }
    val showTint = pressed || tintHold
    val cardColor = androidx.compose.animation.animateColorAsState(
        targetValue = lerp(
            // 16%：M3 pressed 区间（8-12%）偏上——10% 在白卡相邻对比下不可辨（用户实测），
            // 18% 起信息行开始显重；暗色主题同公式自动变亮双向可辨
            scheme.surfaceContainer, scheme.onSurfaceContainer, if (showTint) 0.16f else 0f,
        ),
        // 按下/保持窗内 snap 即时显色；渐隐（效果消失）走 150 微反馈档
        animationSpec = if (showTint) {
            androidx.compose.animation.core.snap()
        } else {
            androidx.compose.animation.core.tween(Motion.MICRO)
        },
        label = "cardPress",
    ).value
    Card(
        modifier = modifier.clickable(
            interactionSource = pressSrc,
            indication = null,
            onClick = onClick,
        ),
        cornerRadius = cornerRadius,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(
            color = cardColor,
            contentColor = scheme.onSurfaceContainer,
        ),
    ) {
        content()
    }
}

/**
 * 无底信息行的按压反馈（与 PressableCard 同语言：按住染 10% 前景色）。
 * 调用方：`.background(color, shape)` + `.clickable(src, indication = null, …)` 共用同一 src。
 */
@Composable
fun rememberRowPressTint(): Pair<MutableInteractionSource, androidx.compose.ui.graphics.Color> {
    val src = remember { MutableInteractionSource() }
    val pressed = src.collectIsPressedAsState().value
    val scheme = MiuixTheme.colorScheme
    // 与 PressableCard 同步的染色保持窗（快跳必现反馈，动作零延迟）
    var tintHold by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            tintHold = true
        } else {
            delay(100)
            tintHold = false
        }
    }
    val showTint = pressed || tintHold
    val color = androidx.compose.animation.animateColorAsState(
        targetValue = lerp(
            // 与 PressableCard 同步 16%（见上注释）
            scheme.surfaceContainer, scheme.onSurfaceContainer, if (showTint) 0.16f else 0f,
        ),
        animationSpec = if (showTint) {
            androidx.compose.animation.core.snap()
        } else {
            androidx.compose.animation.core.tween(Motion.MICRO)
        },
        label = "rowPress",
    ).value
    return src to color
}

/**
 * 卡内分区线（详情头卡/添加页预览卡共用）：全宽 1dp dividerLine。
 * 节奏固定为前 Spacer(8) + 线 + 后 Spacer(6)——标记「档案信息 → 行动区」语义边界。
 */
@Composable
fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}
