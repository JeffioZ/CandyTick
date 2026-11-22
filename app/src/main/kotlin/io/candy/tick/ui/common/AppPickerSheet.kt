package io.candy.tick.ui.common

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.util.appDisplayName
import io.candy.tick.util.labelOf
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowBottomSheet

private data class AppOption(
    val packageName: String,
    val label: String,
)

private const val SEARCH_THRESHOLD = 15
private const val PICKER_OPEN_BUDGET_MS = 80L

private class AppPickerVisit {
    var result by mutableStateOf<Result<List<AppOption>>?>(null)
    var retry by mutableIntStateOf(0)
    var fullHeight = false
}

private fun readLauncherApps(context: Context): List<AppOption> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0).asSequence()
        .filterNot { it.activityInfo.packageName == context.packageName }
        .distinctBy { it.activityInfo.packageName }
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
            visit.fullHeight = quickApps == null || quickApps.size > SEARCH_THRESHOLD
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
    val windowHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxSheetHeight = windowHeight * 0.6f
    val scrollSearchField = windowHeight < 480.dp
    val listState = key(page) { rememberLazyListState() }

    // show 直通（勿用 if 包裹）：退场动画依赖组件持续组合
    WindowBottomSheet(
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
            )
        },
    ) {
        val result = page.result
        val apps = result?.getOrNull().orEmpty()
        val searchable = apps.size > SEARCH_THRESHOLD
        var query by remember(page) { mutableStateOf("") }
        val keyword = query.trim()
        val filtered = remember(apps, keyword) {
            if (keyword.isEmpty()) apps else apps.filter {
                it.label.contains(keyword, ignoreCase = true) ||
                    it.packageName.contains(keyword, ignoreCase = true)
            }
        }
        fun filterApps(value: String) {
            if (value == query || value.length > InputLimits.SEARCH) return
            query = value
            listState.requestScrollToItem(0)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                    icon = null,
                    isError = true,
                    compact = true,
                    title = stringResource(R.string.load_failed),
                    subtitle = stringResource(R.string.status_error_subtitle),
                    actionLabel = stringResource(R.string.retry),
                    onAction = { page.retry++ },
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).wrapContentHeight(),
                )
                return@Column
            }
            if (apps.isEmpty()) {
                Text(
                    text = stringResource(R.string.picker_empty_apps),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (page.fullHeight) Modifier.fillMaxSize().wrapContentHeight() else Modifier)
                        .padding(vertical = 32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                return@Column
            }
            val searchField: @Composable () -> Unit = {
                TextField(
                    value = query,
                    onValueChange = ::filterApps,
                    label = stringResource(R.string.picker_search_app),
                    singleLine = true,
                    trailingIcon = if (query.isNotEmpty()) {
                        { TextFieldActionButton(onClick = { filterApps("") }) }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
            if (searchable && !scrollSearchField) searchField()
            LazyColumn(state = listState) {
                if (searchable && scrollSearchField) item(key = 0) { searchField() }
                if (searchable && filtered.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.picker_no_match),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.fillMaxWidth().fillParentMaxHeight().wrapContentHeight()
                                .padding(horizontal = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
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
