package io.candy.tick.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.candy.tick.R
import io.candy.tick.util.RelTime
import io.candy.tick.util.RelUnit
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import top.yukonga.miuix.kmp.basic.Text

/**
 * 全局分钟节拍：所有相对时间的「现在」每分钟重算一次——此前 now 冻结在组合时刻，
 * 卡片不离开屏幕「刚刚」就一直挂着（首页「刚刚」与详情「x 分钟前」对不上的显示层
 * 根因）。单协程 1/min，Lazily 首次订阅启动，常驻开销可忽略。
 */
private val relTimeTick: StateFlow<Int> = flow {
    var n = 0
    while (true) {
        emit(n)
        delay(60_000)
        n++
    }
}.stateIn(
    CoroutineScope(SupervisorJob() + Dispatchers.Default),
    SharingStarted.Lazily,
    0,
)

/** 「3 天前」双语相对时间文本 */
@Composable
fun RelTimeText(
    date: Instant?,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle? = null,
) {
    if (date == null) return
    val text = relTimeString(date)
    if (style != null) {
        Text(text = text, modifier = modifier, color = color, style = style)
    } else {
        Text(text = text, modifier = modifier, color = color)
    }
}

/** 过去时间 → 「3 天前 / 3 days ago」（分钟节拍自动重算，不冻结） */
@Composable
fun relTimeString(date: Instant): String {
    val tick by relTimeTick.collectAsStateWithLifecycle()
    val now = remember(date, tick) { Instant.now() }
    val span = remember(date, now) { RelTime.spanOf(date, now) }
    return when {
        span.isNow -> stringResource(R.string.rel_now)
        span.unit == RelUnit.MINUTE -> pluralStringResource(R.plurals.rel_minutes, span.value.toInt(), span.value)
        span.unit == RelUnit.HOUR -> pluralStringResource(R.plurals.rel_hours, span.value.toInt(), span.value)
        span.unit == RelUnit.DAY -> pluralStringResource(R.plurals.rel_days, span.value.toInt(), span.value)
        span.unit == RelUnit.MONTH -> pluralStringResource(R.plurals.rel_months, span.value.toInt(), span.value)
        else -> pluralStringResource(R.plurals.rel_years, span.value.toInt(), span.value)
    }
}

/** 未来时间 → 「5 分钟后 / in 5 mins」（限流恢复时间等；分钟节拍自动倒数） */
@Composable
fun relTimeUntilString(target: Instant): String {
    val tick by relTimeTick.collectAsStateWithLifecycle()
    val now = remember(target, tick) { Instant.now() }
    val span = remember(target, now) { RelTime.spanOf(now, target) }
    return when {
        span.isNow -> stringResource(R.string.rel_until_soon)
        span.unit == RelUnit.MINUTE -> pluralStringResource(R.plurals.rel_minutes_until, span.value.toInt(), span.value)
        span.unit == RelUnit.HOUR -> pluralStringResource(R.plurals.rel_hours_until, span.value.toInt(), span.value)
        span.unit == RelUnit.DAY -> pluralStringResource(R.plurals.rel_days_until, span.value.toInt(), span.value)
        span.unit == RelUnit.MONTH -> pluralStringResource(R.plurals.rel_months_until, span.value.toInt(), span.value)
        else -> pluralStringResource(R.plurals.rel_years_until, span.value.toInt(), span.value)
    }
}
