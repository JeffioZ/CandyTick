package io.candy.tick.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

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
