package io.candy.tick.ui.list

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.candy.tick.ui.common.Motion

/** Only IDs survive a home-screen visit. Every displayed row still comes from the latest UiState. */
internal data class HomeListOrder(
    val ids: List<Long>,
    val pinned: Set<Long>,
    val sort: SortOrder,
    val query: String,
) {
    fun whileReading(previous: HomeListOrder): HomeListOrder {
        if (sort != previous.sort || query != previous.query || ids == previous.ids) return this
        // Keep the relative order of existing rows. New rows and explicitly (un)pinned
        // rows take their current rank without applying unrelated, deferred rank changes.
        val kept = previous.ids.toMutableSet().apply {
            retainAll(ids.toSet())
            if (query.isEmpty() && pinned != previous.pinned) {
                removeAll { (it in pinned) != (it in previous.pinned) }
            }
        }
        val reading = previous.ids.filter { it in kept }.iterator()
        return copy(ids = ids.map { if (it in kept) reading.next() else it })
    }
}

/** 归一化进度（1f→0f）专用无回弹弹簧：0.001f 阈值避免长距离末尾 3~4px 跳停 */
private val ReorderSpringSpec: FiniteAnimationSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Motion.REORDER_STIFFNESS,
    visibilityThreshold = 0.001f,
)

internal sealed class HomeRowMotion(
    private val spec: FiniteAnimationSpec<Float>,
    val handlesFade: Boolean = false,
) {
    protected val remaining = Animatable(1f)
    var active by mutableStateOf(true)
        private set

    fun modifier(id: Long): Modifier = if (active) animatedModifier(id) else Modifier
    protected abstract fun animatedModifier(id: Long): Modifier

    suspend fun play(scrolling: Boolean) {
        try {
            if (active && !scrolling) remaining.animateTo(0f, spec)
        } finally {
            active = false
        }
    }
}

/** 列表滚在半路时切换排序回到顶部的兜底过渡：原地轻淡入 + 10dp 柔和落位，不拿旧滚动坐标乱飞 */
private class SortReveal(private val changedIds: Set<Long>) : HomeRowMotion(
    spec = tween(Motion.PANEL, easing = Motion.Decelerate),
    handlesFade = true,
) {
    override fun animatedModifier(id: Long): Modifier =
        if (id in changedIds) Modifier.graphicsLayer {
            val r = remaining.value
            alpha = 1f - 0.50f * r
            translationY = 10.dp.toPx() * r
        } else Modifier
}

/** Only animate between two measured positions in the viewport. A lazy list can otherwise
 * synthesize an origin or a destination at its edge for items that have moved off screen. */
private class HomeRowPlacement(
    private val listState: LazyListState,
    private val oldTops: Map<Long, Int>,
    private val raisedId: Long?,
    spec: FiniteAnimationSpec<Float> = tween(Motion.PANEL, easing = Motion.Decelerate),
    private val allowPartial: Boolean = false,
    private val fadeNewcomers: Boolean = false,
) : HomeRowMotion(spec = spec, handlesFade = fadeNewcomers) {
    private val distances by derivedStateOf {
        val layout = listState.layoutInfo
        buildMap {
            for (item in layout.visibleItemsInfo) {
                val id = item.key as? Long ?: continue
                val oldTop = oldTops[id] ?: continue
                val newTop = item.offset
                val inViewport = if (allowPartial) {
                    newTop < layout.viewportEndOffset && newTop + item.size > layout.viewportStartOffset
                } else {
                    newTop >= layout.viewportStartOffset && newTop + item.size <= layout.viewportEndOffset
                }
                if (oldTop != newTop && inViewport) put(id, oldTop - newTop)
            }
        }
    }

    override fun animatedModifier(id: Long): Modifier {
        if (id !in oldTops) {
            return if (fadeNewcomers) {
                Modifier.graphicsLayer {
                    val r = if (listState.isScrollInProgress) 0f else remaining.value
                    alpha = 1f - 0.65f * r
                    translationY = 10.dp.toPx() * r
                }
            } else Modifier
        }
        // 向上晋升（oldTop > newTop）或显式置顶的卡片临时浮在上层，避免被下让卡片遮挡
        val layer = if (id == raisedId || (distances[id] ?: 0) > 0) Modifier.zIndex(1f) else Modifier
        return layer.graphicsLayer {
            translationY = if (listState.isScrollInProgress) 0f else (distances[id] ?: 0) * remaining.value
        }
    }
}

private data class OrderChange(
    val motion: HomeRowMotion? = null,
    val anchorIndex: Int? = null,
    val anchorOffset: Int = 0,
)

internal data class HomeRows(val items: List<ListViewModel.RepoListItem>, val motion: HomeRowMotion?) {
    val revealingSort: Boolean get() = motion?.handlesFade == true && motion.active
}

