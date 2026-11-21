package io.candy.tick

import android.app.Activity
import android.app.Application
import io.candy.tick.data.Notifications
import io.candy.tick.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Application，只持 AppContainer */
class CandyTickApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 全局崩溃日志：写入外部私有目录（Android/data/io.candy.tick/files/crash.log）
        val crashDir = getExternalFilesDir(null) ?: filesDir
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                java.io.File(crashDir, "crash.log").writeText(
                    buildString {
                        appendLine("Time: ${java.time.Instant.now()}")
                        appendLine("Thread: ${thread.name}")
                        appendLine("Exception: ${throwable.javaClass.name}")
                        appendLine("Message: ${throwable.message}")
                        appendLine("Stack:")
                        throwable.stackTrace.take(30).forEach { appendLine("  at $it") }
                        appendLine()
                        appendLine("=== Full stack ===")
                        appendLine(throwable.stackTraceToString())
                    }
                )
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        container = AppContainer(this)
        // 孤儿 APK 回收（后台线程：仓库已删除但 apks/{id}/ 残留）
        container.appScope.launch(Dispatchers.IO) {
            runCatching {
                container.cacheManager.cleanOrphanApks(
                    container.appStateStore.state.value.repos.map { it.id }.toSet(),
                )
                // Android restores the subscription file without the device's downloaded APKs.
                container.repoRepository.clearMissingDownloads()
            }
        }
        // Scheduling must finish even if the user immediately leaves the settings screen.
        container.appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            container.appStateStore.state.map { it.settings }
                .distinctUntilChanged { old, new ->
                    old.syncFrequency == new.syncFrequency && old.wifiOnly == new.wifiOnly
                }
                .collect { settings ->
                    try {
                        container.syncScheduler.apply(settings)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        android.util.Log.w("SyncScheduler", "Could not apply sync settings", e)
                    }
                }
        }
        // 「每次打开应用」同步：前后台切换计数，0→1 即回到前台（冷/热启动都覆盖）
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1) {
                    container.appScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { container.syncScheduler.maybeSyncOnOpen(container.appStateStore.state.value.settings) }
                    }
                    // 通知对账（纯本地 PM 读取，不走网络，不受仅 Wi-Fi 开关限制）：
                    // 不点通知直接进 App 装完新版的仓库，回到前台即撤下过期通知
                    container.appScope.launch(Dispatchers.IO) {
                        runCatching {
                            Notifications.reconcileRepoNotifications(this@CandyTickApp, container.appStateStore.state.value)
                        }
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                started--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

}
