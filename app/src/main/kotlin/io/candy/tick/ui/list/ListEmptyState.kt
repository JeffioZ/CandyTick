package io.candy.tick.ui.list

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.rememberMotionEnabled
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 首页空态：插画 + 「添加第一个仓库」CTA + 弱化的导入入口（引导只在空态） */
@Composable
internal fun EmptyState(onAdd: () -> Unit, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .background(scheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 光学居中：上 1 : 下 1.22（下方留白稍多）——整块中心落在内容区约 45%，
        // 空态设计的通用光学中心（几何居中略偏上），按比例恒定、不随屏幕长短漂移。
        // 注：上方 spacer 权重越大内容越靠下（此前几轮方向调反的教训）
        Spacer(Modifier.weight(1f))
        EchoPing(mint = scheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.empty_title),
            style = MiuixTheme.textStyles.title4,
            color = scheme.onBackground,
        )
        Text(
            text = stringResource(R.string.empty_subtitle),
            style = MiuixTheme.textStyles.body2,
            color = scheme.onBackgroundVariant,
            // 居中换行：窄屏两行时避免「孤字挂左」的失衡断行
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = stringResource(R.string.empty_cta),
            onClick = onAdd,
        )
        // 弱化的次级入口（CTA 之下、同色系次级文字）：主行动仍是「添加第一个仓库」
        // LinkText 自带 48dp 热区（文字上方约 11dp），配 4dp 间隔后与主按钮净距约 15dp
        Spacer(Modifier.height(4.dp))
        io.candy.tick.ui.common.LinkText(
            text = stringResource(R.string.empty_import),
            onClick = onImport,
            alignment = Alignment.Center,
        )
        Spacer(Modifier.weight(1.22f))
    }
}

/** 回声「ping」：糖珠 + 两圈涟漪扩散（Canvas 直绘，零中间合成层；仅空态非滚动路径，尊重系统动画时长缩放） */
@Composable
private fun EchoPing(mint: Color) {
    // 与 LoadingDots 同一开关（rememberMotionEnabled）：动画时长缩放=0 时涟漪静态化
    val animationsOn = rememberMotionEnabled()
    val phase = if (animationsOn) {
        rememberInfiniteTransition(label = "echoPing").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing)),
            label = "echoPingProgress",
        )
    } else null
    val ringWidth = 2.dp
    Canvas(modifier = Modifier.size(140.dp)) {
        val center = this.center
        // 两圈涟漪：半径 18~64dp 扩散、透明度随扩散衰减
        val progress = phase?.value ?: 0.55f
        val stroke = Stroke(width = ringWidth.toPx())
        repeat(2) { ring ->
            val p = (progress + ring * 0.5f) % 1f
            drawCircle(
                color = mint.copy(alpha = (1f - p) * 0.4f),
                radius = (18 + 46 * p).dp.toPx(),
                center = center,
                style = stroke,
            )
        }
        // 糖珠
        drawCircle(color = mint, radius = 13.dp.toPx(), center = center)
    }
}
