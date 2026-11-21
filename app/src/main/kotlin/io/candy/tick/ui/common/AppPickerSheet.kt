package io.candy.tick.ui.common

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.toImageBitmap
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowBottomSheet

private data class AppOption(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
)

/**
 * 关联本机应用选择器（QUERY_ALL_PACKAGES；列出可启动应用，按名称排序）。
 * 与 BranchPickerSheet 同款模式：60% 屏高上限 + 超过 15 项显示搜索框 +
 * BasicComponent 行（自带按压反馈，与排序/分支/语言选择行统一）。
 */
@Composable
fun AppPickerSheet(
    show: Boolean,
    onPick: (packageName: String, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    // 打开即聚焦：搜索框仅在列表 >15 项时组合，且列表 IO 加载常超 100ms——
    // 固定延迟会打在尚未挂载的 FocusRequester 上静默失败；请求挪到列表就绪后（见下）
    val pickerFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // 应用列表在 IO 线程加载（几百个应用的 label/icon 查询曾阻塞组合主线程）
    var apps by remember { mutableStateOf<List<AppOption>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(false) }
    var appsFailed by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    /** 扫描已装应用（打开与重试共用）：异常不再吞成空列表（此前失败=空白 sheet 无出口） */
    fun loadApps() {
        appsLoading = true
        appsFailed = false
        scope.launch {
            apps = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = context.packageManager
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    pm.queryIntentActivities(intent, 0)
                        .map { ri ->
                            AppOption(
                                packageName = ri.activityInfo.packageName,
                                label = ri.loadLabel(pm).toString(),
                                icon = runCatching { ri.activityInfo.loadIcon(pm) }.getOrNull(),
                            )
                        }
                        .distinctBy { it.packageName }
                        .filterNot { it.packageName == context.packageName }
                        .sortedBy { it.label.lowercase() }
                }.onFailure { appsFailed = true }.getOrDefault(emptyList())
            }
            appsLoading = false
        }
    }

    // sheet 关闭即清搜索词与列表：消灭幽灵草稿（与 Token / 分支弹层同一治理原则）
    LaunchedEffect(show) {
        if (show) {
            loadApps()
        } else {
            if (query.isNotEmpty()) query = ""
            apps = emptyList()
            appsFailed = false
        }
    }
    // 列表就绪且需要搜索框（>15 项）才请求聚焦：此刻 TextField 已组合挂载，requestFocus 生效
    LaunchedEffect(show, apps.size > 15) {
        if (show && apps.size > 15) {
            kotlinx.coroutines.delay(100)
            runCatching { pickerFocus.requestFocus() }
        }
    }
    val keyword = query.trim()
    val filtered = if (keyword.isEmpty()) apps else apps.filter {
        it.label.contains(keyword, ignoreCase = true) ||
            it.packageName.contains(keyword, ignoreCase = true)
    }
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.6f

    // show 直通（勿用 if 包裹）：退场动画依赖组件持续组合
    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.detail_link_app),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight),
        ) {
            if (appsLoading) {
                // 加载位：与分支弹层同款（独立等待位 = 居中 dots）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingDots(dotSize = 5.dp)
                }
            }
            // 扫描失败：错误态 + 重试（此前异常吞成空列表，sheet 直接空白）
            if (appsFailed) {
                StatusPage(
                    icon = null,
                    isError = true,
                    compact = true,
                    title = stringResource(R.string.load_failed),
                    subtitle = stringResource(R.string.status_error_subtitle),
                    actionLabel = stringResource(R.string.retry),
                    onAction = { loadApps() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
            }
            // 零应用（包可见性受限或纯系统环境）：此前全空白无任何解释
            if (!appsLoading && !appsFailed && apps.isEmpty() && query.isBlank()) {
                Text(
                    text = stringResource(R.string.picker_empty_apps),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (apps.size > 15) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.picker_search_app),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
            // 搜索无结果：轻量居中提示（sheet 语境，不套重型空态组件）
            if (query.isNotBlank() && filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.picker_no_match),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            LazyColumn {
                items(filtered, key = { it.packageName }) { app ->
                    BasicComponent(
                        modifier = Modifier.fillMaxWidth(),
                        title = app.label,
                        summary = app.packageName,
                        startAction = {
                            app.icon?.let {
                                AppIconImage(
                                    bitmap = remember(app.packageName) { it.toImageBitmap() },
                                    size = 36.dp,
                                    corner = 9.dp,
                                )
                            } ?: Box(modifier = Modifier.size(36.dp))
                        },
                        onClick = { onPick(app.packageName, app.label) },
                        insideMargin = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp,
                        ),
                    )
                }
            }
        }
    }
}
