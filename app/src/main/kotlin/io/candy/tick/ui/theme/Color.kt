package io.candy.tick.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * CandyTick 全量色板 —— 全工程唯一取色来源（拆分红线：颜色零硬编码）
 * 定稿值：docs/design/design-spec.html 03 章（对比度均为 WCAG 实测）
 */

// —— Candy Mint 色阶（3.1，50–900）——
val Mint50 = Color(0xFFF0FBF6)
val Mint100 = Color(0xFFDCF6EA)
val Mint200 = Color(0xFFB9EDD6)
val Mint300 = Color(0xFF8BDEBB)
val Mint400 = Color(0xFF55CFA0)
val Mint500 = Color(0xFF2EB488)
val Mint600 = Color(0xFF18936C)
val Mint700 = Color(0xFF0E7A59)
val Mint800 = Color(0xFF0B5F49)
val Mint900 = Color(0xFF083F30)

// —— 语义 Token · 浅色主题（3.2）——
val LightPrimary = Mint600 // 按钮 / FAB / 选中态
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryVariant = Mint700 // 链接 / 强调文字
val LightOnPrimaryVariant = LightOnPrimary
val LightPrimaryContainer = Color(0xFFD7F5E7) // 选中底 / 填充块
val LightOnPrimaryContainer = Color(0xFF0B4A37)
val LightBackground = Color(0xFFF4F6F5) // 微绿灰页面底
val LightSurface = Color(0xFFFFFFFF)
val LightTextPrimary = Color(0xFF191D1B)
val LightTextSecondary = Color(0xFF5A625E)
val LightTextTertiary = Color(0xFF8A938F) // 仅 ≥13sp 辅助注
val LightOutline = Color(0xFFE4E8E6)
val LightDivider = LightOutline
val LightSurfaceHigh = Color(0xFFE9EDEB)
val LightSurfaceHighest = LightOutline
val LightOnSurfaceSecondary = Color(0xCC191D1B)
val LightOnSurfaceSummary = Color(0x995A625E)
val LightOnSurfaceActions = Color(0x665A625E)
val LightDisabledSurfaceText = Color(0xFFB9C0BC)
val LightSecondary = LightOutline // 中性控件底
val LightSecondaryVariant = Color(0xFFEDF0EE)
val LightSecondaryContainer = LightBackground
val LightTertiaryContainer = Color(0xFFE3F4EC)
val LightError = Color(0xFFDC2626)
val LightOnError = LightOnPrimary
val LightErrorContainer = Color(0xFFF8D4D4)
val LightOnErrorContainer = Color(0xFFB91C1C)
val LightDisabledPrimary = Mint200
val LightDisabledOnPrimary = LightTextTertiary
val LightDisabledSecondary = LightSecondaryVariant
val LightWindowDimming = Color(0x4D000000)
val LightSliderKeyPoint = Color(0x4D18936C)
val LightSliderKeyPointForeground = Mint600
val LightSliderBackground = Color(0x0F000000)

// —— 语义 Token · 深色主题（3.3，整体提亮一档）——
val DarkPrimary = Color(0xFF5BD6A4)
val DarkOnPrimary = Color(0xFF073325) // 亮薄荷底 + 近黑薄荷字
val DarkPrimaryVariant = Color(0xFF7CE0BC)
val DarkOnPrimaryVariant = DarkOnPrimary
val DarkPrimaryContainer = Color(0xFF0E4A37)
val DarkOnPrimaryContainer = Color(0xFFB9F0DA)
val DarkBackground = Color(0xFF0D100E)
val DarkSurface = Color(0xFF161A18)
val DarkTextPrimary = Color(0xFFE6EAE8)
val DarkTextSecondary = Color(0xFFA4ACA8)
val DarkTextTertiary = Color(0xFF6E7773)
val DarkOutline = Color(0xFF2A302D)
val DarkDivider = Color(0xFF232825)
val DarkSurfaceHigh = Color(0xFF1C201E)
val DarkSurfaceHighest = DarkDivider
val DarkOnSurfaceSecondary = Color(0xCCE6EAE8)
val DarkOnSurfaceSummary = Color(0x80E6EAE8)
val DarkOnSurfaceActions = Color(0x66E6EAE8)
val DarkDisabledSurfaceText = Color(0xFF4A524E)
val DarkSecondary = DarkOutline
val DarkSecondaryVariant = DarkDivider
val DarkSecondaryContainer = Color(0xFF1C211F)
val DarkTertiaryContainer = Color(0xFF0C3D2E)
val DarkError = Color(0xFFF87171)
val DarkOnError = Color(0xFF450A0A)
val DarkErrorContainer = Color(0xFF35201F)
val DarkOnErrorContainer = Color(0xFFF87171)
val DarkDisabledPrimary = Color(0xFF1E4034)
val DarkDisabledOnPrimary = DarkTextTertiary
val DarkDisabledSecondary = DarkSecondaryVariant
val DarkWindowDimming = Color(0x99000000)
val DarkSliderKeyPoint = Color(0x4D5BD6A4)
val DarkSliderKeyPointForeground = DarkPrimary
val DarkSliderBackground = Color(0x26FFFFFF)

