package io.candy.tick.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.candy.tick.data.model.ThemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 当前生效的主题是否深色（状态色深浅变体等消费方使用） */
val LocalDarkTheme = staticCompositionLocalOf { false }

// Candy Mint 双色板：Miuix 53 槽全量映射，不残留库默认蓝
val CandyLightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryVariant = LightPrimaryVariant,
    onPrimaryVariant = LightOnPrimaryVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    disabledPrimary = LightDisabledPrimary,
    disabledOnPrimary = LightDisabledOnPrimary,
    disabledPrimaryButton = LightDisabledPrimary,
    disabledOnPrimaryButton = LightOnPrimary,
    disabledPrimarySlider = LightDisabledPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightTextPrimary,
    secondaryVariant = LightSecondaryVariant,
    onSecondaryVariant = LightTextSecondary,
    disabledSecondary = LightDisabledSecondary,
    disabledOnSecondary = LightDisabledSurfaceText,
    disabledSecondaryVariant = LightDisabledSecondary,
    disabledOnSecondaryVariant = LightTextTertiary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightTextSecondary,
    secondaryContainerVariant = LightSecondaryVariant,
    onSecondaryContainerVariant = LightTextTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightPrimaryVariant,
    tertiaryContainerVariant = LightTertiaryContainer,
    background = LightBackground,
    onBackground = LightTextPrimary,
    onBackgroundVariant = LightTextSecondary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurface,
    onSurfaceSecondary = LightOnSurfaceSecondary,
    onSurfaceVariantSummary = LightOnSurfaceSummary,
    onSurfaceVariantActions = LightOnSurfaceActions,
    disabledOnSurface = LightDisabledSurfaceText,
    surfaceContainer = LightSurface,
    onSurfaceContainer = LightTextPrimary,
    onSurfaceContainerVariant = LightTextTertiary,
    surfaceContainerHigh = LightSurfaceHigh,
    onSurfaceContainerHigh = LightTextTertiary,
    surfaceContainerHighest = LightSurfaceHighest,
    onSurfaceContainerHighest = LightTextPrimary,
    outline = LightOutline,
    dividerLine = LightDivider,
    windowDimming = LightWindowDimming,
    sliderKeyPoint = LightSliderKeyPoint,
    sliderKeyPointForeground = LightSliderKeyPointForeground,
    sliderBackground = LightSliderBackground,
)

val CandyDarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryVariant = DarkPrimaryVariant,
    onPrimaryVariant = DarkOnPrimaryVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    disabledPrimary = DarkDisabledPrimary,
    disabledOnPrimary = DarkDisabledOnPrimary,
    disabledPrimaryButton = DarkDisabledPrimary,
    disabledOnPrimaryButton = DarkDisabledOnPrimary,
    disabledPrimarySlider = DarkDisabledPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkTextPrimary,
    secondaryVariant = DarkSecondaryVariant,
    onSecondaryVariant = DarkTextSecondary,
    disabledSecondary = DarkDisabledSecondary,
    disabledOnSecondary = DarkDisabledSurfaceText,
    disabledSecondaryVariant = DarkDisabledSecondary,
    disabledOnSecondaryVariant = DarkTextTertiary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkTextSecondary,
    secondaryContainerVariant = DarkSecondaryVariant,
    onSecondaryContainerVariant = DarkTextTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkPrimaryVariant,
    tertiaryContainerVariant = DarkTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    onBackgroundVariant = DarkTextSecondary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkBackground,
    onSurfaceSecondary = DarkOnSurfaceSecondary,
    onSurfaceVariantSummary = DarkOnSurfaceSummary,
    onSurfaceVariantActions = DarkOnSurfaceActions,
    disabledOnSurface = DarkDisabledSurfaceText,
    surfaceContainer = DarkSurface,
    onSurfaceContainer = DarkTextPrimary,
    onSurfaceContainerVariant = DarkTextTertiary,
    surfaceContainerHigh = DarkSurfaceHigh,
    onSurfaceContainerHigh = DarkTextTertiary,
    surfaceContainerHighest = DarkSurfaceHighest,
    onSurfaceContainerHighest = DarkTextPrimary,
    outline = DarkOutline,
    dividerLine = DarkDivider,
    windowDimming = DarkWindowDimming,
    sliderKeyPoint = DarkSliderKeyPoint,
    sliderKeyPointForeground = DarkSliderKeyPointForeground,
    sliderBackground = DarkSliderBackground,
)

/** CandyTick 根主题：三档深浅 + Miuix + edge-to-edge 系统栏对比度 */
@Composable
fun CandyTickTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) CandyDarkColors else CandyLightColors
    MiuixTheme(colors = colors) {
        // 静态 MiuixTheme 不提供 LocalContentColor，这里按底色补上
        CompositionLocalProvider(
            LocalContentColor provides colors.onBackground,
            LocalDarkTheme provides dark,
        ) {
            SystemBars(dark = dark)
            content()
        }
    }
}

/** edge-to-edge：窗口不内缩 + 按主题档切换系统栏图标明暗 */
@Composable
private fun SystemBars(dark: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, dark) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
        onDispose {}
    }
}
