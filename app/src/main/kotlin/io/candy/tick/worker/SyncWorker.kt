package io.candy.tick.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.candy.tick.CandyTickApp
import io.candy.tick.data.Notifications
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.SnapshotDiff
import io.candy.tick.data.SourceError
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

    /** API 29-30 上加急作业以前台服务执行，必须提供此信息（否则运行时崩） */
    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val notification = androidx.core.app.NotificationCompat.Builder(
            applicationContext,
            io.candy.tick.data.Notifications.CHANNEL_SYNC,
        )
            .setSmallIcon(io.candy.tick.R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(io.candy.tick.R.string.app_name))
            .setSilent(true)
            .build()
        return androidx.work.ForegroundInfo(id.hashCode(), notification)
    }

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

        // 2) 新版本检测：ETag 条件请求（304 不计配额）；拉取失败保旧 tag——此前瞬时失败
        // 写 null 会漏报一条通知 + 清掉「可更新」标识；方向判定：本机已装 ≥ tag 不算新版
        val tagChanged = AtomicInteger(0)
        coroutineScope {
            state.repos.map { tracked ->
                async {
                    val previous = state.snapshots[tracked.id]?.latestReleaseTag
                    // 导入基线：备份数据（同步时间早于导入时刻）首次同步不通知不计更新数
                    val importedBaseline = state.snapshots[tracked.id]?.lastSyncAt
                        ?.let { state.lastImportAt != null && it.isBefore(state.lastImportAt) } == true
                    when (val outcome = container.repoRepository.syncLatestReleaseTag(tracked.id)) {
                        is RepoRepository.SyncTagOutcome.Tag -> {
                            val latestTag = outcome.tag
                            if (latestTag != null && previous != null && latestTag != previous &&
                                !importedBaseline && !alreadyUpToDate(tracked, latestTag)
                            ) {
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
                        }
                        // Unchanged（304）/ Failed（保旧值）/ Missing：不发通知不写状态
                        else -> Unit
                    }
                    if (total > 0) syncStatus.value = SyncStatus.Running(steps.incrementAndGet(), total * 2)
                }
            }.awaitAll()
        }
        // 组摘要：本轮 ≥2 个仓库发版时折叠为一组（点摘要回首页，子通知各自直达详情）
        if (tagChanged.get() >= 2 && settings.notificationsEnabled) {
            Notifications.notifyRepoUpdatesSummary(applicationContext, tagChanged.get())
        }
        // 通知对账：装完新版的仓库撤下过期通知（纯本地判定，autoCancel 只在点击时生效）
        runCatching {
            Notifications.reconcileRepoNotifications(
                context = applicationContext,
                repos = state.repos,
                tags = container.appStateStore.state.value.snapshots.mapValues { it.value.latestReleaseTag },
            )
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
        // tag 变化与 tip sha 变化的仓库可能重合，取两者较大值（并集近似）并封顶总数
        val changedCount = maxOf(snapshotChanged, tagChanged.get()).coerceAtMost(total)
        // 空仓库（无订阅）不进入信号链：保持 Idle，不弹「已是最新」
        if (total > 0) syncStatus.value = SyncStatus.Done(changed = changedCount, failed = errors.size, total = total)

        // 4) 自身更新（检查默认开；自动下载按设置入队）
        if (settings.selfUpdateCheck) {
            val hasNew = container.selfUpdater.check()
            if (hasNew && settings.selfUpdateAutoDownload) {
                container.syncScheduler.enqueueUpdateDownload()
            }
        }

        // 网络类失败整轮退避重试：此前恒 success（头注释「WorkManager 自带重试」从不触发）。
        // 限流不重试（等下轮周期，避免 403 循环）、混合失败不重试（单仓问题留给下轮）
        val allNetworkFailed = errors.isNotEmpty() &&
            errors.all { it is SourceError.NetworkUnreachable || it is SourceError.Timeout }
        return if (allNetworkFailed && runAttemptCount < 2) Result.retry() else Result.success()
    }

    /** 本机已装版本 ≥ tag（降级/重发同名 release 不吵）：可比较且 ≤ 0 才算已最新；不可比仍通知 */
    private fun alreadyUpToDate(tracked: io.candy.tick.data.model.TrackedRepo, tag: String): Boolean {
        val pkg = tracked.linkedPackage ?: return false
        val local = runCatching {
            applicationContext.packageManager.getPackageInfo(pkg, 0).versionName
        }.getOrNull() ?: return false
        return io.candy.tick.util.VersionCompare.compare(tag, local)?.let { it <= 0 } == true
    }
}
