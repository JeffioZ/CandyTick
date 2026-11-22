package io.candy.tick.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.candy.tick.CandyTickApp
import io.candy.tick.data.Notifications
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.SelfUpdater
import io.candy.tick.data.SnapshotDiff
import io.candy.tick.data.SourceError
import io.candy.tick.data.SyncStatus
import io.candy.tick.data.forEachSyncRepo
import io.candy.tick.util.forAppLanguage
import kotlinx.coroutines.CancellationException
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
        val store = (applicationContext as CandyTickApp).container.appStateStore
        val localized = applicationContext.forAppLanguage(store.state.value.settings.language)
        val notification = androidx.core.app.NotificationCompat.Builder(
            applicationContext,
            io.candy.tick.data.Notifications.CHANNEL_SYNC,
        )
            .setSmallIcon(io.candy.tick.R.drawable.ic_notification)
            .setContentTitle(localized.getString(io.candy.tick.R.string.app_name))
            .setContentText(localized.getString(io.candy.tick.R.string.notif_syncing_repos))
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
        // 读盘持续失败：封顶后按成功收尾——Result.failure 会让周期任务永久 FAILED（不再排下一轮），
        // 读盘问题留给下一个调度周期再试
        if (!container.appStateStore.ensureLoaded()) {
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        }
        val state = container.appStateStore.state.value
        val total = state.repos.size

        // 只在真正处理仓库时通知首页。排队、等待网络和空订阅都保持安静。
        val syncStatus = container.syncStatus
        val run = SyncStatus.Running()
        if (total > 0) syncStatus.value = run
        val before = state.snapshots
        val errors = ConcurrentHashMap<Long, SourceError>()
        state.repos.forEachSyncRepo { tracked ->
            // 逐仓兜底：forEachSyncRepo 的 coroutineScope 会让单仓意外异常取消整批兄弟任务
            val error = try {
                container.repoRepository.refreshRepo(tracked.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.w("SyncWorker", "refresh failed for repo ${tracked.id}", e)
                SourceError.NetworkUnreachable
            }
            if (error != null) errors[tracked.id] = error
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
        state.repos.forEachSyncRepo { tracked ->
            val outcome = try {
                container.repoRepository.syncLatestReleaseTag(tracked.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.w("SyncWorker", "tag sync failed for repo ${tracked.id}", e)
                RepoRepository.SyncTagOutcome.Failed(SourceError.NetworkUnreachable)
            }
            when (outcome) {
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
                                context = applicationContext.forAppLanguage(current.settings.language),
                                repoName = subscribed.noteName ?: "${subscribed.owner}/${subscribed.repo}",
                                tag = latestTag,
                                repoId = tracked.id,
                            )
                        }
                    }
                }
                is RepoRepository.SyncTagOutcome.Failed -> {
                    // 错误分级覆盖：不可访问(404/无权) > 限流 > 瞬时网络。putIfAbsent 会让
                    // 快照阶段的网络错误掩盖 tag 阶段新拿到的限流/404——后者更有诊断价值
                    val existing = errors[tracked.id]
                    if (existing == null || errorSeverity(outcome.error) > errorSeverity(existing)) {
                        errors[tracked.id] = outcome.error
                    }
                }
                else -> Unit
            }
        }
        // 组摘要：本轮 ≥2 个仓库发版时折叠为一组（点摘要回首页，子通知各自直达详情）
        val notificationSettings = container.appStateStore.state.value.settings
        if (newReleaseRepos.size >= 2 && notificationSettings.notificationsEnabled) {
            Notifications.notifyRepoUpdatesSummary(applicationContext.forAppLanguage(notificationSettings.language), newReleaseRepos.size)
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
        // 空仓库（无订阅）不进入信号链：保持 Idle，不弹「已是最新」。
        // 整轮全挂且全为限流时携带恢复时间：首页胶囊据此报「已达上限」而非笼统「同步失败」
        val rateLimitErrors = errors.values.filterIsInstance<SourceError.RateLimited>()
        val allRateLimited = total > 0 && errors.size >= total && rateLimitErrors.size == errors.size
        if (total > 0) syncStatus.value = SyncStatus.Done(
            run,
            changed = changedCount,
            failed = errors.size,
            total = total,
            rateLimited = allRateLimited,
            rateLimitRetryAt = rateLimitErrors.mapNotNull { it.retryAt }.maxOrNull(),
        )

        // 4) 自身更新（检查默认开；自动下载按设置入队）
        val updateSettings = container.appStateStore.state.value.settings
        if (updateSettings.selfUpdateCheck) {
            val hasNew = container.selfUpdater.check { it.selfUpdateCheck }
            // 补装兜底：Downloaded 悬置（进程被杀/会话中断）也重新入队——
            // worker 会按 installFailedTag 决定是否重装，冷启动不再漏
            val pendingInstall = updateSettings.autoInstallDownload &&
                container.selfUpdater.state.value is SelfUpdater.State.Downloaded
            if ((hasNew || pendingInstall) && updateSettings.canAutoDownloadUpdate) {
                container.syncScheduler.enqueueUpdateDownload()
            }
        }

        // 网络类失败整轮退避重试：此前恒 success（头注释「WorkManager 自带重试」从不触发）。
        // 限流不重试（等下轮周期，避免 403 循环）、混合失败不重试（单仓问题留给下轮）
        val allNetworkFailed = total > 0 && errors.size == total &&
            errors.values.all { it is SourceError.NetworkUnreachable || it is SourceError.Timeout }
        return if (allNetworkFailed && runAttemptCount < 2) Result.retry() else Result.success()
    }

    /**
     * 本机已装版本 ≥ tag（降级/重发同名 release 不吵）：可比较且 ≤ 0 才算已最新。
     * 不可比视为已最新：与自更新链路同口径（design.md「不宣称有更新」）——
     * 未读角标仍会亮，只是不再推送可能误报的「发现新版」通知。
     */
    private fun alreadyUpToDate(tracked: io.candy.tick.data.model.TrackedRepo, tag: String): Boolean {
        val local = io.candy.tick.util.installedVersionName(applicationContext, tracked) ?: return false
        return io.candy.tick.util.VersionCompare.compare(tag, local)?.let { it <= 0 } ?: true
    }

    private companion object {
        // 周期任务与「每次打开」使用不同唯一名，设置切换时两者仍可能交叠。
        val syncLock = Mutex()
    }

    private fun errorSeverity(error: SourceError): Int = when (error) {
        is SourceError.NotFound, is SourceError.TokenRejected -> 2
        is SourceError.RateLimited -> 1
        else -> 0
    }
}
