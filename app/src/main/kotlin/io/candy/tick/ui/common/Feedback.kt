package io.candy.tick.ui.common

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.candy.tick.ui.theme.DarkFeedbackOutline
import io.candy.tick.ui.theme.LightFeedbackOutline
import io.candy.tick.ui.theme.LocalDarkTheme

/**
 * 顶栏下方的瞬时反馈。明暗共用圆角、描边、阴影和动效，主题色用于状态图标和操作文字。
 */
object Feedback {

    class Message(
        val text: String,
        val isError: Boolean = false,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
        val durationMs: Long = if (actionLabel != null) 4_500L else 2_200L,
    ) {
        val createdAt = SystemClock.elapsedRealtime()
    }

    private val _current = MutableStateFlow<Message?>(null)
    val current: StateFlow<Message?> = _current

    /** 展示一条反馈；同屏只保留最新（瞬时消息不排队）。破坏性动作的撤销窗口可放长 */
    fun show(
        text: String,
        isError: Boolean = false,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        durationMs: Long? = null,
    ) {
        _current.value = Message(
            text = text,
            isError = isError,
            actionLabel = actionLabel,
            onAction = onAction,
            durationMs = durationMs ?: if (actionLabel != null) 4_500L else 2_200L,
        )
    }

    internal fun dismiss(message: Message): Boolean = _current.compareAndSet(message, null)
}

/** 置于导航上方，顶栏下方居中。 */
@Composable
fun FeedbackHost() {
    val scheme = MiuixTheme.colorScheme
    val msg by Feedback.current.collectAsStateWithLifecycle()
    val accessibility = LocalAccessibilityManager.current
    val dark = LocalDarkTheme.current
    val shape = RoundedCornerShape(16.dp)

    // 末值捕获：退出动画期间继续渲染（内容塌空 = 瞬间消失的教训）
    var shown by remember { mutableStateOf<Feedback.Message?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(msg, accessibility) {
        val m = msg
        if (m == null) {
            visible = false
            return@LaunchedEffect
        }
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = m.durationMs,
            containsIcons = true,
            containsText = true,
            containsControls = m.onAction != null,
        ) ?: m.durationMs
        val remaining = timeout - (SystemClock.elapsedRealtime() - m.createdAt)
        if (remaining <= 0) {
            Feedback.dismiss(m)
            return@LaunchedEffect
        }
        shown = m
        visible = true
        delay(remaining)
        Feedback.dismiss(m)
    }

    // 顶部居中、顶栏之下：状态栏 + 标准顶栏高（64dp）+ 间距，避开标题/动作区；
    // 不与拇指区/手势条/键盘争地盘。动效自上滑入、向上滑出（与原同步胶囊一致）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 76.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(Motion.PANEL)) + slideInVertically(tween(Motion.PANEL)) { -it },
            exit = fadeOut(tween(Motion.PANEL)) + slideOutVertically(tween(Motion.PANEL)) { -it },
        ) {
            shown?.let { m ->
                val iconTint = if (m.isError) scheme.onErrorContainer else scheme.primaryVariant
                val textTint = scheme.onSurfaceContainer
                Row(
                    modifier = Modifier
                        .shadow(2.dp, shape)
                        .clip(shape)
                        .background(if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainer)
                        .border(1.dp, if (dark) DarkFeedbackOutline else LightFeedbackOutline, shape)
                        // 读屏直播（Polite）：同步结果/安装结果此前 TalkBack 完全无感知
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(start = 14.dp, end = if (m.actionLabel == null) 14.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 状态符 Lucide 单源（与 SyncDots 结果符同库同规格 14dp）：语义色
                    top.yukonga.miuix.kmp.basic.Icon(
                        imageVector = ImageVector.vectorResource(
                            if (m.isError) io.candy.tick.R.drawable.ic_close else io.candy.tick.R.drawable.ic_check,
                        ),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = m.text,
                        modifier = Modifier.weight(1f, fill = false).padding(vertical = 10.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = textTint,
                    )
                    m.actionLabel?.let { label ->
                        Spacer(Modifier.width(4.dp))
                        val press = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .clickable(interactionSource = press, indication = null, role = Role.Button) {
                                    if (Feedback.dismiss(m)) m.onAction?.invoke()
                                }
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .indication(press, LocalIndication.current)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = MiuixTheme.textStyles.footnote1,
                                color = scheme.primaryVariant,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}
