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

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEW_RELEASE,
                context.getString(R.string.notif_channel_release),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SELF_UPDATE,
                context.getString(R.string.notif_channel_update),
                NotificationManager.IMPORTANCE_LOW, // 低优先级：不打扰
            ),
        )
    }

    /** 13+ 未授权时静默跳过（不 crash、不弹窗） */
    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun notifyRepoNewVersion(context: Context, repoName: String, tag: String, repoId: Long) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            repoId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_NEW_RELEASE)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // 定稿图标第 10 步替换
            .setContentTitle(repoName)
            .setContentText(context.getString(R.string.notif_new_release, tag))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(repoId.hashCode(), notification)
        }
    }

    /** 自身更新下载完成（点击进 App 设置页安装） */
    fun notifySelfUpdateReady(context: Context, tag: String) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            4242,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_SELF_UPDATE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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
