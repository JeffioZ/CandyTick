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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.candy.tick.R
import io.candy.tick.ui.common.CompactTextAction
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 首页仓库卡片：图标 + 名称 + 徽章 + 相对时间；展开显示提交与版本行 */
@Composable
internal fun RepoCard(
    item: ListViewModel.RepoListItem,
    extras: ListViewModel.CardExtras?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    readViewport: CardReadViewport?,
    onReleaseSeen: (String, Set<String>) -> Unit,
    onCommitSeen: (String, Set<String>) -> Unit,
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
                // 从错误态点「重试」时由展开区就地承接加载态（避免无论有无缓存都跳去顶行双转圈）
                var retryingFromError by remember(expanded) { mutableStateOf(false) }
                // 顶行「正在刷新」最短停留兜底：健康态有缓存刷新时快速失败也不会一闪而过
                val showingUserRefresh = io.candy.tick.ui.common.rememberMinDwellBusy(
                    extras?.load == ListViewModel.CardLoad.USER_REFRESH && !retryingFromError,
                )
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
                        val motionEnabled = io.candy.tick.ui.common.rememberMotionEnabled()
                        val unreadDot by androidx.compose.animation.core.animateDpAsState(
                            if (isChanged) 6.dp else 0.dp,
                            androidx.compose.animation.core.tween(if (motionEnabled) Motion.MICRO else 0),
                            label = "unreadDot",
                        )
                        val unreadDotAlpha by androidx.compose.animation.core.animateFloatAsState(
                            if (isChanged) 1f else 0f,
                            androidx.compose.animation.core.tween(if (motionEnabled) Motion.MICRO else 0),
                            label = "unreadDotAlpha",
                        )
                        Box(
                            modifier = Modifier
                                .size(unreadDot)
                                .alpha(unreadDotAlpha)
                                .background(scheme.primaryVariant, CircleShape),
                        )
                        Spacer(Modifier.width(unreadDot * (5f / 6f)))
                        // 用户展开或重试时才替换时间（无缓存时失败重试由展开区就地切 LoadingDots，避免上下双转圈）。
                        val refreshingLabel = expanded && !pageRefreshing &&
                            extras?.commits != null && showingUserRefresh
                        androidx.compose.animation.AnimatedContent(
                            targetState = refreshingLabel,
                            transitionSpec = { downloadActionTransition(duration = if (motionEnabled) Motion.PHASE else 0) },
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
                                    timeLabel == null -> unread ?: if (item.snapshot == null) {
                                        stringResource(R.string.card_unsynced)
                                    } else null
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
                    // 与详情页 Release 卡片统一的 180° 平滑旋转展开箭头（遵循系统减弱动效）
                    val chevronRotation by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (expanded) 180f else 0f,
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = if (io.candy.tick.ui.common.rememberMotionEnabled()) Motion.PANEL else 0,
                            easing = Motion.Decelerate,
                        ),
                        label = "expandChevronRotation",
                    )
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = scheme.onBackgroundVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = chevronRotation },
                    )
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
                            item = item,
                            extras = extras,
                            releaseTag = releaseTag,
                            onRetry = onRetryExtras?.let { retry ->
                                {
                                    retryingFromError = true
                                    retry()
                                }
                            },
                            readViewport = readViewport.takeIf { expanded },
                            onReleaseSeen = onReleaseSeen,
                            onCommitSeen = onCommitSeen,
                            retryingFromError = retryingFromError,
                            onRetrySettled = { retryingFromError = false },
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
    readViewport: CardReadViewport?,
    onReleaseSeen: (String, Set<String>) -> Unit,
    onCommitSeen: (String, Set<String>) -> Unit,
    retryingFromError: Boolean,
    onRetrySettled: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val activity = item.snapshot?.activity
    val rawInitialLoad = extras == null || extras.load == ListViewModel.CardLoad.INITIAL
    val showingInitialLoading = io.candy.tick.ui.common.rememberMinDwellBusy(rawInitialLoad)
    val rawRetryLoad = retryingFromError &&
        (extras?.load == ListViewModel.CardLoad.INITIAL || extras?.load == ListViewModel.CardLoad.USER_REFRESH)
    var sawRetryActive by remember(retryingFromError) { mutableStateOf(false) }
    if (rawRetryLoad) sawRetryActive = true
    val showingRetryLoading = io.candy.tick.ui.common.rememberMinDwellBusy(rawRetryLoad)
    androidx.compose.runtime.LaunchedEffect(retryingFromError, sawRetryActive, extras?.load, extras?.failed, showingRetryLoading) {
        if (!retryingFromError) return@LaunchedEffect
        if (!sawRetryActive) {
            // 等待 StateFlow 下发 INITIAL/USER_REFRESH；若请求被忽略未触发则 150ms 兜底复位
            kotlinx.coroutines.delay(150L)
            onRetrySettled()
            return@LaunchedEffect
        }
        val idle = extras != null && extras.load == ListViewModel.CardLoad.IDLE
        if (idle && (!extras.failed || !showingRetryLoading)) {
            onRetrySettled()
        }
    }
    val showRetryRow = retryingFromError && (!sawRetryActive || rawRetryLoad || showingRetryLoading)
    val showErrorRow = !showRetryRow && extras != null && extras.failed && !(extras.commits == null && showingInitialLoading)
    val showInitialLoading = !showRetryRow && !showErrorRow && (extras == null || (extras.commits == null && showingInitialLoading))
    // 错误行与重试行自带 32dp 触控/居中高度，收紧面板纵衬到 4dp（视觉上下约 12dp），
    // 避免与常规 16dp 文本行的 10dp 纵衬叠加出过大空白
    val verticalPad = if (showErrorRow || showRetryRow) 4.dp else 10.dp
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
            .padding(horizontal = 8.dp, vertical = verticalPad),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            showRetryRow -> Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                io.candy.tick.ui.common.LoadingDots(dotSize = 5.dp)
                Text(
                    text = stringResource(R.string.card_refreshing),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                )
            }
            showErrorRow -> Row(
                // 首页展开区出错时仅展示干净的单行错误 + 重试（不与旧缓存提交/版本混排；完整缓存由详情页保留）
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_info),
                    contentDescription = null,
                    tint = scheme.onErrorContainer,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.card_details_failed),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                if (onRetry != null) {
                    CompactTextAction(
                        onClick = onRetry,
                        surface = scheme.secondaryVariant,
                        alignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.retry),
                            style = MiuixTheme.textStyles.footnote1.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            ),
                            color = scheme.onErrorContainer,
                        )
                    }
                }
            }
            showInitialLoading -> androidx.compose.foundation.layout.Box(
                modifier = Modifier.height(16.dp), // 与替换后的提交行等高，加载→内容不跳
                contentAlignment = Alignment.CenterStart,
            ) {
                io.candy.tick.ui.common.LoadingDots(dotSize = 5.dp)
            }
            else -> {
                extras?.commits?.take(3)?.forEachIndexed { index, commit ->
                    // 只有与当前快照一致的首行代表积压。预览落后或首行不在屏内时，仍逐行确认。
                    val head = index == 0 && commit.sha == item.snapshot?.lastCommitSha
                    val batch = if (head) activity?.unreadCommitShas.orEmpty() else emptySet()
                    key(item.repo.addedAt, item.repo.branch, item.repo.branch ?: item.snapshot?.defaultBranch, commit.sha, head) {
                        val observe = activity != null && (commit.sha in activity.unreadCommitShas ||
                            batch.isNotEmpty() || (index == 0 && commit.sha !in activity.recentlyViewedCommits))
                        Row(
                            modifier = Modifier.whenRowSeen(readViewport.takeIf { observe }, batch) { onCommitSeen(commit.sha, it) },
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

                // 版本行：最新 release vs 本机版本（与详情页头部共用组件，口径一致）
                if (releaseTag != null) {
                    val head = releaseTag == item.snapshot?.currentReleaseTag
                    val batch = if (head) activity?.unreadReleaseTags.orEmpty() else emptySet()
                    key(item.repo.addedAt, releaseTag, head) {
                        val observe = activity != null && (batch.isNotEmpty() || releaseTag in activity.unreadReleaseTags ||
                            releaseTag !in activity.recentlyViewedReleases)
                        VersionStatusLine(
                            remoteTag = releaseTag,
                            installed = item.installedVersion,
                            comparison = item.comparisonFor(releaseTag),
                            modifier = Modifier.whenRowSeen(readViewport.takeIf { observe }, batch) { onReleaseSeen(releaseTag, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.whenRowSeen(
    viewport: CardReadViewport?,
    batch: Set<String>,
    onSeen: (Set<String>) -> Unit,
): Modifier {
    if (viewport == null) return this
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Fresh tokens distinguish a brief loss of visibility or focus, even between emissions.
    var visibleInterval by remember(viewport) { mutableStateOf<Any?>(null) }
    val latestBatch by rememberUpdatedState(batch)
    val notifySeen by rememberUpdatedState(onSeen)
    LaunchedEffect(lifecycle, viewport) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { visibleInterval to viewport.focusInterval }.collectLatest { (visible, focused) ->
                if (visible == null || focused == null) return@collectLatest
                coroutineScope {
                    // Entries first displayed together share a timer. A removal followed by a
                    // rediscovery starts a new timer even if the old one hasn't fired yet.
                    val timers = mutableMapOf<String, Any>()
                    var firstBatch = true
                    snapshotFlow { latestBatch }.collect { displayed ->
                        timers.keys.retainAll(displayed)
                        val added = displayed - timers.keys
                        val includesRow = firstBatch
                        if (!includesRow && added.isEmpty()) return@collect
                        firstBatch = false
                        val timer = Any()
                        added.forEach { timers[it] = timer }
                        launch {
                            delay(250L)
                            if (visibleInterval !== visible || viewport.focusInterval !== focused ||
                                lifecycle.currentState != Lifecycle.State.RESUMED
                            ) return@launch
                            val ready = added.filterTo(mutableSetOf()) { timers[it] === timer && it in latestBatch }
                            if (includesRow || ready.isNotEmpty()) notifySeen(ready)
                        }
                    }
                }
            }
        }
    }
    return onVisibilityChanged(0L, 1f, viewport.bounds) { visible ->
        visibleInterval = if (visible) visibleInterval ?: Any() else null
    }
}
