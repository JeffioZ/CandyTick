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

private val stateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 状态中枢：内存全量持有（StateFlow）+ 单 JSON 文件原子写。
 * 订阅与设置同文件（design.md 持久化定案）。
 */
@OptIn(ExperimentalSerializationApi::class)
class AppStateStore internal constructor(filesDir: File, noBackupDir: File) {
    constructor(context: Context) : this(context.filesDir, context.noBackupFilesDir)

    internal enum class SaveStatus { SAVED, FAILED, RETRYING, RETRY_FAILED }
    internal enum class LoadStatus { READY, FAILED, RETRYING }

    // Keep the historical filename for Android backup / transfer, while retaining all
    // device-specific state in the canonical file outside both backup transports.
    private val file = File(filesDir, STATE_FILE)
    private val localFile = File(noBackupDir, DEVICE_STATE_FILE)
    private val restoredBySystem = File(noBackupDir, RESTORE_MARKER)
    private var pendingRestoreId: String? = null
    private val tokenFile = File(noBackupDir, "github_token")
    private val recoveryMarker = File(noBackupDir, RECOVERY_MARKER)
    private val legacyCorrupt = File(filesDir, "$STATE_FILE.corrupt")
    private var savedToken: String? = null
    private var needsMigration = false
    private var savedProjection: AppState? = null
    private val _saveStatus = MutableStateFlow(SaveStatus.SAVED)
    internal val saveStatus = _saveStatus.asStateFlow()
    private val _loadStatus = MutableStateFlow(LoadStatus.READY)
    internal val loadStatus = _loadStatus.asStateFlow()

