package io.candy.tick.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.candy.tick.R
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ScrollToTopTitle(
    text: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actionLabel: Int = R.string.scroll_to_top,
    style: TextStyle = MiuixTheme.textStyles.title4,
) {
    Box(
        modifier = modifier
            .titleAction(onClick, actionLabel)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = style,
            color = MiuixTheme.colorScheme.onBackground,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Occupies the Miuix title row, which already provides the top and bottom spacing. */
@Composable
internal fun SheetScrollToTopTitle(
    text: String,
    canScroll: Boolean,
    scrollToTop: suspend (Boolean) -> Unit,
    // 40dp：有搜索框的选择器传此值——标题→搜索框的间距需要收紧（真机反馈）；
    // 其他浮层保持默认 48dp 触控规范
    minHeight: Dp = 48.dp,
) {
    val scope = rememberCoroutineScope()
    val animated = rememberMotionEnabled()
    val onClick: (() -> Unit)? = if (canScroll) {
        { scope.launch { scrollToTop(animated) } }
    } else null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth()
            .titleAction(onClick)
            .sizeIn(minWidth = 48.dp, minHeight = minHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = MiuixTheme.textStyles.title4.fontSize,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Modifier.titleAction(onClick: (() -> Unit)?, actionLabel: Int = R.string.scroll_to_top): Modifier {
    if (onClick == null) return semantics { heading() }
    var focused by remember { mutableStateOf(false) }
    val focusRing = if (focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard) {
        Modifier.border(1.dp, MiuixTheme.colorScheme.primaryVariant, RoundedCornerShape(10.dp))
    } else Modifier
    return this
        .then(focusRing)
        .onFocusChanged { focused = it.isFocused }
        .clickable(
            interactionSource = null,
            indication = null,
            onClickLabel = stringResource(actionLabel),
            role = Role.Button,
            onClick = onClick,
        )
        .semantics { heading() }
}

/** Keep the title and buttons in one measured row. Very narrow windows can scroll to every action. */
@Composable
internal fun TopBarActionRow(minimum: Dp, content: @Composable RowScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = TopAppBarDefaults.NavigationIconPadding)) {
        val row = if (maxWidth < minimum) {
            Modifier.horizontalScroll(rememberScrollState()).width(minimum)
        } else Modifier.fillMaxWidth()
        Row(row, verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** A centered, tappable title in Miuix's inset-aware small app bar. */
@Composable
internal fun CenteredScrollToTopBar(title: String, onBack: () -> Unit, onTitleClick: () -> Unit) {
    val backInset = TopAppBarDefaults.NavigationIconPadding
    SmallTopAppBar(
        title = "",
        color = Color.Transparent,
        navigationIconPadding = 0.dp,
        navigationIcon = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ScrollToTopTitle(
                    text = title,
                    onClick = onTitleClick,
                    modifier = Modifier.padding(horizontal = backInset + 48.dp),
                    style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.Medium),
                )
                PressIconButton(
                    onClick = onBack,
                    contentDescription = stringResource(R.string.back),
                    containerColor = Color.Unspecified,
                    glass = true,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = backInset),
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_back),
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
            }
        },
    )
}

internal suspend fun LazyListState.scrollToTop(animated: Boolean) {
    if (!canScrollBackward && !isScrollInProgress) return
    if (!animated) {
        scrollToItem(0)
        return
    }
    // 长距离先跳到目标附近再动画收尾：直达 0 的长动画途经未测量内容
    //（如第一个版本展开的更新日志）时会中途重新估算目标，表现为
    //「先滚到一半停顿、再滚到顶」的两段式
    if (firstVisibleItemIndex > 1) scrollToItem(1)
    animateScrollToItem(0)
}

internal suspend fun ScrollState.scrollToTop(animated: Boolean) {
    if (value == 0 && !isScrollInProgress) return
    if (animated) animateScrollTo(0) else scrollTo(0)
}
