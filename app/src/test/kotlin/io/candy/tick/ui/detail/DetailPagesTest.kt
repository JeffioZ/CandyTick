package io.candy.tick.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailPagesTest {
    private val previous = listOf("b", "a")

    @Test fun emptyRefreshClearsOldContent() {
        assertEquals(emptyList<String>(), mergePage(previous, 1, emptyList()) { it })
    }

    @Test fun failedRefreshPreservesContent() {
        assertSame(previous, mergePage(previous, 1, null) { it })
    }

    @Test fun overlappingPageKeepsUniqueStableKeys() {
        assertEquals(listOf("b", "a", "z"), mergePage(previous, 2, listOf("a", "z")) { it })
    }

    @Test fun pullJoinsSlowSilentRequestsUntilTheLastResultEvenWhenItFails() {
        val entering = DetailViewModel.UiState(snapshotLoading = true, commitsReloading = true, releasesReloading = true)
        assertFalse(entering.refreshing)
        val pulled = entering.withManualRefresh(requested = true)
        assertTrue(pulled.refreshing)
        assertSame(pulled, pulled.withManualRefresh(requested = true))
        val waiting = pulled.copy(snapshotLoading = false, commitsReloading = false).withManualRefresh()
        assertTrue(waiting.refreshing)
        val done = waiting.copy(releasesReloading = false, releasesFailed = true).withManualRefresh()
        assertFalse(done.manualRefresh)
        assertFalse(done.refreshing)
        assertFalse(done.copy(snapshotLoading = true).refreshing)
    }

    @Test fun changingBranchesDuringRefreshWaitsForTheReplacementPage() {
        val metadataPending = DetailViewModel.UiState(snapshotLoading = true).withManualRefresh(requested = true)
        val nextBranch = metadataPending.forNewBranch().copy(snapshotLoading = false).withManualRefresh()
        assertTrue(nextBranch.refreshing)
        assertFalse(nextBranch.copy(commitsReloading = false).withManualRefresh().manualRefresh)
        assertFalse(DetailViewModel.UiState().forNewBranch().refreshing)
    }

    @Test fun aCompletedOrPaginatingTimelineOffersNoSilentRefreshToJoin() {
        val paginating = DetailViewModel.UiState(commitsLoading = true)
        assertSame(paginating, paginating.withManualRefresh(requested = true))
        val settled = paginating.copy(commitsLoading = false)
        assertSame(settled, settled.withManualRefresh(requested = true))
    }
}