// —— 状态五色（3.4；bar = 色条/圆点，badgeContainer/badgeText = 徽章容器配色）——
data class StatusColors(val bar: Color, val badgeContainer: Color, val badgeText: Color)

val StatusActiveLight = StatusColors(Color(0xFF22C55E), Color(0xFFD9F5E4), Color(0xFF116D36)) // ≤30 天
val StatusModerateLight = StatusColors(Color(0xFFEAB308), Color(0xFFFAEBBC), Color(0xFF854D0E)) // ≤180 天
val StatusLowLight = StatusColors(Color(0xFFF97316), Color(0xFFF8DCBF), Color(0xFF9C3D03)) // ≤365 天
val StatusAbandonedLight = StatusColors(Color(0xFFEF4444), Color(0xFFF8D4D4), Color(0xFFB91C1C)) // >365 天
val StatusArchivedLight = StatusColors(Color(0xFF9E9E9E), Color(0xFFE2E9E5), Color(0xFF525C57)) // archived
val StatusActiveDark = StatusColors(Color(0xFF4ADE80), Color(0xFF1A2C22), Color(0xFF4ADE80))
val StatusModerateDark = StatusColors(Color(0xFFFACC15), Color(0xFF33291A), Color(0xFFFACC15))
val StatusLowDark = StatusColors(Color(0xFFFB923C), Color(0xFF32231A), Color(0xFFFB923C))
val StatusAbandonedDark = StatusColors(Color(0xFFF87171), Color(0xFF35201F), Color(0xFFF87171))
val StatusArchivedDark = StatusColors(Color(0xFFB0B0B0), Color(0xFF232825), Color(0xFFA7B3AD))

// —— 设置行彩色芯片（LibChecker 风格：实色圆角方块 + 白色 glyph）——
val ChipBlue = Color(0xFF5B8DEF)
val ChipTeal = Color(0xFF38B9A6)
val ChipSky = Color(0xFF64A9F5)
val ChipAmber = Color(0xFFF6B352)
val ChipPink = Color(0xFFF07FA0)
val ChipGreen = Color(0xFF66C18C)
val ChipViolet = Color(0xFF9B8CF4)
val ChipMint = Color(0xFF2EB488)
val ChipSlate = Color(0xFF7A93B5)
val ChipOrange = Color(0xFFEF8B4E)
val ChipSand = Color(0xFFC9A45C)
val ChipPeriwinkle = Color(0xFF7E9Bf0)

// —— 首字母头像 8 色环（135° 双色渐变，owner hash 取色）——
val AvatarPalette = listOf(
    Color(0xFFF59E0B) to Color(0xFFD97706),
    Color(0xFFEF4444) to Color(0xFFB91C1C),
    Color(0xFF8B5CF6) to Color(0xFF6D28D9),
    Color(0xFF0EA5E9) to Color(0xFF0369A1),
    Color(0xFF10B981) to Color(0xFF047857),
    Color(0xFFF97316) to Color(0xFFC2410C),
    Color(0xFFEC4899) to Color(0xFFBE185D),
    Color(0xFF64748B) to Color(0xFF334155),
)
