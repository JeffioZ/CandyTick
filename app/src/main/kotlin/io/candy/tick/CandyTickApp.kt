package io.candy.tick

import android.app.Application
import io.candy.tick.di.AppContainer
import io.candy.tick.worker.SyncScheduler

/** Application，只持 AppContainer */
class CandyTickApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 默认调度（设置变更时 UPDATE 重排）
        runCatching {
            container.syncScheduler.apply(container.appStateStore.state.value.settings)
        }
    }
}
