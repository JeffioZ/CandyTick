package io.candy.tick.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.ui.common.InaccessibleBadge
import io.candy.tick.ui.common.OwnerRepoLine
import io.candy.tick.ui.common.StatusBadge
import io.candy.tick.ui.common.VersionStatusLine
import io.candy.tick.ui.common.relTimeString
import io.candy.tick.ui.theme.MonoTokenStyle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import io.candy.tick.ui.common.Motion

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
    readViewport: LayoutBoundsHolder?,
    onReleaseSeen: (String) -> Unit,
    onCommitSeen: (String, Boolean) -> Unit,
    onRetryExtras: (() -> Unit)? = null, // 展开面板加载失败的显式重试
    modifier: Modifier = Modifier,
    interactionModifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val pinned = item.repo.pinnedAt != null
    val pinnedDescription = if (pinned) stringResource(R.string.pinned_a11y) else null
    val unreadRelease = !item.snapshot?.activity?.unreadReleaseTags.isNullOrEmpty()
    val unreadCommit = !item.snapshot?.activity?.unreadCommitShas.isNullOrEmpty()
    val unread = when {
        unreadRelease && unreadCommit -> stringResource(R.string.card_unread_both)
        unreadRelease -> stringResource(R.string.card_unread_release)
        unreadCommit -> stringResource(R.string.card_unread_commit)
        else -> null
    }
    io.candy.tick.ui.common.PressableCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        interactionModifier = interactionModifier.semantics {
            if (pinnedDescription != null) stateDescription = pinnedDescription
        },
    ) {
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
                        // 等宽标识符家族（与详情头卡路径/tag/sha 一致）
                        io.candy.tick.ui.common.OwnerRepoLine(owner = item.repo.owner, repo = item.repo.repo)
                    }
                    if (item.error != null) InaccessibleBadge() else StatusBadge(item.status)
                }

                val expandPress = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = stringResource(if (expanded) R.string.collapse_details else R.string.expand_details),
                            role = androidx.compose.ui.semantics.Role.Button,
                            interactionSource = expandPress,
                            indication = null,
                        ) { onToggle() }
                        .semantics { if (unread != null) stateDescription = unread }
                        // 收起和展开共用同一对留白，留白也响应展开操作。
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .indication(expandPress, LocalIndication.current)
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
                        // 刷新中：时间原地换为加载反馈（skill: Content Jumping——稳定容器零高度
                        // 变化）。此前反馈行插在展开面板里：折叠态刷新完全无反馈 + 插行导致
                        // 卡片高度跳变；头部这一行折叠/展开常驻，原地换字两态通吃
                        if (extras?.refreshing == true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                io.candy.tick.ui.common.LoadingDots(dotSize = 4.dp)
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = stringResource(R.string.card_refreshing),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = scheme.onBackgroundVariant,
                                )
                            }
                        } else {
                            val timeLabel = item.activityAt?.let { instant ->
                                val label = when {
                                    item.releaseIsLatest -> R.string.card_last_release
                                    item.snapshot?.lastCommitDate != null -> R.string.card_last_commit
                                    else -> R.string.card_last_push
                                }
                                stringResource(label, relTimeString(instant))
                            }
                            val otherUnread = when {
                                item.releaseIsLatest && unreadCommit -> R.string.card_unread_commit
                                !item.releaseIsLatest && unreadRelease -> R.string.card_unread_release
                                else -> null
                            }
                            val label = when {
                                timeLabel == null -> unread
                                otherUnread != null -> stringResource(R.string.card_activity_with_unread, timeLabel, stringResource(otherUnread))
                                else -> timeLabel
                            }
                            if (label != null) Text(
                                text = label,
                                style = MiuixTheme.textStyles.footnote2,
                                color = if (isChanged) scheme.primaryVariant else scheme.onBackgroundVariant,
                            )
                        }
                    }
                    // Reserve the pin's place as cards change groups, including at larger text sizes.
                    Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterStart) {
                        if (pinned) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_pin),
                                contentDescription = null,
                                tint = scheme.onBackgroundVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
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
                        animationSpec = androidx.compose.animation.core.tween(Motion.MICRO),
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
                        animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                        expandFrom = Alignment.Top,
                    ) + androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                    ),
                    exit = androidx.compose.animation.shrinkVertically(
                        animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                        shrinkTowards = Alignment.Top,
                    ) + androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                    ),
                ) {
                    // 图层隔离：展开动画逐帧只改裁剪/尺寸，面板内容缓存在独立层
                    // （与 ReleaseList 展开区同款缓解）
                    Column(
                        modifier = Modifier.graphicsLayer(),
                    ) {
                        // 卡中卡信息面板：展开内容有容器底，与折叠态主信息分层
                        ExpandedDetails(
                            item, extras, versionEpoch, onRetryExtras, readViewport.takeIf { expanded },
                            onReleaseSeen, onCommitSeen,
                        )
                        // 展开面板底部与卡片顶部各留 8dp。
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
    versionEpoch: Int,
    onRetry: (() -> Unit)?,
    readViewport: LayoutBoundsHolder?,
    onReleaseSeen: (String) -> Unit,
    onCommitSeen: (String, Boolean) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val activity = item.snapshot?.activity
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
            // 加载失败行：errorContainer 底 + onErrorContainer 字（token 正确配对，与
            // 版本卡/头卡失败条同语言）——此前 onErrorContainer 直写在面板灰底上
            extras.failed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_info),
                    contentDescription = null,
                    tint = scheme.onErrorContainer,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.card_details_failed),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onErrorContainer,
                )
                Spacer(Modifier.weight(1f))
                // 显式重试链（行内恢复紧邻错误）：此前恢复=收起再展开，不可发现
                if (onRetry != null) {
                    val (retrySrc, retryTint) = io.candy.tick.ui.common.rememberRowPressTint()
                    Text(
                        text = stringResource(R.string.retry),
                        style = MiuixTheme.textStyles.footnote1.copy(
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        ),
                        color = scheme.onErrorContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(retryTint, RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = retrySrc,
                                indication = null,
                                onClick = onRetry,
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (extras?.loading != true) {
            extras?.commits?.take(3)?.forEachIndexed { index, commit ->
                key(commit.sha) {
                    val observe = activity != null && (commit.sha in activity.unreadCommitShas ||
                        (index == 0 && commit.sha !in activity.recentlyViewedCommits))
                    Row(
                        modifier = Modifier.whenRowSeen(readViewport.takeIf { observe }) { onCommitSeen(commit.sha, index == 0) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
        }

        // 版本行：最新 release vs 本机版本（与详情页头部共用组件，口径一致）
        val release = extras?.latestRelease
        if (release != null) key(release.tagName) {
            val observe = activity != null && (release.tagName in activity.unreadReleaseTags ||
                release.tagName !in activity.recentlyViewedReleases)
            VersionStatusLine(
                repo = item.repo,
                remoteTag = release.tagName,
                installEpoch = versionEpoch,
                modifier = Modifier.whenRowSeen(readViewport.takeIf { observe }) { onReleaseSeen(release.tagName) },
            )
        }
    }
}

private fun Modifier.whenRowSeen(viewport: LayoutBoundsHolder?, onSeen: () -> Unit): Modifier =
    if (viewport == null) this else onVisibilityChanged(250L, 1f, viewport) { visible ->
        if (visible) onSeen()
    }
