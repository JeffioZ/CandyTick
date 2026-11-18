package io.candy.tick.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.candy.tick.CandyTickApp
import io.candy.tick.data.Notifications
import io.candy.tick.data.SelfUpdater

/**
 * 自身更新自动下载（一次性，仅 Wi-Fi）：
 * 下载（下载保留，只留最近一份）→ 12+ 且自身安装来源为本 app 时静默安装，
 * 否则通知栏「新版本已下载 · 点击安装」走系统确认。失败静默退避（retry）。
 */
class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CandyTickApp).container
        val updater = container.selfUpdater
        val settings = container.appStateStore.state.value.settings
        if (!settings.selfUpdateAutoDownload) return Result.success()

        var newState = updater.state.value as? SelfUpdater.State.NewVersion
        if (newState == null) {
            if (!updater.check()) return Result.success()
            newState = updater.state.value as? SelfUpdater.State.NewVersion ?: return Result.success()
        }
        val asset = newState.asset ?: return Result.success()

        val file = updater.download(asset) ?: return Result.retry() // 下载失败：静默退避
        return if (updater.canInstallSilently()) {
            updater.installSilently(file)
            Result.success()
        } else {
            Notifications.notifySelfUpdateReady(applicationContext, newState.tag)
            Result.success()
        }
    }
}
