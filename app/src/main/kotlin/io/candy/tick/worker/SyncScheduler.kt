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
import java.util.concurrent.TimeUnit

/** 周期同步调度（频率 + 仅 Wi-Fi / 仅充电约束；设置变更即重排） */
class SyncScheduler(private val context: Context) {

    fun apply(settings: Settings) {
        val manager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .apply {
                if (settings.wifiOnly) setRequiredNetworkType(NetworkType.UNMETERED)
                if (settings.chargingOnly) setRequiresCharging(true)
            }
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(settings.syncFrequency.hours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(WORK_SYNC, ExistingPeriodicWorkPolicy.UPDATE, request)
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

    companion object {
        const val WORK_SYNC = "candytick_sync"
        const val WORK_UPDATE_DOWNLOAD = "candytick_update_download"
    }
}
