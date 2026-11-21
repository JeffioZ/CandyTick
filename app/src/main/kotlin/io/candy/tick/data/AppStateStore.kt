package io.candy.tick.data

import android.content.Context
import android.util.Log
import io.candy.tick.data.model.AppState
import io.candy.tick.data.model.RepoActivity
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/**
 * 状态中枢：内存全量持有（StateFlow）+ 单 JSON 文件原子写。
 * 订阅与设置同文件（design.md 持久化定案）。
 */
class AppStateStore internal constructor(filesDir: File, noBackupDir: File) {
    constructor(context: Context) : this(context.filesDir, context.noBackupFilesDir)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val file = File(filesDir, STATE_FILE)
    private val tokenFile = File(noBackupDir, "github_token")
    private var savedToken: String? = null
    private var needsMigration = false
    private val _state = MutableStateFlow(loadStateAndToken())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val stateLock = Any()
    private val writer = Mutex()
    private var saved = _state.value

    /** 内存变更按序发布，单个写入者合并等待期间的更新。返回时此次变更已尝试落盘。 */
    suspend fun update(transform: (AppState) -> AppState) {
        synchronized(stateLock) {
            _state.value = transform(_state.value)
        }
        withContext(NonCancellable + Dispatchers.IO) {
            writer.withLock {
                val latest = _state.value
                if ((needsMigration || latest !== saved) && persist(latest)) saved = latest
            }
        }
    }

    /** 导出为 JSON（仅订阅与快照；设置重置为默认——绝不把 Token 带进分享内容） */
    fun exportJson(): String {
        val current = _state.value
        val sanitized = current.copy(
            repos = current.repos.map { it.copy(downloadedApk = null) },
            snapshots = current.snapshots.mapValues { (_, snapshot) -> snapshot.copy(activity = RepoActivity()) },
            settings = io.candy.tick.data.model.Settings(),
            downloadedSelfUpdate = null,
        )
        return json.encodeToString(sanitized)
    }

    /**
     * 从备份 JSON 合并导入（按仓库 id 去重，不覆盖现有数据与设置）。
     * @return 新增订阅数；-1 = 文件解析失败
     */
    suspend fun importJson(text: String): Int {
        val imported = runCatching { json.decodeFromString<AppState>(text) }.getOrNull() ?: return -1
        var added = 0
        update { s ->
            val existing = s.repos.mapTo(HashSet()) { it.id }
            val newRepos = imported.repos.filter { existing.add(it.id) }.map { it.copy(downloadedApk = null) }
            added = newRepos.size
            if (newRepos.isEmpty()) {
                s
            } else {
                val newIds = newRepos.mapTo(HashSet()) { it.id }
                s.copy(
                    repos = s.repos + newRepos,
                    snapshots = s.snapshots + imported.snapshots.filterKeys { it in newIds }.mapValues { (_, snapshot) ->
                        snapshot.copy(importedCommitBaseline = true, importedReleaseBaseline = true, pendingTipSync = true,
                            activity = RepoActivity())
                    },
                )
            }
        }
        return added
    }

    private fun loadOrReset(): AppState = runCatching {
        file.takeIf { it.exists() }?.readText()?.let { text ->
            val loaded = json.decodeFromString<AppState>(text)
            // 旧版本允许在备份内重复订阅，并带回其他设备的本地文件名。
            val repos = loaded.repos.distinctBy { it.id }.map { repo ->
                val name = repo.downloadedApk?.fileName
                if (name != null && (File(name).name != name || name == "." || name == "..")) {
                    repo.copy(downloadedApk = null)
                } else repo
            }
            val ids = repos.mapTo(HashSet()) { it.id }
            val snapshots = loaded.snapshots.filterKeys { it in ids }.mapValues { (_, snapshot) ->
                if (loaded.lastImportAt != null && snapshot.lastSyncAt.isBefore(loaded.lastImportAt)) {
                    snapshot.copy(importedCommitBaseline = true, importedReleaseBaseline = true, pendingTipSync = true)
                } else snapshot
            }
            loaded.copy(repos = repos, snapshots = snapshots, lastImportAt = null)
        }
    }.getOrElse {
        // 损坏：留档取证、从头开始，绝不 crash
        Log.w(TAG, "state file corrupt, resetting", it)
        runCatching { file.renameTo(File(file.parentFile, file.name + ".corrupt")) }
        null
    } ?: AppState()

    private fun loadStateAndToken(): AppState {
        val state = loadOrReset()
        val legacy = state.settings.githubToken
        savedToken = try {
            tokenFile.takeIf { it.isFile }?.readText()
        } catch (e: IOException) {
            Log.w(TAG, "token read failed", e)
            return state
        }
        // 空文件表示用户明确清除了 token，进程中断时也不会重新启用旧 JSON 的值。
        val token = (savedToken ?: legacy)?.takeIf { it.isNotBlank() }
        val restored = state.copy(settings = state.settings.copy(githubToken = token))
        needsMigration = legacy != null
        if (needsMigration) persist(restored)
        return restored
    }

    private fun persist(state: AppState): Boolean =
        try {
            val token = state.settings.githubToken.orEmpty()
            if (token != savedToken.orEmpty() || (needsMigration && savedToken == null)) {
                atomicWrite(tokenFile, token)
                savedToken = token
            }
            atomicWrite(file, json.encodeToString(state.copy(settings = state.settings.copy(githubToken = null))))
            needsMigration = false
            true
        } catch (e: IOException) {
            // 落盘失败不阻断内存态（下次 update 重试）
            Log.w(TAG, "persist failed", e)
            false
        }

    private fun atomicWrite(destination: File, text: String) {
        val tmp = File(destination.parentFile, destination.name + ".tmp")
        tmp.outputStream().use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), destination.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), destination.toPath(), REPLACE_EXISTING)
        }
    }

    private companion object {
        const val TAG = "AppStateStore"
        const val STATE_FILE = "app_state.json"
    }
}

private val REPLACE_EXISTING = StandardCopyOption.REPLACE_EXISTING
private val ATOMIC_MOVE = StandardCopyOption.ATOMIC_MOVE
