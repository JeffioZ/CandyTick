package io.candy.tick

import android.app.Application
import io.candy.tick.di.AppContainer

/** Application，只持 AppContainer */
class CandyTickApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
