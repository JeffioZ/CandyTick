package io.candy.tick.ui.list

import io.candy.tick.data.isInaccessible
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.InlineErrorRow
import io.candy.tick.ui.common.InaccessibleBadge
import io.candy.tick.ui.common.RepoIdentityHeader
import io.candy.tick.ui.common.StatusBadge
import io.candy.tick.ui.common.VersionStatusLine
import io.candy.tick.ui.common.downloadActionTransition
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
    onToggle: () -> Unit,
    onClick: () -> Unit,
    readViewport: LayoutBoundsHolder?,
    onReleaseSeen: (String) -> Unit,
    onCommitSeen: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pageRefreshing: Boolean = false,
    isChanged: Boolean = false,
    updatable: Boolean = false,
    onRetryExtras: (() -> Unit)? = null,
    interactionModifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val pinned = item.repo.pinnedAt != null
    val pinnedDescription = if (pinned) stringResource(R.string.pinned_a11y) else null
    val snapshot = item.snapshot
    // A successful sync can confirm that the last APK release has been removed.
    val releaseTag = if (snapshot?.releaseBaselineKnown == true) {
        snapshot.currentReleaseTag
    } else {
        snapshot?.currentReleaseTag ?: extras?.latestRelease?.tagName
    }
    val unreadRelease = !snapshot?.activity?.unreadReleaseTags.isNullOrEmpty()
    val unreadCommit = !snapshot?.activity?.unreadCommitShas.isNullOrEmpty()
    val unread = when {
        unreadRelease && unreadCommit -> stringResource(R.string.card_unread_both)
        unreadRelease -> stringResource(R.string.card_unread_release)
        unreadCommit -> stringResource(R.string.card_unread_commit)
        else -> null
    }
    io.candy.tick.ui.common.PressableCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        onClickLabel = stringResource(R.string.card_open_repo_a11y),
        interactionModifier = interactionModifier.semantics {
            if (pinnedDescription != null) stateDescription = pinnedDescription
        },
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                RepoIdentityHeader(
                    owner = item.repo.owner,
                    repo = item.repo.repo,
                    title = item.repo.noteName ?: item.repo.repo,
                    titleMaxLines = Int.MAX_VALUE,
                    // 只有「确实不可访问」（404/Token 无权）才翻徽章；网络/限流等瞬时失败
                    // 不翻——快照状态仍真实（上次成功同步的活跃度），此前一律翻造成与详情页矛盾
                    badge = {
                        if (item.error?.isInaccessible == true) InaccessibleBadge() else StatusBadge(item.status)
                    },
                ) {
                    io.candy.tick.ui.common.RepoIconBlock(repo = item.repo, installedVersion = item.installedVersion)
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
                        // 低调「有更新」标记：6dp 薄荷圆点，自身静态；出现 / 消失走微反馈档（150ms）：圆点缩小 +
                        // 淡入淡出，间距同步归零——内容被看到记为已读时，后面的文字平移回位，
                        // 而不是圆点瞬间消失、文字跳 11dp。（不用 AnimatedVisibility：它的宽度收展是矩形裁切，
                        // 圆点后半段会被竖着切掉一截，不如缩放自然）
                        val unreadDot by androidx.compose.animation.core.animateDpAsState(
                            if (isChanged) 6.dp else 0.dp,
                            androidx.compose.animation.core.tween(Motion.MICRO),
                            label = "unreadDot",
                        )
                        val unreadDotAlpha by androidx.compose.animation.core.animateFloatAsState(
                            if (isChanged) 1f else 0f,
                            androidx.compose.animation.core.tween(Motion.MICRO),
                            label = "unreadDotAlpha",
                        )
                        Box(
                            modifier = Modifier
                                .size(unreadDot)
                                .alpha(unreadDotAlpha)
                                .background(scheme.primaryVariant, CircleShape),
                        )
                        Spacer(Modifier.width(unreadDot * (5f / 6f)))
                        // 用户展开或重试时才替换时间。批量刷新由页面提示，卡片保留已知时间。
                        // 时间文字与「正在刷新」之间走相变过渡（与下载按钮四态同一套，180ms），不再硬切
                        val refreshingLabel = expanded && !pageRefreshing && extras?.load == ListViewModel.CardLoad.USER_REFRESH
                        androidx.compose.animation.AnimatedContent(
                            targetState = refreshingLabel,
                            transitionSpec = { downloadActionTransition() },
                            label = "cardActivity",
                        ) { refreshing ->
                            if (refreshing) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    io.candy.tick.ui.common.LoadingDots(contentDescription = null)
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
                                // 与圆点同一条时间线：记为已读时薄荷绿随圆点一起淡回次要色，而不是瞬间换色
                                val labelTint by androidx.compose.animation.animateColorAsState(
                                    targetValue = if (isChanged) scheme.primaryVariant else scheme.onBackgroundVariant,
                                    animationSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                                    label = "activityTint",
                                )
                                if (label != null) Text(
                                    text = label,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = labelTint,
                                )
                            }
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
                    // 版本行暂无数据时保留兜底（展开也不至于零提示）。
                    // 内衬与状态徽章同值（8/3）：同是 footnote2 文字的胶囊，同一张卡上高度一致。
                    // 显隐只做淡入淡出（微反馈档）：标签贴着右侧箭头排，宽度一动位置就会跑；
                    // 不带宽度收展时占位保留到淡出结束，标签在原位淡出后再让出空间
                    androidx.compose.animation.AnimatedVisibility(
                        visible = updatable && !(expanded && releaseTag != null),
                        enter = androidx.compose.animation.fadeIn(
                            animationSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                        ),
                        exit = androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                        ),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.card_updatable),
                                style = MiuixTheme.textStyles.footnote2,
                                color = scheme.primaryVariant,
                                modifier = Modifier
                                    .background(scheme.primaryContainer, CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
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
                            item, extras, releaseTag, onRetryExtras, readViewport.takeIf { expanded },
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
    releaseTag: String?,
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
            // 首次展开时加载占位换成提交行、出错条出现 / 消失：面板高度走 220ms 渐变，
            // 下面的卡片跟着平滑移动，而不是瞬间跳一段。内容按完整高度排版，
            // 超出渐变高度的部分由外层 AnimatedVisibility 裁掉
            .animateContentSize(androidx.compose.animation.core.tween(Motion.PANEL))
            // 全宽（与按压色块同宽同圆角）：「按哪里、展开哪里」的视觉逻辑一致；
            // 内衬 8dp 与上方展开行同值：面板里的文字和「最近提交…」落在同一条文字线上
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.secondaryVariant)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            extras == null || extras.load == ListViewModel.CardLoad.INITIAL -> androidx.compose.foundation.layout.Box(
                modifier = Modifier.height(16.dp), // 与替换后的提交行等高，加载→内容不跳
                contentAlignment = Alignment.CenterStart,
            ) {
                io.candy.tick.ui.common.LoadingDots(dotSize = 5.dp)
            }
            extras.failed -> InlineErrorRow(
                message = stringResource(R.string.card_details_failed),
                onRetry = onRetry,
                // 卡内错误条不做 liveRegion 播报：会被 clickable merge，相对时间每分钟刷新时整卡重读
                announce = false,
            )
        }
        if (extras?.load != ListViewModel.CardLoad.INITIAL) {
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
        if (releaseTag != null) key(releaseTag) {
            val observe = activity != null && (releaseTag in activity.unreadReleaseTags ||
                releaseTag !in activity.recentlyViewedReleases)
            VersionStatusLine(
                remoteTag = releaseTag,
                installed = item.installedVersion,
                comparison = item.comparisonFor(releaseTag),
                modifier = Modifier.whenRowSeen(readViewport.takeIf { observe }) { onReleaseSeen(releaseTag) },
            )
        }
    }
}

private fun Modifier.whenRowSeen(viewport: LayoutBoundsHolder?, onSeen: () -> Unit): Modifier =
    if (viewport == null) this else onVisibilityChanged(250L, 1f, viewport) { visible ->
        if (visible) onSeen()
    }
