package io.candy.tick.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.candy.tick.CandyTickApp
import io.candy.tick.data.Notifications
import io.candy.tick.data.SelfUpdater

/**
 * 自身更新自动下载（一次性，全网络——包仅 ~4MB，开关本身即用户同意）：
 * 下载（下载保留，只留最近一份）→ 12+ 且自身安装来源为本 app 时静默安装，
 * 否则通知栏「新版本已下载 · 点击安装」走系统确认。失败静默退避（retry）。
 */
class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CandyTickApp).container
        if (!container.appStateStore.ensureLoaded()) return Result.retry()
        val updater = container.selfUpdater
        val settings = container.appStateStore.state.value.settings
        if (!settings.selfUpdateAutoDownload) return Result.success()

        var newState = updater.state.value as? SelfUpdater.State.NewVersion
        if (newState == null) {
            if (!updater.check()) {
                val failed = updater.state.value as? SelfUpdater.State.Failed
                return if (failed?.reason == SelfUpdater.Reason.NETWORK && runAttemptCount < 2) Result.retry() else Result.success()
            }
            newState = updater.state.value as? SelfUpdater.State.NewVersion ?: return Result.success()
        }
        val asset = newState.asset ?: return Result.success()
        if (newState.incomparable) return Result.success()

        val file = updater.download(asset) ?: return when {
            updater.state.value is SelfUpdater.State.Downloaded -> Result.success()
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
        val current = container.appStateStore.state.value.settings
        if (current.selfUpdateAutoDownload && current.autoInstallDownload && updater.canInstallSilently()) {
            updater.installSilently(file)
        } else if (current.notificationsEnabled) {
            Notifications.notifySelfUpdateReady(applicationContext, newState.tag)
        }
        return Result.success()
    }
}
