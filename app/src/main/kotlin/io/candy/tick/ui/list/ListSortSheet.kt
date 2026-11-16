package io.candy.tick.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

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
            SortRow(
                label = stringResource(R.string.sort_updatable),
                selected = current == SortMode.UPDATABLE,
                onClick = { onSelect(SortMode.UPDATABLE) },
            )
        }
    }
}

@Composable
private fun SortRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = null,
                    tint = scheme.primaryVariant,
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = if (selected) scheme.primaryVariant else scheme.onBackground,
        )
    }
}
