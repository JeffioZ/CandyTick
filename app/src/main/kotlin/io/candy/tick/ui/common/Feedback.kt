package io.candy.tick.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全局统一瞬时反馈：顶部居中悬浮胶囊（✓/✕ + 文字 + 可选动作），顶栏下方。
 * 取代此前并存的三套通道（顶部结果胶囊 / Miuix Snackbar / 系统 Toast）——
 * 位置、配色、动效全应用一致；skill 准则：无动作 2.2s、有动作 4.5s 自动消失，统一动效 token。
 */
object Feedback {

    data class Message(
        val id: Long,
        val text: String,
        val isError: Boolean = false,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
    ) {
        val durationMs: Long get() = if (actionLabel != null) 4_500L else 2_200L
    }

    private var nextId = 0L
    private val _current = MutableStateFlow<Message?>(null)
    val current: StateFlow<Message?> = _current

    /** 展示一条反馈；同屏只保留最新（瞬时消息不排队） */
    fun show(
        text: String,
        isError: Boolean = false,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        _current.value = Message(nextId++, text, isError, actionLabel, onAction)
    }
}

/** 宿主：置于 Activity 内容最上层（导航之上），底部居中、键盘上方 */
@Composable
fun FeedbackHost() {
    val scheme = MiuixTheme.colorScheme
    val msg by Feedback.current.collectAsStateWithLifecycle()

    // 末值捕获：退出动画期间继续渲染（内容塌空 = 瞬间消失的教训）
    var shown by remember { mutableStateOf<Feedback.Message?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(msg) {
        val m = msg ?: return@LaunchedEffect
        shown = m
        visible = true
        delay(m.durationMs)
        visible = false
    }

    // 顶部居中、顶栏之下：状态栏 + 标准顶栏高（64dp）+ 间距，避开标题/动作区；
    // 不与拇指区/手势条/键盘争地盘。动效自上滑入、向上滑出（与原同步胶囊一致）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 76.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { -it },
            exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { -it },
        ) {
            shown?.let { m ->
                // 语义容器：成功=品牌薄荷（primaryContainer 系），错误=红容器（与错误横幅同语言）。
                // 整颗胶囊说结果的颜色，替代之前的灰底灰字（中性浮层在薄荷品牌里发闷无主）
                val container = if (m.isError) scheme.errorContainer else scheme.primaryContainer
                val onContainer = if (m.isError) scheme.onErrorContainer else scheme.onPrimaryContainer
                Row(
                    modifier = Modifier
                        .padding(top = 0.dp)
                        .shadow(4.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(container)
                        .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (m.isError) "✕" else "✓",
                        style = MiuixTheme.textStyles.footnote1,
                        color = onContainer,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = m.text,
                        style = MiuixTheme.textStyles.footnote1,
                        color = onContainer,
                    )
                    m.actionLabel?.let { label ->
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = label,
                            style = MiuixTheme.textStyles.footnote1,
                            color = onContainer,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    m.onAction?.invoke()
                                    visible = false
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
