package io.candy.tick.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.candy.tick.R
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.ui.theme.AvatarPalette
import io.candy.tick.ui.theme.MonoTokenStyle
import io.candy.tick.util.CountFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 仓库头像唯一来源（首页卡/搜索卡/预览卡/详情头卡共用）：
 * 已关联本机应用 → 真 APP 图标；未关联 → 智能双字母块（owner/repo 哈希 8 色环，135° 清新渐变）。
 */

/** 图标解码上限 = 全应用最大展示槽（LinkedRepoAvatar 的 56dp）：所有槽共用一份缓存，
 *  解码不随槽位分裂（同应用出现两处只存一份位图）；新增更大槽位须同步改这里，
 *  否则位图被拉伸发虚 */
private val IconDecodeCeiling = 56.dp

@Composable
fun RepoIconBlock(repo: TrackedRepo, installedVersion: String?) {
    val pkg = repo.linkedPackage
    RepoAvatar(
        owner = repo.owner,
        repo = repo.repo,
        label = repo.noteName?.takeIf { it.isNotBlank() } ?: repo.repo,
        icon = repoAvatarIcon(pkg, rememberAppIconResult(pkg, refreshKey = installedVersion)),
        size = 48.dp,
        corner = 12.dp,
    )
}

@Composable
internal fun RepoAvatar(
    owner: String,
    repo: String,
    icon: AppIconResult?,
    size: Dp,
    corner: Dp,
    label: String = repo,
) {
    when {
        icon == null -> Box(
            Modifier.size(size).background(MiuixTheme.colorScheme.secondaryVariant, RoundedCornerShape(corner)),
        )
        icon.bitmap != null -> AppIconImage(bitmap = icon.bitmap, size = size, corner = corner)
        else -> AvatarBlock(colorSeed = "$owner/$repo", label = label, size = size, corner = corner)
    }
}

@Composable
internal fun LinkedRepoAvatar(
    owner: String,
    repo: String,
    icon: AppIconResult?,
    label: String = repo,
) {
    if (rememberMotionEnabled()) {
        Crossfade(targetState = icon, animationSpec = tween(Motion.PHASE), label = "linkedAvatar") {
            RepoAvatar(owner = owner, repo = repo, icon = it, size = IconDecodeCeiling, corner = 14.dp, label = label)
        }
    } else {
        RepoAvatar(owner = owner, repo = repo, icon = icon, size = IconDecodeCeiling, corner = 14.dp, label = label)
    }
}

/** A completed lookup may have no icon if the app has been uninstalled. */
internal data class AppIconResult(val bitmap: ImageBitmap?, val lastUpdateTime: Long? = null)

private val ownerAvatar = AppIconResult(null)

/**
 * 进程级图标缓存：可见行随滚动反复进出组合，PM 解码不能每次重来。
 * key 含 lastUpdateTime——应用更新/重装后自动失效；8MB 字节限额（56dp×3x ARGB ≈ 113KB/枚）。
 */
private val iconCache = object : android.util.LruCache<String, AppIconResult>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: AppIconResult): Int =
        value.bitmap?.let { it.width * it.height * 4 } ?: 0
}

/** Null reserves the avatar slot while its identity or bitmap is still being resolved. */
internal fun repoAvatarIcon(packageName: String?, result: AppIconResult?, matching: Boolean = false): AppIconResult? =
    if (packageName == null && !matching) ownerAvatar else result

/** 可见行才查询图标；包切换时先清空上一应用的位图。 */
@Composable
fun rememberAppIcon(packageName: String?): ImageBitmap? = rememberAppIconResult(packageName)?.bitmap

@Composable
internal fun rememberAppIconResult(
    packageName: String?,
    initial: AppIconResult? = null,
    refreshKey: Any? = null,
): AppIconResult? {
    if (packageName == null) return null
    val context = LocalContext.current
    val iconSizePx = with(LocalDensity.current) { IconDecodeCeiling.roundToPx().coerceAtLeast(1) }
    return key(context, packageName, iconSizePx) {
        produceState(initial, refreshKey) {
            val previous = value
            value = withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val info = pm.getPackageInfo(packageName, 0)
                    if (previous?.bitmap != null && previous.lastUpdateTime == info.lastUpdateTime) {
                        previous
                    } else {
                        val cacheKey = "$packageName@${info.lastUpdateTime}@$iconSizePx"
                        iconCache.get(cacheKey) ?: AppIconResult(
                            info.applicationInfo?.loadIcon(pm)?.toImageBitmap(iconSizePx),
                            info.lastUpdateTime,
                        ).also { if (it.bitmap != null) iconCache.put(cacheKey, it) } // 空位图不入缓存：sizeOf=0 的条目永不被淘汰
                    }
                } catch (_: Exception) {
                    ownerAvatar
                }
            }
        }.value
    }
}

