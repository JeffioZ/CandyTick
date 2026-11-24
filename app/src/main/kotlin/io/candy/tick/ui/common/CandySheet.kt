package io.candy.tick.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * 全 App 底部弹层唯一来源：miuix [WindowBottomSheet] + 内容区的导航栏底部内边距。
 *
 * 该弹层是 edge-to-edge 的窗口级 Dialog，库主体只加 IME 内边距；导航栏内边距按库的契约
 * 由调用方内容自补（上游 example 同款），否则末行与底部按钮会伸到系统导航栏之下。
 * 外层已消费 IME 内边距，这里再取导航栏内边距不会与键盘叠加出空白。
 */
@Composable
internal fun CandySheet(
    show: Boolean,
    onDismissRequest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    endAction: @Composable (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    allowDismiss: Boolean = true,
    enableNestedScroll: Boolean = true,
    content: @Composable () -> Unit,
) {
    WindowBottomSheet(
        show = show,
        modifier = modifier,
        title = title,
        startAction = startAction,
        endAction = endAction,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        allowDismiss = allowDismiss,
        enableNestedScroll = enableNestedScroll,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
        ) {
            content()
        }
    }
}
