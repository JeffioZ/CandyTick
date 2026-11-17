package io.candy.tick

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
                Box(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(container)
                    // 全局瞬时反馈宿主：导航之上、状态栏下居中（顶部信息带）
                    io.candy.tick.ui.common.FeedbackHost()
                }
            }
        }
    }
}
