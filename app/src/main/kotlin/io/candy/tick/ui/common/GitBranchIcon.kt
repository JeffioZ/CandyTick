package io.candy.tick.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

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
