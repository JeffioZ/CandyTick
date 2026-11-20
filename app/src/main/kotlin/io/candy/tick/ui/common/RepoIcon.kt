package io.candy.tick.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.ui.theme.AvatarPalette
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 仓库头像唯一来源（首页卡/搜索卡/预览卡/详情头卡共用）：
 * 已关联本机应用 → 真 APP 图标；未关联 → 首字母块（owner hash 8 色环，135° 渐变）。
 */
@Composable
fun RepoIconBlock(owner: String, linkedPackage: String?, size: Dp = 48.dp, corner: Dp = 12.dp) {
    val context = LocalContext.current
    if (linkedPackage != null) {
        val bitmap = remember(linkedPackage) {
            runCatching { context.packageManager.getApplicationIcon(linkedPackage).toImageBitmap() }.getOrNull()
        }
        if (bitmap != null) {
            AppIconImage(bitmap = bitmap, size = size, corner = corner)
            return
        }
    }
    AvatarBlock(owner = owner, size = size, corner = corner)
}

/** TrackedRepo 便利重载 */
@Composable
fun RepoIconBlock(repo: TrackedRepo, size: Dp = 48.dp, corner: Dp = 12.dp) =
    RepoIconBlock(owner = repo.owner, linkedPackage = repo.linkedPackage, size = size, corner = corner)

@Composable
private fun AvatarBlock(owner: String, size: Dp, corner: Dp) {
    val (start, end) = remember(owner) { avatarGradient(owner) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner)) // 圆角随调用统一（此前 size/3.2 与外部固定值微差）
            .background(
                Brush.linearGradient(
                    listOf(start, end),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = owner.firstOrNull()?.uppercase() ?: "?",
            style = MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
    }
}

private fun avatarGradient(owner: String): Pair<Color, Color> {
    val hash = abs(owner.hashCode())
    return AvatarPalette[hash % AvatarPalette.size]
}
