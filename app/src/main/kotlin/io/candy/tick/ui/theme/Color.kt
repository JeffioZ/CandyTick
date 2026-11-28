package io.candy.tick.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * CandyTick 全量色板 —— 全工程唯一取色来源（拆分红线：颜色零硬编码）
 * 定稿值：docs/design/design-spec.html 03 章（对比度均为 WCAG 实测）
 */

// —— Candy Mint 色阶（在用：200/600/700；其余档位无引用已裁剪）——
val Mint200 = Color(0xFFB9EDD6)
val Mint600 = Color(0xFF16A075)
val Mint700 = Color(0xFF0E805E)

// —— 语义 Token · 浅色主题（3.2）——
// 主按钮 / Switch / 进度条采用更鲜活通透的 Mint600（#16A075）；文字、链接与小图标统一走
// LightPrimaryVariant（Mint700 #0E805E，对白底 4.92:1、对页面底 4.53:1，守住 WCAG AA）
val LightPrimary = Mint600 // 按钮 / Switch 选中态 / 主 CTA
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryVariant = Mint700 // 链接 / 强调文字 / 小图标
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
// 输入框未聚焦描边（outline 槽）。规则：两套主题的感知对比度相同（APCA Lc ≈ 20，APCA 区分深浅极性）。
// WCAG 比值不分极性，同为 3:1 时浅色看起来重得多，所以浅色不按 WCAG 比值取，而是对齐深色的观感
val LightFieldBorder = LightFeedbackOutline // 对页面底 1.43:1，低于 WCAG 1.4.11 的 3:1，有意取舍
// 输入框尾部按钮的图标色：浅色是无底块的次要文字；深色见 DarkFieldActionTint
val LightFieldActionTint = LightTextSecondary
val DarkFieldBorder = Color(0xFF59625D) // 对页面底 3.03:1，WCAG 1.4.11 达标的最淡取值
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
val LightDisabledButton = Color(0xFFECEFED) // 柔和淡灰禁用底（弱于正常次要底 #E4E8E6，页面/卡片/玻璃浮层均有轻轮廓）
val LightDisabledOnButton = Color(0xFF9AA39F) // 对禁用底 ≈2.25:1：一眼可辨禁用且文字可读
val LightDisabledSecondary = LightDisabledButton
val LightWindowDimming = Color(0x4D000000)
val LightSliderKeyPoint = Color(0x4D16A075)
val LightSliderKeyPointForeground = LightPrimary
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
// 深色输入框尾部图标提亮到正文色的 80%
val DarkFieldActionTint = DarkOnSurfaceSecondary
val DarkTertiaryContainer = Color(0xFF0C3D2E)
val DarkError = Color(0xFFF87171)
val DarkOnError = Color(0xFF450A0A)
val DarkErrorContainer = Color(0xFF35201F)
val DarkOnErrorContainer = Color(0xFFF87171)
val DarkDisabledPrimary = Color(0xFF1E4034)
val DarkDisabledOnPrimary = DarkTextTertiary
val DarkDisabledButton = Color(0xFF1D2220) // 更沉的中性深灰禁用底（弱于正常次要底 #2A302D）
val DarkDisabledOnButton = Color(0xFF5C6561) // 对禁用底 ≈2.55:1：与浅色感知灰显对称
val DarkDisabledSecondary = DarkDisabledButton
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

// 设置行图标底色（7 组语义色，去除近重复色相）。浅色与深色共用，白色线条的对比度均达到 3.3:1。
val ChipBlue = Color(0xFF598AEA)
val ChipTeal = Color(0xFF309D8D)
val ChipPink = Color(0xFFD16E8B)
val ChipGreen = Color(0xFF539C71)
val ChipViolet = Color(0xFF8D7FDE)
val ChipSlate = Color(0xFF768FB0)
val ChipOrange = Color(0xFFCB7642)

// 仓库字母头像：按 owner/repo 选 8 组现代清透色相，渐变两端对白字均 ≥ 4.5:1（WCAG AA）。
val AvatarPalette = listOf(
    Color(0xFF0E7A59) to Color(0xFF095D43), // 薄荷翠
    Color(0xFF1D6FD1) to Color(0xFF1553A6), // 晴海蓝
    Color(0xFF4F5AD6) to Color(0xFF3A42B0), // 靛蓝
    Color(0xFF7A4ED9) to Color(0xFF5D34B8), // 柔紫
    Color(0xFFC2366B) to Color(0xFF9B2452), // 莓红
    Color(0xFF0F7685) to Color(0xFF0A5966), // 青瓷
    Color(0xFF2E7D4F) to Color(0xFF1F5E3A), // 松绿
    Color(0xFF54687D) to Color(0xFF3B4C5E), // 蓝灰
)
