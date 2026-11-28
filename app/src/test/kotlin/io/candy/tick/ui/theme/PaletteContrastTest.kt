package io.candy.tick.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
    @Test fun whiteSettingsIconsStayReadableOnColoredChips() {
        val backgrounds = listOf(
            ChipBlue, ChipTeal, ChipPink, ChipGreen,
            ChipViolet, ChipSlate, ChipOrange,
        )
        for (background in backgrounds) assertContrast(Color.White, background, 3f)
    }

    @Test fun lightFeedbackTextActionsAndStatusIconsStayReadable() {
        // Decorative edge against the pill fill, separate from text and action contrast.
        assertContrast(LightFeedbackOutline, LightSurface, 1.5f)
        assertContrast(LightTextPrimary, LightSurface, 4.5f)
        assertContrast(LightPrimaryVariant, LightSurface, 4.5f)
        assertContrast(LightOnErrorContainer, LightSurface, 3f)
    }

    @Test fun fieldStrokesStayVisibleAgainstTheirSurfaces() {
        // 未聚焦输入框描边（Theme outline 槽 → LightFieldBorder / DarkFieldBorder）对实际字段底色和页面底。
        // 两套主题按感知对比度对齐（APCA 区分深浅极性），所以 WCAG 比值不同：
        // 浅色取「淡而可辨」的下限（原 LightOutline 仅 1.14:1 几乎不可见），深色对页面底满足 3:1
        assertContrast(LightFieldBorder, LightSecondaryContainer, 1.3f)
        assertContrast(LightFieldBorder, LightBackground, 1.3f)
        assertContrast(DarkFieldBorder, DarkSecondaryContainer, 2.5f)
        assertContrast(DarkFieldBorder, DarkBackground, 3f)
    }

    @Test fun darkFeedbackTextActionsAndStatusIconsStayReadable() {
        assertContrast(DarkFeedbackOutline, DarkSurfaceHigh, 2.2f)
        assertContrast(DarkTextPrimary, DarkSurfaceHigh, 4.5f)
        assertContrast(DarkPrimaryVariant, DarkSurfaceHigh, 4.5f)
        assertContrast(DarkOnErrorContainer, DarkSurfaceHigh, 3f)
    }

    @Test fun smallSupportingTextStaysReadableOnItsSurfaces() {
        assertContrast(LightTextSecondary, LightDisabledPrimary, 4.5f)
        assertContrast(DarkTextSecondary, DarkDisabledPrimary, 4.5f)
        for (background in listOf(LightSurface, LightSurfaceHigh, LightSecondaryVariant)) {
            assertContrast(LightTextTertiary, background, 4.5f)
        }
        for (background in listOf(DarkSurface, DarkSurfaceHigh, DarkSecondaryVariant)) {
            assertContrast(DarkTextTertiary, background, 4.5f)
        }
    }

    @Test fun repositoryInitialsStayReadableAcrossAvatarGradients() {
        for ((start, end) in AvatarPalette) {
            assertContrast(Color.White, start, 4.5f)
            assertContrast(Color.White, end, 4.5f)
        }
    }

    private fun assertContrast(a: Color, b: Color, minimum: Float) {
        val first = a.luminance()
        val second = b.luminance()
        val ratio = (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
        assertTrue("Expected at least $minimum, got $ratio", ratio >= minimum)
    }
}
