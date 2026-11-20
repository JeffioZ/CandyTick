package io.candy.tick.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.ui.common.InaccessibleBadge
import io.candy.tick.ui.common.StatusBadge
import io.candy.tick.ui.common.VersionStatusLine
import io.candy.tick.ui.common.relTimeString
import io.candy.tick.ui.common.toImageBitmap
import io.candy.tick.ui.theme.AvatarPalette
import io.candy.tick.ui.theme.MonoTokenStyle
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector

/** 首页仓库卡片：图标 + 名称 + 徽章 + 相对时间；展开显示提交与版本行 */
@Composable
fun RepoCard(
    item: ListViewModel.RepoListItem,
    extras: ListViewModel.CardExtras?,
    expanded: Boolean,
    isChanged: Boolean = false,
    updatable: Boolean = false,
    versionEpoch: Int = 0, // ON_RESUME 递增：安装后版本行重算
    onToggle: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    io.candy.tick.ui.common.PressableCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    io.candy.tick.ui.common.RepoIconBlock(repo = item.repo)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.repo.noteName ?: item.repo.repo,
                            style = MiuixTheme.textStyles.title4,
                            color = scheme.onBackground,
                        )
                        Text(
                            text = "${item.repo.owner}/${item.repo.repo}",
                            // 等宽标识符家族（与详情头卡路径/tag/sha 一致）
                            style = io.candy.tick.ui.theme.MonoTokenStyle,
                            color = scheme.onBackgroundVariant,
                        )
                    }
                    if (item.repo.pinnedAt != null) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_pin),
                            contentDescription = stringResource(R.string.pinned_a11y),
                            // 薄荷 accent：置顶是用户主动状态（与灰色被动信息区分）
                            tint = scheme.primaryVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (item.error != null) InaccessibleBadge() else StatusBadge(item.status)
                }

                // 2+10 = 展开面板上方 10+2 一致（12dp）：上下呼吸对称
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 色块外上下留 3dp：按压色块不贴上方内容、不贴下方面板/卡边（对称空隙）
                        .padding(vertical = 3.dp)
                        // clip 在 clickable 前：ripple 随之圆角化（直角矩形按压效果生硬）
                        .clip(RoundedCornerShape(10.dp))
                        // clickable 在 padding 之前：留白计入命中区（~40dp 达标），
                        // 且点击缓冲带不再穿透到卡片 onClick（误进详情）
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onToggle() }
                        // 内容边距 8（ripple 全宽）：文字不贴按压色块边缘；与面板边距统一对称
                        .padding(horizontal = 8.dp)
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item.snapshot?.pushedAt?.let { pushed ->
                        if (isChanged) {
                            // 低调「有更新」标记：6dp 薄荷圆点，静态无动画
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(scheme.primaryVariant),
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            text = stringResource(R.string.card_last_push, relTimeString(pushed)),
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (isChanged) scheme.primaryVariant else scheme.onBackgroundVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 「发现新版」直显标识（薄荷胶囊）：收起态的唯一新版提示；
                    // 展开且版本行可见时隐藏（版本行已给完整 tag 对比，两处同说=重复），
                    // 版本行暂无数据时保留兜底（展开也不至于零提示）
                    if (updatable && !(expanded && extras?.latestRelease != null)) {
                        Text(
                            text = stringResource(R.string.card_updatable),
                            style = MiuixTheme.textStyles.footnote2,
                            color = scheme.primaryVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(scheme.primaryContainer)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    // 普通 ⌄/⌃ 箭头，交叉淡入淡出（不旋转，无「转圈」感）
                    androidx.compose.animation.Crossfade(
                        targetState = expanded,
                        animationSpec = androidx.compose.animation.core.tween(150),
                        label = "expandArrow",
                    ) { exp ->
                        // 显式 18dp 钉死：Lucide chevron viewport 稳定，Crossfade 切换无尺寸跳
                        Icon(
                            imageVector = if (exp) {
                                ImageVector.vectorResource(R.drawable.ic_chevron_up)
                            } else {
                                ImageVector.vectorResource(R.drawable.ic_chevron_down)
                            },
                            contentDescription = null,
                            tint = scheme.onBackgroundVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // 展开收起钉 220 表层档：高度与透明度同拍（默认 spring 时长不可控且在体系外）
                androidx.compose.animation.AnimatedVisibility(
                    visible = expanded,
                    // 贴顶收展（expandFrom/shrinkTowards = Top）：内容锚定与卡身衔接的一侧，
                    // 底边与卡片高度同步生长/收缩——默认贴底时面板与卡底各自运动，
                    // 收起时"卡中卡"与卡身分离感（两个色块没一起收）
                    enter = androidx.compose.animation.expandVertically(
                        animationSpec = androidx.compose.animation.core.tween(220),
                        expandFrom = Alignment.Top,
                    ) + androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(220),
                    ),
                    exit = androidx.compose.animation.shrinkVertically(
                        animationSpec = androidx.compose.animation.core.tween(220),
                        shrinkTowards = Alignment.Top,
                    ) + androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(220),
                    ),
                ) {
                    Column {
                        Spacer(Modifier.height(2.dp))
                        // 卡中卡信息面板：展开内容有容器底，与折叠态主信息分层
                        ExpandedDetails(item = item, extras = extras, versionEpoch = versionEpoch, refreshing = extras?.refreshing == true)
                        // 卡底留白放展开块内：收起时随动画移除，折叠态底部与顶对称（8dp）
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
    }
}

@Composable
private fun ExpandedDetails(
    item: ListViewModel.RepoListItem,
    extras: ListViewModel.CardExtras?,
    versionEpoch: Int = 0,
    refreshing: Boolean = false,
) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 全宽（与按压色块同宽同圆角）：「按哪里、展开哪里」的视觉逻辑一致
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.secondaryVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            extras == null || extras.loading -> androidx.compose.foundation.layout.Box(
                modifier = Modifier.height(16.dp), // 与替换后的提交行等高，加载→内容不跳
                contentAlignment = Alignment.CenterStart,
            ) {
                io.candy.tick.ui.common.LoadingDots(dotSize = 5.dp)
            }
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

        // 版本行：最新 release vs 本机版本（与详情页头部共用组件，口径一致）
        val release = extras?.latestRelease
        if (release != null) VersionStatusLine(repo = item.repo, remoteTag = release.tagName, installEpoch = versionEpoch)

        // 静默重拉提示（150 淡入淡出）：旧数据照常显示，只是告知"正在取新数据"——
        // 此前暗更没有任何反馈（点了没反应，数据过会儿突然变了）
        androidx.compose.animation.AnimatedVisibility(
            visible = refreshing,
            // fade + 高度展开/收缩（贴顶）：此前只有 fade，出现/消失时占位突变 → 卡片高度跳变生硬
            enter = androidx.compose.animation.expandVertically(
                animationSpec = androidx.compose.animation.core.tween(150),
                expandFrom = Alignment.Top,
            ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
            exit = androidx.compose.animation.shrinkVertically(
                animationSpec = androidx.compose.animation.core.tween(150),
                shrinkTowards = Alignment.Top,
            ) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)),
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                io.candy.tick.ui.common.LoadingDots(dotSize = 4.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.card_refreshing),
                    style = MiuixTheme.textStyles.footnote2,
                    color = scheme.onBackgroundVariant,
                )
            }
        }
    }
}

private fun avatarGradient(owner: String): Pair<Color, Color> {
    val palette = AvatarPalette
    val hash = abs(owner.hashCode())
    return palette[hash % palette.size]
}
