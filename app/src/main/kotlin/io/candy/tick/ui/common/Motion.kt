package io.candy.tick.ui.common

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.anim.DecelerateEasing

/**
 * 动效令牌（全 App 唯一来源，40 处调用点审计后收敛；改动档位先看这里）。
 *
 * 时长与减速曲线供各组件共用：
 * - PRESS     80ms  按压染色（反馈下限：快到不抢手）
 * - MICRO    150ms  微反馈：淡入淡出、列表项增删、小展开、圆心点
 * - PHASE    180ms  同容器相变（AnimatedContent + SizeTransform 必须同拍）
 * - PANEL    220ms  面板展开/横幅/搜索框/滑动提交回位
 * - PROGRESS 300ms  进度条平滑（线性：进度本身单调，easing 会骗人）
 *
 * 不并入的两处例外：
 * - 导航转场（AppNavHost.navSpec）：覆盖式全滑入——新页 100% 不透明滑入 + 旧页 25%
 *   视差、无 fade，这才是系统 Activity 的真实形态（交叉淡化时代「镜像 Activity」的
 *   说法并不成立）；predictive 返回另用线性曲线（手势 seek 要求位移与手指一致）；
 * - LoadingDots 900ms / EchoPing 2400ms：装饰循环（受 rememberMotionEnabled 降级保护）。
 */
object Motion {
    /** 按压染色档 */
    const val PRESS = 80

    /** 微反馈档 */
    const val MICRO = 150

    /** 相变档 */
    const val PHASE = 180

    /** 面板档 */
    const val PANEL = 220

    /** 进度平滑档 */
    const val PROGRESS = 300

    /** 进出场 slide/expand 的减速曲线（miuix 官方 anim 包，HyperOS 落位手感） */
    val Decelerate = DecelerateEasing()

}

internal fun <S> AnimatedContentTransitionScope<S>.downloadActionTransition(
    duration: Int = Motion.PHASE,
    clip: Boolean = false,
): ContentTransform {
    val enter = fadeIn(tween(duration)) + slideInVertically(tween(duration)) { it / 6 }
    return (enter togetherWith fadeOut(tween(duration))).using(SizeTransform(clip = clip) { _, _ -> tween(duration) })
}

private val LocalMotionEnabled = compositionLocalOf { true }

/** Share one observer across the application, including when the setting changes at runtime. */
@Composable
internal fun ProvideMotionPreference(content: @Composable () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val setting = android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
    fun isEnabled() = android.provider.Settings.Global.getFloat(resolver, setting, 1f) != 0f
    var enabled by remember(resolver) { mutableStateOf(isEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = isEnabled() }
        }
        resolver.registerContentObserver(android.provider.Settings.Global.getUriFor(setting), false, observer)
        enabled = isEnabled()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalMotionEnabled provides enabled, content = content)
}

@Composable
fun rememberMotionEnabled(): Boolean = LocalMotionEnabled.current
