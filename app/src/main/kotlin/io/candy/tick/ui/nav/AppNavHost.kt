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
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.candy.tick.ui.add.AddScreen
import io.candy.tick.ui.detail.DetailScreen
import io.candy.tick.ui.list.ListScreen
import io.candy.tick.ui.settings.SettingsScreen
import io.candy.tick.ui.store.StoreSettingsScreen
import io.candy.tick.ui.store.StoreUpdatesScreen
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey

@Serializable
data object RouteHome : AppRoute

@Serializable
data object RouteAdd : AppRoute

@Serializable
data class RouteSettings(val focusImport: Boolean = false) : AppRoute

@Serializable
data class RouteDetail(
    val repoId: Long,
    val releaseTag: String? = null,
    val releaseRequestId: String? = null,
) : AppRoute

@Serializable
data object RouteStoreUpdates : AppRoute

@Serializable
data object RouteStoreSettings : AppRoute

@Composable
fun AppNavHost(
    pendingTarget: AppRoute? = null,
    onNavConsumed: (AppRoute) -> Unit = {},
) {
    // 冷启动：初始栈直达目标（返回语义：目标 → 首页 → 桌面）
    val backStack = rememberNavBackStack(
        *buildList {
            add(RouteHome)
            if (pendingTarget != null && pendingTarget != RouteHome) add(pendingTarget)
        }.toTypedArray(),
    )
    androidx.compose.runtime.LaunchedEffect(pendingTarget) {
        val target = pendingTarget ?: return@LaunchedEffect
        val existing = backStack.indexOfLast { key ->
            key == target || (target is RouteDetail && key is RouteDetail && key.repoId == target.repoId)
        }
        if (existing < 0) {
            backStack.add(target)
        } else {
            while (backStack.lastIndex > existing) backStack.removeLastOrNull()
            if (backStack[existing] != target) backStack[existing] = target
        }
        onNavConsumed(target)
    }
    fun openFromHome(key: NavKey) {
        when (backStack.lastOrNull()) {
            RouteHome -> backStack.add(key)
            null -> {
                backStack.add(RouteHome)
                backStack.add(key)
            }
            else -> {
                // 兜底：栈顶异常不是 Home（真机复现过导入订阅后首页点击全部失效、重启才恢复）。
                // 不弹栈直接入栈——转场窗口（260ms）内旧页仍可点，弹栈会误清刚 push 的页面
                //（Add 页已填内容会丢）；卡死态下 push 后目标页正常出现，栈里残留一页可逐层退回。
                // 同 key 去重：转场窗口内的重复点击不得叠出两份同页（返回时首按看似无反应）
                android.util.Log.w("AppNavHost", "openFromHome top=${backStack.lastOrNull()} unexpected, pushing above it")
                if (backStack.lastOrNull() != key) backStack.add(key)
            }
        }
    }
    fun goBackFrom(key: NavKey) {
        if (backStack.size > 1 && backStack.last() == key) backStack.removeLastOrNull()
    }
    fun openOrPopTo(target: AppRoute) {
        val existing = backStack.indexOfLast { it == target }
        if (existing >= 0) {
            while (backStack.lastIndex > existing) backStack.removeLastOrNull()
        } else {
            backStack.add(target)
        }
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
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
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
                val content: @Composable (NavKey) -> Unit = { k ->
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
                                onAdd = { openFromHome(RouteAdd) },
                                onOpenDetail = { openFromHome(RouteDetail(it)) },
                                onOpenSettings = { openFromHome(RouteSettings()) },
                                onOpenImport = { openFromHome(RouteSettings(focusImport = true)) },
                                onOpenStoreUpdates = { openFromHome(RouteStoreUpdates) },
                            )
                            RouteAdd -> AddScreen(
                                onClose = { goBackFrom(k) },
                                onAdded = { id ->
                                    // Completion may arrive during the outgoing animation after Back or a notification.
                                    val stillOnAdd = backStack.lastOrNull() == k
                                    if (stillOnAdd) backStack[backStack.lastIndex] = RouteDetail(id)
                                    else backStack.remove(k)
                                },
                            )
                            is RouteSettings -> SettingsScreen(
                                onBack = { goBackFrom(k) },
                                autoOpenImport = k.focusImport,
                                onOpenStoreSettings = { openOrPopTo(RouteStoreSettings) },
                            )
                            RouteStoreUpdates -> StoreUpdatesScreen(
                                onBack = { goBackFrom(k) },
                                onOpenStoreSettings = { openOrPopTo(RouteStoreSettings) },
                            )
                            RouteStoreSettings -> StoreSettingsScreen(
                                onBack = { goBackFrom(k) },
                                onOpenStoreUpdates = { openOrPopTo(RouteStoreUpdates) },
                            )
                            is RouteDetail -> DetailScreen(
                                repoId = k.repoId,
                                onBack = { goBackFrom(k) },
                                onRemoved = {
                                    backStack.removeAll { it is RouteDetail && it.repoId == k.repoId }
                                },
                                releaseRequestId = k.releaseRequestId,
                                releaseTag = k.releaseTag,
                            )
                        }
                    }
                }
                // 重复点击同仓通知时只更新请求，保留原有的详情 ViewModel、列表位置与页面转场状态。
                if (key is RouteDetail) {
                    NavEntry<NavKey>(key, contentKey = key.repoId, content = content)
                } else {
                    NavEntry(key, content = content)
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
