package io.candy.tick.ui.list

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.ui.common.CandyIcons
import io.candy.tick.ui.common.InaccessibleBadge
import io.candy.tick.ui.common.StatusBadge
import io.candy.tick.ui.common.relTimeString
import io.candy.tick.ui.common.toImageBitmap
import io.candy.tick.ui.theme.AvatarPalette
import io.candy.tick.ui.theme.MonoTokenStyle
import io.candy.tick.util.VersionCompare
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 首页仓库卡片：4dp 色条 + 图标 + 名称 + 徽章 + 相对时间；展开显示提交与版本行 */
@Composable
fun RepoCard(
    item: ListViewModel.RepoListItem,
    extras: ListViewModel.CardExtras?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val barColor by animateColorAsState(
        targetValue = if (item.error != null) {
            scheme.onErrorContainer
        } else {
            barColorOf(item)
        },
        label = "statusBar",
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        cornerRadius = 16.dp,
        onClick = onClick,
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // 左侧状态色条
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(barColor),
            )
            Column(modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RepoIcon(repo = item.repo)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.repo.noteName ?: item.repo.repo,
                            style = MiuixTheme.textStyles.subtitle,
                            color = scheme.onBackground,
                        )
                        Text(
                            text = "${item.repo.owner}/${item.repo.repo}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onBackgroundVariant,
                        )
                    }
                    if (item.error != null) InaccessibleBadge() else StatusBadge(item.status)
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.snapshot?.pushedAt?.let { pushed ->
                        Text(
                            text = stringResource(R.string.card_last_push, relTimeString(pushed)),
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onBackgroundVariant,
                        )
                    }
                    if (expanded) {
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = CandyIcons.Delete,
                                contentDescription = stringResource(R.string.remove_repo),
                                tint = scheme.onBackgroundVariant,
                            )
                        }
                    }
                }

                if (expanded) {
                    Spacer(Modifier.height(4.dp))
                    ExpandedDetails(item = item, extras = extras)
                }
            }
        }
    }
}

@Composable
private fun barColorOf(item: ListViewModel.RepoListItem): Color {
    val colors = io.candy.tick.ui.common.statusColorOf(item.status) ?: return MiuixTheme.colorScheme.outline
    return colors.bar
}

@Composable
private fun ExpandedDetails(item: ListViewModel.RepoListItem, extras: ListViewModel.CardExtras?) {
    val scheme = MiuixTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            extras == null || extras.loading -> Text(
                text = stringResource(R.string.loading),
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onBackgroundVariant,
            )
            extras.commits == null -> Text(
                text = stringResource(R.string.card_details_failed),
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onErrorContainer,
            )
            else -> extras.commits.take(3).forEach { commit ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = commit.sha.take(7),
                        style = MonoTokenStyle,
                        color = scheme.primaryVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = commit.message.lineSequence().firstOrNull().orEmpty(),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onBackgroundVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        // 版本行：最新 release vs 本机版本（宁可不比）
        val release = extras?.latestRelease
        if (release != null) VersionLine(repo = item.repo, release = release)
    }
}

@Composable
private fun VersionLine(repo: TrackedRepo, release: io.candy.tick.data.model.ReleaseRecord) {
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val local = repo.linkedPackage?.let { pkg ->
        runCatching {
            context.packageManager.getPackageInfo(pkg, 0).versionName
        }.getOrNull()
    }
    val cmp = if (local != null) VersionCompare.compare(release.tagName, local) else null
    val highlight = cmp != null && cmp > 0
    val text = when {
        local == null -> stringResource(R.string.version_latest_only, release.tagName)
        cmp == null -> stringResource(R.string.version_incomparable, release.tagName)
        highlight -> stringResource(R.string.version_update_available, release.tagName)
        else -> stringResource(R.string.version_line, release.tagName, local)
    }
    Text(
        text = text,
        style = MonoTokenStyle,
        color = if (highlight) scheme.primaryVariant else scheme.onBackgroundVariant,
    )
}

/** 卡片图标两档：已关联 → 真 APP 图标；未关联 → 首字母块（owner hash 8 色环 135° 渐变） */
@Composable
private fun RepoIcon(repo: TrackedRepo) {
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
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            return
        }
    }
    AvatarBlock(owner = repo.owner)
}

@Composable
private fun AvatarBlock(owner: String) {
    val (start, end) = remember(owner) { avatarGradient(owner) }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(start, end), start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))),
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
    val palette = AvatarPalette
    val hash = abs(owner.hashCode())
    return palette[hash % palette.size]
}
