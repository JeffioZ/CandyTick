package io.candy.tick.data

import android.Manifest
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

/** 通知封装：渠道两枚；13+ 运行时权限检查（被拒 → 设置页常驻提醒，不反复弹窗） */
object Notifications {

    const val CHANNEL_NEW_RELEASE = "new_release"
    const val CHANNEL_SELF_UPDATE = "self_update"

    private const val GROUP_REPO_UPDATES = "repo_updates" // 多仓库同轮发版折叠为一组
    private const val SUMMARY_ID = 1001 // 组摘要固定 id：每轮覆盖更新

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
    fun notifySelfUpdateReady(context: Context, tag: String) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_NAV_SETTINGS, true)
        val pending = PendingIntent.getActivity(
            context,
            4242,
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
            NotificationManagerCompat.from(context).notify(4242, notification)
        }
    }
}
