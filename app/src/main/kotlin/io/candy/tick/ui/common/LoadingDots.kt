package io.candy.tick.ui.common

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.SyncStatus
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.sin

/** 短抖动不点亮；与下拉刷新头的最短驻留共用同一档时长 */
private val BusyRevealDelayMillis = Motion.PROGRESS.toLong()

/** Pass a null description when adjacent text already describes the operation. */
@Composable
fun LoadingDots(
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.primaryVariant,
    dotSize: Dp = 4.dp, // 行内伴随 4dp；独立等待位点用 5dp（用户正盯着它等）
    contentDescription: String? = stringResource(R.string.loading),
) {
    val phase = if (rememberMotionEnabled()) {
        rememberInfiniteTransition(label = "loadingDots").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "wave",
        )
    } else null
    val gap = 3.dp
    Canvas(
        modifier = modifier
            .statusSemantics(contentDescription, busy = true)
            .size(width = dotSize * 3 + gap * 2, height = dotSize * 2.25f),
    ) {
        val fraction = phase?.value
        val diameter = dotSize.toPx()
        val step = diameter + gap.toPx()
        repeat(3) { index ->
            val wave = if (fraction == null) 0f else sin((fraction - index / 3f) * 2 * PI).toFloat()
            drawCircle(
                color = color,
                radius = diameter / 2,
                center = Offset(center.x + (index - 1) * step, center.y - diameter * 0.625f * wave),
                alpha = if (fraction == null) 0.8f else 0.725f + 0.275f * wave,
            )
        }
    }
}

private fun Modifier.statusSemantics(label: String?, busy: Boolean): Modifier =
    if (label == null) this else semantics {
        contentDescription = label
        if (busy) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }

/** Hide brief operations during navigation. Once visible, allow one readable beat. */
@Composable
internal fun <T : Any> rememberBusyValue(value: T?): T? {
    var shown by remember { mutableStateOf<T?>(null) }
    if (value != null || shown != null) {
        var shownAt by remember { mutableLongStateOf(0L) }
        LaunchedEffect(value) {
            if (value == null) {
                waitForMinimumDisplay(shownAt)
            } else if (shown == null) {
                delay(BusyRevealDelayMillis)
                shownAt = SystemClock.uptimeMillis()
            }
            shown = value
        }
    }
    return shown
}

private suspend fun waitForMinimumDisplay(shownAt: Long) {
    delay(Motion.PHASE - (SystemClock.uptimeMillis() - shownAt))
}

/**
 * 主动触发的加载/重试最短驻留兜底：点击瞬间即刻显露（0ms 延迟），若请求在极短时间内结束
 * （例如断网/限流 15ms 返回失败）则至少稳稳展示 [minDwellMs]，避免 loading 一闪而过像没点中。
 * 系统关闭动画时直接透传不额外停留。
 */
@Composable
internal fun rememberMinDwellBusy(busy: Boolean, minDwellMs: Long = 380L): Boolean {
    val motionEnabled = rememberMotionEnabled()
    var shown by remember { mutableStateOf(busy) }
    var shownAt by remember { mutableLongStateOf(if (busy) SystemClock.uptimeMillis() else 0L) }
    LaunchedEffect(busy, motionEnabled) {
        if (busy) {
            if (!shown) {
                shown = true
                shownAt = SystemClock.uptimeMillis()
            }
        } else if (shown) {
            if (motionEnabled && minDwellMs > 0L) {
                val remaining = minDwellMs - (SystemClock.uptimeMillis() - shownAt)
                if (remaining > 0L) delay(remaining)
            }
            shown = false
        }
    }
    return busy || shown
}

/**
 * 首页标题右侧的三点与完成／失败符。
 * 固定宽度占位槽（宽度由调用方的 modifier 给）——槽常在，标题再长 dots 也不会被挤出/挤进 actions 区；
 * 300ms 延迟防闪（短抖动不点亮），结束后停一拍再收。
 */
@Composable
fun SyncDots(
    running: SyncStatus.Running?,
    done: SyncStatus.Done?,
    modifier: Modifier = Modifier,
) {
    var phase by remember { mutableStateOf<SyncStatus>(SyncStatus.Idle) }
    var revealedRun by remember { mutableStateOf<SyncStatus.Running?>(null) }
    var shownAt by remember { mutableLongStateOf(0L) }
    val result = if (running == null) done else null
    LaunchedEffect(running, result) {
        if (running != null) {
            phase = SyncStatus.Idle
            revealedRun = null
            delay(BusyRevealDelayMillis)
            revealedRun = running
            shownAt = SystemClock.uptimeMillis()
            phase = running
        } else {
            if (phase is SyncStatus.Running) waitForMinimumDisplay(shownAt)
            // A quick sync can finish before its dots appear. Only conclude a wave the user saw.
            val completed = result?.takeIf { it.run === revealedRun }
            phase = completed ?: SyncStatus.Idle
            if (completed != null) {
                // Toast 已接棒播报更新/失败结果，标题图标统一停留 2s 即可利落退场
                delay(2_000L)
                phase = SyncStatus.Idle
            }
        }
    }

    val motionEnabled = rememberMotionEnabled()
    val label = when (val status = phase) {
        SyncStatus.Idle -> null
        is SyncStatus.Running -> stringResource(R.string.notif_syncing_repos)
        is SyncStatus.Done -> when {
            status.allFailed -> pluralStringResource(R.plurals.sync_result_all_failed, status.failed, status.failed)
            status.failed > 0 -> pluralStringResource(R.plurals.sync_result_partial_failed, status.failed, status.failed)
            else -> stringResource(R.string.refresh_done)
        }
    }
    val scheme = MiuixTheme.colorScheme
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        // One semantic node describes the target while both glyphs are drawn during the fade.
        Crossfade(
            targetState = phase,
            modifier = Modifier.size(18.dp).statusSemantics(label, busy = phase is SyncStatus.Running),
            animationSpec = tween(if (motionEnabled) Motion.MICRO else 0),
            label = "syncStatus",
        ) { status ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (status) {
                    SyncStatus.Idle -> Unit
                    is SyncStatus.Running -> LoadingDots(contentDescription = null)
                    is SyncStatus.Done -> {
                        val icon = when {
                            status.allFailed -> R.drawable.ic_close_circle
                            status.failed > 0 -> R.drawable.ic_info
                            else -> R.drawable.ic_check_circle
                        }
                        val tint = when {
                            status.allFailed -> scheme.onErrorContainer
                            status.failed > 0 -> scheme.onBackgroundVariant
                            else -> scheme.primaryVariant
                        }
                        Icon(
                            imageVector = ImageVector.vectorResource(icon),
                            contentDescription = null,
                            tint = tint,
                            // 14→16：带圈字形在 14dp 下内勾仅 ~4.7dp 不易辨；16 与 toast 状态符同口径
                            //（18dp 槽位留 1dp 呼吸边）
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
