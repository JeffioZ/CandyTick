package io.candy.tick.data

import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.data.model.TrackedRepo
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepoRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val tracked = TrackedRepo(42, "o", "r", addedAt = Instant.EPOCH)
    private val info = RepoInfo(
        githubId = 42, owner = "o", repo = "r", stars = 0, pushedAt = null, archived = false,
        description = null, etag = null, defaultBranch = "main",
    )

    @Test fun removingRepoDuringRefreshCannotRestoreItsSnapshot() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = repository {
            entered.complete(Unit)
            release.await()
            RepoInfoResult.Fetched(info)
        }
        repo.restoreRepo(tracked, info.toSnapshot())
        val refresh = async { repo.refreshRepo(tracked.id) }
        entered.await()
        repo.removeRepo(tracked.id)
        release.complete(Unit)
        refresh.await()
        assertTrue(repo.state.value.repos.isEmpty())
        assertTrue(repo.state.value.snapshots.isEmpty())
    }

    @Test fun concurrentAddsAndUndoKeepOneRepository() = runBlocking {
        val repo = repository { RepoInfoResult.Fetched(info) }
        val results = (1..8).map { async { repo.addRepo(info) } }.awaitAll()
        assertEquals(1, results.count { it is RepoRepository.AddResult.Added })
        assertEquals(7, results.count { it == RepoRepository.AddResult.Duplicate })
        repo.restoreRepo(tracked, info.toSnapshot())
        assertEquals(1, repo.state.value.repos.size)
    }

    @Test fun linkingAppWithMatchingCustomNamePreservesItsOrigin() = runBlocking {
        val repo = repository { RepoInfoResult.Fetched(info) }
        repo.restoreRepo(tracked.copy(noteName = "Custom"), null)
        repo.setLinkedPackage(tracked.id, "pkg", "Custom")
        assertFalse(repo.state.value.repos.single().autoNote)
        repo.setLinkedPackage(tracked.id, null)
        assertEquals("Custom", repo.state.value.repos.single().noteName)
    }

    @Test fun firstReleaseIsNewOnlyAfterObservingAnEmptyReleaseList() {
        val unknown = info.toSnapshot()
        val release = unknown.copy(latestReleaseTag = "1")
        assertFalse(SnapshotDiff.hasNewActivity(unknown, release))
        assertTrue(SnapshotDiff.hasNewActivity(unknown.copy(releaseBaselineKnown = true), release))
    }

    private fun repository(fetch: suspend () -> RepoInfoResult): RepoRepository {
        val files = temporary.newFolder("files")
        val store = AppStateStore(files, temporary.newFolder("noBackup"))
        val provider = object : SourceProvider {
            override suspend fun repoInfo(owner: String, repo: String, etag: String?) = fetch()
            override suspend fun commits(owner: String, repo: String, page: Int, sha: String?) = emptyList<CommitRecord>()
            override suspend fun releases(owner: String, repo: String, page: Int, perPage: Int) = emptyList<ReleaseRecord>()
        }
        return RepoRepository(store, mapOf(PROVIDER_GITHUB to provider), files)
    }
}
