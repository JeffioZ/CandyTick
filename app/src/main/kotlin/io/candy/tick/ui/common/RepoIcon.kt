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
 * 卡片/详情共用图标：已关联本机应用 → 真 APP 图标；
 * 未关联 → 首字母块（owner 名 hash 8 色环，135° 渐变统一配方）。
 */
@Composable
fun RepoIconBlock(repo: TrackedRepo, size: Dp = 48.dp, corner: Dp = 12.dp) {
    val context = LocalContext.current
    val linked = repo.linkedPackage
    if (linked != null) {
        val bitmap = remember(linked) {
            runCatching { context.packageManager.getApplicationIcon(linked).toImageBitmap() }.getOrNull()
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(size).clip(RoundedCornerShape(corner)),
            )
            return
        }
    }
    AvatarBlock(owner = repo.owner, size = size)
}

@Composable
private fun AvatarBlock(owner: String, size: Dp) {
    val (start, end) = remember(owner) { avatarGradient(owner) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
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
