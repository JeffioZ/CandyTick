package io.candy.tick.ui.nav

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
fun AppNavHost(
    container: AppContainer,
    pendingRepoId: Long? = null, // 通知点击目标：仓库新版本 → 详情页
    pendingSettings: Boolean = false, // 自更新通知 → 设置页
    onNavConsumed: () -> Unit = {},
) {
    // 冷启动：初始栈直达目标（返回语义：目标 → 首页 → 桌面）
    val backStack = rememberNavBackStack(
        *buildList {
            add(RouteHome)
            pendingRepoId?.let { add(RouteDetail(it)) }
            if (pendingSettings) add(RouteSettings)
        }.toTypedArray(),
    )
    // 消费通知目标（热启动压栈；冷启动初始栈已含 → 栈顶去重天然跳过）
    androidx.compose.runtime.LaunchedEffect(pendingRepoId, pendingSettings) {
        when {
            pendingSettings -> {
                if (backStack.lastOrNull() != RouteSettings) backStack.add(RouteSettings)
                onNavConsumed()
            }
            pendingRepoId != null -> {
                val target = RouteDetail(pendingRepoId)
                if (backStack.lastOrNull() != target) backStack.add(target)
                onNavConsumed()
            }
        }
    }
    fun close(key: NavKey) {
        backStack.remove(key)
    }
    // 大屏/折叠屏适配（单点规则，全部页面共用）：背景全宽 + 内容列限宽 640dp 居中——
    // 手机（<600dp）与现状零差异；折叠展开/平板的行长落回可读区间（65-75 字符）。
    // 顶栏随内容列对齐；转场位移以列宽为基准。新页面自动获得该形态，无需逐页适配
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            // 对称水平推移（25% 行程等距）：轻快落位感——不对称行程+双向 fade 的旧组合
            // 中间帧两页半透明叠加（背景透出=「漂」感）；对称等速 + 减速曲线是系统 Activity 手感
            transitionSpec = {
            (slideInHorizontally(initialOffsetX = { it / 4 }, animationSpec = navSpec()) +
                fadeIn(navSpec())) togetherWith
                (slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = exitSpec()) +
                    fadeOut(exitSpec()))
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
            // 内容列限宽：NavDisplay 收窄至 640dp 并水平居中（外层 Box 背景补全两侧）
            modifier = Modifier.widthIn(max = 640.dp),
        )
    }
}

/** 减速曲线（页面「落位」感），260ms——比旧 300ms 轻快，比组件动效（220 档）长半档 */
private fun <T> navSpec() = tween<T>(durationMillis = 260, easing = LinearOutSlowInEasing)

/** 退出档 = 进场的 ~65%（skill: exit-faster-than-enter）——旧页更快让位，两页叠加
 *  窗口收窄，首页顶栏按钮群在转场中的「残留/遮挡」感随之消失 */
private fun <T> exitSpec() = tween<T>(durationMillis = 170, easing = LinearOutSlowInEasing)

private fun popTransform() =
    (slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = navSpec()) +
        fadeIn(navSpec())) togetherWith
        (slideOutHorizontally(targetOffsetX = { it / 4 }, animationSpec = exitSpec()) +
            fadeOut(exitSpec()))
