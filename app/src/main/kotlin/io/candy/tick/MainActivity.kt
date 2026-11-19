package io.candy.tick

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.candy.tick.ui.nav.AppNavHost
import io.candy.tick.ui.theme.CandyTickTheme

class MainActivity : ComponentActivity() {

    // 通知导航目标：冷启动（onCreate）与热启动（onNewIntent，singleTop 复用实例）双路径写入；
    // AppNavHost 消费后清空（mutableStateOf 驱动 composition 内的导航）
    private var pendingNavRepoId by androidx.compose.runtime.mutableStateOf<Long?>(null)
    private var pendingNavSettings by androidx.compose.runtime.mutableStateOf(false)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        consumeNavIntent(intent)
    }

    private fun consumeNavIntent(intent: android.content.Intent) {
        intent.getLongExtra(io.candy.tick.data.Notifications.EXTRA_NAV_REPO, -1L)
            .takeIf { it != -1L }?.let { pendingNavRepoId = it }
        if (intent.getBooleanExtra(io.candy.tick.data.Notifications.EXTRA_NAV_SETTINGS, false)) {
            pendingNavSettings = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // 仅真冷启动消费 extra：旋转/后台回收重建会带着原 intent 重走 onCreate，
        // 此时再消费会把用户已离开的通知目标页重新压回栈
        if (savedInstanceState == null) consumeNavIntent(intent)
        // 应用保存的语言设置（在 setContent 前生效）；跟随系统（空串）时重置为系统 locale
        val savedLang = (application as CandyTickApp).container.appStateStore.state.value.settings.language
        val locale = if (savedLang.isNotBlank()) {
            java.util.Locale.forLanguageTag(savedLang)
        } else {
            android.content.res.Resources.getSystem().configuration.locales[0]
        }
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        enableEdgeToEdge()
        val container = (application as CandyTickApp).container
        setContent {
            val state by container.appStateStore.state.collectAsStateWithLifecycle()
            // 语言切换（不 recreate）：ContextWrapper 包住 Activity，只覆写 getResources()
            // 的 locale——ViewModel（LocalViewModelStoreOwner）、startActivity、Window 等
            // 全部委托原 Activity，stringResource 走 wrapper.resources 拿新 locale 的串
            val activity = this
            val localizedContext = androidx.compose.runtime.remember(state.settings.language) {
                val lang = state.settings.language
                val locale = if (lang.isNotBlank()) {
                    java.util.Locale.forLanguageTag(lang)
                } else {
                    android.content.res.Resources.getSystem().configuration.locales[0]
                }
                // 进程级默认 locale 同步（String.format 等非 resources 路径的格式化）
                java.util.Locale.setDefault(locale)
                val config = android.content.res.Configuration(activity.resources.configuration)
                config.setLocale(locale)
                val localizedRes = activity.createConfigurationContext(config).resources
                object : android.content.ContextWrapper(activity) {
                    override fun getResources(): android.content.res.Resources = localizedRes
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides localizedContext,
            ) {
                CandyTickTheme(themeMode = state.settings.themeMode) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppNavHost(
                            container = container,
                            pendingRepoId = pendingNavRepoId,
                            pendingSettings = pendingNavSettings,
                            onNavConsumed = {
                                pendingNavRepoId = null
                                pendingNavSettings = false
                            },
                        )
                        io.candy.tick.ui.common.FeedbackHost()
                        // 全局更新弹窗：设置页未打开时（后台自动检查）也能弹出
                        io.candy.tick.ui.common.UpdateSheet(
                            selfUpdater = container.selfUpdater,
                            autoInstall = state.settings.autoInstallDownload,
                        )
                    }
                }
            }
        }
    }
}
