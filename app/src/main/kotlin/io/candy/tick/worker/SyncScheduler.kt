package io.candy.tick.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import io.candy.tick.data.model.Settings
import io.candy.tick.data.model.SyncFrequency
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** 同步调度：周期频率（含「每次打开应用」模式）+ 仅 Wi-Fi 约束（按设置）；设置变更即重排 */
class SyncScheduler(private val context: Context) {

    private val scheduling = Mutex()
    private var lastOnOpenSync = -1L

    private fun networkConstraints(settings: Settings) = Constraints.Builder()
        .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    suspend fun apply(settings: Settings) {
        // 设置已写入磁盘，离开设置页时也要完成相应的任务切换。
        withContext(NonCancellable) {
            scheduling.withLock {
                val manager = WorkManager.getInstance(context)
                val constraints = networkConstraints(settings)
                if (settings.syncFrequency == SyncFrequency.ON_OPEN) {
                    manager.cancelUniqueWork(WORK_SYNC).await()
                    scheduleOnOpen(manager, constraints, enqueueIfMissing = false)
                } else {
                    manager.cancelUniqueWork(WORK_SYNC_ON_OPEN).await()
                    lastOnOpenSync = -1L
                    val request = PeriodicWorkRequestBuilder<SyncWorker>(
                        settings.syncFrequency.hours.toLong(), TimeUnit.HOURS,
                    ).setConstraints(constraints).build()
                    manager.enqueueUniquePeriodicWork(WORK_SYNC, ExistingPeriodicWorkPolicy.UPDATE, request).await()
                }
            }
        }
    }

    /**
     * 「每次打开应用」模式：前台切换（冷/热启动）触发一次同步。
     * 节流 60 秒，避免快速切换前后台反复排队。
     */
    suspend fun maybeSyncOnOpen(settings: Settings): Unit = scheduling.withLock {
        if (settings.syncFrequency != SyncFrequency.ON_OPEN) return@withLock
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastOnOpenSync >= 0 && now - lastOnOpenSync < ON_OPEN_THROTTLE_MS) return@withLock
        scheduleOnOpen(WorkManager.getInstance(context), networkConstraints(settings), enqueueIfMissing = true)
        lastOnOpenSync = now
    }

    private suspend fun scheduleOnOpen(manager: WorkManager, constraints: Constraints, enqueueIfMissing: Boolean) {
        val existing = manager.getWorkInfosForUniqueWorkFlow(WORK_SYNC_ON_OPEN).first()
            .firstOrNull { !it.state.isFinished }
        if (existing == null && !enqueueIfMissing) return
        if (existing?.constraints?.requiredNetworkType == constraints.requiredNetworkType) return

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // 仅在网络约束实际变化时替换，确保收紧限制后正在执行的任务也停止使用计费网络。
        val policy = if (existing == null) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        manager.enqueueUniqueWork(WORK_SYNC_ON_OPEN, policy, request).await()
    }

    /** 自身更新自动下载（一次性，全网络，退避重试——包仅 ~4MB，开关本身即用户同意） */
    suspend fun enqueueUpdateDownload() {
        val manager = WorkManager.getInstance(context)
        val builder = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
        val existing = manager.getWorkInfosForUniqueWorkFlow(WORK_UPDATE_DOWNLOAD).first()
            .firstOrNull { !it.state.isFinished }
        if (existing == null) {
            manager.enqueueUniqueWork(WORK_UPDATE_DOWNLOAD, ExistingWorkPolicy.KEEP, builder.build())
        } else if (existing.constraints.requiredNetworkType != NetworkType.CONNECTED) {
            // 升级旧版仅限 Wi-Fi 的排队任务，保留正在下载的任务和已有重试退避。
            manager.updateWork(builder.setId(existing.id).build())
        }
    }

    companion object {
        const val WORK_SYNC = "candytick_sync"
        const val WORK_SYNC_ON_OPEN = "candytick_sync_on_open"
        const val WORK_UPDATE_DOWNLOAD = "candytick_update_download"
        private const val ON_OPEN_THROTTLE_MS = 60_000L // 防快速前后台抖动；「每次打开」承诺以 1 分钟为粒度兑现

    }
}
