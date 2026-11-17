package io.candy.tick.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 纯 V 形箭头对（卡片展开/收起指示）。miuix 的 ExpandMore/Less 是支架形且
 * viewport 不一致（1088 vs 1122，切换有尺寸跳）；此处按 GitBranchIcon 同规范
 * 自绘：1400 网格、笔宽 117、圆端圆角，两形除方向外零差异元素。
 */
val ChevronDownIcon: ImageVector = ImageVector.Builder(
    name = "ChevronDown",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 1400f,
    viewportHeight = 1400f,
).apply {
    addPath(
        pathData = addPathNodes("M300,560 L700,960 L1100,560"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 117f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()

val ChevronUpIcon: ImageVector = ImageVector.Builder(
    name = "ChevronUp",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 1400f,
    viewportHeight = 1400f,
).apply {
    addPath(
        pathData = addPathNodes("M300,960 L700,560 L1100,960"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 117f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()
