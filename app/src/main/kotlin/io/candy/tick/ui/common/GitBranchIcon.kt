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
 * 完整方框（左上角闭合），右上边收短留出开口，箭头 45° 穿出指向右上。
 * 几何防粘连：箭头头（y200）与框上边（y330）的 x 区间不重叠；
 * 斜线在框上边高度处已越过其右端（x880），从开口区穿出不与线相交。
 * 元素密度高于 GitBranch，笔画收细到 96（≈7%）防黑块。
 * tint 由调用方注入。
 */
val ExternalLinkIcon: ImageVector = ImageVector.Builder(
    name = "ExternalLink",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 1400f,
    viewportHeight = 1400f,
).apply {
    // 框：上边（右端收短留出箭头开口）/ 左 / 下 / 右下段，一笔连成（左上角闭合）
    addPath(
        pathData = addPathNodes("M880,330 L280,330 L280,1070 L1070,1070 L1070,720"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 96f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
    // 斜箭头（45°，尾在框内、头在框外）+ L 形箭头头
    addPath(
        pathData = addPathNodes("M620,760 L1180,200 M1010,200 L1180,200 L1180,370"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 96f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()