@Composable
internal fun rememberHomeRows(
    vm: ListViewModel,
    state: ListViewModel.UiState,
    listState: LazyListState,
    resumed: Boolean,
    motionEnabled: Boolean,
    holdOrder: Boolean,
    keepReadingOrder: Boolean,
    headerCount: Int = 0,
): HomeRows {
    val ranked = remember(state.items, state.sortOrder, state.query) {
        HomeListOrder(
            ids = state.items.map { it.repo.id },
            pinned = state.items.mapNotNullTo(mutableSetOf()) { if (it.repo.pinnedAt != null) it.repo.id else null },
            sort = state.sortOrder,
            query = state.query.trim(),
        )
    }
    val atTop by remember(listState) {
        derivedStateOf {
            listState.layoutInfo.totalItemsCount > 0 && !listState.isScrollInProgress &&
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    val useLatest = resumed && atTop && !keepReadingOrder
    val order = remember(vm, ranked, useLatest, holdOrder) {
        val previous = vm.displayedHomeOrder
        when {
            previous == null -> ranked
            // Keep rendering fresh card data while the sheet owns the sorting interaction.
            holdOrder -> ranked.copy(sort = previous.sort).whileReading(previous)
            useLatest -> ranked
            else -> ranked.whileReading(previous)
        }
    }
    val change = remember(vm, listState, order) {
        val previous = vm.displayedHomeOrder
        if (previous == null || order.ids.isEmpty()) return@remember OrderChange()
        if (order.query != previous.query) return@remember OrderChange(anchorIndex = 0)
        if (previous.ids == order.ids) return@remember OrderChange()

        val canAnimate = resumed && motionEnabled && !holdOrder && !listState.isScrollInProgress
        val layout = listState.layoutInfo
        if (order.sort != previous.sort) {
            val sortMotion = if (!canAnimate) {
                null
            } else if (atTop) {
                val oldTops = buildMap {
                    for (row in layout.visibleItemsInfo) {
                        val id = row.key as? Long ?: continue
                        if (row.offset < layout.viewportEndOffset && row.offset + row.size > layout.viewportStartOffset) {
                            put(id, row.offset)
                        }
                    }
                }
                HomeRowPlacement(
                    listState = listState,
                    oldTops = oldTops,
                    raisedId = null,
                    spec = ReorderSpringSpec,
                    allowPartial = true,
                    fadeNewcomers = true,
                )
            } else {
                SortReveal(buildSet {
                    order.ids.forEachIndexed { index, id ->
                        if (previous.ids.getOrNull(index) != id) add(id)
                    }
                })
            }
            return@remember OrderChange(motion = sortMotion, anchorIndex = 0)
        }

        val pin = if (order.query.isEmpty() && order.pinned != previous.pinned) {
            val changed = (order.pinned - previous.pinned) + (previous.pinned - order.pinned)
            changed.singleOrNull()?.takeIf { it in previous.ids && it in order.ids }?.let { id ->
                planHomePinPlacement(previous.ids, order.ids, id, layout, atTop, headerCount = headerCount)
            }
        } else null
        val membershipChanged = previous.ids.size != order.ids.size || previous.ids.toSet() != order.ids.toSet()
        if (pin == null && !membershipChanged) {
            // 同步后名次变动（仅在顶部生效；滚在半路时已由 whileReading 锁死顺序，不会触达此处）
            val syncOldTops = if (canAnimate && atTop) {
                buildMap {
                    for (row in layout.visibleItemsInfo) {
                        val id = row.key as? Long ?: continue
                        if (row.offset < layout.viewportEndOffset && row.offset + row.size > layout.viewportStartOffset) {
                            put(id, row.offset)
                        }
                    }
                }
            } else emptyMap()
            return@remember OrderChange(
                motion = if (syncOldTops.isEmpty()) null else HomeRowPlacement(
                    listState = listState,
                    oldTops = syncOldTops,
                    raisedId = null,
                    spec = ReorderSpringSpec,
                    allowPartial = true,
                    fadeNewcomers = true,
                ),
                anchorIndex = if (atTop) 0 else null,
            )
        }
        val oldTops = if (canAnimate) {
            buildMap {
                for (row in layout.visibleItemsInfo) {
                    val id = row.key as? Long ?: continue
                    if ((pin == null || id in pin.animatedIds) && row.offset >= layout.viewportStartOffset &&
                        row.offset + row.size <= layout.viewportEndOffset
                    ) put(id, row.offset)
                }
            }
        } else emptyMap()
        OrderChange(
            motion = if (oldTops.isEmpty()) null else HomeRowPlacement(listState, oldTops, pin?.raisedId),
            anchorIndex = pin?.anchorIndex ?: if (atTop) 0 else null,
            anchorOffset = pin?.anchorScrollOffset ?: 0,
        )
    }
    SideEffect {
        if (vm.displayedHomeOrder != order) {
            vm.displayedHomeOrder = order
            change.anchorIndex?.let { listState.requestScrollToItem(it, change.anchorOffset) }
        }
    }
    val motion = change.motion?.takeIf { motionEnabled && resumed && !holdOrder }
    val scrolling = motion != null && listState.isScrollInProgress
    LaunchedEffect(motion, scrolling) { motion?.play(scrolling) }
    val items = remember(state.items, ranked.ids, order.ids) {
        if (order.ids == ranked.ids) state.items else {
            val live = state.items.associateBy { it.repo.id }
            order.ids.map { live.getValue(it) }
        }
    }
    return HomeRows(items, motion)
}
