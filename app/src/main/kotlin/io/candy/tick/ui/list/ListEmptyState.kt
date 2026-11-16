package io.candy.tick.ui.list

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.theme.DarkPrimary
import io.candy.tick.ui.theme.LightPrimary
import io.candy.tick.ui.theme.LocalDarkTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 首页空态：插画 + 「添加第一个仓库」CTA（引导只在空态） */
@Composable
internal fun EmptyState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    val dark = LocalDarkTheme.current
    val mint = if (dark) DarkPrimary else LightPrimary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .background(scheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EchoPing(mint = mint)
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
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = stringResource(R.string.empty_cta),
            onClick = onAdd,
        )
    }
}

/** 回声「ping」：糖珠 + 两圈涟漪扩散（Canvas 直绘，零中间合成层；仅空态非滚动路径，尊重系统动画时长缩放） */
@Composable
private fun EchoPing(mint: Color) {
    val context = LocalContext.current
    val animationsOn = remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
    val progress by if (animationsOn) {
        rememberInfiniteTransition(label = "echoPing").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing)),
            label = "echoPingProgress",
        )
    } else {
        remember { mutableStateOf(0.55f) }
    }
    val ringWidth = 2.dp
    Canvas(modifier = Modifier.size(140.dp)) {
        val center = this.center
        // 两圈涟漪：半径 18~64dp 扩散、透明度随扩散衰减
        listOf(0f, 0.5f).forEach { offset ->
            val p = (progress + offset) % 1f
            drawCircle(
                color = mint.copy(alpha = (1f - p) * 0.4f),
                radius = (18 + 46 * p).dp.toPx(),
                center = center,
                style = Stroke(width = ringWidth.toPx()),
            )
        }
        // 糖珠
        drawCircle(color = mint, radius = 13.dp.toPx(), center = center)
    }
}
