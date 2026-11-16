package io.candy.tick.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * PackageInstaller 封装：仓库手动安装 + 自身更新共用。
 * 安装结果经广播回传，以 SharedFlow 暴露给 UI / Worker。
 */
class InstallManager(private val context: Context) {

    sealed interface InstallResult {
        data object Success : InstallResult
        data object PendingUserAction : InstallResult // 系统确认界面已拉起
        data object NeedsInstallPermission : InstallResult // 未授予「安装未知应用」，已带去系统设置
        data class Failed(val message: String?, val signatureMismatch: Boolean) : InstallResult
    }

    private val _results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 16)
    val results: SharedFlow<InstallResult> = _results

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> _results.tryEmit(InstallResult.Success)
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // API 33+ 用类型安全版，29-32 用 deprecated 版（不加会 NoSuchMethodError）
                    val confirm = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    runCatching {
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        confirm?.let { ctx.startActivity(it) }
                    }
                    _results.tryEmit(InstallResult.PendingUserAction)
                }
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    // INSTALL_FAILED_UPDATE_INCOMPATIBLE（签名不匹配：常见于 F-Droid 与 GitHub 渠道混装）
                    _results.tryEmit(
                        InstallResult.Failed(
                            message = message,
                            signatureMismatch = message?.contains("UPDATE_INCOMPATIBLE") == true,
                        ),
                    )
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** 是否已授予「安装未知应用」 */
    fun canRequestInstalls(): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    /** 打开本应用的「安装未知应用」系统授权页 */
    fun openInstallPermissionSettings() {
        runCatching {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /** 提交安装会话（29+；系统确认 / 静默由安装来源与系统决定） */
    fun installApk(file: File) {
        if (!canRequestInstalls()) {
            // 未授权直接提交会 SecurityException：带去授权页 + 提示回来重试
            openInstallPermissionSettings()
            _results.tryEmit(InstallResult.NeedsInstallPermission)
            return
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("candytick-apk", 0, file.length()).use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val sender = PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
            session.commit(sender)
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "io.candy.tick.INSTALL_RESULT"
    }
}
