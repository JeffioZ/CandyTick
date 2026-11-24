package io.candy.tick.ui.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.CandySheet
import io.candy.tick.ui.common.SheetScrollToTopTitle
import io.candy.tick.ui.common.SheetChoiceRow
import io.candy.tick.ui.common.scrollToTop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val sortOptions = listOf(
    SortMode.LAST_PUSH to R.string.sort_last_push,
    SortMode.UPDATABLE to R.string.sort_updatable,
    SortMode.ADDED to R.string.sort_added,
    SortMode.STATUS to R.string.sort_status,
    SortMode.NAME to R.string.sort_name,
)

@Composable
internal fun SortSheet(
    show: Boolean,
    current: SortOrder,
    updatableCount: Int,
    onSelect: (SortMode) -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    val scroll = rememberScrollState()
    CandySheet(
        show = show,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
        startAction = {
            SheetScrollToTopTitle(
                text = stringResource(R.string.sort_title),
                canScroll = scroll.canScrollBackward,
                scrollToTop = scroll::scrollToTop,
            )
        },
    ) {
        Column(
            Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * 0.7f).verticalScroll(scroll),
        ) {
            Text(
                text = stringResource(R.string.sort_direction_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                textAlign = TextAlign.Center,
            )
            sortOptions.forEach { (mode, title) ->
                val isCurrent = current.mode == mode
                val ascending = if (isCurrent) current.ascending else mode.defaultAscending
                val direction = stringResource(if (ascending) R.string.sort_ascending else R.string.sort_descending)
                val reverseAction = stringResource(if (ascending) R.string.sort_use_descending else R.string.sort_use_ascending)
                SheetChoiceRow(
                    modifier = Modifier
                        .semantics(mergeDescendants = true) {
                            selected = isCurrent
                            if (isCurrent) {
                                stateDescription = direction
                                onClick(label = reverseAction, action = null)
                            }
                        },
                    title = stringResource(title),
                    summary = sortSummary(mode, ascending, updatableCount),
                    endActions = {
                        if (isCurrent) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_back),
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primaryVariant,
                                modifier = Modifier.size(20.dp).rotate(if (ascending) 90f else -90f),
                            )
                        }
                    },
                    onClick = { onSelect(mode) },
                )
            }
        }
    }
}

@Composable
private fun sortSummary(mode: SortMode, ascending: Boolean, updatableCount: Int): String = when (mode) {
    SortMode.LAST_PUSH -> stringResource(if (ascending) R.string.sort_activity_oldest else R.string.sort_activity_newest)
    SortMode.ADDED -> stringResource(if (ascending) R.string.sort_added_oldest else R.string.sort_added_newest)
    SortMode.STATUS -> stringResource(if (ascending) R.string.sort_status_inactive else R.string.sort_status_active)
    SortMode.NAME -> stringResource(if (ascending) R.string.sort_name_az else R.string.sort_name_za)
    SortMode.UPDATABLE -> if (updatableCount == 0) {
        stringResource(R.string.sort_no_updates)
    } else {
        pluralStringResource(
            if (ascending) R.plurals.sort_updates_last else R.plurals.sort_updates_first,
            updatableCount, updatableCount,
        )
    }
}
