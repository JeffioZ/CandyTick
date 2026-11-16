package io.candy.tick.ui.nav

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.candy.tick.di.AppContainer
import io.candy.tick.ui.add.AddScreen
import io.candy.tick.ui.detail.DetailScreen
import io.candy.tick.ui.list.ListScreen
import io.candy.tick.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
data object RouteHome : NavKey

@Serializable
data object RouteAdd : NavKey

@Serializable
data object RouteSettings : NavKey

@Serializable
data class RouteDetail(val repoId: Long) : NavKey

@Composable
fun AppNavHost(container: AppContainer) {
    val backStack = rememberNavBackStack(RouteHome)
    fun close(key: NavKey) {
        backStack.remove(key)
    }
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeAt(backStack.lastIndex) },
        // 系统式水平推移：新页从右滑入（30% 行程 + 淡入），旧页向左让位（25% + 淡出）
        transitionSpec = {
            (slideInHorizontally(initialOffsetX = { it / 3 }, animationSpec = navSpec()) +
                fadeIn(navSpec())) togetherWith
                (slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = navSpec()) +
                    fadeOut(navSpec()))
        },
        // 返回：与前进镜像（旧页从左滑回，当前页滑向右侧）
        popTransitionSpec = { popTransform() },
        // 预测性返回（Android 14+ 手势预览阶段共用同一形态）
        predictivePopTransitionSpec = { popTransform() },
        entryProvider = { key: NavKey ->
            NavEntry(key) { k ->
                when (k) {
                    RouteHome -> ListScreen(
                        onAdd = { backStack.add(RouteAdd) },
                        onOpenDetail = { backStack.add(RouteDetail(it)) },
                        onOpenSettings = { backStack.add(RouteSettings) },
                    )
                    RouteAdd -> AddScreen(
                        onClose = { close(k) },
                        onAdded = { id ->
                            close(k)
                            backStack.add(RouteDetail(id))
                        },
                    )
                    RouteSettings -> SettingsScreen(
                        onBack = { close(k) },
                    )
                    is RouteDetail -> DetailScreen(
                        repoId = k.repoId,
                        onBack = { close(k) },
                    )
                }
            }
        },
    )
}

/** 系统标准「快出慢入」曲线，300ms（Material 动效推荐区间） */
private fun <T> navSpec() = tween<T>(durationMillis = 300, easing = FastOutSlowInEasing)

private fun popTransform() =
    (slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = navSpec()) +
        fadeIn(navSpec())) togetherWith
        (slideOutHorizontally(targetOffsetX = { it / 3 }, animationSpec = navSpec()) +
            fadeOut(navSpec()))
