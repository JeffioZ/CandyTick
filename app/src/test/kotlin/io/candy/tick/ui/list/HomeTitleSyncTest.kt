package io.candy.tick.ui.list

import io.candy.tick.data.SyncStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTitleSyncTest {
    @Test fun returningDuringTheSameRunKeepsTheTitleQuiet() {
        val title = HomeTitleSync()
        val first = SyncStatus.Running()
        assertFalse(title.shouldAnimate(SyncStatus.Idle))
        assertTrue(title.shouldAnimate(first))
        title.leave(first)
        assertFalse(title.shouldAnimate(first))
        assertFalse(title.shouldAnimate(SyncStatus.Done(first, changed = 1, failed = 0)))
        assertTrue(title.shouldAnimate(SyncStatus.Running()))
    }
}
