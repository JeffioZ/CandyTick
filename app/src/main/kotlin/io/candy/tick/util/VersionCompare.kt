package io.candy.tick.util

/**
 * 版本比较（真实世界格式优先，仍守「宁可不比，不要比错」底线）。
 *
 * 规则来源：
 * - SemVer 2.0.0（semver.org）：预发布 < 正式版；build 元数据（+…）不参与排序；
 *   数字标识符 < 字母标识符；预发布字段多者大（alpha < alpha.1）
 * - Debian policy（deb-version(7)）：`~` 排在一切之前（1.0~rc1 < 1.0~ < 1.0），
 *   F-Droid 系应用的 versionName 常见此写法
 * - Obtainium 实战教训（lib/utils/version_normalization.dart）：OS 报告的 versionName
 *   常带打包后缀（1.6.15-debug / 0.9.108 strip / 1.0.5-release+26090410），
 *   必须视为同一版本，否则永远误报「有更新」
 *
 * 支持格式：x.y.z / 26.09 / 26.05.01 / CalVer+hash（26.11.caccb78）/ 预发布
 * （2.2.2-rc0、1.0-beta.1、下划线 2.2.2_rc0）/ v/V 前缀 / +build / ~ 波浪号。
 * 段数不等：缺失段按 0 补（1.2 == 1.2.0；26.11 < 26.11.caccb78）。
 *
 * 两个已知的保守权衡（宁可不比，不要比错）：
 * - 数字开头的 dash 后缀（"1.2-4" 这类构建号，Obtainium #946 场景）按预发布参与排序，
 *   与干净版本 "1.2" 比较时判为更旧——方向安全（不会误报更新）
 * - 远端 tag 带打包词（"1.6.15-release"）不做剥离，同样判为更旧——宁可漏报不误报
 *
 * 不可比（返回 null）：主版本所有段都无数字（如 "latest" 这类纯命名 tag）。
 */
object VersionCompare {

    /**
     * 打包装饰词（Obtainium 实战清单）：OS 端 versionName 常见、但不参与排序的后缀。
     * 仅从本机侧剥离；远端 tag 带这些词仍按字母段参与比较（方向保守：不误报更新）。
     */
    private val PACKAGING_WORDS = setOf(
        "strip", "debug", "release", "stable", "final", "standard",
        "build", "signed", "unsigned", "universal", "nogms",
    )

    /** 仅当 v/V 后紧跟数字才剥（"v26.11" ✓ / "vbeta1" ✗） */
    private val LEADING_V = Regex("^[vV](?=\\d)")

    /** 排序前归一化：下划线 → 连字符；v/V 前缀；丢弃 +build（SemVer：build 不参与排序） */
    private fun normalize(version: String): String = version.trim()
        .replace('_', '-')
        .replaceFirst(LEADING_V, "")
        .substringBefore('+')

    /**
     * 比较 remote（release tag）与 local（OS 报告的 versionName）。
     * @return 正数 = remote 更新；0 = 一致；null = 无法比较
     */
    fun compare(remote: String, local: String): Int? {
        val r = parse(normalize(remote), dropPackaging = false) ?: return null
        val l = parse(normalize(local), dropPackaging = true) ?: return null
        val mainCmp = compareLists(r.first, l.first, padZero = true)
        if (mainCmp != 0) return mainCmp
        // 主版本相等才看预发布：正式版 > 预发布；两边都有则逐标识符比
        return when {
            r.second.isEmpty() && l.second.isEmpty() -> 0
            r.second.isEmpty() -> 1
            l.second.isEmpty() -> -1
            else -> compareLists(r.second, l.second, padZero = false)
        }
    }

    /**
     * 拆成（主段列表, 预发布标识符列表）；主段一个数字都没有 → null（纯命名式 tag 不可比）。
     * dropPackaging=true 时预发布里的打包装饰词（debug/final/…）剔除——它们不是版本语义。
     */
    private fun parse(version: String, dropPackaging: Boolean): Pair<List<String>, List<String>>? {
        val main = version.substringBefore('-').trim('.', ' ')
        if (main.isEmpty() || main.none { it.isDigit() }) return null
        val pre = version.substringAfter('-', "").trim('.', ' ')
        val mainParts = main.split('.').filter { it.isNotEmpty() }
        val preParts = if (pre.isEmpty()) emptyList() else pre.split('.')
            .filter { it.isNotEmpty() }
            .filter { !dropPackaging || it.lowercase() !in PACKAGING_WORDS }
        return mainParts to preParts
    }

    /**
     * 逐段比较两个标识符列表。
     * padZero=true（主版本）：缺失段按 "0" 补（1.2 == 1.2.0 < 1.2.c）；
     * padZero=false（预发布）：先耗尽者小（SemVer：alpha < alpha.1）。
     */
    private fun compareLists(a: List<String>, b: List<String>, padZero: Boolean): Int {
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrNull(i)
            val y = b.getOrNull(i)
            if (x == null && y == null) break
            if (x == null) return if (padZero) compareSegment("0", y!!) else -1
            if (y == null) return if (padZero) compareSegment(x, "0") else 1
            val cmp = compareSegment(x, y)
            if (cmp != 0) return cmp
        }
        return 0
    }

    /** 单段比较：token 化（数字串/字母串交替），数字 token 数值比、字母 token 字典比（忽略大小写） */
    private fun compareSegment(x: String, y: String): Int {
        if (x.equals(y, ignoreCase = true)) return 0
        val xt = tokenize(x)
        val yt = tokenize(y)
        val size = maxOf(xt.size, yt.size)
        for (i in 0 until size) {
            val a = xt.getOrNull(i)
            val b = yt.getOrNull(i)
            val aTilde = a is Alpha && a.value == "~"
            val bTilde = b is Alpha && b.value == "~"
            when {
                // Debian：~ 比一切小——包括对方已耗尽（1.0~ < 1.0）
                aTilde && bTilde -> Unit
                aTilde -> return -1
                bTilde -> return 1
                a == null -> return -1 // 先耗尽者小：rc < rc0
                b == null -> return 1
                else -> {
                    val cmp = when {
                        a is Num && b is Num -> a.value.compareTo(b.value)
                        a is Alpha && b is Alpha -> a.value.compareTo(b.value, ignoreCase = true)
                        a is Num && b is Alpha -> -1 // 数字标识符 < 字母标识符（SemVer；0 < hash 段）
                        else -> 1
                    }
                    if (cmp != 0) return cmp
                }
            }
        }
        return 0
    }

    /** "rc10" → [Alpha("rc"), Num(10)]；"caccb78" → [Alpha("caccb"), Num(78)] */
    private fun tokenize(segment: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < segment.length) {
            val digit = segment[i].isDigit()
            var j = i
            while (j < segment.length && segment[j].isDigit() == digit) j++
            // 超长数字串（外部 tag 不可信）降级为 Long.MAX_VALUE：不崩溃，方向保守
            tokens += if (digit) {
                Num(segment.substring(i, j).toLongOrNull() ?: Long.MAX_VALUE)
            } else {
                Alpha(segment.substring(i, j))
            }
            i = j
        }
        return tokens
    }

    private sealed interface Token
    private data class Num(val value: Long) : Token
    private data class Alpha(val value: String) : Token
}
