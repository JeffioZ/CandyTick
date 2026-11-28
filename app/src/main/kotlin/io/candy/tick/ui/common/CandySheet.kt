package io.candy.tick.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import io.candy.tick.ui.theme.LocalDarkTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.progressiveBlur
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 根页面液态玻璃采样层（API 33+ 非空；API 29-32 为 null，自动降级为不透明表面） */
internal val LocalAppBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * 浮层内部卡片统一底色：
 * - API 33+ 液态玻璃浮层内：使用微半透层，卡片随玻璃一起透光，不再像不透明贴纸；
 * - API 29-32 不透明降级态：沿用柔和实色层（浅色 background / 深色 surfaceContainerHigh）。
 */
@Composable
internal fun sheetCardColor(): Color {
    val scheme = MiuixTheme.colorScheme
    val dark = LocalDarkTheme.current
    return if (LocalAppBackdrop.current != null) {
        if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.045f)
    } else {
        if (dark) scheme.surfaceContainerHigh else scheme.background
    }
}

/** 浮层内无底块可点项（如可视化选择器的外框）的静止底色：玻璃态透明、降级态与浮层同底 */
@Composable
internal fun sheetInteractiveBaseColor(): Color =
    if (LocalAppBackdrop.current != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer

/**
 * 浮层内部分隔线与细描边色：
 * - API 33+ 液态玻璃浮层内：浅色 `dividerLine` (#E4E8E6) 叠在半透灰底 (`Black 4.5%`) 上对比度近 1:1，
 *   改用半透明黑/白细线确保清晰又克制；
 * - API 29-32 不透明降级态：沿用 `scheme.dividerLine`。
 */
@Composable
internal fun sheetDividerColor(): Color {
    val dark = LocalDarkTheme.current
    return if (LocalAppBackdrop.current != null) {
        if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f)
    } else {
        MiuixTheme.colorScheme.dividerLine
    }
}

/** 对齐 Miuix 0.9.5 GlassTopAppBarKt.TopBarProgressiveGradient 官方衰减曲线（pow(x, 0.25)） */
private val TopBarProgressiveGradient = ProgressiveBlur.Top.copy(curve = 0.25f)

/** 对齐 Miuix 0.9.5 TopAppBarBandBrush 的 16 段 Perlin smootherstep 渐变遮罩，首尾一二阶导数归零消除马赫带 */
private fun topBarSmootherstepBrush(bg: Color, strength: Float): Brush {
    val stops = Array(17) { i ->
        val t = i / 16f
        val smooth = t * t * t * (t * (6f * t - 15f) + 10f)
        t to bg.copy(alpha = 0.9f * (1f - smooth) * strength)
    }
    return Brush.verticalGradient(colorStops = stops)
}

/**
 * 顶栏背后渐进模糊层（API 33+）：完全对齐 miuix 0.9.5 GlassTopAppBar 官方参数
 * （[top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults.ProgressiveBlurRadius] 32dp +
 * `ProgressiveBlur.Top.copy(curve = 0.25f)` + 16 段 Perlin smootherstep 遮罩）；
 * 仅在列表离开顶部（[active] = true）时平滑渐入，静止在顶部零离屏采样开销；
 * API 29-32（[backdrop] 为 null）自动降级为不透明页面底色，防止列表滚到透明顶栏下与标题文字重叠；
 * 只走 miuix-blur 引擎，不触发 glassPanel 着色器。
 */
@Composable
internal fun Modifier.topBarProgressiveBlur(
    backdrop: Backdrop?,
    active: Boolean = true,
): Modifier {
    val bg = MiuixTheme.colorScheme.background
    if (backdrop == null) return this.background(bg)
    val motionEnabled = rememberMotionEnabled()
    val strength by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = if (motionEnabled) tween(Motion.PANEL) else snap(),
        label = "topBarBlurStrength",
    )
    // drawBackdrop 常驻修饰符链（不在首次滚动时动态挂载），避免首次下滑触发布局重排掉帧
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { RectangleShape },
            effects = {
                if (strength > 0.001f) {
                    val r = top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults.ProgressiveBlurRadius.toPx() * strength
                    progressiveBlur(r, r, TopBarProgressiveGradient)
                }
            },
        )
        .then(
            if (strength > 0.001f) {
                Modifier.background(topBarSmootherstepBrush(bg, strength))
            } else {
                Modifier
            },
        )
}

/**
 * 全应用的底部弹层挂载于根 Scaffold，使用 [GlassBottomSheet] 的默认背景遮罩。
 * 支持玻璃效果时面板采样背后的页面，旧系统使用不透明面板。内容区预留导航栏空间。
 */
@Composable
internal fun CandySheet(
    show: Boolean,
    onDismissRequest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    GlassBottomSheet(
        show = show,
        backdrop = LocalAppBackdrop.current,
        modifier = modifier,
        renderInRootScaffold = true,
        title = title,
        startAction = startAction,
        containerColor = MiuixTheme.colorScheme.surfaceContainer,
        allowDismiss = onDismissRequest != null,
        onDismissRequest = onDismissRequest ?: {},
        onDismissFinished = onDismissFinished,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
        ) {
            content()
        }
    }
}
