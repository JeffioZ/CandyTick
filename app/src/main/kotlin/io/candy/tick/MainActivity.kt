package io.candy.tick

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.candy.tick.ui.nav.AppNavHost
import io.candy.tick.ui.theme.CandyTickTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CandyTickApp).container
        setContent {
            val state by container.appStateStore.state.collectAsStateWithLifecycle()
            CandyTickTheme(themeMode = state.settings.themeMode) {
                AppNavHost(container)
            }
        }
    }
}
