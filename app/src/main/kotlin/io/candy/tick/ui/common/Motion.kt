package io.candy.tick.ui.common

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import top.yukonga.miuix.kmp.anim.DecelerateEasing

/**
 * 动效令牌（全 App 唯一来源，40 处调用点审计后收敛；改动档位先看这里）。
 *
 * 时长四档（tween）+ 一条 miuix 家法 spring，替代散落各文件的字面量：
 * - PRESS     80ms  按压染色（反馈下限：快到不抢手）
 * - MICRO    150ms  微反馈：淡入淡出、列表项增删、小展开、圆心点
 * - PHASE    180ms  同容器相变（AnimatedContent + SizeTransform 必须同拍）
 * - PANEL    220ms  面板展开/横幅/搜索框/滑动提交回位
 * - PROGRESS 300ms  进度条平滑（线性：进度本身单调，easing 会骗人）
 *
 * spring：miuix 家法——与库内 Switch.kt 同源（dampingRatio 0.99f / stiffness 438.6f，
 * ≈Folme 临界阻尼落位手感）。所有「状态值」动画（滑块位移、列表重排）统一取用，
 * 不各自手调；AnimatedValue 重定向天然可取消（skill: cancellable transitions）。
 *
 * 不并入的两处例外：
 * - 导航转场 260ms LinearOutSlowInEasing（AppNavHost.navSpec）：刻意镜像系统
 *   Activity 手感，页面级一次性转场；
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

    /** 位移/落位 spring（Dp）：详情页签滑块等 */
    val SlideSpring: AnimationSpec<Dp> = spring(dampingRatio = 0.99f, stiffness = 438.6f)

    /** 位移/落位 spring（IntOffset）：animateItem 列表重排 placement 等 */
    val OffsetSpring: SpringSpec<IntOffset> = spring(dampingRatio = 0.99f, stiffness = 438.6f)
}

/**
 * 动效无障碍（skill: reduced-motion）：系统「动画时长缩放」为 0（开发者选项/无障碍关闭动画）时，
 * 持续性装饰动画（波点/回声/粒子）应静态化——持续运动是前庭不适主因；
 * 一次性微过渡（展开/弹层 220ms 级）短暂无害，保留。
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        }.getOrDefault(true)
    }
}