/**
 * star + 计数（详情头卡/搜索卡/预览卡名称行尾共用）：12dp 星标 + 3dp + footnote1 数字。
 * 整组光学下移 [STAR_OPTICAL_NUDGE]：CenterVertically 对的是标题行盒的几何中心，
 * 行盒含上下留白而字形墨迹中心略低——纯几何居中会让整组（星+数字）看起来偏高
 * （Icon Alignment 反模式：几何居中 ≠ 视觉居中）。实机仍觉偏高就调到 1.dp，单点参数。
 */
private val STAR_OPTICAL_NUDGE = 0.5.dp

@Composable
private fun StarCount(count: String, modifier: Modifier = Modifier) {
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

/** The badge shares the title line. The path can use all the space beside the avatar. */
@Composable
internal fun RepoIdentityHeader(
    owner: String,
    repo: String,
    title: String = repo,
    stars: Int? = null,
    titleMaxLines: Int = 1,
    badge: (@Composable () -> Unit)? = null,
    avatar: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        avatar()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.title4,
                        color = MiuixTheme.colorScheme.onBackground,
                        maxLines = titleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (stars != null) {
                        Spacer(Modifier.width(8.dp))
                        StarCount(CountFormat.format(stars))
                    }
                }
                badge?.invoke()
            }
            val path = remember(owner, repo) { AnnotatedString("$owner/$repo") }
            val displayPath = remember(path) {
                // Android may move a whole slug past the slash. Offer breaks between its parts.
                buildString {
                    for (ch in path.text) {
                        append(ch)
                        if (ch == '-' || ch == '_' || ch == '.') append('\u200B')
                    }
                }
            }
            Text(
                text = displayPath,
                style = MonoTokenStyle.copy(lineBreak = LineBreak.Simple),
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.clearAndSetSemantics { text = path },
            )
        }
    }
}

@Composable
private fun AvatarBlock(colorSeed: String, label: String, size: Dp, corner: Dp) {
    val (start, end) = remember(colorSeed) { avatarGradient(colorSeed) }
    val monogram = remember(label) { smartRepoMonogram(label) }
    val shape = RoundedCornerShape(corner)
    val baseDensity = LocalDensity.current
    val fontSize = if (monogram.length > 1) (size.value * 0.34f).sp else (size.value * 0.40f).sp
    // 头像内字母属图形徽章（右侧已有随系统缩放的完整标题），锁定 fontScale=1f 防大字号挤爆并净化读屏
    CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 1f)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(start, end),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                )
                .border(0.5.dp, Color.White.copy(alpha = 0.16f), shape)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = monogram,
                fontSize = fontSize,
                lineHeight = fontSize,
                letterSpacing = (-0.3).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private fun smartRepoMonogram(label: String): String {
    val clean = label.dropWhile { !it.isLetterOrDigit() }
    if (clean.isEmpty()) return "?"
    val first = clean[0]
    // 中日韩等表意文字单字信息密度高且字宽大，仅取首字
    if (first.code > 0x2E7F) return first.toString()
    val parts = clean.split('-', '_', '.', ' ').filter { it.isNotEmpty() }
    if (parts.size >= 2) {
        return "${parts[0][0].uppercaseChar()}${parts[1][0].uppercaseChar()}"
    }
    for (i in 0 until clean.lastIndex) {
        if (clean[i].isLowerCase() && clean[i + 1].isUpperCase()) {
            return "${first.uppercaseChar()}${clean[i + 1].uppercaseChar()}"
        }
    }
    return if (clean.length >= 2) {
        "${first.uppercaseChar()}${clean[1].lowercaseChar()}"
    } else {
        first.uppercaseChar().toString()
    }
}

private fun avatarGradient(seed: String): Pair<Color, Color> {
    val index = Math.floorMod(seed.hashCode(), AvatarPalette.size)
    return AvatarPalette[index]
}
