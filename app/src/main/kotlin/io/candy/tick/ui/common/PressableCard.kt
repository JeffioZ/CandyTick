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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.candy.tick.ui.common.Motion

/**
 * 可点击卡片统一按压反馈（全 App 唯一来源）：
 * - 按压 = 卡面染 10% 前景色（Material 3 pressed state layer 标准区间；主题自带
 *   surfaceContainerHigh 档实测仅 2-8% 明度差，暗色下肉眼不可辨）
 * - 80 微反馈档（快跳点击也来得及看到染色再被转场盖过）；纯色变零位移零露底——
 *   Sink 缩放会在 surface/background 色差下露出深色环，观感如「背后有色块阴影」
 *   （Miuix Card 无阴影可关）
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
    val cardColor = androidx.compose.animation.animateColorAsState(
        targetValue = lerp(
            scheme.surfaceContainer, scheme.onSurfaceContainer, if (pressed) 0.10f else 0f,
        ),
        // 80ms 到位：快跳点击（按下 ~40ms 即释放）也要在导航转场盖上来之前看到染色
        // （skill: 反馈 80-150ms 内到位；此前 150ms 在快跳下只走到 ~5%，等于没反馈）
        animationSpec = androidx.compose.animation.core.tween(Motion.PRESS),
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
    val color = androidx.compose.animation.animateColorAsState(
        targetValue = lerp(
            scheme.surfaceContainer, scheme.onSurfaceContainer, if (pressed) 0.10f else 0f,
        ),
        // 与 PressableCard 同步 80ms 到位（skill: 反馈 80-150ms 内到位）
        animationSpec = androidx.compose.animation.core.tween(Motion.PRESS),
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
