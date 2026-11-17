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
        // 应用保存的语言设置（在 setContent 前生效）
        val savedLang = (application as CandyTickApp).container.appStateStore.state.value.settings.language
        if (savedLang.isNotBlank()) {
            val locale = java.util.Locale.forLanguageTag(savedLang)
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(resources.configuration)
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        }
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
