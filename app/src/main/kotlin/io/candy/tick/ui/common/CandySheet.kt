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

/**
 * 顶栏背后渐进模糊层（API 33+）：对齐 miuix GlassTopAppBarDefaults 32dp 半径；
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
    val dark = LocalDarkTheme.current
    val motionEnabled = rememberMotionEnabled()
    val strength by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = if (motionEnabled) tween(Motion.PANEL) else snap(),
        label = "topBarBlurStrength",
    )
    val topAlpha = 0.84f * strength
    val midAlpha = (if (dark) 0.44f else 0.42f) * strength
    // drawBackdrop 常驻修饰符链（不在首次滚动时动态挂载），避免首次下滑触发布局重排掉帧
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { RectangleShape },
            effects = {
                if (strength > 0.001f) {
                    val r = 32.dp.toPx() * strength
                    progressiveBlur(r, r, ProgressiveBlur.Top)
                }
            },
        )
        .then(
            if (strength > 0.001f) {
                Modifier.background(
                    Brush.verticalGradient(
                        0f to bg.copy(alpha = topAlpha),
                        0.65f to bg.copy(alpha = midAlpha),
                        1f to Color.Transparent,
                    ),
                )
            } else {
                Modifier
            },
        )
}

/**
 * 全 App 底部弹层唯一来源：miuix 0.9.5 [GlassBottomSheet]（挂载于根 Scaffold）+ 导航栏底部内边距。
 *
 * - API 33+：实时采样背后页面呈现 HyperOS 液态玻璃质感 + SheetMotion 动效；遮罩调轻
 *   （浅 20% / 深 32%）让深色模式也能清晰透出底层画面折射层次；
 * - API 29-32：backdrop 为 null，自动降级为 `surfaceContainer` 不透明表面与原 `windowDimming`；
 * - 导航栏内边距由内容区自补，避免末行与底部按钮伸到系统导航栏之下。
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
    val scheme = MiuixTheme.colorScheme
    val dark = LocalDarkTheme.current
    val backdrop = LocalAppBackdrop.current
    val dimColor = if (backdrop != null) {
        if (dark) Color(0x52000000) else Color(0x33000000)
    } else {
        scheme.windowDimming
    }
    GlassBottomSheet(
        show = show,
        backdrop = backdrop,
        modifier = modifier,
        renderInRootScaffold = true,
        title = title,
        startAction = startAction,
        containerColor = scheme.surfaceContainer,
        dimColor = dimColor,
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
