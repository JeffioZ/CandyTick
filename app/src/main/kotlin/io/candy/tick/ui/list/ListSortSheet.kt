package io.candy.tick.ui.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector

/** 排序选择底部弹层 */
@Composable
internal fun SortSheet(
    show: Boolean,
    current: SortMode,
    onSelect: (SortMode) -> Unit,
    onDismiss: () -> Unit,
) {
    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.sort_title),
        onDismissRequest = onDismiss,
    ) {
        Column {
            SortRow(
                label = stringResource(R.string.sort_last_push),
                selected = current == SortMode.LAST_PUSH,
                onClick = { onSelect(SortMode.LAST_PUSH) },
            )
            // 有更新提到第二位：监控 app 的行动信号（有更新要集中装），不该藏在末尾
            SortRow(
                label = stringResource(R.string.sort_updatable),
                selected = current == SortMode.UPDATABLE,
                onClick = { onSelect(SortMode.UPDATABLE) },
            )
            SortRow(
                label = stringResource(R.string.sort_added),
                selected = current == SortMode.ADDED,
                onClick = { onSelect(SortMode.ADDED) },
            )
            SortRow(
                label = stringResource(R.string.sort_status),
                selected = current == SortMode.STATUS,
                onClick = { onSelect(SortMode.STATUS) },
            )
            SortRow(
                label = stringResource(R.string.sort_name),
                selected = current == SortMode.NAME,
                onClick = { onSelect(SortMode.NAME) },
            )
            SortRow(
                label = stringResource(R.string.sort_name_desc),
                selected = current == SortMode.NAME_DESC,
                onClick = { onSelect(SortMode.NAME_DESC) },
            )
        }
    }
}

@Composable
private fun SortRow(label: String, selected: Boolean, onClick: () -> Unit) {
    // 与 BranchRow / OptionRow 同款 BasicComponent：自带按压反馈与行规格，弹层选择行统一语言
    top.yukonga.miuix.kmp.basic.BasicComponent(
        // selected 语义与 OptionRow 同源：✓ 图标纯视觉，读屏需播报选中态
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected },
        title = label,
        endActions = {
            if (selected) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primaryVariant,
                    modifier = Modifier.size(20.dp), // intrinsic 26dp，统一选中标记 20dp
                )
            }
        },
        onClick = onClick,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp,
        ),
    )
}
