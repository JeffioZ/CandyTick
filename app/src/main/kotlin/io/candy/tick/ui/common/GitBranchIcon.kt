package io.candy.tick.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 自绘图标范围（项目约定）：除 logo / 桌面图标外，UI 图标一律用 miuix 库；
 * 本文件只保留「库空白处的功能补位」——GitBranch（GitHub 分支概念，库无此字形）
 * 与 ExternalLink（跳转网页，库的 Link 是锁链语义不符）。两者严格按库规范绘制
 * （1400 网格、117 圆端笔画、24dp），视觉与 extended 家族一致。
 * 历史注：ChevronPair 曾因库版 ExpandMore/Less viewport 不一致（1088/1122）自绘，
 * 后改由调用处显式 size 钉死解决，已退役回库图标。
 */

/**
 * git 分支字形（详情页顶栏「切换分支」）。
 * miuix 图标库无分支字形，此处按其设计语言自绘：1400 网格、圆端粗笔画
 * （宽 117 ≈ 笔重 8.4%）、实心端点圆。拓扑为 GitHub octicons 经典款：
 * 竖线自左下起点上行，顶部圆角右转，横线至右上分支头；左上拐角处一枚节点。
 * tint 由调用方注入。
 */
val GitBranchIcon: ImageVector = ImageVector.Builder(
    name = "GitBranch",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 1400f,
    viewportHeight = 1400f,
).apply {
    // 两段笔画在拐角圆点相接（octicons 结构）：竖线止于顶圆内（不穿透成“天线”），横线右接分支头
    addPath(
        pathData = addPathNodes("M350,890 L350,470 M470,350 L890,350"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 117f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
    // 端点圆 ×3（r=160）：左下起点 (350,1050)、左上拐角 (350,350)、右上分支头 (1050,350)
    addPath(
        pathData = addPathNodes(
            "M510,1050 A160,160 0 0 1 190,1050 A160,160 0 0 1 510,1050 Z " +
                "M510,350 A160,160 0 0 1 190,350 A160,160 0 0 1 510,350 Z " +
                "M1210,350 A160,160 0 0 1 890,350 A160,160 0 0 1 1210,350 Z",
        ),
        fill = SolidColor(Color.Black),
    )
}.build()

/**
 * 外部链接字形（「在浏览器打开 / 前往仓库页」）。
 * miuix 图标库的 Link 是锁链，语义是「关联」而非「跳转网页」——
 * 此处按同一设计语言自绘 external-link 经典拓扑（feather/octicons 同款）：
 * 方框右上角开口，箭头自框内射向右上。1400 网格、圆端粗笔画 117。
 * tint 由调用方注入。
 */
val ExternalLinkIcon: ImageVector = ImageVector.Builder(
    name = "ExternalLink",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 1400f,
    viewportHeight = 1400f,
).apply {
    // 框：上边左段 / 左 / 下 / 右下段（右上角留给箭头穿出）
    addPath(
        pathData = addPathNodes("M420,350 L930,350 M250,520 L250,1050 L1050,1050 L1050,690"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 117f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
    // 斜箭头 + L 形箭头头（射出框右上角）
    addPath(
        pathData = addPathNodes("M640,760 L1120,280 M790,280 L1120,280 L1120,610"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 117f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()
