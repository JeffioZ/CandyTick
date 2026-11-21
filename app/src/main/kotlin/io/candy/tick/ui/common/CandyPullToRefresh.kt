package io.candy.tick.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.candy.tick.R
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.PullToRefreshState
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

/**
 * 首页和详情共用四态刷新头。Miuix 的 contentPadding 只移动刷新头的绘制位置；
 * 列表与顶栏之间的留白由各页面自己分配。
 */
@Composable
internal fun <T> CandyPullToRefresh(
    requestState: StateFlow<T>,
    refreshing: (T) -> Boolean,
    onRefresh: () -> Unit,
    pullToRefreshState: PullToRefreshState,
    topAppBarScrollBehavior: ScrollBehavior?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    var headerRefreshing by remember(requestState, refreshing) { mutableStateOf(refreshing(requestState.value)) }
    LaunchedEffect(requestState, refreshing) {
        requestState.collect { headerRefreshing = refreshing(it) }
    }
    PullToRefresh(
        isRefreshing = headerRefreshing,
        onRefresh = {
            onRefresh()
            // Miuix checks the hoisted flag as soon as its release spring settles.
            // Acknowledge from the same source here, before its collector's next turn.
            headerRefreshing = refreshing(requestState.value)
        },
        pullToRefreshState = pullToRefreshState,
        topAppBarScrollBehavior = topAppBarScrollBehavior,
        contentPadding = PaddingValues(top = 9.dp),
        color = scheme.primaryVariant, // 薄荷主色：与全应用活动色统一
        refreshTexts = listOf(
            stringResource(R.string.refresh_pull),
            stringResource(R.string.refresh_release),
            stringResource(R.string.refresh_refreshing),
            stringResource(R.string.refresh_done),
        ),
        modifier = modifier,
    ) {
        content()
    }
}
