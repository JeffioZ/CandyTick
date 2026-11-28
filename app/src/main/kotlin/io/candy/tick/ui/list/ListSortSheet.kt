package io.candy.tick.ui.list

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.common.SheetScrollToTopTitle
import io.candy.tick.ui.common.rememberMotionEnabled
import io.candy.tick.ui.common.scrollToTop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
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
    val motionEnabled = rememberMotionEnabled()
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
        val scheme = MiuixTheme.colorScheme
        val rowShape = RoundedCornerShape(14.dp)
        Column(
            modifier = Modifier
                .heightIn(max = LocalWindowInfo.current.containerDpSize.height * 0.7f)
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp)
                .padding(top = 4.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            sortOptions.forEach { (mode, title) ->
                val isCurrent = current.mode == mode
                val ascending = if (isCurrent) current.ascending else mode.defaultAscending
                val direction = stringResource(if (ascending) R.string.sort_ascending else R.string.sort_descending)
                val reverseAction = stringResource(if (ascending) R.string.sort_use_descending else R.string.sort_use_ascending)
                val rowBg by animateColorAsState(
                    targetValue = if (isCurrent) scheme.primaryVariant.copy(alpha = 0.11f) else Color.Transparent,
                    animationSpec = tween(if (motionEnabled) Motion.MICRO else 0),
                    label = "sortRowBg",
                )
                val titleColor by animateColorAsState(
                    targetValue = if (isCurrent) scheme.primaryVariant else scheme.onSurface,
                    animationSpec = tween(if (motionEnabled) Motion.MICRO else 0),
                    label = "sortTitleColor",
                )
                BasicComponent(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(rowShape)
                        .background(rowBg)
                        .semantics(mergeDescendants = true) {
                            selected = isCurrent
                            if (isCurrent) {
                                stateDescription = direction
                                onClick(label = reverseAction, action = null)
                            }
                        },
                    title = stringResource(title),
                    titleColor = BasicComponentDefaults.titleColor(color = titleColor),
                    summary = sortSummary(mode, ascending, updatableCount),
                    endActions = {
                        if (isCurrent) {
                            val arrowRotation by animateFloatAsState(
                                targetValue = if (ascending) 90f else -90f,
                                animationSpec = tween(
                                    durationMillis = if (motionEnabled) Motion.PANEL else 0,
                                    easing = Motion.Decelerate,
                                ),
                                label = "sortArrowRotation",
                            )
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_back),
                                contentDescription = null,
                                tint = scheme.primaryVariant,
                                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = arrowRotation },
                            )
                        }
                    },
                    onClick = { onSelect(mode) },
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            Text(
                text = stringResource(R.string.sort_direction_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onBackgroundVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 8.dp, end = 8.dp),
                textAlign = TextAlign.Center,
            )
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
