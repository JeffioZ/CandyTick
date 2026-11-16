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

    /** 供通知等非 Compose 场景使用的近似文案（跟随系统语言粗分中英） */
    fun format(past: Instant, now: Instant, zh: Boolean): String {
        val s = spanOf(past, now)
        if (s.isNow) return if (zh) "刚刚" else "just now"
        return when (s.unit) {
            RelUnit.MINUTE -> if (zh) "${s.value} 分钟前" else "${s.value} min ago"
            RelUnit.HOUR -> if (zh) "${s.value} 小时前" else "${s.value} h ago"
            RelUnit.DAY -> if (zh) "${s.value} 天前" else "${s.value} d ago"
            RelUnit.MONTH -> if (zh) "${s.value} 个月前" else "${s.value} mo ago"
            RelUnit.YEAR -> if (zh) "${s.value} 年前" else "${s.value} y ago"
            null -> ""
        }
    }
}
