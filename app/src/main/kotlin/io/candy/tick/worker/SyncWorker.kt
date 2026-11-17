package io.candy.tick.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.candy.tick.CandyTickApp
import io.candy.tick.data.Notifications
import io.candy.tick.data.SnapshotDiff
import io.candy.tick.data.SyncStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

/**
 * 周期同步：刷新全部快照（ETag 条件请求）→ 记录 / 通知新版本 → 自身更新检查。
 * 一切失败静默退避（WorkManager 自带重试），不打扰。
 * 进度与结果经 container.syncStatus 供首页展示（细线 + 胶囊）。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CandyTickApp).container
        val state = container.appStateStore.state.value
        val settings = state.settings
        val total = state.repos.size

        // 1) 快照刷新（单仓库失败不拖垮其余）；逐仓上报进度
        val syncStatus = container.syncStatus
        val before = state.snapshots
        val steps = AtomicInteger(0)
        val errors = coroutineScope {
            state.repos.map { tracked ->
                async {
                    val error = container.repoRepository.refreshRepo(tracked.id)
                    if (total > 0) syncStatus.value = SyncStatus.Running(steps.incrementAndGet(), total * 2)
                    error
                }
            }.awaitAll().filterNotNull()
        }

        // 2) 新版本检测：latestReleaseTag 与上次不同才通知（首次记录不打扰）；并发 + 计入总进度
        val tagChanged = AtomicInteger(0)
        coroutineScope {
            state.repos.map { tracked ->
                async {
                    val latestTag = runCatching {
                        container.repoRepository.releases(tracked.id, 1).firstOrNull()?.tagName
                    }.getOrNull()
                    val previous = state.snapshots[tracked.id]?.latestReleaseTag
                    // 导入基线：备份数据（同步时间早于导入时刻）首次同步不通知不计更新数
                    val importedBaseline = state.snapshots[tracked.id]?.lastSyncAt
                        ?.let { state.lastImportAt != null && it.isBefore(state.lastImportAt) } == true
                    runCatching { container.repoRepository.setLatestReleaseTag(tracked.id, latestTag) }
                    if (latestTag != null && previous != null && latestTag != previous && !importedBaseline) {
                        tagChanged.incrementAndGet()
                        if (settings.notificationsEnabled) {
                            Notifications.notifyRepoNewVersion(
                                context = applicationContext,
                                repoName = tracked.noteName ?: "${tracked.owner}/${tracked.repo}",
                                tag = latestTag,
                                repoId = tracked.id,
                            )
                        }
                    }
                    if (total > 0) syncStatus.value = SyncStatus.Running(steps.incrementAndGet(), total * 2)
                }
            }.awaitAll()
        }

        // 3) 结果：有更新数与首页圆点同口径（SnapshotDiff 共享判定）
        val after = container.appStateStore.state.value.snapshots
        val snapshotChanged = state.repos.count { tracked ->
            before[tracked.id]?.let { b ->
                // 导入基线数据的首轮同步不计入（与首页圆点、通知同口径）
                val importedBaseline = b.lastSyncAt != null &&
                    state.lastImportAt != null && b.lastSyncAt.isBefore(state.lastImportAt)
                !importedBaseline && after[tracked.id]?.let { SnapshotDiff.hasNewActivity(b, it) } == true
            } == true
        }
        // tag 变化的仓库可能与 pushedAt 推进重合，取两者较大值（并集近似）并封顶总数
        val changedCount = maxOf(snapshotChanged, tagChanged.get()).coerceAtMost(total)
        // 空仓库（无订阅）不进入信号链：保持 Idle，不弹「已是最新」
        if (total > 0) syncStatus.value = SyncStatus.Done(changed = changedCount, failed = errors.size)

        // 4) 自身更新（检查默认开；自动下载按设置入队）
        if (settings.selfUpdateCheck) {
            val hasNew = container.selfUpdater.check()
            if (hasNew && settings.selfUpdateAutoDownload) {
                container.syncScheduler.enqueueUpdateDownload()
            }
        }

        return Result.success()
    }
}
