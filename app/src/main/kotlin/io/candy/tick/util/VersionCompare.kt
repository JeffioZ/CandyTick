package io.candy.tick.util

/**
 * 版本比较（Obtainium 保守哲学：宁可不比，不要比错）。
 * 仅支持三种标准格式：x.y.z / x.y / 纯数字；其余一律判不可比。
 */
object VersionCompare {

    private val STRICT = Regex("""^\d+(\.\d+){0,2}$""")

    /** 源端剥装饰：v/V 前缀、-prerelease / +build 后缀（OS 端报告的版本绝不修改） */
    fun stripRemoteDecoration(tag: String): String = tag.trim()
        .removePrefix("v").removePrefix("V")
        .substringBefore('-').substringBefore('+')

    private fun parseStrict(version: String): IntArray? {
        if (!STRICT.matches(version)) return null
        return version.split('.').map { it.toInt() }.toIntArray()
    }

    /**
     * 比较 remote（release tag，允许装饰）与 local（OS 报告，原样）。
     * @return 正数 = remote 更新；0 = 一致；null = 无法比较
     */
    fun compare(remote: String, local: String): Int? {
        val r = parseStrict(stripRemoteDecoration(remote)) ?: return null
        val l = parseStrict(local.trim()) ?: return null
        val size = maxOf(r.size, l.size)
        for (i in 0 until size) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a - b
        }
        return 0
    }
}
