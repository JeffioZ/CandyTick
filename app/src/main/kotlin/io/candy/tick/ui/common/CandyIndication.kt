package io.candy.tick.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.interfaces.HoldDownInteraction

/**
 * App 标准按压反馈的 indication 形态，挂在主题根部的 LocalIndication 上，供走默认
 * indication 的组件使用（miuix Button、BasicComponent 等）。与 [rememberRowPressTint]
 * 共用 [collectPressTint] 的时间线：按下即显、松手保持 80ms、150ms 淡出。
 *
 * 以 [color] 的半透明染色画在内容之下：表面是什么底色都等效于「向 [color] 染色」，
 * 文字与图标不被压暗。底色画在 clickable 之后的组件不会显示它；这类组件
 * （[PressIconButton] 等）都自绘染色。
 */
@Immutable
class CandyIndication(
    private val color: Color,
    private val maxAlpha: Float = PRESS_TINT_LIGHT,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, color, maxAlpha)

    override fun hashCode(): Int = 31 * color.hashCode() + maxAlpha.hashCode()

    override fun equals(other: Any?): Boolean =
        other is CandyIndication && other.color == color && other.maxAlpha == maxAlpha

    private class Node(
        private val source: InteractionSource,
        private val color: Color,
        private val maxAlpha: Float,
    ) : Modifier.Node(),
        DrawModifierNode {
        private var amount = Animatable(0f)
        private var focused by mutableStateOf(false)

        override fun onAttach() {
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                collectPressTint(HoldAsPress(source), amount) { focused = it }
            }
        }

        override fun onDetach() {
            amount = Animatable(0f)
            focused = false
        }

        override fun ContentDrawScope.draw() {
            // 键盘/方向键聚焦时常显：库组件自身没有焦点环，这是它们唯一的焦点指示
            val strength = if (focused) 1f else amount.value
            if (strength > 0f) drawRect(color, alpha = maxAlpha * strength)
            drawContent()
        }
    }
}

/**
 * miuix 的长按保持（HoldDown）折算成普通按压，走同一条反馈时间线；其余交互（含焦点）
 * 原样透传——[collectPressTint] 靠焦点交互决定键盘聚焦时是否常显。
 */
private class HoldAsPress(private val delegate: InteractionSource) : InteractionSource {
    override val interactions: Flow<Interaction> = delegate.interactions.map { interaction ->
        when (interaction) {
            is PressInteraction -> interaction
            is HoldDownInteraction.HoldDown -> PressInteraction.Press(Offset.Zero)
            is HoldDownInteraction.Release -> PressInteraction.Release(PressInteraction.Press(Offset.Zero))
            else -> interaction
        }
    }
}
