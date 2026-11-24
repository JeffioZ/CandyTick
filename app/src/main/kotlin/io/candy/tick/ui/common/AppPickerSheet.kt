package io.candy.tick.ui.common

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.util.appDisplayName
import io.candy.tick.util.launcherEntries
import io.candy.tick.util.labelOf

private data class AppOption(
    val packageName: String,
    val label: String,
)

private const val PICKER_OPEN_BUDGET_MS = 80L

private class AppPickerVisit {
    var result by mutableStateOf<Result<List<AppOption>>?>(null)
    var retry by mutableIntStateOf(0)
    var fullHeight = false
}

private fun readLauncherApps(context: Context): List<AppOption> {
    val pm = context.packageManager
    return launcherEntries(context)
        .map { entry ->
            val pkg = entry.activityInfo.packageName
            AppOption(pkg, appDisplayName(pkg, entry.loadLabel(pm)) ?: labelOf(context, pkg) ?: pkg)
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

/**
 * 列出有桌面启动入口的应用，按名称排序。面板最多占屏高的 60%，超过 15 项时可搜索。
 */
@Composable
fun AppPickerSheet(
    show: Boolean,
    onPick: (packageName: String, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val visit = if (show) remember(context) { AppPickerVisit() } else null
    var displayed by remember { mutableStateOf<AppPickerVisit?>(null) }
    val visible by rememberUpdatedState(show)
    LaunchedEffect(visit, visit?.retry) {
        if (visit == null) return@LaunchedEffect
        visit.result = null
        val scan = async(Dispatchers.IO) { runCatching { readLauncherApps(context) } }
        if (displayed !== visit) {
            // A quick scan can size a short list exactly. On a cold scan, show the loading
            // surface within one press interval and keep that same height when rows arrive.
            val quickResult = withTimeoutOrNull(PICKER_OPEN_BUDGET_MS) { scan.await() }
            val quickApps = quickResult?.getOrNull()
            visit.fullHeight = quickApps == null || quickApps.size > PICKER_SEARCH_THRESHOLD
            visit.result = quickResult
            displayed = visit
            if (quickResult != null) return@LaunchedEffect
        }
        visit.result = scan.await()
    }
    val open = visit != null && displayed === visit
    BackHandler(enabled = show && !open, onBack = onDismiss)
    // displayed is retained until the exit animation finishes.
    val page = displayed ?: return
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val maxSheetHeight = windowHeight * 0.6f
    val listState = key(page) { rememberLazyListState() }

    // show 直通（勿用 if 包裹）：退场动画依赖组件持续组合
    CandySheet(
        show = open,
        onDismissRequest = onDismiss,
        onDismissFinished = {
            if (!visible) displayed = null
        },
        startAction = {
            SheetScrollToTopTitle(
                text = stringResource(R.string.detail_link_app),
                canScroll = listState.canScrollBackward,
                scrollToTop = listState::scrollToTop,
                minHeight = 40.dp, // 有搜索框的选择器收紧标题槽（间距平衡，真机反馈）
            )
        },
    ) {
        val result = page.result
        val apps = result?.getOrNull().orEmpty()
        val searchable = apps.size > PICKER_SEARCH_THRESHOLD
        var query by remember(page) { mutableStateOf("") }
        val keyword = query.trim()
        val filtered = remember(apps, keyword) {
            if (keyword.isEmpty()) apps else apps.filter {
                it.label.contains(keyword, ignoreCase = true) ||
                    it.packageName.contains(keyword, ignoreCase = true)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 有搜索框时 top=0：搜索框自身高度已是标题与内容的分隔，再叠 4dp 会
                // 使上方间距大于下方（真机反馈）；无搜索框（少量选项）保留 4dp 与
                // 设置/更新弹窗统一
                .padding(top = if (searchable) 0.dp else 4.dp)
                // The sheet applies IME insets. The requested height is clamped to its available space.
                .then(if (page.fullHeight) Modifier.height(maxSheetHeight) else Modifier.heightIn(max = maxSheetHeight)),
        ) {
            if (result == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingDots(dotSize = 5.dp)
                }
                return@Column
            }
            if (result.isFailure) {
                StatusPage(
                    isError = true,
                    compact = true,
                    title = stringResource(R.string.apps_load_failed),
                    actionLabel = stringResource(R.string.retry),
                    onAction = { page.retry++ },
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).wrapContentHeight(),
                )
                return@Column
            }
            if (apps.isEmpty()) {
                StatusPage(
                    icon = ImageVector.vectorResource(R.drawable.ic_package),
                    title = stringResource(R.string.picker_empty_apps),
                    compact = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (page.fullHeight) Modifier.fillMaxSize() else Modifier)
                        .verticalScroll(rememberScrollState()).wrapContentHeight().padding(vertical = 24.dp),
                )
                return@Column
            }
            PickerList(listState, searchable, query, stringResource(R.string.picker_search_app), { query = it }) {
                if (searchable && filtered.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier.fillParentMaxHeight().fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            StatusPage(
                                icon = ImageVector.vectorResource(R.drawable.ic_search),
                                title = stringResource(R.string.picker_no_match),
                                compact = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                items(filtered, key = { it.packageName }) { app ->
                    SheetChoiceRow(
                        title = app.label,
                        summary = app.packageName,
                        startAction = {
                            rememberAppIcon(app.packageName)?.let {
                                AppIconImage(
                                    bitmap = it,
                                    size = 36.dp,
                                    corner = 9.dp,
                                )
                            } ?: Box(modifier = Modifier.size(36.dp))
                        },
                        onClick = { onPick(app.packageName, app.label) },
                    )
                }
            }
        }
    }
}
