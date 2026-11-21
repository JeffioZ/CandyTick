package io.candy.tick.ui.nav

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
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
        // pop 语义优先：正常路径（key 在栈顶）随返回转场退出；通知热启动叠栈时 key
        // 非栈顶（此时无转场可言），退回精确移除以免破坏返回路径
        if (backStack.lastOrNull() == key) backStack.removeLastOrNull() else backStack.remove(key)
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
            // 覆盖式转场（Android/HyperOS 系统惯例）：新页 100% 不透明滑入、旧页 25% 视差
            // 平移、全程无 fade——两页任何瞬间都不互相透明，交叉淡化时代的顶栏按钮
            // 「残留/遮挡」物理消失。前置：每页自备不透明背景（entryProvider 内包裹）。
            transitionSpec = {
            slideInHorizontally(initialOffsetX = { it }, animationSpec = navSpec()) togetherWith
                slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = navSpec())
            },
            // 返回：与前进镜像（详情页 100% 滑出右侧，下层页从 -25% 归位）
            popTransitionSpec = { popTransform() },
            // 预测性返回：预览帧被系统按手势进度逐帧 seek——必须线性（缓动会让位移 ≠
            // 手指，100% 行程下放大成「前段不动、后段猛冲」），与 pop 的缓动曲线分开
            predictivePopTransitionSpec = { predTransform() },
            entryProvider = { key: NavKey ->
            NavEntry(key) { k ->
                // 每页自备不透明背景（覆盖式转场前置）：背景画在页与页之间而非最外层，
                // 否则 100% 滑入的透明页会把旧页整块透出来（评审阻断项）。640dp 列外的
                // 留白仍由外层 Box 负责（同色，视觉零变化）
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background),
                ) {
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
            }
            },
            // 内容列限宽：NavDisplay 收窄至 640dp 并水平居中（外层 Box 背景补全两侧）
            modifier = Modifier.widthIn(max = 640.dp),
        )
    }
}

/** 减速曲线（页面「落位」感），260ms——比旧 300ms 轻快，比组件动效（220 档）长半档 */
private fun <T> navSpec() = tween<T>(durationMillis = 260, easing = LinearOutSlowInEasing)

private fun popTransform() =
    slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = navSpec()) togetherWith
        slideOutHorizontally(targetOffsetX = { it }, animationSpec = navSpec())

/** 预测性返回：手势 seek 逐帧驱动，线性曲线保证位移与手指一致 */
private fun predTransform() =
    slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(260, easing = LinearEasing)) togetherWith
        slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260, easing = LinearEasing))
