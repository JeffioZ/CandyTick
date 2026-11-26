package io.candy.tick

import android.app.Activity
import android.app.Application
import io.candy.tick.data.Notifications
import io.candy.tick.di.AppContainer
import io.candy.tick.ui.common.Feedback
import io.candy.tick.util.forAppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Application，只持 AppContainer */
class CandyTickApp : Application() {

    lateinit var container: AppContainer
        private set

    private var startedActivities = 0
    private val visible = MutableStateFlow(false)

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
        container = AppContainer(this) { visible.value }
        // 状态文件损坏重置：向用户明说，而不是表现为「订阅悄悄变空」
        container.appScope.launch {
            container.appStateStore.corruptResetNotices.collect {
                runCatching {
                    val localized = applicationContext.forAppLanguage(
                        container.appStateStore.state.value.settings.language,
                    )
                    Feedback.show(
                        localized.getString(io.candy.tick.R.string.state_corrupt_notice),
                        isError = true,
                        durationMs = 6_000L,
                        errorCode = "E-STATE",
                    )
                }
            }
        }
        container.appScope.launch(Dispatchers.IO) {
            val store = container.appStateStore
            store.awaitLoaded()
            store.state.map { state -> if (state.settings.notificationsEnabled) state.repos else emptyList() }
                .distinctUntilChanged()
                .map { repos -> repos.mapNotNull { repo -> repo.downloadedApk?.let { repo.id to it.fileName } } }
                .distinctUntilChanged()
                .collect {
                    // app 级作用域无兜底 handler：这里抛错会杀进程，收集必须自兜
                    runCatching { Notifications.reconcileRepoDownloads(this@CandyTickApp, store.state) }
                        .onFailure { android.util.Log.w("CandyTickApp", "reconcileRepoDownloads failed", it) }
                }
        }
        // 孤儿 APK 回收（后台线程：仓库已删除但 apks/{id}/ 残留）
        container.appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            container.appStateStore.awaitLoaded()
            val restored = container.appStateStore.state.value
            withContext(Dispatchers.IO) {
                runCatching {
                    container.cacheManager.cleanStaleFiles(restored, container.appStateStore.corruptReset)
                    // Android restores the subscription file without the device's downloaded APKs.
                    container.repoRepository.clearMissingDownloads()
                }
            }
        }
        // Scheduling must finish even if the user immediately leaves the settings screen.
        container.appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            container.appStateStore.awaitLoaded()
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
        container.appScope.launch(Dispatchers.IO) {
            container.appStateStore.awaitLoaded()
            visible.filter { it }.collect {
                runCatching { container.syncScheduler.maybeSyncOnOpen(container.appStateStore.state.value.settings) }
                runCatching {
                    Notifications.reconcileRepoNotifications(this@CandyTickApp, container.appStateStore.state.value)
                }
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (++startedActivities == 1) visible.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                if (--startedActivities == 0) visible.value = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

}
