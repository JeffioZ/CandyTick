package io.candy.tick.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.candy.tick.MainActivity
import io.candy.tick.R
import io.candy.tick.data.model.AppState
import io.candy.tick.util.VersionCompare
import io.candy.tick.util.installedVersionName

/** 仓库更新、自更新与同步各用独立渠道；13+ 运行时权限检查（被拒 → 设置页常驻提醒，不反复弹窗） */
object Notifications {

    const val CHANNEL_NEW_RELEASE = "new_release"
    const val CHANNEL_SELF_UPDATE = "self_update"
    const val CHANNEL_SYNC = "sync" // 加急同步前台服务（API 29-30）：独立渠道，不与自更新提醒捆绑

    private const val GROUP_REPO_UPDATES = "repo_updates" // 多仓库同轮发版折叠为一组
    private const val SUMMARY_ID = 1001 // 组摘要固定 id：每轮覆盖更新
    private const val SELF_UPDATE_ID = 4242 // 自更新就绪通知固定 id

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEW_RELEASE,
                context.getString(R.string.notif_channel_release),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                // 系统设置（长按渠道）里的说明：用户管理通知前的预期管理
                description = context.getString(R.string.notif_channel_release_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SELF_UPDATE,
                context.getString(R.string.notif_channel_update),
                NotificationManager.IMPORTANCE_LOW, // 低优先级：不打扰
            ).apply {
                description = context.getString(R.string.notif_channel_update_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                context.getString(R.string.notif_channel_sync),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_sync_desc)
            },
        )
    }

    /** 13+ 未授权时静默跳过（不 crash、不弹窗） */
    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED)

    // 通知点击导航目标（MainActivity 解析后直达对应页面）
    const val EXTRA_NAV_REPO = "nav_repo_id" // 仓库新版本 → 该仓库详情页
    const val EXTRA_NAV_RELEASE_TAG = "nav_release_tag"
    const val EXTRA_NAV_SETTINGS = "nav_settings" // 自更新就绪 → 设置页
    const val ACTION_NAV_HOME = "io.candy.tick.NOTIFY_REPO_SUMMARY"

    // canNotify() 已在函数头守门（13+ 未授权直接 return），notify 调用另包 runCatching 兜底；
    // lint 的静态权限分析不识别跨函数守卫，就地标注
    @SuppressLint("MissingPermission")
    @Synchronized
    fun notifyRepoNewVersion(context: Context, repoName: String, tag: String, repoId: Long) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
            // PendingIntent 的身份不含 extras。同仓不同版本各用独立 action，点击旧通知时仍指向旧版。
            .setAction("io.candy.tick.NOTIFY_REPO.$repoId.$tag")
            .putExtra(EXTRA_NAV_REPO, repoId)
            .putExtra(EXTRA_NAV_RELEASE_TAG, tag)
        val pending = PendingIntent.getActivity(
            context,
            repoId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_NEW_RELEASE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(repoName)
            .setContentText(context.getString(R.string.notif_new_release, tag))
            .addExtras(Bundle().apply { putString(EXTRA_NAV_RELEASE_TAG, tag) })
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(GROUP_REPO_UPDATES) // 多仓库同轮发版折叠（≥2 条时摘要汇总）
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(repoId.toString(), 0, notification)
        }
    }

    /** 展示仍有待处理通知的仓库数，点击摘要回首页。 */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun notifyRepoUpdatesSummary(context: Context, count: Int) {
        if (count < 1 || !canNotify(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION_NAV_HOME)
        val pending = PendingIntent.getActivity(
            context,
            SUMMARY_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_NEW_RELEASE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.resources.getQuantityString(R.plurals.notif_updates_summary, count, count))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(GROUP_REPO_UPDATES)
            .setGroupSummary(true)
            .setNumber(count)
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(SUMMARY_ID, notification)
        }
    }

    /** 自身更新下载完成（点击进 App 设置页安装） */
    @SuppressLint("MissingPermission")
    fun notifySelfUpdateReady(context: Context, tag: String) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_NAV_SETTINGS, true)
        val pending = PendingIntent.getActivity(
            context,
            SELF_UPDATE_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_SELF_UPDATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_update_ready_title))
            .setContentText(context.getString(R.string.notif_update_ready, tag))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(SELF_UPDATE_ID, notification)
        }
    }

    /**
     * 通知对账：已装版本 ≥ 通知的 tag → 该仓通知使命已完成，主动撤下。
     * 场景：不点通知、直接进 App 装完新版——autoCancel 只在点击时生效，这里补主动清理。
     * 判定保守：未关联 / 未安装 / 无法比较 → 保留（信息仍有效，不误撤）。
     */
    @Synchronized
    fun reconcileRepoNotifications(context: Context, state: AppState) {
        val manager = NotificationManagerCompat.from(context)
        val active = runCatching { manager.activeNotifications.filter { it.notification.channelId == CHANNEL_NEW_RELEASE } }
            .getOrNull().orEmpty()
        if (active.isEmpty()) return
        val byTag = state.repos.associateBy { it.id.toString() }
        val byLegacyId = state.repos.associateBy { it.id.hashCode() }
        var remaining = 0
        var summaryCount: Int? = null
        for (entry in active) {
            if (entry.tag == null && entry.id == SUMMARY_ID) {
                summaryCount = entry.notification.number
                continue
            }
            val repo = if (entry.tag != null) byTag[entry.tag] else byLegacyId[entry.id]
            // A legacy notification has no tag. Use the last version processed for notifications,
            // since the foreground may already have seen a newer, as-yet unannounced release.
            val tag = entry.notification.extras.getString(EXTRA_NAV_RELEASE_TAG)
                ?: repo?.let { state.snapshots[it.id]?.latestReleaseTag }
            val local = repo?.let { installedVersionName(context, it) }
            val installed = tag != null && local != null && VersionCompare.compare(tag, local)?.let { it <= 0 } == true
            val noPublishedApk = repo?.let { state.snapshots[it.id] }?.let {
                it.releaseBaselineKnown && it.currentReleaseTag == null
            } == true
            if (repo == null || installed || noPublishedApk) {
                if (runCatching { manager.cancel(entry.tag, entry.id) }.isFailure) remaining++
            } else {
                remaining++
            }
        }
        if (remaining == 0 && summaryCount != null) {
            runCatching { manager.cancel(SUMMARY_ID) }
        } else if (state.settings.notificationsEnabled && remaining != summaryCount && (remaining >= 2 || summaryCount != null)) {
            notifyRepoUpdatesSummary(context, remaining)
        }
    }

    /** 自更新装完撤「下载完成」通知（SelfUpdater 检测到本机已是新版时调用） */
    fun cancelSelfUpdateReady(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(SELF_UPDATE_ID) }
    }
}
