package io.candy.tick.ui.detail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.candy.tick.ui.common.LoadingDots
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.detail.DetailViewModel.DetailTab
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val HistoryItemSpacing = 10.dp
private const val PLACEHOLDER_TYPE = "history-placeholder"
private val DetailTab.placeholderHeight get() = if (this == DetailTab.COMMITS) 60.dp else 88.dp

/** Measure the free space after the tabs (or the notification lookup message). Using the actual
 * list viewport accounts for wrapping headers, banners, insets and split-screen windows. */
@Composable
internal fun visiblePlaceholderCount(list: LazyListState, tab: DetailTab, precedingKey: String): Int {
    val cardHeight = with(LocalDensity.current) { tab.placeholderHeight.roundToPx() }
    val count by remember(list, tab, cardHeight, precedingKey) {
        derivedStateOf {
            val layout = list.layoutInfo
            val gap = layout.mainAxisItemSpacing
            val preceding = layout.visibleItemsInfo.firstOrNull { it.key == precedingKey }
            val top = if (preceding != null) preceding.offset + preceding.size + gap else {
                layout.visibleItemsInfo.firstOrNull { it.contentType == PLACEHOLDER_TYPE }?.offset
                    ?: return@derivedStateOf 0
            }
            val available = layout.viewportEndOffset - layout.afterContentPadding - maxOf(top, layout.viewportStartOffset)
            val limit = if (tab == DetailTab.COMMITS) 4 else 2
            ((available + gap) / (cardHeight + gap)).coerceIn(0, limit)
        }
    }
    return count
}

@Composable
internal fun rememberHistoryContentOpacity(placeholderVisible: Boolean, motionEnabled: Boolean): State<Float> {
    // Brief appearances only dim the incoming content in proportion to how far the skeleton got.
    val duration = if (placeholderVisible) Motion.PANEL else Motion.MICRO
    return animateFloatAsState(
        targetValue = if (motionEnabled && placeholderVisible) 0f else 1f,
        animationSpec = tween(if (motionEnabled) duration else 0, easing = LinearEasing),
        label = "historyContentOpacity",
    )
}

internal fun LazyListScope.historyPlaceholders(tab: DetailTab, count: Int, motionEnabled: Boolean) {
    items(count, key = { "placeholder:${tab.name}:$it" }, contentType = { PLACEHOLDER_TYPE }) { index ->
        val reveal = if (motionEnabled) Modifier.animateItem(
            fadeInSpec = tween(Motion.PANEL, delayMillis = 24 * index, easing = LinearEasing),
            placementSpec = null,
            fadeOutSpec = tween(Motion.MICRO, easing = LinearEasing),
        ) else Modifier
        val bar = MiuixTheme.colorScheme.dividerLine
        val corners = RoundedCornerShape(4.dp)
        // Existing rows retain their tone when more complete cards fit in the viewport.
        Card(reveal.fillMaxWidth().alpha(0.88f - 0.1f * index)) {
            Column(
                modifier = Modifier.fillMaxWidth().height(tab.placeholderHeight).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                val titleWidth = if (index % 2 == 0) 0.76f else 0.60f
                if (tab == DetailTab.COMMITS) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Spacer(Modifier.fillMaxWidth(0.34f).height(8.dp).background(bar, corners))
                        Spacer(Modifier.width(36.dp).height(8.dp).background(bar, corners))
                    }
                    Spacer(Modifier.fillMaxWidth(titleWidth).height(12.dp).background(bar, corners))
                } else {
                    Spacer(Modifier.fillMaxWidth(titleWidth).height(14.dp).background(bar, corners))
                    Spacer(Modifier.fillMaxWidth(0.38f).height(8.dp).background(bar, corners))
                }
            }
        }
    }
}

@Composable
internal fun DetailPagingIndicator() {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        LoadingDots(dotSize = 5.dp)
    }
}
