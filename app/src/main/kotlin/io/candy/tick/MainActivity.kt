package io.candy.tick

import android.content.Intent
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.candy.tick.data.Notifications
import io.candy.tick.ui.common.StorageStatusHost
import io.candy.tick.ui.nav.AppNavHost
import io.candy.tick.ui.nav.AppRoute
import io.candy.tick.ui.nav.RouteDetail
import io.candy.tick.ui.nav.RouteHome
import io.candy.tick.ui.nav.RouteSettings
import io.candy.tick.ui.theme.CandyTickTheme
import kotlinx.serialization.json.Json
import java.util.UUID

private const val SAVED_NOTIFICATION_TARGET = "pending_notification_target"

class MainActivity : ComponentActivity() {

    private var pendingNavTarget by mutableStateOf<AppRoute?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeNavIntent(intent)
    }

    private fun consumeNavIntent(intent: Intent) {
        val repoId = intent.getLongExtra(Notifications.EXTRA_NAV_REPO, -1L)
        pendingNavTarget = when {
            repoId != -1L -> RouteDetail(
                repoId = repoId,
                releaseTag = intent.getStringExtra(Notifications.EXTRA_NAV_RELEASE_TAG)?.takeIf { it.isNotBlank() },
                releaseRequestId = UUID.randomUUID().toString(),
            )
            intent.getBooleanExtra(Notifications.EXTRA_NAV_SETTINGS, false) -> RouteSettings
            intent.action == Notifications.ACTION_NAV_HOME -> RouteHome
            else -> return
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingNavTarget?.let { outState.putString(SAVED_NOTIFICATION_TARGET, Json.encodeToString<AppRoute>(it)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // 开屏退场谢幕（2026-11-21）：App 就绪后系统默认硬切收走开屏——冷启动快时
        // 图标一闪而过（开屏语义是「遮加载」，显示时长=启动耗时）。这里接管退场：
        // 图标 1.35× 放大（涟漪散开，「ping 有回应」的谢幕）+ 整屏 300ms 淡出。
        // 浅色态底色=页面底，整屏淡出不可见、只剩图标谢幕；深色态 102B1F→0D100E
        // 交叉淡化替代硬切。不拖慢启动就绪，只把可交互顺延 ~300ms（无感级）；
        // 系统「动画时长缩放=0」时 animator 立即完成 → remove() 即时，自动退化。
        // 10/11 走 core-splashscreen compat，同一 API 生效。
        splashScreen.setOnExitAnimationListener { provider ->
            val duration = 300L // 动效体系 300 档（进度平滑/导航同级）
            val interpolator = android.view.animation.DecelerateInterpolator()
            provider.iconView.animate()
                .scaleX(1.35f)
                .scaleY(1.35f)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .start()
            provider.view.animate()
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .withEndAction { provider.remove() } // 必须remove，否则开屏永驻
                .start()
        }
        // 仅真冷启动消费 extra：旋转/后台回收重建会带着原 intent 重走 onCreate，
        // 此时再消费会把用户已离开的通知目标页重新压回栈
        if (savedInstanceState == null) {
            consumeNavIntent(intent)
        } else {
            pendingNavTarget = savedInstanceState.getString(SAVED_NOTIFICATION_TARGET)?.let {
                Json.decodeFromString<AppRoute>(it)
            }
        }
        // 应用保存的语言设置（在 setContent 前生效）；跟随系统（空串）时重置为系统 locale
        val container = (application as CandyTickApp).container
        val initialSettings = container.appStateStore.state.value.settings
        val savedLang = initialSettings.language
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
        val settingsFlow = container.appStateStore.state.map { it.settings }.distinctUntilChanged()
        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = initialSettings)
            // 语言切换（不 recreate）：ContextWrapper 包住 Activity，只覆写 getResources()
            // 的 locale——ViewModel（LocalViewModelStoreOwner）、startActivity、Window 等
            // 全部委托原 Activity，stringResource 走 wrapper.resources 拿新 locale 的串
            val activity = this
            val localizedContext = androidx.compose.runtime.remember(settings.language) {
                val lang = settings.language
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
                CandyTickTheme(themeMode = settings.themeMode) {
                    StorageStatusHost(container.appStateStore) {
                        val requested = pendingNavTarget
                        val target = if (requested is RouteDetail) {
                            val loaded by container.appStateStore.state.collectAsStateWithLifecycle()
                            if (loaded.repos.any { it.id == requested.repoId }) requested else RouteHome
                        } else requested
                        AppNavHost(
                            pendingTarget = target,
                            onNavConsumed = { if (pendingNavTarget == requested && it == target) pendingNavTarget = null },
                        )
                        io.candy.tick.ui.common.FeedbackHost()
                        // 全局更新弹窗：设置页未打开时（后台自动检查）也能弹出
                        io.candy.tick.ui.common.UpdateSheet(container.selfUpdater)
                    }
                }
            }
        }
    }
}
