package io.candy.tick.util

import java.time.Duration
import java.time.Instant

/** 相对时间跨度（双语文案由 UI 层 strings.xml 承接） */
enum class RelUnit { MINUTE, HOUR, DAY, MONTH, YEAR }

data class RelSpan(val value: Long, val unit: RelUnit?) {
    val isNow: Boolean get() = unit == null
}

/** 自写双语相对时间（design.md：java.time 原生，不引库） */
object RelTime {

    fun spanOf(past: Instant, now: Instant = Instant.now()): RelSpan {
        val seconds = Duration.between(past, now).seconds
        return when {
            seconds < 60 -> RelSpan(0, null)
            seconds < 3600 -> RelSpan(seconds / 60, RelUnit.MINUTE)
            seconds < 86_400 -> RelSpan(seconds / 3600, RelUnit.HOUR)
            seconds < 30L * 86_400 -> RelSpan(seconds / 86_400, RelUnit.DAY)
            seconds < 365L * 86_400 -> RelSpan(seconds / (30L * 86_400), RelUnit.MONTH)
            else -> RelSpan(seconds / (365L * 86_400), RelUnit.YEAR)
        }
    }
}
