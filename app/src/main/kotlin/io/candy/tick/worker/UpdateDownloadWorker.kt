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
                    updater.installAutomatically(downloaded)
                    return Result.success()
                }
                val failed = updater.state.value as? SelfUpdater.State.Failed
                // 限流同样退避重试（WorkManager backoff 可能跨过限额窗口，≤2 次代价可忽略）——
                // 拆出 RATE_LIMITED 后这里若只认 NETWORK，后台检查撞限流会静默变成「不重试」
                val retryable = failed?.reason == SelfUpdater.Reason.NETWORK || failed?.reason == SelfUpdater.Reason.RATE_LIMITED
                return if (retryable && runAttemptCount < 2) Result.retry() else Result.success()
            }
            newState = updater.state.value as? SelfUpdater.State.NewVersion ?: return Result.success()
        }
        if (newState.asset == null || newState.incomparable) return Result.success()

        val downloaded = updater.downloadAutomatically(newState) ?: return when {
            !container.appStateStore.state.value.settings.canAutoDownloadUpdate -> Result.success()
            updater.state.value is SelfUpdater.State.Downloaded -> Result.success()
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
        if (!updater.installAutomatically(downloaded)) {
            val current = container.appStateStore.state.value.settings
            if (current.notificationsEnabled && updater.state.value == downloaded) {
                Notifications.notifySelfUpdateReady(applicationContext.forAppLanguage(current.language), downloaded.tag)
            }
        }
        return Result.success()
    }
}
