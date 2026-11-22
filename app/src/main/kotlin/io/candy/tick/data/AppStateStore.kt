package io.candy.tick.data

import android.content.Context
import android.util.Log
import io.candy.tick.data.model.AppState
import io.candy.tick.data.model.RepoActivity
import io.candy.tick.data.model.RepoSnapshot
import io.candy.tick.data.model.TrackedRepo
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.decodeFromString

/**
 * 状态中枢：内存全量持有（StateFlow）+ 单 JSON 文件原子写。
 * 订阅与设置同文件（design.md 持久化定案）。
 */
@OptIn(ExperimentalSerializationApi::class)
class AppStateStore internal constructor(filesDir: File, noBackupDir: File) {
    constructor(context: Context) : this(context.filesDir, context.noBackupFilesDir)

    internal enum class SaveStatus { SAVED, FAILED, RETRYING, RETRY_FAILED }
    internal enum class LoadStatus { READY, FAILED, RETRYING }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val file = File(filesDir, STATE_FILE)
    private val tokenFile = File(noBackupDir, "github_token")
    private var savedToken: String? = null
    private var needsMigration = false
    private val _saveStatus = MutableStateFlow(SaveStatus.SAVED)
    internal val saveStatus = _saveStatus.asStateFlow()
    private val _loadStatus = MutableStateFlow(LoadStatus.READY)
    internal val loadStatus = _loadStatus.asStateFlow()

    // 必须先于 _state 声明：构造期 readState() 的损坏分支就要 tryEmit，后置声明会初始化顺序 NPE
    /** 本进程内发生过「损坏重置」。导出守卫用它拦住空列表覆盖用户唯一旧备份。 */
    @Volatile internal var corruptReset = false
        private set
    private val _corruptResetNotices = MutableSharedFlow<Unit>(replay = 1)
    /** 损坏重置通知；replay=1 因为构造期读盘即可能触发，晚到的收集器也要收到一次 */
    internal val corruptResetNotices = _corruptResetNotices.asSharedFlow()

