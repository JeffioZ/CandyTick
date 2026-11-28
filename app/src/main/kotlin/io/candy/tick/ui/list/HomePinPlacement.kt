package io.candy.tick.ui.list

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListItemInfo
import kotlin.math.abs

internal data class HomePinPlacement(
    val animatedIds: Set<Long>,
    val raisedId: Long?,
    val anchorIndex: Int?,
    val anchorScrollOffset: Int = 0,
)

/** Plan one pin/unpin from the layout that was on screen when the order changed. */
internal fun planHomePinPlacement(
    oldIds: List<Long>,
    newIds: List<Long>,
    movedId: Long,
    layout: LazyListLayoutInfo,
    atTop: Boolean,
    headerCount: Int = 0,
): HomePinPlacement {
    val stationary = HomePinPlacement(emptySet(), null, if (atTop && newIds.isNotEmpty()) 0 else null)
    val from = oldIds.indexOf(movedId)
    val to = newIds.indexOf(movedId)
    if (from < 0 || to < 0 || from == to || oldIds.size != newIds.size) return stationary
    if (oldIds.filterNot { it == movedId } != newIds.filterNot { it == movedId }) return stationary

    fun LazyListItemInfo.rank() = index - headerCount
    val visible = layout.visibleItemsInfo
    val cards = visible.filter { it.key is Long }
    if (cards.any { oldIds.getOrNull(it.rank()) != it.key }) return stationary
    val start = layout.viewportStartOffset
    val end = layout.viewportEndOffset
    if (end <= start || cards.isEmpty()) return stationary
    fun fits(y: Int, height: Int) = y >= start && y + height <= end

    val source = cards.firstOrNull { it.key == movedId }
    val neighbors = cards.filter { it.key != movedId && it.offset < end && it.offset + it.size > start }
        .sortedWith(compareBy({ !fits(it.offset, it.size) }, { abs(it.rank() - from) }))
    if (source == null || neighbors.isEmpty()) {
        if (atTop) return stationary
        val neighbor = neighbors.firstOrNull()
        // An expanded card can fill the entire viewport. Keep its former neighbor
        // in view instead of letting a stable key follow the card across the list.
        val id = neighbor?.key as? Long ?: source?.let { oldIds.getOrNull(from + 1) ?: oldIds.getOrNull(from - 1) }
        return HomePinPlacement(emptySet(), null, id?.let { newIds.indexOf(it) + headerCount }, -(neighbor?.offset ?: 0))
    }

    val stride = source.size + layout.mainAxisItemSpacing
    fun shiftOf(index: Int) = when {
        index in to until from -> stride
        index > from && index <= to -> -stride
        else -> 0
    }

    // Positions at the old scroll offset. Moving down, the trailing edge of the
    // destination slot stays fixed even when that slot has a different height.
    val slot = cards.firstOrNull { it.rank() == to }
    val destination = slot?.let { if (to < from) it.offset else it.offset + it.size - source.size }
    val sameScreen = destination != null && fits(source.offset, source.size) && fits(destination, source.size)
    val minScroll = if (atTop) 0 else cards.firstOrNull { it.rank() == 0 }?.offset?.coerceAtMost(0) ?: Int.MIN_VALUE
    val footer = visible.firstOrNull { it.index == layout.totalItemsCount - 1 && it.key !is Long }
    val maxScroll = footer?.let { (it.offset + it.size + layout.afterContentPadding - end).coerceAtLeast(0) }
        ?: Int.MAX_VALUE
    fun clipped(scroll: Int): Boolean {
        val y = destination?.minus(scroll) ?: return false
        return y < end && y + source.size > start && !fits(y, source.size)
    }

    // Prefer an unaffected, fully visible neighbor that can stay at the exact
    // same pixel without leaving a sliver of the moved card at either edge.
    var anchor = neighbors.firstOrNull {
        val shift = shiftOf(it.rank())
        // For an unmeasured destination, a row *between* the two ranks is the
        // reliable anchor: the moved card ends up beyond that side of the viewport.
        if (destination == null) shift != 0 else !clipped(shift.coerceIn(minScroll, maxScroll))
    }
        ?: neighbors.first()
    fun topAfter(item: LazyListItemInfo, scroll: Int) = item.offset + shiftOf(item.rank()) - scroll
    fun overlapsAfter(item: LazyListItemInfo, scroll: Int): Boolean {
        val y = topAfter(item, scroll)
        return y < end && y + item.size > start
    }
    var scroll = if (atTop || sameScreen) 0 else shiftOf(anchor.rank()).coerceIn(minScroll, maxScroll)
    if (destination != null && clipped(scroll)) {
        // When neither neighbor can stay fixed, move the viewport the shortest
        // distance needed to put the card fully on one side of the edge.
        val clearEdge = buildList {
            add(destination - end)
            add(destination + source.size - start)
            if (source.size <= end - start) {
                add(scroll.coerceIn(destination + source.size - end, destination - start))
            }
        }
        val options = clearEdge.filter {
            it in minScroll..maxScroll && (source.size <= end - start || abs(it - scroll) <= (end - start) / 2)
        }.sortedBy { abs(it - scroll) }
        // Even at the top, a small forward scroll can reveal the whole card.
        // Keep the same neighbor fully visible if possible, then accept a
        // partially visible neighbor before picking another one on this screen.
        scroll = options.firstOrNull { fits(topAfter(anchor, it), anchor.size) }
            ?: options.firstOrNull { overlapsAfter(anchor, it) }
            ?: options.firstOrNull { step -> neighbors.any { fits(topAfter(it, step), it.size) } }
            ?: scroll
        if (!overlapsAfter(anchor, scroll)) {
            anchor = neighbors.firstOrNull { fits(topAfter(it, scroll), it.size) } ?: anchor
        }
    }

    val raised = movedId.takeIf { sameScreen }
    val animated = buildSet {
        if (raised != null) add(raised)
        for (item in neighbors) {
            val delta = shiftOf(item.rank()) - scroll
            if (delta != 0 && abs(delta) <= (end - start) / 2 &&
                fits(item.offset, item.size) && fits(item.offset + delta, item.size)
            ) add(item.key as Long)
        }
    }
    if (atTop && scroll == 0) return HomePinPlacement(animated, raised, 0)
    val index = newIds.indexOf(anchor.key as Long) + headerCount
    return HomePinPlacement(animated, raised, index, -topAfter(anchor, scroll))
}
