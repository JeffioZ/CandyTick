package io.candy.tick.data

import android.content.Context
import android.util.Log
import io.candy.tick.data.model.AppState
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/**
 * 状态中枢：内存全量持有（StateFlow）+ 单 JSON 文件原子写。
 * 订阅与设置同文件（design.md 持久化定案）。
 */
class AppStateStore(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val file = File(context.filesDir, STATE_FILE)
    private val _state = MutableStateFlow(loadOrReset())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** 单一写入口：先更新内存，再异步落盘（结构相等则跳过） */
    suspend fun update(transform: (AppState) -> AppState) {
        val next = transform(_state.value)
        if (next != _state.value) {
            _state.value = next
            withContext(Dispatchers.IO) { persist(next) }
        }
    }

    private fun loadOrReset(): AppState = runCatching {
        file.takeIf { it.exists() }?.readText()?.let { json.decodeFromString<AppState>(it) }
    }.getOrElse {
        // 损坏：留档取证、从头开始，绝不 crash
        Log.w(TAG, "state file corrupt, resetting", it)
        runCatching { file.renameTo(File(file.parentFile, file.name + ".corrupt")) }
        null
    } ?: AppState()

    private fun persist(state: AppState) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(state))
            try {
                Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            // 落盘失败不阻断内存态（下次 update 重试）
            Log.w(TAG, "persist failed", e)
        }
    }

    private companion object {
        const val TAG = "AppStateStore"
        const val STATE_FILE = "app_state.json"
    }
}

private val REPLACE_EXISTING = StandardCopyOption.REPLACE_EXISTING
private val ATOMIC_MOVE = StandardCopyOption.ATOMIC_MOVE
