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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

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
            .setContentText(applicationContext.getString(io.candy.tick.R.string.notif_syncing_repos))
            .setSilent(true)
            .build()
        return androidx.work.ForegroundInfo(id.hashCode(), notification)
    }

    override suspend fun doWork(): Result = syncLock.withLock {
        val container = (applicationContext as CandyTickApp).container
        try {
            sync(container)
        } finally {
            val current = container.syncStatus.value
            if (current is SyncStatus.Running) container.syncStatus.compareAndSet(current, SyncStatus.Idle)
        }
    }

    private suspend fun sync(container: io.candy.tick.di.AppContainer): Result {
        val state = container.appStateStore.state.value
        val total = state.repos.size

        // 只在真正处理仓库时通知首页。排队、等待网络和空订阅都保持安静。
        val syncStatus = container.syncStatus
        val run = SyncStatus.Running()
        if (total > 0) syncStatus.value = run
        val before = state.snapshots
        val errors = ConcurrentHashMap<Long, SourceError>()
        coroutineScope {
            state.repos.map { tracked ->
                async {
                    val error = container.repoRepository.refreshRepo(tracked.id)
                    if (error != null) errors[tracked.id] = error
                }
            }.awaitAll()
        }

        // 1.5) 关联应用已卸载的仓库自动解除关联：卸载可能发生在本 App 未运行时（广播已
        // 错过），关联 = 对已装应用的事实链接，失效即解除；autoClear 保留 dismissed 记忆，
        // 用户重装应用后 auto-match 自动重绑
        state.repos.filter { it.linkedPackage != null }.forEach { tracked ->
            val pkg = tracked.linkedPackage ?: return@forEach
            try {
                applicationContext.packageManager.getPackageInfo(pkg, 0)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                container.repoRepository.setLinkedPackage(tracked.id, null, autoClear = true, expectedPackage = pkg)
            }
        }

        // 2) 新版本检测：ETag 条件请求（304 无需响应正文）；拉取失败保旧 tag——此前瞬时失败
        // 写 null 会漏报一条通知 + 清掉「可更新」标识；方向判定：本机已装 ≥ tag 不算新版
        val newReleaseRepos = ConcurrentHashMap.newKeySet<Long>()
        coroutineScope {
            state.repos.map { tracked ->
                async {
                    when (val outcome = container.repoRepository.syncLatestReleaseTag(tracked.id)) {
                        is RepoRepository.SyncTagOutcome.Tag -> {
                            val latestTag = outcome.tag
                            val current = container.appStateStore.state.value
                            val subscribed = current.repos.firstOrNull { it.id == tracked.id && it.addedAt == tracked.addedAt }
                            if (latestTag != null && outcome.changed && subscribed != null &&
                                current.snapshots[tracked.id]?.activity?.unreadReleaseTags?.contains(latestTag) == true &&
                                !alreadyUpToDate(subscribed, latestTag)
                            ) {
                                newReleaseRepos.add(tracked.id)
                                if (current.settings.notificationsEnabled) {
                                    Notifications.notifyRepoNewVersion(
                                        context = applicationContext,
                                        repoName = subscribed.noteName ?: "${subscribed.owner}/${subscribed.repo}",
                                        tag = latestTag,
                                        repoId = tracked.id,
                                    )
                                }
                            }
                        }
                        is RepoRepository.SyncTagOutcome.Failed -> errors.putIfAbsent(tracked.id, outcome.error)
                        else -> Unit
                    }
                }
            }.awaitAll()
        }
        // 组摘要：本轮 ≥2 个仓库发版时折叠为一组（点摘要回首页，子通知各自直达详情）
        if (newReleaseRepos.size >= 2 && container.appStateStore.state.value.settings.notificationsEnabled) {
            Notifications.notifyRepoUpdatesSummary(applicationContext, newReleaseRepos.size)
        }
        // 通知对账：装完新版的仓库撤下过期通知（纯本地判定，autoCancel 只在点击时生效）
        runCatching {
            Notifications.reconcileRepoNotifications(applicationContext, container.appStateStore.state.value)
        }

        // 3) 结果：有更新数与首页圆点同口径（SnapshotDiff 共享判定）
        val after = container.appStateStore.state.value.snapshots
        val changedCount = state.repos.count { tracked ->
            tracked.id in newReleaseRepos || before[tracked.id]?.let { b ->
                after[tracked.id]?.let { SnapshotDiff.hasNewActivity(b, it) } == true
            } == true
        }
        // 空仓库（无订阅）不进入信号链：保持 Idle，不弹「已是最新」
        if (total > 0) syncStatus.value = SyncStatus.Done(run, changed = changedCount, failed = errors.size, total = total)

        // 4) 自身更新（检查默认开；自动下载按设置入队）
        if (container.appStateStore.state.value.settings.selfUpdateCheck) {
            val hasNew = container.selfUpdater.check()
            if (hasNew && container.appStateStore.state.value.settings.selfUpdateAutoDownload) {
                container.syncScheduler.enqueueUpdateDownload()
            }
        }

        // 网络类失败整轮退避重试：此前恒 success（头注释「WorkManager 自带重试」从不触发）。
        // 限流不重试（等下轮周期，避免 403 循环）、混合失败不重试（单仓问题留给下轮）
        val allNetworkFailed = total > 0 && errors.size == total &&
            errors.values.all { it is SourceError.NetworkUnreachable || it is SourceError.Timeout }
        return if (allNetworkFailed && runAttemptCount < 2) Result.retry() else Result.success()
    }

    /** 本机已装版本 ≥ tag（降级/重发同名 release 不吵）：可比较且 ≤ 0 才算已最新；不可比仍通知 */
    private fun alreadyUpToDate(tracked: io.candy.tick.data.model.TrackedRepo, tag: String): Boolean {
        val local = io.candy.tick.util.installedVersionName(applicationContext, tracked) ?: return false
        return io.candy.tick.util.VersionCompare.compare(tag, local)?.let { it <= 0 } == true
    }

    private companion object {
        // 周期任务与「每次打开」使用不同唯一名，设置切换时两者仍可能交叠。
        val syncLock = Mutex()
    }
}
