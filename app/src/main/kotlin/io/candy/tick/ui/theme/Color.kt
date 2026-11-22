package io.candy.tick.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * CandyTick 全量色板 —— 全工程唯一取色来源（拆分红线：颜色零硬编码）
 * 定稿值：docs/design/design-spec.html 03 章（对比度均为 WCAG 实测）
 */

// —— Candy Mint 色阶（在用：200/600/700；其余档位无引用已裁剪）——
val Mint200 = Color(0xFFB9EDD6)
val Mint600 = Color(0xFF18936C)
val Mint700 = Color(0xFF0E7A59)

// —— 语义 Token · 浅色主题（3.2）——
// 主按钮容器：Mint700（白字实测 5.32:1 达 AA）。Mint600 白字仅 3.87:1——按钮字号
// 15-16sp 属普通文本，够不到大文本豁免线（≥18.66px 加粗），2026-11-21 审计加深一档
val LightPrimary = Mint700 // 按钮 / Switch 选中态 / 主 CTA
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryVariant = Mint700 // 链接 / 强调文字
val LightOnPrimaryVariant = LightOnPrimary
val LightPrimaryContainer = Color(0xFFD7F5E7) // 选中底 / 填充块
val LightOnPrimaryContainer = Color(0xFF0B4A37)
val LightBackground = Color(0xFFF4F6F5) // 微绿灰页面底
val LightSurface = Color(0xFFFFFFFF)
val LightTextPrimary = Color(0xFF191D1B)
val LightTextSecondary = Color(0xFF5A625E)
val LightTextTertiary = Color(0xFF646D69) // 小字号辅助文字在 surfaceContainerHigh 上仍有 4.52:1
val LightOutline = Color(0xFFE4E8E6)
val LightFeedbackOutline = Color(0xFFC9D1CD)
val LightDivider = LightOutline
val LightSurfaceHigh = Color(0xFFE9EDEB)
val LightSurfaceHighest = LightOutline
val LightOnSurfaceSecondary = Color(0xCC191D1B)
// 90% alpha：summary 对比度 2.54→4.67:1（WCAG AA 4.5 达标；60% 时浅色模式不达标，2026-11-21 审计）
val LightOnSurfaceSummary = Color(0xE65A625E)
val LightOnSurfaceActions = Color(0x665A625E)
val LightDisabledSurfaceText = Color(0xFFB9C0BC)
val LightSecondary = LightOutline // 中性控件底
val LightSecondaryVariant = Color(0xFFEDF0EE)
val LightSecondaryContainer = Color(0xFFEDF0EE) // ≠ Background，TextField 可见
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
val DarkTextTertiary = Color(0xFF919B96)
val DarkOutline = Color(0xFF2A302D)
val DarkFeedbackOutline = Color(0xFF505954) // 悬浮提示与卡面、页面底均可辨
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
val DarkFieldActionContainer = Color(0xFF49574F)
val DarkFieldActionOutline = Color(0xFF666F6A)
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

// 设置行图标底色。浅色与深色共用，白色线条的对比度均达到 3.3:1。
val ChipBlue = Color(0xFF598AEA)
val ChipTeal = Color(0xFF309D8D)
val ChipSky = Color(0xFF5590D0)
val ChipAmber = Color(0xFFB6843D)
val ChipPink = Color(0xFFD16E8B)
val ChipGreen = Color(0xFF539C71)
val ChipViolet = Color(0xFF8D7FDE)
val ChipSlate = Color(0xFF768FB0)
val ChipOrange = Color(0xFFCB7642)
val ChipSand = Color(0xFFA7884C)
val ChipPeriwinkle = Color(0xFF708AD6)

// 首字母头像：按 owner 选八种色相，渐变两端都能承托白色字母。
val AvatarPalette = listOf(
    Color(0xFFAB6305) to Color(0xFF89500C),
    Color(0xFFDC3232) to Color(0xFFAF1A1A),
    Color(0xFF8354EB) to Color(0xFF6526CC),
    Color(0xFF047CB3) to Color(0xFF075A88),
    Color(0xFF068359) to Color(0xFF056045),
    Color(0xFFBE510E) to Color(0xFF92370C),
    Color(0xFFCF2D78) to Color(0xFFAA1652),
    Color(0xFF64748B) to Color(0xFF334155),
)
