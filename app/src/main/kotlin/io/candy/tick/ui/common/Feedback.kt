package io.candy.tick.ui.common

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
        val durationMs: Long,
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
        errorCode: String? = null,
    ) {
        val displayText = withErrorCode(text, errorCode)
        // 被逐出的可操作消息（目前仅滑删撤销）若仍在有效期内，暂存待槽空出后重新上屏：
        // 单槽只显示最新，但「可撤销」承诺不因后续消息静默作废（自然到期才作废）
        val evicted = _current.value
        if (evicted?.onAction != null &&
            SystemClock.elapsedRealtime() - evicted.createdAt < evicted.durationMs
        ) {
            held = evicted
        }
        _current.value = Message(
            text = displayText,
            isError = isError,
            actionLabel = actionLabel,
            onAction = onAction,
            durationMs = durationMs ?: if (actionLabel != null) 4_500L else 2_200L,
        )
    }

    /** 统一在失败文案末尾拼短错误码（已含同码时不重复追加） */
    fun withErrorCode(text: String, errorCode: String?): String {
        val code = errorCode?.trim()?.takeIf { it.isNotEmpty() } ?: return text
        return if (text.contains("($code)")) text else "$text ($code)"
    }

    internal fun dismiss(message: Message): Boolean = _current.compareAndSet(message, null)

    /** 宿主销毁（Activity 重建）即清空：current/held 里的 onAction 持有旧页闭包，
     *  换代后重放等于在死上下文里执行（此前跨代残留，撤销动作打到已销毁的页面状态上）。
     *  代价：旋转等重建瞬间正在展示的可撤销消息会一并丢弃（toast 生命周期本就秒级，接受） */
    internal fun clear() {
        held = null
        _current.value = null
    }

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
    DisposableEffect(Unit) {
        onDispose { Feedback.clear() }
    }
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
        // 展示窗口自上屏起一次性计时（不随后台/遮挡暂停）；可撤销性另由 Message.createdAt 把关
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

    val motionEnabled = rememberMotionEnabled()

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
            enter = if (motionEnabled) fadeIn(tween(Motion.PANEL)) else EnterTransition.None,
            exit = if (motionEnabled) fadeOut(tween(Motion.PANEL)) else ExitTransition.None,
        ) {
            shown?.let { m ->
                val accentTint = if (m.isError) scheme.onErrorContainer else scheme.primaryVariant
                val textTint = scheme.onSurfaceContainer
                val toastBg = if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainer
                val borderColor = if (m.isError) {
                    scheme.onErrorContainer.copy(alpha = if (dark) 0.55f else 0.45f)
                } else if (dark) {
                    DarkFeedbackOutline
                } else {
                    LightFeedbackOutline
                }
                val shadowTint = Color.Black.copy(alpha = if (dark) 0.4f else 0.24f)
                Row(
                    modifier = Modifier
                        // 连续触发新 Toast 时宽度平滑过渡；置于 shadow 前确保阴影与容器同步缩放
                        .then(
                            if (motionEnabled) {
                                Modifier.animateContentSize(tween(Motion.MICRO, easing = Motion.Decelerate))
                            } else {
                                Modifier
                            },
                        )
                        .shadow(6.dp, feedbackShape, ambientColor = shadowTint, spotColor = shadowTint)
                        .clip(feedbackShape)
                        .background(toastBg)
                        .border(0.75.dp, borderColor, feedbackShape)
                        // 读屏直播（Polite）：同步结果/安装结果此前 TalkBack 完全无感知
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(start = 14.dp, end = if (m.actionLabel == null) 14.dp else feedbackActionInset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 状态符 Lucide 带圈版（circle-check/circle-x，不套圆底徽章防圈套圈）
                    top.yukonga.miuix.kmp.basic.Icon(
                        imageVector = ImageVector.vectorResource(
                            if (m.isError) io.candy.tick.R.drawable.ic_close_circle else io.candy.tick.R.drawable.ic_check_circle,
                        ),
                        contentDescription = null,
                        tint = accentTint,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = m.text,
                        modifier = Modifier.weight(1f, fill = false).padding(vertical = 10.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = textTint,
                    )
                    m.actionLabel?.let { label ->
                        Spacer(Modifier.width(4.dp))
                        // 取消/操作钮带状态色 12% 实体叠底（普通薄荷绿 / 错误柔和红），按下时向深色插值
                        val chipColor = accentTint.copy(alpha = 0.12f).compositeOver(toastBg)
                        val (press, pressColor) = rememberRowPressTint(surface = chipColor)
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
                                color = accentTint,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}
