package io.candy.tick.ui.common

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

    /** 被逐出的可操作消息（单条暂存；连撤多条时只保最新一条被逐出的） */
    private var held: Message? = null

    /** 展示一条反馈；同屏只保留最新（瞬时消息不排队）。破坏性动作的撤销窗口可放长 */
    fun show(
        text: String,
        isError: Boolean = false,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        durationMs: Long? = null,
    ) {
        // 被逐出的可操作消息（目前仅滑删撤销）若仍在有效期内，暂存待槽空出后重新上屏：
        // 单槽只显示最新，但「可撤销」承诺不因后续消息静默作废（自然到期才作废）
        val evicted = _current.value
        if (evicted?.onAction != null &&
            SystemClock.elapsedRealtime() - evicted.createdAt < evicted.durationMs
        ) {
            held = evicted
        }
        _current.value = Message(
            text = text,
            isError = isError,
            actionLabel = actionLabel,
            onAction = onAction,
            durationMs = durationMs ?: if (actionLabel != null) 4_500L else 2_200L,
        )
    }

    internal fun dismiss(message: Message): Boolean = _current.compareAndSet(message, null)

    /** 槽空出后取回仍在窗口内的暂存消息（过期即作废，返回 null） */
    internal fun retakeHeld(): Message? {
        val m = held ?: return null
        held = null
        return m.takeIf { SystemClock.elapsedRealtime() - it.createdAt < it.durationMs }
    }

    internal fun resurface(message: Message) {
        _current.value = message
    }
}

private val feedbackRadius = 16.dp
private val feedbackActionInset = 4.dp
private val feedbackShape = RoundedCornerShape(feedbackRadius)
private val feedbackActionShape = RoundedCornerShape(feedbackRadius - feedbackActionInset)

/** 置于导航上方，顶栏下方居中。 */
@Composable
fun FeedbackHost() {
    val scheme = MiuixTheme.colorScheme
    val msg by Feedback.current.collectAsStateWithLifecycle()
    val accessibility = LocalAccessibilityManager.current
    val dark = LocalDarkTheme.current

    // 末值捕获：退出动画期间继续渲染（内容塌空 = 瞬间消失的教训）
    var shown by remember { mutableStateOf<Feedback.Message?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(msg, accessibility) {
        val m = msg
        if (m == null) {
            visible = false
            // 退出动画结束后释放引用：onAction 闭包持有 VM/快照，不能留到进程结束
            delay(Motion.PANEL.toLong() + 100)
            if (Feedback.current.value == null) {
                shown = null
                // 被逐出的撤销类消息：槽已空且仍在窗口内，重新上屏（显示窗口重算，可撤销性以 createdAt 为准）
                Feedback.retakeHeld()?.let { Feedback.resurface(it) }
            }
            return@LaunchedEffect
        }
        // 窗口从首次上屏起算：后台/被遮挡期间不计时（此前在后台就把窗口耗尽，表现为从未显示就消失）
        val firstShownAt = SystemClock.elapsedRealtime()
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = m.durationMs,
            containsIcons = true,
            containsText = true,
            containsControls = m.onAction != null,
        ) ?: m.durationMs
        shown = m
        visible = true
        delay(timeout)
        Feedback.dismiss(m)
    }

    // 顶部居中、顶栏之下：状态栏 + 标准顶栏高（64dp）+ 间距，避开标题/动作区；
    // 不与拇指区/手势条/键盘争地盘。动效为原位淡入淡出——可交互目标不移动（见 AnimatedVisibility 注释）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 76.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            // 原位淡入淡出（不再自上滑入）：胶囊出现在最终位置——可交互目标不移动，
            // 快速反应点撤销不会点在「落点前」；关闭系统动画的用户直接显隐
            enter = if (rememberMotionEnabled()) fadeIn(tween(Motion.PANEL)) else EnterTransition.None,
            exit = if (rememberMotionEnabled()) fadeOut(tween(Motion.PANEL)) else ExitTransition.None,
        ) {
            shown?.let { m ->
                val iconTint = if (m.isError) scheme.onErrorContainer else scheme.primaryVariant
                val textTint = scheme.onSurfaceContainer
                Row(
                    modifier = Modifier
                        .shadow(2.dp, feedbackShape)
                        .clip(feedbackShape)
                        .background(if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainer)
                        .border(1.dp, if (dark) DarkFeedbackOutline else LightFeedbackOutline, feedbackShape)
                        // 读屏直播（Polite）：同步结果/安装结果此前 TalkBack 完全无感知
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(start = 14.dp, end = if (m.actionLabel == null) 14.dp else feedbackActionInset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 状态符 Lucide 带圈版（circle-check/circle-x，与 SyncDots 结果符同库同口径）：
                    // 语义色；16dp——圈占视觉重量，比裸符的 14dp 大一档保证内部字形可辨
                    top.yukonga.miuix.kmp.basic.Icon(
                        imageVector = ImageVector.vectorResource(
                            if (m.isError) io.candy.tick.R.drawable.ic_close_circle else io.candy.tick.R.drawable.ic_check_circle,
                        ),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(16.dp),
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
                        // 按压反馈升到 App 标准（rememberRowPressTint：快按即显 + 80ms 保持 + 16%），
                        // 与图标钮/卡片同手感——此前 MiuixIndication 库默认（~10%、渐变）快按几乎不可见
                        val pillBackground = if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainer
                        val (press, pressColor) = rememberRowPressTint(surface = pillBackground)
                        Box(
                            modifier = Modifier
                                .clickable(interactionSource = press, indication = null, role = Role.Button) {
                                    if (Feedback.dismiss(m)) m.onAction?.invoke()
                                }
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .padding(vertical = feedbackActionInset)
                                .clip(feedbackActionShape)
                                .background(pressColor, feedbackActionShape)
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
