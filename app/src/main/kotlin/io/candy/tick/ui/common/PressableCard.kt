package io.candy.tick.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared surface and press indication for tappable repository and commit cards. */
@Composable
fun PressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    interactionModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val indication = remember(scheme.surfaceContainer, scheme.onSurfaceContainer) {
        CardPressIndication(scheme.surfaceContainer, scheme.onSurfaceContainer)
    }
    Card(
        modifier = modifier,
        cornerRadius = cornerRadius,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(
            color = scheme.surfaceContainer,
            contentColor = scheme.onSurfaceContainer,
        ),
    ) {
        Column(
            modifier = interactionModifier.fillMaxWidth().clickable(
                interactionSource = null,
                indication = indication,
                onClick = onClick,
            ),
            content = content,
        )
    }
}

private data class CardPressIndication(val surface: Color, val foreground: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        CardPressNode(interactionSource, surface, foreground)
}

private class CardPressNode(
    private val source: InteractionSource,
    private val surface: Color,
    private val foreground: Color,
) : Modifier.Node(), DrawModifierNode {
    private var amount = Animatable(0f)
    private var focused by mutableStateOf(false)

    override fun onAttach() {
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            collectPressTint(source, amount) { focused = it }
        }
    }

    override fun onDetach() {
        amount = Animatable(0f)
        focused = false
    }

    override fun ContentDrawScope.draw() {
        val strength = if (focused) 1f else amount.value
        if (strength > 0f) drawRect(lerp(surface, foreground, PRESS_TINT * strength))
        drawContent()
    }
}

/** A scroll container can emit Press and Release in the same frame. Keep a confirmed tap visible
 * for one rendered frame plus a short hold, without delaying click delivery or tinting a cancelled drag.
 */
@Composable
fun rememberRowPressTint(
    surface: Color = MiuixTheme.colorScheme.surfaceContainer,
): Pair<MutableInteractionSource, Color> {
    val source = remember { MutableInteractionSource() }
    val amount = remember { Animatable(0f) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        collectPressTint(source, amount) { focused = it }
    }
    val scheme = MiuixTheme.colorScheme
    val alpha = if (focused) PRESS_TINT else PRESS_TINT * amount.value
    return source to lerp(surface, scheme.onSurfaceContainer, alpha)
}

private const val PRESS_TINT = 0.16f

internal suspend fun collectPressTint(
    source: InteractionSource,
    amount: Animatable<Float, AnimationVector1D>,
    onFocusChange: (Boolean) -> Unit,
) {
    val activeFocus = mutableSetOf<FocusInteraction.Focus>()
    source.interactions.onEach {
        when (it) {
            is FocusInteraction.Focus -> if (activeFocus.add(it)) onFocusChange(true)
            is FocusInteraction.Unfocus -> if (activeFocus.remove(it.focus)) onFocusChange(activeFocus.isNotEmpty())
        }
    }.filterIsInstance<PressInteraction>().collectLatest { interaction ->
        when (interaction) {
            is PressInteraction.Press -> amount.snapTo(1f)
            is PressInteraction.Release -> {
                amount.snapTo(1f)
                withFrameNanos { }
                delay(Motion.PRESS.toLong())
                amount.animateTo(0f, tween(Motion.MICRO))
            }
            is PressInteraction.Cancel -> amount.snapTo(0f)
        }
    }
}

/** 卡内全宽分隔线，边距由相邻的信息区或操作区提供。 */
@Composable
fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}
