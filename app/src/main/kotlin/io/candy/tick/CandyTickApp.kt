package io.candy.tick

import android.app.Activity
import android.app.Application
import io.candy.tick.di.AppContainer

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
        // 默认调度（设置变更时 UPDATE 重排）
        runCatching {
            container.syncScheduler.apply(container.appStateStore.state.value.settings)
        }
        // 「每次打开应用」同步：前后台切换计数，0→1 即回到前台（冷/热启动都覆盖）
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1) {
                    runCatching {
                        container.syncScheduler.maybeSyncOnOpen(
                            container.appStateStore.state.value.settings,
                        )
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
