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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** 刷新头最短驻留：防「重试→秒败→一闪而过」的闪烁感。与 SyncDots 的防闪延迟共用同一档时长 */
private val MIN_REFRESHING_SHOWN_MS = Motion.PROGRESS.toLong()

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
    // 最短显示：失败/304 来得快时刷新头一闪而过（loading 反馈要匹配等待、避免闪烁）。
    // 自首次置忙起保底 300ms（Motion.PROGRESS 档，与 SyncDots 的防闪延迟共用同一取值）。
    var busySince by remember(requestState, refreshing) { mutableStateOf(0L) }
    LaunchedEffect(requestState, refreshing) {
        requestState.collect {
            if (refreshing(it)) {
                // 每轮刷新都重记起点：保底窗口内起的新一轮也有自己的 300ms
                busySince = android.os.SystemClock.elapsedRealtime()
                headerRefreshing = true
            } else if (headerRefreshing) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - busySince
                if (elapsed < MIN_REFRESHING_SHOWN_MS) delay(MIN_REFRESHING_SHOWN_MS - elapsed)
                // 保底期间可能又起一轮新刷新：只有确实空闲才收起
                if (!refreshing(requestState.value)) headerRefreshing = false
            }
        }
    }
    PullToRefresh(
        isRefreshing = headerRefreshing,
        onRefresh = {
            onRefresh()
            // 回读而非强制置 true：onRefresh 可能不产生状态发射（如刷新已在途被合并），
            // 强制置 true 后 collector 无事件收尾 → 刷新头永久转圈
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
