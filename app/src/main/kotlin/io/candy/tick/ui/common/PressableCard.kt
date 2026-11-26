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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import io.candy.tick.ui.theme.LocalDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared surface and press indication for tappable repository and commit cards. */
@Composable
fun PressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionModifier: Modifier = Modifier,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val maxAlpha = pressTint(LocalDarkTheme.current)
    val indication = remember(scheme.surfaceContainer, scheme.onSurfaceContainer, maxAlpha) {
        CardPressIndication(scheme.surfaceContainer, scheme.onSurfaceContainer, maxAlpha)
    }
    Card(
        modifier = modifier,
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
                // 整卡可点：补按钮语义，TalkBack 播报「点按两次即可…」而不是普通文本
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick,
            ),
            content = content,
        )
    }
}

private data class CardPressIndication(
    val surface: Color,
    val foreground: Color,
    val maxAlpha: Float,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        CardPressNode(interactionSource, surface, foreground, maxAlpha)
}

private class CardPressNode(
    private val source: InteractionSource,
    private val surface: Color,
    private val foreground: Color,
    private val maxAlpha: Float,
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
        if (strength > 0f) drawRect(lerp(surface, foreground, maxAlpha * strength))
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
    val maxAlpha = pressTint(LocalDarkTheme.current)
    val alpha = if (focused) maxAlpha else maxAlpha * amount.value
    return source to lerp(surface, scheme.onSurfaceContainer, alpha)
}

/** 按压染色强度（区分深浅极性）：浅色 16% 柔和深灰，深色 9% 避免暗底叠加浅白发亮突兀。 */
internal const val PRESS_TINT_LIGHT = 0.16f
internal const val PRESS_TINT_DARK = 0.09f

internal fun pressTint(dark: Boolean): Float = if (dark) PRESS_TINT_DARK else PRESS_TINT_LIGHT

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