    private val _state = MutableStateFlow(readState() ?: AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val stateLock = Any()
    private val writer = Mutex()
    private var saved = _state.value

    internal suspend fun awaitLoaded() {
        if (_loadStatus.value != LoadStatus.READY) {
            // FAILED 先自愈一次：瞬时 IO 故障可当场恢复；持续失败维持挂起语义（由调用方限时兜底）
            if (_loadStatus.value == LoadStatus.FAILED) ensureLoaded()
            loadStatus.first { it == LoadStatus.READY }
        }
    }

    internal suspend fun ensureLoaded(): Boolean {
        if (_loadStatus.value == LoadStatus.READY) return true
        return withContext(Dispatchers.IO) {
            writer.withLock {
                if (_loadStatus.value == LoadStatus.READY) return@withLock true
                _loadStatus.value = LoadStatus.RETRYING
                val restored = readState() ?: return@withLock false
                _state.value = restored
                saved = _state.value
                _loadStatus.value = LoadStatus.READY
                true
            }
        }
    }

    /** 内存变更按序发布，单个写入者合并等待期间的更新。返回时此次变更已尝试落盘。 */
    suspend fun update(transform: (AppState) -> AppState) {
        awaitLoaded()
        synchronized(stateLock) {
            _state.value = transform(_state.value)
        }
        saveLatest()
    }

    internal suspend fun retrySave(): Boolean {
        val previous = _saveStatus.value
        if (previous != SaveStatus.FAILED && previous != SaveStatus.RETRY_FAILED) return false
        if (!_saveStatus.compareAndSet(previous, SaveStatus.RETRYING)) return false
        saveLatest(manualRetry = true)
        return _saveStatus.value == SaveStatus.SAVED
    }

    private suspend fun saveLatest(manualRetry: Boolean = false) {
        withContext(NonCancellable + Dispatchers.IO) {
            writer.withLock {
                val latest = _state.value
                if (latest === saved && _saveStatus.value == SaveStatus.SAVED) return@withLock
                if (!persist(latest)) {
                    if (manualRetry) _saveStatus.value = SaveStatus.RETRY_FAILED
                    else _saveStatus.compareAndSet(SaveStatus.SAVED, SaveStatus.FAILED)
                    return@withLock
                }
                saved = latest
                synchronized(stateLock) {
                    // A newer edit has its own write pending. Only dismiss when disk has caught up.
                    if (_state.value === latest) _saveStatus.value = SaveStatus.SAVED
                    else if (manualRetry) _saveStatus.compareAndSet(SaveStatus.RETRYING, SaveStatus.FAILED)
                }
            }
        }
    }

    @Serializable
    private data class SubscriptionBackup(val repos: List<TrackedRepo>, val snapshots: Map<Long, RepoSnapshot> = emptyMap())

    /** null = 数据未就绪（读盘失败 / 损坏重置后仍是空列表）：调用方应中止导出而不是分享空备份 */
    fun exportJson(): String? {
        if (_loadStatus.value != LoadStatus.READY) return null
        val current = _state.value
        if (corruptReset && current.repos.isEmpty()) return null
        val sanitized = SubscriptionBackup(
            repos = current.repos.map { it.copy(downloadedApk = null) },
            snapshots = current.snapshots.mapValues { (_, snapshot) -> snapshot.copy(activity = RepoActivity()) },
        )
        return json.encodeToString(sanitized)
    }

    /**
     * 从备份 JSON 合并导入（按仓库 id 去重，不覆盖现有数据与设置）。
     * @return 新增订阅数；-1 = 文件解析失败
     */
    suspend fun importJson(text: String): Int {
        val imported = try {
            json.decodeFromString<SubscriptionBackup>(text)
        } catch (_: IllegalArgumentException) { return -1 }
        return mergeBackup(imported)
    }

    suspend fun importJson(input: InputStream): Int {
        val imported = try {
            json.decodeFromStream<SubscriptionBackup>(input)
        } catch (_: IllegalArgumentException) { return -1 }
        return mergeBackup(imported)
    }

    private suspend fun mergeBackup(imported: SubscriptionBackup): Int {
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

    private fun loadOrReset(): AppState = try {
        Files.newInputStream(file.toPath()).use { input ->
            val loaded = json.decodeFromStream<AppState>(input)
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
    } catch (_: NoSuchFileException) {
        AppState()
    } catch (e: kotlinx.serialization.SerializationException) {
        Log.w(TAG, "state file corrupt, keeping a copy", e)
        Files.move(file.toPath(), File(file.parentFile, file.name + ".corrupt").toPath(), REPLACE_EXISTING)
        // 必须让用户看见重置，而不是表现为「订阅悄悄变空」；.corrupt 副本留在 files 目录供人工恢复
        corruptReset = true
        _corruptResetNotices.tryEmit(Unit)
        AppState()
    }

    private fun readState(): AppState? = try {
        loadStateAndToken()
    } catch (e: Exception) {
        if (e !is IOException && e !is SecurityException) throw e
        Log.w(TAG, "state unavailable, waiting for retry", e)
        _loadStatus.value = LoadStatus.FAILED
        null
    }

    private fun loadStateAndToken(): AppState {
        val state = loadOrReset()
        val legacy = state.settings.githubToken
        savedToken = try {
            String(Files.readAllBytes(tokenFile.toPath()), Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            null
        }
        // 空文件表示用户明确清除了 token，进程中断时也不会重新启用旧 JSON 的值。
        val token = (savedToken ?: legacy)?.takeIf { it.isNotBlank() }
        val restored = state.copy(settings = state.settings.copy(githubToken = token))
        needsMigration = legacy != null
        if (needsMigration && !persist(restored)) _saveStatus.value = SaveStatus.FAILED
        return restored
    }

    private fun persist(state: AppState): Boolean =
        try {
            val token = state.settings.githubToken.orEmpty()
            if (token != savedToken.orEmpty() || (needsMigration && savedToken == null)) {
                atomicWrite(tokenFile) { it.write(token.toByteArray(Charsets.UTF_8)) }
                savedToken = token
            }
            atomicWrite(file) { output ->
                json.encodeToStream(state.copy(settings = state.settings.copy(githubToken = null)), output)
            }
            needsMigration = false
            true
        } catch (e: Exception) {
            if (e !is IOException && e !is SecurityException) throw e
            Log.w(TAG, "persist failed", e)
            false
        }

    private fun atomicWrite(destination: File, write: (OutputStream) -> Unit) {
        val tmp = File(destination.parentFile, destination.name + ".tmp")
        tmp.outputStream().use {
            write(it)
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
