package io.candy.tick.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.CandySheet
import io.candy.tick.ui.common.InlineErrorRow
import io.candy.tick.ui.common.LoadingDots
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.common.OptionRow
import io.candy.tick.ui.common.PICKER_SEARCH_THRESHOLD
import io.candy.tick.ui.common.PickerList
import io.candy.tick.ui.common.SheetChoiceRow
import io.candy.tick.ui.common.SheetScrollToTopTitle
import io.candy.tick.ui.common.StatusPage
import io.candy.tick.ui.common.relTimeString
import io.candy.tick.ui.common.scrollToTop

/**
 * 分支选择器随窗口限高，列表较长时提供筛选。
 * 默认分支优先显示。浏览的分支在所有页中都找不到时，允许切回默认分支。
 */
@Composable
internal fun BranchPickerSheet(
    show: Boolean,
    state: DetailViewModel.UiState,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    onLoadBranches: () -> Unit,
) {
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val maxSheetHeight = windowHeight * 0.6f
    var query by remember { mutableStateOf("") }
    val branches = state.branches.orEmpty()
    val searchable = branches.size > PICKER_SEARCH_THRESHOLD
    // GitHub 式分支切换器：纯分支列表，当前查看的分支打勾——
    // branch=null（跟随默认）时勾打在上游默认分支上；无「Default branch」幻影行
    val defaultBranch = state.snapshot?.defaultBranch
    val currentViewed = state.repo?.branch ?: defaultBranch
    val keyword = query.trim()
    val filtered = remember(branches, keyword) {
        if (keyword.isEmpty()) branches else branches.filter { it.name.contains(keyword, ignoreCase = true) }
    }
    val listReady = state.branches != null && state.nextBranchPage == null
    val missingSelection = state.repo?.branch?.takeIf { name ->
        listReady && branches.none { it.name == name } && name.contains(keyword, ignoreCase = true)
    }
    // show 直通（勿用 if 包裹组件）：关闭时组件仍在组合中，库的退场动画才能播放
    val listState = rememberLazyListState()
    CandySheet(
        show = show,
        onDismissRequest = onDismiss,
        onDismissFinished = {
            query = ""
            listState.requestScrollToItem(0)
        },
        startAction = {
            SheetScrollToTopTitle(
                text = stringResource(R.string.branch_picker_title),
                canScroll = listState.canScrollBackward,
                scrollToTop = listState::scrollToTop,
                minHeight = 40.dp, // 有搜索框的选择器收紧标题槽（间距平衡，真机反馈）
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 有搜索框时 top=0：搜索框自身高度已是分隔，叠 4dp 会使上方大于下方（同应用选择器）；
                // 无搜索框（少量分支）保留 4dp 与设置/更新弹窗统一
                .padding(top = if (searchable) 0.dp else 4.dp)
                .animateContentSize(tween(Motion.PANEL))
                .then(if (searchable) Modifier.height(maxSheetHeight) else Modifier.heightIn(max = maxSheetHeight)),
        ) {
            PickerList(listState, searchable, query, stringResource(R.string.branch_search_hint), { query = it }) {
                if (listReady && filtered.isEmpty() && missingSelection == null) {
                    item(key = "empty") {
                        // fillParentMaxHeight + Center：空态在剩余空间内垂直居中（此前贴顶不居中，
                        // 展示/不展示输入法都有问题——真机反馈；分支+应用选择器同修）
                        Box(
                            modifier = Modifier.fillParentMaxHeight().fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            StatusPage(
                                icon = ImageVector.vectorResource(if (keyword.isEmpty()) R.drawable.ic_git_branch else R.drawable.ic_search),
                                title = stringResource(if (keyword.isEmpty()) R.string.branch_empty else R.string.branch_no_match),
                                compact = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                items(filtered, key = { it.name }) { b ->
                    // 点默认分支 = 存 null（保留「跟随默认」语义，上游改名自动跟）。
                    // 副标题 null 时传占位空格：行高恒为「有副标题」高度，日期到达只换文字
                    // 不变高度——标题零位移（animateContentSize 只动画容器尺寸，内部布局
                    // 的标题居中位置变化是瞬时的，真机反馈仍跳——此为根治）
                    val summary = b.tipDate?.let { relTimeString(it) } ?: " "
                    OptionRow(b.name, b.name == currentViewed, summary = summary) {
                        onSelect(if (b.name == defaultBranch) null else b.name)
                        onDismiss()
                    }
                }
                missingSelection?.let { name ->
                    item(key = 1) {
                        OptionRow(name, true, summary = stringResource(R.string.branch_removed_hint)) {
                            onSelect(null)
                            onDismiss()
                        }
                    }
                }
                if (state.nextBranchPage != null) item(key = 2) {
                    when {
                        state.branchesLoading -> Box(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) { LoadingDots(dotSize = 5.dp) }
                        state.branchesFailed && branches.isEmpty() -> StatusPage(
                            isError = true, compact = true,
                            title = stringResource(R.string.branches_load_failed),
                            actionLabel = stringResource(R.string.retry), onAction = onLoadBranches,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        )
                        state.branchesFailed -> Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            InlineErrorRow(stringResource(R.string.branches_load_failed), onLoadBranches)
                        }
                        state.branches != null -> {
                            val label = if (keyword.isNotEmpty() && filtered.isEmpty()) R.string.branch_search_next
                                else R.string.branch_load_more
                            SheetChoiceRow(stringResource(label), onLoadBranches)
                        }
                    }
                }
            }
        }
    }
}
