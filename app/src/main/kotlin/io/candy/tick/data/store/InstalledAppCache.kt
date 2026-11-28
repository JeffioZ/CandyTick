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

    private val _scan = MutableStateFlow<InstalledAppScan?>(null)
    val scan: StateFlow<InstalledAppScan?> = _scan.asStateFlow()

    private val mutex = Mutex()
    @Volatile
    private var loadedForSystemFlag: Boolean? = null

    val isLoaded: Boolean get() = loadedForSystemFlag != null

    suspend fun ensureLoaded(includeSystemApps: Boolean) {
        if (loadedForSystemFlag == includeSystemApps) return
        refresh(includeSystemApps)
    }

    suspend fun refresh(includeSystemApps: Boolean): InstalledAppScan = mutex.withLock {
        val scanned = withContext(Dispatchers.IO) {
            InstalledAppScanner.scan(appContext, includeSystemApps = includeSystemApps)
        }
        loadedForSystemFlag = includeSystemApps.takeIf { scanned.complete }
        _scan.value = scanned
        scanned
    }
}

internal const val PERM_GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS"

internal fun needsInstalledAppsPermission(context: Context): Boolean {
    val supported = runCatching { context.packageManager.getPermissionInfo(PERM_GET_INSTALLED_APPS, 0) }.isSuccess
    return supported && androidx.core.content.ContextCompat.checkSelfPermission(context, PERM_GET_INSTALLED_APPS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
}
