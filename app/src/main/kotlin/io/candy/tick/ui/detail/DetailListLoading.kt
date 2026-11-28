package io.candy.tick.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.FooterText
import io.candy.tick.ui.common.InlineErrorRow
import io.candy.tick.ui.common.LoadingDots
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.common.StatusPage
import io.candy.tick.ui.common.rememberMotionEnabled
import io.candy.tick.ui.detail.DetailViewModel.DetailTab
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val HistoryItemSpacing = 10.dp
private const val FIRST_PAGE_GRACE_MILLIS = 100L
private val DetailTab.placeholderHeight get() = if (this == DetailTab.COMMITS) 60.dp else 88.dp

/** A quick response skips the skeleton. Completing the request immediately disposes its timer. */
@Composable
internal fun firstPagePlaceholderReady(pending: Boolean): Boolean {
    if (!pending) return false
    val ready by produceState(false) {
        delay(FIRST_PAGE_GRACE_MILLIS)
        value = true
    }
    return ready
}

/** Measure the free space after the tabs (or the notification lookup message). Using the actual
 * viewport accounts for wrapping headers and insets. Keep one card reachable below a tall header. */
@Composable
internal fun historyPlaceholderCount(list: LazyListState, tab: DetailTab, precedingKey: String): Int {
    val cardHeight = with(LocalDensity.current) { tab.placeholderHeight.roundToPx() }
    val count by remember(list, tab, cardHeight, precedingKey) {
        derivedStateOf {
            val layout = list.layoutInfo
            val gap = layout.mainAxisItemSpacing
            val preceding = layout.visibleItemsInfo.firstOrNull { it.key == precedingKey }
            val top = if (preceding != null) preceding.offset + preceding.size + gap else {
                layout.visibleItemsInfo.firstOrNull { it.contentType == tab }?.offset
                    ?: return@derivedStateOf 1
            }
            val available = layout.viewportEndOffset - layout.afterContentPadding - maxOf(top, layout.viewportStartOffset)
            val limit = if (tab == DetailTab.COMMITS) 4 else 2
            ((available + gap) / (cardHeight + gap)).coerceIn(1, limit)
        }
    }
    return count
}

@Composable
internal fun rememberHistoryContentOpacity(
    list: LazyListState, tab: DetailTab, showingPlaceholders: Boolean, motionEnabled: Boolean,
): State<Float> {
    val inView by remember(list, tab) {
        derivedStateOf { list.layoutInfo.visibleItemsInfo.any { it.contentType == tab } }
    }
    return animateFloatAsState(
        targetValue = if (motionEnabled && showingPlaceholders && inView) 0f else 1f,
        animationSpec = tween(if (motionEnabled) Motion.MICRO else 0, easing = Motion.Decelerate),
        label = "historyContentOpacity",
    )
}

internal fun LazyItemScope.refreshItemMotion(enabled: Boolean): Modifier = if (enabled) {
    Modifier.animateItem(
        fadeInSpec = tween(Motion.MICRO),
        placementSpec = tween(Motion.PHASE, easing = Motion.Decelerate),
        fadeOutSpec = tween(Motion.MICRO),
    )
} else Modifier

@Composable
private fun Modifier.historyLoadingSemantics(index: Int): Modifier {
    val loading = if (index == 0) stringResource(R.string.loading) else null
    return clearAndSetSemantics {
        if (loading != null) {
            contentDescription = loading
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        }
    }
}

@Composable
private fun HistorySkeletonLines(tab: DetailTab, index: Int, modifier: Modifier) {
    val baseColor = MiuixTheme.colorScheme.onSurface
    val maxAlpha = if (io.candy.tick.ui.theme.LocalDarkTheme.current) 0.11f else 0.08f
    val motionEnabled = rememberMotionEnabled()
    val pulseState = if (motionEnabled) {
        rememberInfiniteTransition(label = "skeletonPulse").animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "skeletonAlpha",
        )
    } else null
    Canvas(modifier.padding(horizontal = 16.dp)) {
        // 在 draw 阶段读动画值，零重组开销；系统关闭动画时静态显示
        val color = baseColor.copy(alpha = maxAlpha * (pulseState?.value ?: 0.85f))
        val gap = 8.dp.toPx()
        val meta = 8.dp.toPx()
        val title = if (tab == DetailTab.COMMITS) 12.dp.toPx() else 14.dp.toPx()
        val radius = CornerRadius(4.dp.toPx())
        val titleWidth = size.width * if (index % 2 == 0) 0.76f else 0.60f
        val top = ((size.height - title - meta - gap) / 2f).coerceAtLeast(0f)
        fun bar(x: Float, y: Float, width: Float, height: Float) {
            drawRoundRect(color, Offset(x, y), Size(width, height), radius)
        }
        if (tab == DetailTab.COMMITS) {
            bar(0f, top, size.width * 0.34f, meta)
            val time = minOf(36.dp.toPx(), size.width * 0.22f)
            bar(size.width - time, top, time, meta)
            bar(0f, top + meta + gap, titleWidth, title)
        } else {
            bar(0f, top, titleWidth, title)
            bar(0f, top + title + gap, size.width * 0.38f, meta)
        }
    }
}

