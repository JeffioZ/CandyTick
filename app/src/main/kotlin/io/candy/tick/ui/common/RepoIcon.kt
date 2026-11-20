package io.candy.tick.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.ui.theme.AvatarPalette
import io.candy.tick.ui.theme.MonoTokenStyle
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 仓库头像唯一来源（首页卡/搜索卡/预览卡/详情头卡共用）：
 * 已关联本机应用 → 真 APP 图标；未关联 → 首字母块（owner hash 8 色环，135° 渐变）。
 */
@Composable
fun RepoIconBlock(owner: String, linkedPackage: String?, size: Dp = 48.dp, corner: Dp = 12.dp) {
    rememberAppIcon(linkedPackage)?.let {
        AppIconImage(bitmap = it, size = size, corner = corner)
        return
    }
    AvatarBlock(owner = owner, size = size, corner = corner)
}

/** 已安装应用的图标（remember 缓存，PM 查询失败返回 null）——全 App 唯一来源 */
@Composable
fun rememberAppIcon(packageName: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (packageName == null) return null
    val context = LocalContext.current
    return remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toImageBitmap() }.getOrNull()
    }
}

/** TrackedRepo 便利重载 */
@Composable
fun RepoIconBlock(repo: TrackedRepo, size: Dp = 48.dp, corner: Dp = 12.dp) =
    RepoIconBlock(owner = repo.owner, linkedPackage = repo.linkedPackage, size = size, corner = corner)

/**
 * star + 计数（详情头卡/搜索卡/预览卡名称行尾共用）：12dp 星标 + 3dp + footnote1 数字。
 * 整组光学下移 [STAR_OPTICAL_NUDGE]：CenterVertically 对的是标题行盒的几何中心，
 * 行盒含上下留白而字形墨迹中心略低——纯几何居中会让整组（星+数字）看起来偏高
 * （Icon Alignment 反模式：几何居中 ≠ 视觉居中）。实机仍觉偏高就调到 1.dp，单点参数。
 */
private val STAR_OPTICAL_NUDGE = 0.5.dp

@Composable
fun StarCount(count: String, modifier: Modifier = Modifier) {
    val tint = MiuixTheme.colorScheme.onBackgroundVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.offset(y = STAR_OPTICAL_NUDGE),
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_star),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = count,
            style = MiuixTheme.textStyles.footnote1,
            color = tint,
        )
    }
}

/** owner/repo 等宽标识行（首页卡/搜索卡/预览卡/详情头卡身份区共用）：MonoTokenStyle + 灰字 */
@Composable
fun OwnerRepoLine(owner: String, repo: String) {
    Text(
        text = "$owner/$repo",
        style = MonoTokenStyle,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
    )
}

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
