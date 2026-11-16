package io.candy.tick.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.candy.tick.CandyTickApp
import io.candy.tick.data.Notifications
import io.candy.tick.data.PROVIDER_GITHUB

/**
 * 周期同步：刷新全部快照（ETag 条件请求）→ 记录 / 通知新版本 → 自身更新检查。
 * 一切失败静默退避（WorkManager 自带重试），不打扰。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CandyTickApp).container
        val state = container.appStateStore.state.value
        val settings = state.settings

        // 1) 快照刷新（单仓库失败不拖垮其余）
        container.repoRepository.refreshAll()

        // 2) 新版本检测：latestReleaseTag 与上次不同才通知（首次记录不打扰）
        for (tracked in state.repos) {
            val latestTag = runCatching {
                container.repoRepository.releases(tracked.id, 1).firstOrNull()?.tagName
            }.getOrNull()
            val previous = state.snapshots[tracked.id]?.latestReleaseTag
            runCatching { container.repoRepository.setLatestReleaseTag(tracked.id, latestTag) }
            if (latestTag != null && previous != null && latestTag != previous && settings.notificationsEnabled) {
                Notifications.notifyRepoNewVersion(
                    context = applicationContext,
                    repoName = tracked.noteName ?: "${tracked.owner}/${tracked.repo}",
                    tag = latestTag,
                    repoId = tracked.id,
                )
            }
        }

        // 3) 自身更新（检查默认开；自动下载按设置入队）
        if (settings.selfUpdateCheck) {
            val hasNew = container.selfUpdater.check()
            if (hasNew && settings.selfUpdateAutoDownload) {
                container.syncScheduler.enqueueUpdateDownload()
            }
        }

        return Result.success()
    }
}