    // 必须先于 _state 声明：构造期 readState() 的损坏分支就要 tryEmit，后置声明会初始化顺序 NPE
    /** 订阅恢复前，启动清理保留损坏时已有的文件。 */
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
        updateAndConfirm(transform)
    }

    suspend fun updateAndConfirm(transform: (AppState) -> AppState): Boolean {
        awaitLoaded()
        synchronized(stateLock) {
            _state.value = transform(_state.value)
        }
        return saveLatest()
    }

    internal suspend fun retrySave(): Boolean {
        val previous = _saveStatus.value
        if (previous != SaveStatus.FAILED && previous != SaveStatus.RETRY_FAILED) return false
        if (!_saveStatus.compareAndSet(previous, SaveStatus.RETRYING)) return false
        saveLatest(manualRetry = true)
        return _saveStatus.value == SaveStatus.SAVED
    }

    private suspend fun saveLatest(manualRetry: Boolean = false): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            writer.withLock {
                val latest = _state.value
                if (latest === saved && _saveStatus.value == SaveStatus.SAVED) return@withLock true
                if (!persist(latest, finishRecovery = latest.repos.size != saved.repos.size)) {
                    if (manualRetry) _saveStatus.value = SaveStatus.RETRY_FAILED
                    else _saveStatus.compareAndSet(SaveStatus.SAVED, SaveStatus.FAILED)
                    return@withLock false
                }
                saved = latest
                synchronized(stateLock) {
                    // A newer edit has its own write pending. Only dismiss when disk has caught up.
                    if (_state.value === latest) _saveStatus.value = SaveStatus.SAVED
                    else if (manualRetry) _saveStatus.compareAndSet(SaveStatus.RETRYING, SaveStatus.FAILED)
                }
                true
            }
        }

    @Serializable
    private data class SubscriptionBackup(val repos: List<TrackedRepo>, val snapshots: Map<Long, RepoSnapshot> = emptyMap())

    /** null = 数据未就绪（读盘失败 / 损坏重置后仍是空列表）：调用方应中止导出而不是分享空备份 */
    fun exportJson(): String? {
        if (_loadStatus.value != LoadStatus.READY) return null
        val current = _state.value
        if (current.repos.isEmpty() && recoveryMarker.isFile) return null
        // 只导仓库基础信息（含关联包名/备注/置顶；不含已下载包记录）。快照是同步机器的
        // 内脏（ETag/基线/上次已知状态），跨设备无意义且 schema 一变旧备份就解析失败；
        // 裸导入 = 全新添加语义：首刷静默立基线不误亮未读，无网时卡片为现成的无徽章骨架态。
        // snapshots 字段保留于数据类（默认空）——旧版完整备份仍照常导入（带基线压制）
        val sanitized = SubscriptionBackup(
            repos = current.repos.map { it.copy(downloadedApk = null) },
        )
        return stateJson.encodeToString(sanitized)
    }

    /**
     * 从备份 JSON 合并导入（按仓库 id 去重，不覆盖现有数据与设置）。
     * @return 新增订阅数；-1 = 文件解析失败
     */
    suspend fun importJson(text: String): Int {
        val imported = try {
            stateJson.decodeFromString<SubscriptionBackup>(text)
        } catch (_: IllegalArgumentException) { return -1 }
        return mergeBackup(imported)
    }

    suspend fun importJson(input: InputStream): Int {
        val imported = try {
            stateJson.decodeFromStream<SubscriptionBackup>(input)
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

    private fun loadOrReset(source: File): AppState = try {
        readStateFile(source)
    } catch (_: NoSuchFileException) {
        AppState()
    } catch (e: kotlinx.serialization.SerializationException) {
        Log.w(TAG, "state file corrupt, keeping a copy", e)
        // Record detection time before moving the only copy. Renaming a file retains its mtime.
        markRecovery()
        archiveCorrupt(source)
        // 留下检测时间和原始字节，供后续恢复。
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
        if (legacyCorrupt.isFile) {
            // Legacy renames preserved the source's old mtime. Date this recovery once on upgrade.
            if (!recoveryMarker.isFile) markRecovery()
            archiveCorrupt(legacyCorrupt)
        }
        val restoreId = if (restoredBySystem.isFile) restoredBySystem.readText() else null
        pendingRestoreId = restoreId
        val restoredInput = restoreId?.let { File(localFile.parentFile, "state-$it.restore") }
        val localSource = if (localFile.isFile) localFile else file
        val hadSource = localSource.isFile || restoredInput?.isFile == true
        var state = loadOrReset(localSource)
        // Old local preferences could only be set for installed apps. Staged and applied system
        // restores may contain apps that have never been present here.
        val tracked = state.settings.storePrefs.trackedPackages
        val migrateLocalPrefs = localSource.isFile && restoreId == null && state.appliedRestoreId == null &&
            state.storeState.observedPreferencePackages != tracked
        if (migrateLocalPrefs) state = state.copy(storeState = state.storeState.copy(observedPreferencePackages = tracked))
        if (localSource == localFile && !localFile.isFile && file.isFile) state = loadOrReset(file).portable()
        val fromRestore = restoredInput?.isFile == true && state.appliedRestoreId != restoreId
        if (fromRestore) {
            val input = requireNotNull(restoredInput)
            val incoming = loadOrReset(input)
            if (input.isFile) state = state.mergeSystemRestore(incoming, requireNotNull(restoreId))
        }
        val legacy = state.settings.githubToken
        savedToken = try {
            String(Files.readAllBytes(tokenFile.toPath()), Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            null
        }
        // 空文件表示用户明确清除了 token，进程中断时也不会重新启用旧 JSON 的值。
        val token = (savedToken ?: legacy)?.takeIf { it.isNotBlank() }
        val restored = state.copy(settings = state.settings.copy(githubToken = token), backupFileStamp = null)
        needsMigration = legacy != null
        val published = !fromRestore && localFile.isFile && file.isFile && state.backupFileStamp == file.stamp() &&
            !temporaryFile(file).exists()
        savedProjection = if (published) restored.portable() else null
        if (hadSource && (!published || needsMigration || migrateLocalPrefs || corruptReset || restoreId != null)) {
            val recovered = fromRestore && restored.appliedRestoreId == restoreId && restored.repos.isNotEmpty()
            if (!persist(restored, finishRecovery = recovered)) _saveStatus.value = SaveStatus.FAILED
        }
        return restored
    }

    private fun markRecovery() = atomicWrite(recoveryMarker) { it.write(System.currentTimeMillis().toString().toByteArray()) }

    private fun archiveCorrupt(source: File) {
        val archive = File(localFile.parentFile, source.name + "." + java.util.UUID.randomUUID() + ".corrupt")
        Files.move(source.toPath(), archive.toPath())
    }

    private fun persist(state: AppState, finishRecovery: Boolean): Boolean =
        try {
            val token = state.settings.githubToken.orEmpty()
            if (token != savedToken.orEmpty() || (needsMigration && savedToken == null)) {
                atomicWrite(tokenFile) { it.write(token.toByteArray(Charsets.UTF_8)) }
                savedToken = token
            }
            val projected = state.portable()
            val staged = if (savedProjection != projected || !file.isFile || temporaryFile(file).exists()) {
                writeTemporary(file) { output -> stateJson.encodeToStream(projected, output) }
            } else null
            // The old backup stays intact until the authoritative device file commits. An
            // interrupted publication leaves the temporary copy for startup to detect.
            atomicWrite(localFile) { output ->
                stateJson.encodeToStream(state.copy(settings = state.settings.copy(githubToken = null),
                    backupFileStamp = (staged ?: file).stamp()), output)
            }
            if (staged != null) {
                publish(staged, file)
                savedProjection = projected
            }
            pendingRestoreId?.let { id ->
                if (restoredBySystem.isFile && restoredBySystem.readText() == id) {
                    Files.deleteIfExists(restoredBySystem.toPath())
                    File(localFile.parentFile, "state-$id.restore").delete()
                }
                pendingRestoreId = null
            }
            if (finishRecovery && recoveryMarker.isFile) {
                Files.delete(recoveryMarker.toPath())
                corruptReset = false
            }
            needsMigration = false
            true
        } catch (e: Exception) {
            if (e !is IOException && e !is SecurityException) throw e
            Log.w(TAG, "persist failed", e)
            false
        }

    private fun temporaryFile(destination: File) = File(destination.parentFile, destination.name + ".tmp")

    private fun writeTemporary(destination: File, write: (OutputStream) -> Unit): File {
        val tmp = temporaryFile(destination)
        tmp.outputStream().use {
            write(it)
            it.fd.sync()
        }
        return tmp
    }

    private fun atomicWrite(destination: File, write: (OutputStream) -> Unit) =
        publish(writeTemporary(destination, write), destination)

    private fun publish(tmp: File, destination: File) {
        try {
            Files.move(tmp.toPath(), destination.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), destination.toPath(), REPLACE_EXISTING)
        }
    }

    internal companion object {
        const val TAG = "AppStateStore"
        const val STATE_FILE = "app_state.json"
        const val DEVICE_STATE_FILE = "device_state.json"
        const val RECOVERY_MARKER = "state_recovery_pending"
        const val RESTORE_MARKER = "system_restore_pending"
    }
}

private fun File.stamp() = "${length()}:${lastModified()}"

@OptIn(ExperimentalSerializationApi::class)
private fun readStateFile(source: File): AppState = Files.newInputStream(source.toPath()).use { input ->
    val loaded = stateJson.decodeFromStream<AppState>(input)
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

private fun AppState.mergeSystemRestore(restored: AppState, restoreId: String): AppState {
    val incoming = restored.portable()
    val localRepos = repos.associateBy { it.id }
    val compatibleIds = incoming.repos.filter { repo ->
        val existing = localRepos[repo.id]
        existing == null || existing.branch == repo.branch
    }.mapTo(HashSet()) { it.id }
    return copy(
        repos = repos + incoming.repos.filter { it.id !in localRepos },
        snapshots = incoming.snapshots.filterKeys { it in compatibleIds } + snapshots,
        settings = incoming.settings.copy(
            githubToken = settings.githubToken,
            githubUsername = settings.githubUsername,
            translationDisclosureAccepted = settings.translationDisclosureAccepted,
        ),
        appliedRestoreId = restoreId,
    )
}

private fun AppState.portable() = AppState(
    repos = repos.map { if (it.downloadedApk == null) it else it.copy(downloadedApk = null) },
    snapshots = snapshots,
    settings = settings.copy(githubToken = null, githubUsername = null, translationDisclosureAccepted = false),
)

/** Full-backup restores (including historical full-state files) apply device-independent data only. */
class StateBackupAgent : android.app.backup.BackupAgentHelper() {
    private val transferFile get() = File(filesDir, "portable_state.json")

    @OptIn(ExperimentalSerializationApi::class)
    override fun onFullBackup(data: android.app.backup.FullBackupDataOutput) {
        val published = File(filesDir, AppStateStore.STATE_FILE)
        val local = File(noBackupFilesDir, AppStateStore.DEVICE_STATE_FILE).takeIf { it.isFile } ?: published
        val marker = File(noBackupFilesDir, AppStateStore.RESTORE_MARKER)
        val restoreId = marker.takeIf { it.isFile }?.readText()
        val staged = restoreId?.let { File(noBackupFilesDir, "state-$it.restore") }
        val copy = transferFile
        try {
            var state = if (local.isFile) readStateFile(local) else AppState()
            if (staged != null && staged.isFile && state.appliedRestoreId != restoreId) {
                state = state.mergeSystemRestore(readStateFile(staged), requireNotNull(restoreId))
            }
            if (state.repos.isEmpty() && File(noBackupFilesDir, AppStateStore.RECOVERY_MARKER).isFile) {
                throw IOException("Subscriptions are awaiting recovery")
            }
            if (!local.isFile && staged?.isFile != true) return
            if (restoreId == null && local != published && published.isFile &&
                state.backupFileStamp == published.stamp() && !File(filesDir, "${published.name}.tmp").exists()
            ) {
                fullBackupFile(published, data)
                return
            }
            copy.outputStream().use { stateJson.encodeToStream(state.portable(), it) }
            fullBackupFile(copy, data)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw IOException("Cannot read application state for backup", e)
        } finally {
            copy.delete()
        }
    }

    override fun onRestoreFile(
        data: android.os.ParcelFileDescriptor, size: Long, destination: File, type: Int,
        mode: Long, mtime: Long,
    ) {
        val target = destination.canonicalFile
        if (type != TYPE_FILE || (target != File(filesDir, AppStateStore.STATE_FILE).canonicalFile && target != transferFile.canonicalFile)) {
            super.onRestoreFile(data, size, destination, type, mode, mtime)
            return
        }
        // Stage a complete incoming backup without overwriting same-device data.
        val id = java.util.UUID.randomUUID().toString()
        val staged = File(noBackupFilesDir, "state-$id.restore")
        if (size < 0) throw IOException("Invalid restored state size")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(android.os.ParcelFileDescriptor.dup(data.fileDescriptor)).use { input ->
            staged.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = size
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count <= 0) throw java.io.EOFException("Incomplete restored application state")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.fd.sync()
            }
        }
        val marker = android.util.AtomicFile(File(noBackupFilesDir, AppStateStore.RESTORE_MARKER))
        val stream = marker.startWrite()
        try {
            stream.write(id.toByteArray(Charsets.UTF_8))
            marker.finishWrite(stream)
        } catch (e: IOException) {
            marker.failWrite(stream)
            throw e
        }
    }
}

private val REPLACE_EXISTING = StandardCopyOption.REPLACE_EXISTING
private val ATOMIC_MOVE = StandardCopyOption.ATOMIC_MOVE
