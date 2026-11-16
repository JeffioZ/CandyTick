package io.candy.tick

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.candy.tick.ui.common.ThemePreviewScreen
import io.candy.tick.ui.theme.CandyTickTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CandyTickApp).container
        setContent {
            val state by container.appStateStore.state.collectAsStateWithLifecycle()
            // TODO(施工第 4 步): 内容换 AppNavHost（首页/详情/添加/设置）
            CandyTickTheme(themeMode = state.settings.themeMode) {
                ThemePreviewScreen(
                    themeMode = state.settings.themeMode,
                    onThemeMode = { mode ->
                        lifecycleScope.launch { container.repoRepository.setThemeMode(mode) }
                    },
                )
            }
        }
    }
}
