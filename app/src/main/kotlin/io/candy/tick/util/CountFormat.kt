package io.candy.tick.util

/**
 * GitHub 风格数字缩写（与 github.com 搜索结果/仓库页一致）：
 * 999 → "999" · 1234 → "1.2k" · 12345 → "12.3k" · 123456 → "123k" · 1234567 → "1.2M"
 */
object CountFormat {

    fun format(count: Int): String = when {
        count >= 1_000_000 -> trim(count / 1_000_000.0) + "M"
        count >= 1_000 -> trim(count / 1_000.0) + "k"
        else -> count.toString()
    }

    /** ≥100 或整数值去小数位，否则保留一位（Locale.US：小数点恒为「.」，GitHub 风格） */
    private fun trim(v: Double): String = when {
        v >= 100 -> v.toLong().toString()
        v == v.toLong().toDouble() -> v.toLong().toString()
        else -> String.format(java.util.Locale.US, "%.1f", v)
    }
}
