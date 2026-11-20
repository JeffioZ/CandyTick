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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.candy.tick.MainActivity
import io.candy.tick.R
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.ui.common.localVersionName
import io.candy.tick.util.VersionCompare

/** 通知封装：渠道两枚；13+ 运行时权限检查（被拒 → 设置页常驻提醒，不反复弹窗） */
object Notifications {

    const val CHANNEL_NEW_RELEASE = "new_release"
    const val CHANNEL_SELF_UPDATE = "self_update"

    private const val GROUP_REPO_UPDATES = "repo_updates" // 多仓库同轮发版折叠为一组
    private const val SUMMARY_ID = 1001 // 组摘要固定 id：每轮覆盖更新
    private const val SELF_UPDATE_ID = 4242 // 自更新就绪通知固定 id

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
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
    }

    /** 13+ 未授权时静默跳过（不 crash、不弹窗） */
    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    // 通知点击导航目标（MainActivity 解析后直达对应页面）
    const val EXTRA_NAV_REPO = "nav_repo_id" // 仓库新版本 → 该仓库详情页
    const val EXTRA_NAV_SETTINGS = "nav_settings" // 自更新就绪 → 设置页

    // canNotify() 已在函数头守门（13+ 未授权直接 return），notify 调用另包 runCatching 兜底；
    // lint 的静态权限分析不识别跨函数守卫，就地标注
    @SuppressLint("MissingPermission")
    fun notifyRepoNewVersion(context: Context, repoName: String, tag: String, repoId: Long) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_NAV_REPO, repoId)
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
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(GROUP_REPO_UPDATES) // 多仓库同轮发版折叠（≥2 条时摘要汇总）
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(repoId.hashCode(), notification)
        }
    }

    /** 组摘要：一轮同步 ≥2 个仓库发版时折叠展示「N 个仓库有新版本」（点击回首页） */
    @SuppressLint("MissingPermission")
    fun notifyRepoUpdatesSummary(context: Context, count: Int) {
        if (count < 2 || !canNotify(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java) // 无目标 extra：回首页自览
        val pending = PendingIntent.getActivity(
            context,
            SUMMARY_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_NEW_RELEASE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_updates_summary, count))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(GROUP_REPO_UPDATES)
            .setGroupSummary(true)
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
    fun reconcileRepoNotifications(
        context: Context,
        repos: List<TrackedRepo>,
        tags: Map<Long, String?>, // repoId → latestReleaseTag
    ) {
        val manager = NotificationManagerCompat.from(context)
        repos.forEach { repo ->
            val tag = tags[repo.id] ?: return@forEach
            val local = localVersionName(context, repo) ?: return@forEach
            val handled = VersionCompare.compare(tag, local)?.let { it <= 0 } ?: return@forEach
            if (handled) runCatching { manager.cancel(repo.id.hashCode()) }
        }
        // 已退订仓库的残留通知：id 不在现存仓集合 → 撤（退订后不点通知的陈旧场景。
        // 代价：删除撤销窗口内若恰逢对账会误撤——下次发版会重新通知，无害）
        val liveIds = repos.map { it.id.hashCode() }.toSet()
        val active = runCatching { manager.activeNotifications.toList() }.getOrDefault(emptyList())
        active.forEach {
            if (it.notification.channelId == CHANNEL_NEW_RELEASE &&
                it.id != SUMMARY_ID && it.id !in liveIds
            ) {
                runCatching { manager.cancel(it.id) }
            }
        }
        // 子通知全撤后摘要成孤儿（「N 个仓库有新版本」却点不出内容），一并清理
        val childrenActive = active.any {
            it.notification.channelId == CHANNEL_NEW_RELEASE &&
                it.id != SUMMARY_ID && it.id != SELF_UPDATE_ID && it.id in liveIds
        }
        if (!childrenActive) runCatching { manager.cancel(SUMMARY_ID) }
    }

    /** 装完即撤单仓通知（安装广播命中本仓关联包名时调用） */
    fun cancelRepoUpdate(context: Context, repoId: Long) {
        runCatching { NotificationManagerCompat.from(context).cancel(repoId.hashCode()) }
    }

    /** 自更新装完撤「下载完成」通知（SelfUpdater 检测到本机已是新版时调用） */
    fun cancelSelfUpdateReady(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(SELF_UPDATE_ID) }
    }
}
