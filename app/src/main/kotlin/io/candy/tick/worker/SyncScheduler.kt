package io.candy.tick.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.candy.tick.data.model.Settings
import io.candy.tick.data.model.SyncFrequency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** 同步调度：周期频率（含「每次打开应用」模式）+ 仅 Wi-Fi 约束（按设置）；设置变更即重排 */
class SyncScheduler(
    private val context: Context,
    private val syncStatus: kotlinx.coroutines.flow.MutableStateFlow<io.candy.tick.data.SyncStatus>,
    private val watchdogScope: kotlinx.coroutines.CoroutineScope,
) {

    fun apply(settings: Settings) {
        val manager = WorkManager.getInstance(context)
        if (settings.syncFrequency == SyncFrequency.ON_OPEN) {
            manager.cancelUniqueWork(WORK_SYNC) // 打开即同步模式下不排后台周期任务
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .apply {
                if (settings.wifiOnly) setRequiredNetworkType(NetworkType.UNMETERED)
            }
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(settings.syncFrequency.hours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(WORK_SYNC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * 「每次打开应用」模式：前台切换（冷/热启动）触发一次同步，
     * 10 分钟节流防止频繁切前后台打爆配额（304 不计配额，但保守为上）。
     */
    fun maybeSyncOnOpen(settings: Settings) {
        if (settings.syncFrequency != SyncFrequency.ON_OPEN) return
        val now = System.currentTimeMillis()
        if (now - lastOnOpenSync < ON_OPEN_THROTTLE_MS) return
        lastOnOpenSync = now
        // 乐观置 Running：enqueue 即点亮顶栏波点——WorkManager 加急作业在 ROM 省电策略/
        // 网络约束（仅 Wi-Fi）未满足时会排队延迟，此前队列期间无任何 UI 反馈（"没反应"）；
        // Worker 开跑后覆盖为真实进度，完成设 Done。
        syncStatus.value = io.candy.tick.data.SyncStatus.Running(0, 1)
        armOnOpenWatchdog()
        val manager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            // 与周期同步同规则：用户开了「仅 Wi-Fi 同步」后打开即同步也不走流量
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(
                    if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                ).build(),
            )
            // 加急作业：消除 WorkManager 调度延迟，启动即跑（10 分钟节流远在加急配额内）
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        manager.enqueueUniqueWork(WORK_SYNC_ON_OPEN, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * 开屏哨兵看门狗：Running(0,1) 只由这里写入（worker 真实进度 step>0 / total=2N）。
     * 排队作业可能因「仅 Wi-Fi 无 Wi-Fi」无限不执行——此前 sentinel 永不落 Done，
     * 首页下拉刷新的让位等待被永久锁死。超时仍见哨兵即回 Idle（波点收起、刷新可走）。
     */
    private fun armOnOpenWatchdog() {
        watchdogScope.launch {
            kotlinx.coroutines.delay(WATCHDOG_MS)
            val current = syncStatus.value
            if (current is io.candy.tick.data.SyncStatus.Running && current.done == 0 && current.total == 1) {
                syncStatus.value = io.candy.tick.data.SyncStatus.Idle
            }
        }
    }

    /** 自身更新自动下载（一次性，全网络，退避重试——包仅 ~4MB，开关本身即用户同意） */
    fun enqueueUpdateDownload() {
        val manager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        // 手工实现「排队换新 / 运行不动」（一次性策略没有 UPDATE）：KEEP 会让揣着旧规格的
        // 排队任务永生（旧版 UNMETERED 约束升级后仍焊死在请求上）；REPLACE 会掐断运行中的
        // 下载重下、取消临安装的任务错过通知。运行中→跳过，其余→REPLACE 换新规格
        val running = runCatching {
            manager.getWorkInfosForUniqueWork(WORK_UPDATE_DOWNLOAD).get()
                .any { it.state == WorkInfo.State.RUNNING }
        }.getOrDefault(true) // 查询失败宁可不入队（下轮同步自然再来）
        if (!running) {
            manager.enqueueUniqueWork(WORK_UPDATE_DOWNLOAD, ExistingWorkPolicy.REPLACE, request)
        }
    }

    @Volatile
    private var lastOnOpenSync: Long = 0L

    companion object {
        const val WORK_SYNC = "candytick_sync"
        const val WORK_SYNC_ON_OPEN = "candytick_sync_on_open"
        const val WORK_UPDATE_DOWNLOAD = "candytick_update_download"
        private const val ON_OPEN_THROTTLE_MS = 10 * 60 * 1000L

        /** 哨兵超时：加急作业正常启动远快于此；被约束卡住即解锁 UI */
        private const val WATCHDOG_MS = 25_000L
    }
}
