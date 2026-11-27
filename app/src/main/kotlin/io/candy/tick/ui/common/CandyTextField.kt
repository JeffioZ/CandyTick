package io.candy.tick.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLengthReject
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全 App 输入框唯一来源：miuix 0.9.5 状态式 [TextField] 外加 1dp 未聚焦描边。
 *
 * 库在未聚焦时描边宽度为 0dp，只靠填充色区分，而本项目的填充色对页面底只有 1.06:1，
 * 几乎看不出输入框的边界。库的描边画在边界内侧、填充不透明，调用处 modifier 里的库描边
 * 会被盖住，所以这里在内容之后自绘一圈；聚焦时本描边收至 0，让位给库自己的 2dp 主色描边。
 * 描边色取 outline 槽，库自带的组件不读这个槽；深浅两套的取值规则见 Color.kt。
 */
@Composable
fun CandyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    maskPassword: Boolean = false,
    maxLength: Int? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val state = rememberTextFieldState(initialText = value)
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val inputTransformation = remember(maxLength) {
        maxLength?.let { InputTransformation.maxLengthReject(it) }
    }
    LaunchedEffect(value) {
        if (state.text.toString() != value) {
            state.setTextAndPlaceCursorAtEnd(value)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { current ->
            if (current != latestValue) latestOnValueChange(current)
        }
    }
    val actionHandler = remember(keyboardActions) {
        val action = keyboardActions.onSearch
            ?: keyboardActions.onDone
            ?: keyboardActions.onGo
            ?: keyboardActions.onSend
            ?: keyboardActions.onNext
        action?.let { cb -> KeyboardActionHandler { cb.invoke(EmptyKeyboardActionScope) } }
    }
    val outputTransformation = remember(maskPassword) {
        if (maskPassword) {
            OutputTransformation {
                for (i in 0 until length) replace(i, i + 1, "\u2022")
            }
        } else null
    }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val borderColor = MiuixTheme.colorScheme.outline
    val motionEnabled = rememberMotionEnabled()
    val borderWidth by animateDpAsState(
        targetValue = if (focused) 0.dp else 1.dp,
        animationSpec = tween(if (motionEnabled) Motion.MICRO else 0),
        label = "fieldBorder",
    )
    TextField(
        state = state,
        modifier = modifier
            .fillMaxWidth()
            .fieldBorder(borderWidth, borderColor, TextFieldDefaults.CornerRadius, isSquircleEnabled()),
        label = label,
        inputTransformation = inputTransformation,
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = actionHandler,
        outputTransformation = outputTransformation,
        trailingIcon = trailingIcon,
        interactionSource = source,
    )
}

private object EmptyKeyboardActionScope : KeyboardActionScope {
    override fun defaultKeyboardAction(imeAction: ImeAction) = Unit
}

/**
 * 画在内容之后的内缩描边：几何与库的 `squircleBorder` 相同（描边落在边界内侧、
 * 宽度收至 0 时不画），区别只是叠在输入框的填充之上。
 */
private fun Modifier.fieldBorder(
    width: Dp,
    color: Color,
    cornerRadius: Dp,
    squircle: Boolean,
): Modifier = this.drawWithCache {
    val widthPx = width.toPx()
    val innerWidth = size.width - widthPx
    val innerHeight = size.height - widthPx
    val path = if (widthPx > 0f && innerWidth > 0f && innerHeight > 0f) {
        Path().apply {
            addSquircleRect(
                width = innerWidth,
                height = innerHeight,
                cornerRadius = (cornerRadius.toPx() - widthPx / 2f).coerceAtLeast(0f),
                squircleEnabled = squircle,
            )
        }
    } else {
        null
    }
    onDrawWithContent {
        drawContent()
        if (path != null) translate(widthPx / 2f, widthPx / 2f) { drawPath(path, color, style = Stroke(widthPx)) }
    }
}
