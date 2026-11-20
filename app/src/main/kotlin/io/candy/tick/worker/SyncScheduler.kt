package io.candy.tick.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.candy.tick.data.model.Settings
import io.candy.tick.data.model.SyncFrequency
import java.util.concurrent.TimeUnit

/** 同步调度：周期频率（含「每次打开应用」模式）+ 仅 Wi-Fi / 仅充电约束；设置变更即重排 */
class SyncScheduler(private val context: Context) {

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

    /** 自身更新自动下载（一次性，仅 Wi-Fi，退避重试） */
    fun enqueueUpdateDownload() {
        val manager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build(),
            )
            .build()
        manager.enqueueUniqueWork(WORK_UPDATE_DOWNLOAD, ExistingWorkPolicy.KEEP, request)
    }

    @Volatile
    private var lastOnOpenSync: Long = 0L

    companion object {
        const val WORK_SYNC = "candytick_sync"
        const val WORK_SYNC_ON_OPEN = "candytick_sync_on_open"
        const val WORK_UPDATE_DOWNLOAD = "candytick_update_download"
        private const val ON_OPEN_THROTTLE_MS = 10 * 60 * 1000L
    }
}
