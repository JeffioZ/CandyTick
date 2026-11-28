package io.candy.tick.ui.list

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePinPlacementTest {

    private data class FakeItem(
        override val index: Int,
        override val key: Any,
        override val offset: Int,
        override val size: Int,
    ) : LazyListItemInfo

    private data class FakeLayout(
        override val visibleItemsInfo: List<LazyListItemInfo>,
        override val totalItemsCount: Int,
        override val viewportEndOffset: Int = 800,
        override val mainAxisItemSpacing: Int = 8,
    ) : LazyListLayoutInfo {
        override val viewportStartOffset: Int = 0
        override val viewportSize: IntSize = IntSize(400, viewportEndOffset)
        override val orientation: Orientation = Orientation.Vertical
        override val reverseLayout: Boolean = false
        override val beforeContentPadding: Int = 0
        override val afterContentPadding: Int = 0
    }

    @Test
    fun pinWithBannerHeaderAnimatesAndOffsetsAnchorIndex() {
        val oldIds = listOf(10L, 20L, 30L)
        val newIds = listOf(30L, 10L, 20L)
        val layout = FakeLayout(
            visibleItemsInfo = listOf(
                FakeItem(index = 0, key = "home_banner", offset = 0, size = 0),
                FakeItem(index = 1, key = 10L, offset = 8, size = 100),
                FakeItem(index = 2, key = 20L, offset = 116, size = 100),
                FakeItem(index = 3, key = 30L, offset = 224, size = 100),
            ),
            totalItemsCount = 4,
        )

        val placement = planHomePinPlacement(
            oldIds = oldIds,
            newIds = newIds,
            movedId = 30L,
            layout = layout,
            atTop = true,
            headerCount = 1,
        )

        assertEquals(30L, placement.raisedId)
        assertTrue(setOf(10L, 20L, 30L).all { it in placement.animatedIds })
        assertEquals(0, placement.anchorIndex)
    }

    @Test
    fun pinMidListWithBannerHeaderOffsetsAnchorIndexByHeaderCount() {
        val oldIds = listOf(1L, 2L, 3L, 4L, 5L)
        val newIds = listOf(4L, 1L, 2L, 3L, 5L)
        val layout = FakeLayout(
            visibleItemsInfo = listOf(
                FakeItem(index = 3, key = 3L, offset = 0, size = 120),
                FakeItem(index = 4, key = 4L, offset = 128, size = 120),
                FakeItem(index = 5, key = 5L, offset = 256, size = 120),
            ),
            totalItemsCount = 6,
            viewportEndOffset = 400,
        )

        val placement = planHomePinPlacement(
            oldIds = oldIds,
            newIds = newIds,
            movedId = 4L,
            layout = layout,
            atTop = false,
            headerCount = 1,
        )

        // Anchor is 3L (rank 3 in newIds -> LazyColumn index 4 with headerCount = 1)
        assertEquals(newIds.indexOf(3L) + 1, placement.anchorIndex)
    }
}
