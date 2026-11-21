package io.candy.tick.ui.list

import androidx.compose.animation.core.Animatable
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

internal sealed class HomeRowMotion(private val duration: Int) {
    protected val remaining = Animatable(1f)
    var active by mutableStateOf(true)
        private set

    fun modifier(id: Long): Modifier = if (active) animatedModifier(id) else Modifier
    protected abstract fun animatedModifier(id: Long): Modifier

    suspend fun play(scrolling: Boolean) {
        try {
            if (active && !scrolling) remaining.animateTo(0f, tween(duration, easing = Motion.Decelerate))
        } finally {
            active = false
        }
    }
}

/** The new ranking is readable from the first frame. Rows that kept their rank stay still. */
private class SortReveal(private val changedIds: Set<Long>) : HomeRowMotion(Motion.MICRO) {
    override fun animatedModifier(id: Long): Modifier =
        if (id in changedIds) Modifier.graphicsLayer { alpha = 1f - 0.24f * remaining.value }
        else Modifier
}

/** Only animate between two measured positions in the viewport. A lazy list can otherwise
 * synthesize an origin or a destination at its edge for items that have moved off screen. */
private class HomeRowPlacement(
    private val listState: LazyListState,
    private val oldTops: Map<Long, Int>,
    private val raisedId: Long?,
) : HomeRowMotion(Motion.PANEL) {
    private val distances by derivedStateOf {
        val layout = listState.layoutInfo
        buildMap {
            for (item in layout.visibleItemsInfo) {
                val id = item.key as? Long ?: continue
                val oldTop = oldTops[id] ?: continue
                val newTop = item.offset
                if (oldTop != newTop && newTop >= layout.viewportStartOffset &&
                    newTop + item.size <= layout.viewportEndOffset
                ) put(id, oldTop - newTop)
            }
        }
    }

    override fun animatedModifier(id: Long): Modifier {
        if (id !in oldTops) return Modifier
        val layer = if (id == raisedId) Modifier.zIndex(1f) else Modifier
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
    val revealingSort: Boolean get() = motion is SortReveal && motion.active
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
        if (order.sort != previous.sort) {
            val reveal = if (canAnimate) {
                SortReveal(buildSet {
                    order.ids.forEachIndexed { index, id ->
                        if (previous.ids.getOrNull(index) != id) add(id)
                    }
                })
            } else null
            return@remember OrderChange(motion = reveal, anchorIndex = 0)
        }

        val layout = listState.layoutInfo
        val pin = if (order.query.isEmpty() && order.pinned != previous.pinned) {
            val changed = (order.pinned - previous.pinned) + (previous.pinned - order.pinned)
            changed.singleOrNull()?.takeIf { it in previous.ids && it in order.ids }?.let { id ->
                planHomePinPlacement(previous.ids, order.ids, id, layout, atTop)
            }
        } else null
        val membershipChanged = previous.ids.size != order.ids.size || previous.ids.toSet() != order.ids.toSet()
        val oldTops = if (canAnimate && (pin != null || membershipChanged)) {
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
