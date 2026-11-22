package io.candy.tick.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import io.candy.tick.R
import io.candy.tick.util.ApkPicker
import io.candy.tick.util.openAppSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * PackageInstaller 封装：仓库手动安装 + 自身更新共用。
 * 安装结果经广播回传，以 SharedFlow 暴露给 UI / Worker。
 */
class InstallManager(private val context: Context, private val scope: CoroutineScope) {

    sealed interface InstallResult {
        data object Success : InstallResult
        data object PendingUserAction : InstallResult // 系统确认界面已拉起
        data object Cancelled : InstallResult // 用户在系统确认页取消（不是失败，不出错误胶囊）
        data object NeedsInstallPermission : InstallResult // 未授予「安装未知应用」，已带去系统设置
        data class Failed(val message: String?, val signatureMismatch: Boolean) : InstallResult
    }

    private data class Event(val repoId: Long?, val result: InstallResult)
    private val results = MutableSharedFlow<Event>(extraBufferCapacity = 16)

    fun resultsFor(repoId: Long?): Flow<InstallResult> =
        results.mapNotNull { event -> event.result.takeIf { event.repoId == repoId } }

    /** 系统装包事件（PACKAGE_REPLACED/ADDED 的包名）：装后清理的唯一事实源 */
    private val _packageInstalled = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val packageInstalled: kotlinx.coroutines.flow.SharedFlow<String> = _packageInstalled

    init {
        // 动态注册无需权限：系统装完任何包都会播（含三方安装器走 ACTION_VIEW 的路径）——
        // 装完瞬间即事件，不依赖回前台轮询或版本比较推断
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            // package: scheme 广播不带 MIME type——addDataType 会全部漏收（经典坑）
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    intent.data?.schemeSpecificPart?.let { _packageInstalled.tryEmit(it) }
                }
            },
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val repoId = if (intent.hasExtra(EXTRA_REPO_ID)) intent.getLongExtra(EXTRA_REPO_ID, 0) else null
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val result = when (status) {
                PackageInstaller.STATUS_SUCCESS -> InstallResult.Success
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    if (intent.getBooleanExtra(EXTRA_SILENT_SELF, false)) {
                        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                        if (sessionId >= 0) runCatching { ctx.packageManager.packageInstaller.abandonSession(sessionId) }
                        results.tryEmit(Event(null, InstallResult.Failed(null, false)))
                        return
                    }
                    // API 33+ 用类型安全版，29-32 用 deprecated 版（不加会 NoSuchMethodError）
                    val confirm = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirm != null && runCatching {
                            ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isSuccess
                    ) InstallResult.PendingUserAction else InstallResult.Failed(null, false)
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Cancelled
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    // INSTALL_FAILED_UPDATE_INCOMPATIBLE（签名不匹配：常见于 F-Droid 与 GitHub 渠道混装）
                    InstallResult.Failed(
                        message = message,
                        signatureMismatch = message?.contains("UPDATE_INCOMPATIBLE") == true,
                    )
                }
            }
            results.tryEmit(Event(repoId, result))
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** 是否已授予「安装未知应用」 */
    fun canRequestInstalls(): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    /** 打开本应用的「安装未知应用」系统授权页 */
    fun openInstallPermissionSettings(): Boolean = context.openAppSettings(
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.fromParts("package", context.packageName, null),
        ),
    )

    /**
     * 仓库 APK 安装入口：ACTION_VIEW + content URI——经系统 resolver 路由，
     * 用户锁定的第三方安装器（InstallerX / Shizuku 系）因此可以接管；
     * 标准 APK 可回退系统安装会话；分包归档需交给支持该格式的应用。
     */
    fun installApk(file: File, repoId: Long? = null, displayName: String = file.name) {
        // Older downloads may have a generated .apk filename even for a split archive.
        val extension = ApkPicker.packageExtension(displayName) ?: file.extension.lowercase()
        val isApk = extension == "apk"
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file, File(displayName).name,
            )
        }.getOrNull()
        val view = uri?.let {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(it, if (isApk) "application/vnd.android.package-archive" else "application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (view != null) {
            runCatching { context.startActivity(view) }.onSuccess {
                // 安装流程已交外部接管（第三方或系统安装器 UI），结果不再回调本应用
                results.tryEmit(Event(repoId, InstallResult.PendingUserAction))
                return
            }
        }
        if (isApk) {
            scope.launch(Dispatchers.IO) { installViaSession(file, repoId) }
        } else {
            val reason = context.getString(io.candy.tick.R.string.bundle_installer_required)
            results.tryEmit(Event(repoId, InstallResult.Failed(reason, signatureMismatch = false)))
        }
    }

    /** 系统安装会话（自身更新静默安装 / Intent 通道的回退；29+） */
    fun installViaSession(file: File, repoId: Long? = null, silentSelfUpdate: Boolean = false) {
        if (!canRequestInstalls()) {
            // 未授权直接提交会 SecurityException：带去授权页 + 提示回来重试
            if (silentSelfUpdate) {
                results.tryEmit(Event(null, InstallResult.Failed(null, false)))
            } else {
                val result = if (openInstallPermissionSettings()) {
                    InstallResult.NeedsInstallPermission
                } else {
                    InstallResult.Failed(context.getString(R.string.system_settings_unavailable), false)
                }
                results.tryEmit(Event(repoId, result))
            }
            return
        }
        val installer = context.packageManager.packageInstaller
        var uncommittedId: Int? = null
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (silentSelfUpdate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setAppPackageName(context.packageName)
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            uncommittedId = sessionId
            installer.openSession(sessionId).use { session ->
                session.openWrite("candytick-apk", 0, file.length()).use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val intent = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
                if (silentSelfUpdate) intent.putExtra(EXTRA_SILENT_SELF, true)
                if (repoId != null) intent.putExtra(EXTRA_REPO_ID, repoId)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val sender = PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
                session.commit(sender)
                uncommittedId = null
            }
        } catch (e: Exception) {
            // 会话异常（磁盘满/安全异常等）此前裸抛到点击回调直接崩
            results.tryEmit(Event(repoId, InstallResult.Failed(message = e.message, signatureMismatch = false)))
        } finally {
            uncommittedId?.let { runCatching { installer.abandonSession(it) } }
        }
    }

    companion object {
        private const val EXTRA_SILENT_SELF = "silent_self_update"
        private const val EXTRA_REPO_ID = "repo_id"
        const val ACTION_INSTALL_RESULT = "io.candy.tick.INSTALL_RESULT"
    }
}
