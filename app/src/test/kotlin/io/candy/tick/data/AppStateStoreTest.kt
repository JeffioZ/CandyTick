package io.candy.tick.data

import io.candy.tick.data.model.AppState
import io.candy.tick.data.model.DownloadedApk
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.data.model.RepoSnapshot
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 旧版（瘦身前）完整备份的写出格式：全部字段都显式写出 */
private val legacyJson = Json { encodeDefaults = true }

class AppStateStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun concurrentChangesSurviveRestart() = runBlocking {
        val files = temporary.newFolder("files")
        val noBackup = temporary.newFolder("noBackup")
        val store = AppStateStore(files, noBackup)
        coroutineScope {
            (1L..100L).forEach { id ->
                launch(Dispatchers.Default) {
                    store.update { it.copy(repos = it.repos + repo(id)) }
                }
            }
        }
        assertEquals(100, store.state.value.repos.size)
        assertEquals(store.state.value, AppStateStore(files, noBackup).state.value)
    }

    @Test fun migratesLegacyCredentialAndKeepsRemovalAcrossRestarts() = runBlocking {
        val files = temporary.newFolder("files")
        val noBackup = temporary.newFolder("noBackup")
        val stateFile = File(files, "app_state.json")
        val legacy = """{"settings":{"githubToken":"test-legacy-secret"}}"""
        stateFile.writeText(legacy)
        val store = AppStateStore(files, noBackup)
        assertEquals("test-legacy-secret", store.state.value.settings.githubToken)
        assertFalse(stateFile.readText().contains("test-legacy-secret"))
        // 测试内存储存就绪，导出必非空（null 语义只用于运行时守卫）
        assertFalse(store.exportJson()!!.contains("test-legacy-secret"))

        val reopened = AppStateStore(files, noBackup)
        assertEquals("test-legacy-secret", reopened.state.value.settings.githubToken)
        reopened.update { it.copy(settings = it.settings.copy(githubToken = null)) }
        // Simulate an interrupted migration leaving the old JSON behind.
        stateFile.writeText(legacy)
        assertNull(AppStateStore(files, noBackup).state.value.settings.githubToken)
        assertFalse(stateFile.readText().contains("test-legacy-secret"))
    }

    @Test fun backupImportDeduplicatesAndDropsDeviceLocalDownloads() = runBlocking {
        val store = AppStateStore(temporary.newFolder("files"), temporary.newFolder("noBackup"))
        val oldRepo = """{"id":1,"owner":"o","repo":"r","addedAt":"2020-01-01T00:00:00Z","downloadedApk":{"fileName":"old.apk","version":"1"}}"""
        val backup = """{"repos":[$oldRepo,$oldRepo],"settings":{"githubToken":"backup-secret"}}"""
        assertEquals(1, store.importJson(backup))
        assertEquals(0, store.importJson(backup))
        assertNull(store.state.value.settings.githubToken)
        assertNull(store.state.value.repos.single().downloadedApk)
        store.update { it.copy(repos = listOf(it.repos.single().copy(downloadedApk = DownloadedApk("local.apk", "2")))) }
        assertFalse(store.exportJson()!!.contains("local.apk"))
    }

    private fun repo(id: Long) = TrackedRepo(id, "owner", "repo-$id", addedAt = Instant.EPOCH)

    @Test fun importingRepositoryOnlySuppressesItsOwnFirstActivity() = runBlocking {
        val snapshot = RepoSnapshot(0, null, false, null, Instant.EPOCH, null,
            latestReleaseTag = "1", lastCommitSha = "a")
        val target = AppStateStore(temporary.newFolder("target"), temporary.newFolder("targetCredentials"))
        // 导出已瘦身（不带快照）；带基线的导入只剩旧版完整备份这一条路，直接喂旧格式
        val legacyBackup = legacyJson.encodeToString(
            AppState(repos = listOf(repo(2)), snapshots = mapOf(2L to snapshot)),
        )
        target.update { it.copy(repos = listOf(repo(1)), snapshots = mapOf(1L to snapshot)) }
        assertEquals(1, target.importJson(legacyBackup))
        val after = snapshot.copy(latestReleaseTag = "2", lastCommitSha = "b")
        assertTrue(SnapshotDiff.hasNewActivity(target.state.value.snapshots.getValue(1), after))
        assertFalse(SnapshotDiff.hasNewActivity(target.state.value.snapshots.getValue(2), after))
    }
}
