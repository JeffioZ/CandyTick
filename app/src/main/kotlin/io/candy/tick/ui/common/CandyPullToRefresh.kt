package io.candy.tick.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.candy.tick.R
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.PullToRefreshState
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.unit.dp

/**
 * 全 App 下拉刷新唯一配置源（首页/详情共用）：四态常驻文字（下拉/释放/正在/完成——
 * 文字从头就在 = 头部高度全相恒定，间距跳变从根消失）+ contentPadding top（圆环不顶
 * 顶栏）。两页的间距补偿在各自 LazyColumn 的 contentPadding top 里（首页 13/详情 19），
 * 与此处的 9dp 配对——改这里时记得检查两页的配对值。
 */
@Composable
fun CandyPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    pullToRefreshState: PullToRefreshState,
    topAppBarScrollBehavior: ScrollBehavior?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    PullToRefresh(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
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