internal fun LazyListScope.historyPlaceholders(tab: DetailTab, count: Int, motionEnabled: Boolean) {
    items(count, key = { "placeholder:${tab.name}:$it" }, contentType = { tab }) { index ->
        val reveal = if (motionEnabled) Modifier.animateItem(
            fadeInSpec = tween(Motion.MICRO, easing = Motion.Decelerate),
            placementSpec = null,
            fadeOutSpec = tween(Motion.MICRO, easing = Motion.Decelerate),
        ) else Modifier
        // Existing rows retain their tone when more complete cards fit in the viewport.
        Card(reveal.fillMaxWidth().historyLoadingSemantics(index).alpha(0.88f - 0.1f * index)) {
            HistorySkeletonLines(tab, index, Modifier.fillMaxWidth().height(tab.placeholderHeight))
        }
    }
}

private enum class HistoryFooterState { Idle, Paging, Retrying, Failed, Empty, Changed, End }

@Composable
internal fun LazyItemScope.HistoryFooter(
    state: DetailViewModel.UiState,
    tab: DetailTab,
    displayedCount: Int,
    showPagingIndicator: Boolean,
    modifier: Modifier,
    onLoadMore: () -> Unit,
) {
    val commits = tab == DetailTab.COMMITS
    val count = if (commits) state.commits.size else state.releases.size
    val end = if (commits) state.commitsEnd else state.releasesEnd
    val failed = if (commits) state.commitsFailed else state.releasesFailed
    val loading = if (commits) state.commitsLoading else state.releasesLoading
    val reloading = if (commits) state.commitsReloading else state.releasesReloading
    val paused = if (commits) state.commitsPagingPaused else state.releasesPagingPaused
    if (!reloading && !loading && !failed && !end && !paused && !state.manualRefresh && count > 0) {
        LaunchedEffect(count) { onLoadMore() }
    }
    val phase = when {
        failed && loading && !reloading -> HistoryFooterState.Retrying
        failed -> HistoryFooterState.Failed
        paused -> HistoryFooterState.Changed
        end && displayedCount == 0 -> HistoryFooterState.Empty
        end -> HistoryFooterState.End
        !reloading && loading && showPagingIndicator -> HistoryFooterState.Paging
        else -> HistoryFooterState.Idle
    }
    // 统一防闪烁兜底：快速失败时 Retrying 至少稳住 380ms（尊重关闭动画），成功则零延迟直出
    val dwellingRetry = io.candy.tick.ui.common.rememberMinDwellBusy(phase == HistoryFooterState.Retrying)
    val shownPhase = if (phase == HistoryFooterState.Failed && dwellingRetry) {
        HistoryFooterState.Retrying
    } else {
        phase
    }
    val duration = if (rememberMotionEnabled()) Motion.PHASE else 0
    val emptySpace = Modifier.fillParentMaxHeight(0.45f)
    AnimatedContent(
        targetState = shownPhase to displayedCount,
        contentKey = { it.first },
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
        transitionSpec = {
            (fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)))
                .using(SizeTransform { _, _ -> tween(duration, easing = Motion.Decelerate) })
        },
        label = "historyFooter",
    ) { (visiblePhase, visibleCount) ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Only full-list states reserve this space. Natural content can grow past it on short screens.
            if (visibleCount == 0 && visiblePhase != HistoryFooterState.Idle) Spacer(emptySpace)
            when (visiblePhase) {
                HistoryFooterState.Idle -> Unit
                HistoryFooterState.Paging -> Box(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    contentAlignment = Alignment.Center,
                ) { LoadingDots(dotSize = 5.dp) }
                HistoryFooterState.Retrying -> Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    LoadingDots(dotSize = 5.dp)
                }
                HistoryFooterState.Failed -> {
                    val message = stringResource(if (commits) R.string.commits_load_failed else R.string.releases_load_failed)
                    val retryEnabled = failed && !loading && !reloading
                    if (visibleCount > 0) {
                        InlineErrorRow(message, onRetry = onLoadMore, retryEnabled = retryEnabled)
                    } else {
                        StatusPage(
                            isError = true, title = message,
                            actionLabel = stringResource(R.string.retry), onAction = onLoadMore, actionEnabled = retryEnabled,
                        )
                    }
                }
                HistoryFooterState.Empty -> StatusPage(
                    icon = ImageVector.vectorResource(if (commits) R.drawable.ic_git_commit else R.drawable.ic_tag),
                    title = stringResource(if (commits) R.string.detail_no_commits else R.string.detail_no_releases),
                )
                HistoryFooterState.Changed -> top.yukonga.miuix.kmp.basic.Text(
                    text = stringResource(R.string.detail_history_changed),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                HistoryFooterState.End -> FooterText(
                    if (commits) R.plurals.footer_commits else R.plurals.footer_releases,
                    visibleCount,
                )
            }
        }
    }
}
