package io.candy.tick.data.store

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App 级本机已装应用快照缓存：
 * - 在后台提前预热（App 启动 / 开启渠道 / 装包完成），进「本机应用更新」页首帧即有数据，
 *   消除「先空后跳出数字」的突兀感与进场转场掉帧；
 * - 下拉刷新或切换「检查系统应用」时走 [refresh] 强制重扫。
 */
class InstalledAppCache(private val appContext: Context) {

    private val _apps = MutableStateFlow<List<LocalInstalledApp>>(emptyList())
    val apps: StateFlow<List<LocalInstalledApp>> = _apps.asStateFlow()

    private val mutex = Mutex()
    @Volatile
    private var loadedForSystemFlag: Boolean? = null

    val isLoaded: Boolean get() = loadedForSystemFlag != null

    suspend fun ensureLoaded(includeSystemApps: Boolean) {
        if (loadedForSystemFlag == includeSystemApps && _apps.value.isNotEmpty()) return
        refresh(includeSystemApps)
    }

    suspend fun refresh(includeSystemApps: Boolean): List<LocalInstalledApp> = mutex.withLock {
        val scanned = withContext(Dispatchers.IO) {
            InstalledAppScanner.scan(appContext, includeSystemApps = includeSystemApps)
        }
        loadedForSystemFlag = includeSystemApps
        if (scanned != _apps.value) {
            _apps.value = scanned
        }
        scanned
    }
}
