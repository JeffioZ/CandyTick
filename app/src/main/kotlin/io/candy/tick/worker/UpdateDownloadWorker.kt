package io.candy.tick.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.candy.tick.CandyTickApp
import io.candy.tick.data.Notifications
import io.candy.tick.data.SelfUpdater
import io.candy.tick.util.forAppLanguage

/**
 * 自身更新自动下载（一次性，全网络——包仅 ~4MB，开关本身即用户同意）：
 * 下载（下载保留，只留最近一份）→ 12+ 且自身安装来源为本 app 时静默安装，
 * 否则通知栏「新版本已下载 · 点击安装」走系统确认。失败静默退避（retry）。
 */
class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CandyTickApp).container
        // 读盘持续失败：封顶后按成功收尾（一次性任务，下一轮自更新检查会重新入队）
        if (!container.appStateStore.ensureLoaded()) {
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        }
        val settings = container.appStateStore.state.value.settings
        if (!settings.canAutoDownloadUpdate) return Result.success()
        val updater = container.selfUpdater
        var newState = updater.state.value as? SelfUpdater.State.NewVersion
        if (newState == null) {
            if (!updater.check { it.canAutoDownloadUpdate }) {
                if (!container.appStateStore.state.value.settings.canAutoDownloadUpdate) return Result.success()
                // 补装：最新 tag 已下载但安装从未落地（进程被杀 / 上次会话中断）。
                // 每轮自更新检查重入一次；本进程内安装失败过的 tag 不再补装（防通知轰炸，用户可经弹窗手动重试）
                val downloaded = updater.state.value as? SelfUpdater.State.Downloaded
                if (downloaded != null) {
                    val current = container.appStateStore.state.value.settings
                    if (current.autoInstallDownload && updater.canInstallSilently() &&
                        updater.canRetrySilentInstall(downloaded.tag) && downloaded.file.isFile
                    ) {
                        updater.installSilently(downloaded.file)
                    }
                    return Result.success()
                }
                val failed = updater.state.value as? SelfUpdater.State.Failed
                return if (failed?.reason == SelfUpdater.Reason.NETWORK && runAttemptCount < 2) Result.retry() else Result.success()
            }
            newState = updater.state.value as? SelfUpdater.State.NewVersion ?: return Result.success()
        }
        val asset = newState.asset ?: return Result.success()
        if (newState.incomparable) return Result.success()

        val file = updater.downloadAutomatically(asset) ?: return when {
            !container.appStateStore.state.value.settings.canAutoDownloadUpdate -> Result.success()
            updater.state.value is SelfUpdater.State.Downloaded -> Result.success()
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
        val current = container.appStateStore.state.value.settings
        if (current.canAutoDownloadUpdate && current.autoInstallDownload && updater.canInstallSilently()) {
            updater.installSilently(file)
        } else if (current.notificationsEnabled) {
            Notifications.notifySelfUpdateReady(applicationContext.forAppLanguage(current.language), newState.tag)
        }
        return Result.success()
    }
}
