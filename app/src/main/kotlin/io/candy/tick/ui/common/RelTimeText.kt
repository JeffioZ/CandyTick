package io.candy.tick.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import io.candy.tick.R
import io.candy.tick.util.RelTime
import io.candy.tick.util.RelUnit
import java.time.Instant
import top.yukonga.miuix.kmp.basic.Text

/** 「3 天前」双语相对时间文本 */
@Composable
fun RelTimeText(
    date: Instant?,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle? = null,
) {
    val text = relTimeString(date ?: return)
    if (style != null) {
        Text(text = text, modifier = modifier, color = color, style = style)
    } else {
        Text(text = text, modifier = modifier, color = color)
    }
}

@Composable
fun relTimeString(date: Instant): String {
    val now = remember(date) { Instant.now() }
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
