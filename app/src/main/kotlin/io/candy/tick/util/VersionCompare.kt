package io.candy.tick.util

import io.candy.tick.data.model.TrackedRepo

/**
 * 版本比较（真实世界格式优先，仍守「宁可不比，不要比错」底线）。
 *
 * 规则来源：
 * - SemVer 2.0.0（semver.org）：预发布 < 正式版；build 元数据（+…）不参与排序；
 *   数字标识符 < 字母标识符；预发布字段多者大（alpha < alpha.1）
 * - Debian policy（deb-version(7)）：`~` 排在一切之前（1.0~ < 1.0~rc1 < 1.0），
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
 *   与干净版本 "1.2" 比较时判为更旧。
 * - 同一数字基线下的不同 Git 哈希、同一主版本的未知渠道只可辨认是否相同。
 *
 * 不可比（返回 null）：主版本没有可辨认的数字开头，或同一基线带有不明渠道标识。
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

    private val PRERELEASE_STAGE = Regex("(?:alpha|beta|rc|pre|preview|dev|nightly|snapshot)\\d*", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")
    private val COMMIT_HASH = Regex("[0-9a-fA-F]{7,64}")

    /** 版本摘要只去掉数字前的一个 v/V，保留渠道和构建号。 */
    fun displayVersion(raw: String): String {
        val name = raw.trim()
        return if (name.length >= 2 && (name[0] == 'v' || name[0] == 'V') && name[1] in '0'..'9') {
            name.substring(1)
        } else name
    }

    /** 有些 APK 把产品名也写入 versionName。只移除完整匹配的应用名或仓库名。 */
    fun installedName(raw: String, repo: TrackedRepo): String {
        val name = raw.trim()
        val space = name.indexOfLast { it.isWhitespace() }
        if (space < 0) return name
        val prefix = name.substring(0, space).trimEnd()
        if (!prefix.equals(repo.repo, ignoreCase = true) &&
            !(repo.autoNote && prefix.equals(repo.noteName, ignoreCase = true))
        ) return name
        val version = name.substring(space + 1)
        return if (displayVersion(version).firstOrNull()?.isDigit() == true) version else name
    }

    /** 版本历史标注具体的安装版本，须保留构建和渠道标识。 */
    fun matchesTag(remote: String, local: String): Boolean {
        val tag = displayVersion(remote)
        return tag.isNotEmpty() && tag == displayVersion(local)
    }

    /** 排序前归一化：下划线 → 连字符；v/V 前缀；丢弃 +build（SemVer：build 不参与排序） */
    private fun normalize(version: String): String = displayVersion(version)
        .replace('_', '-')
        .substringBefore('+')

    /**
     * 比较 remote（release tag）与 local（OS 报告的 versionName）。
     * @return 正数 = remote 更新；0 = 一致；null = 无法比较
     */
    fun compare(remote: String, local: String): Int? {
        if (matchesTag(remote, local)) return 0
        val normalizedRemote = normalize(remote)
        val normalizedLocal = normalize(local)
        val r = parse(normalizedRemote, dropPackaging = false) ?: return null
        val l = parse(normalizedLocal, dropPackaging = true) ?: return null
        if (normalizedRemote == normalizedLocal) return 0
        if (differentCommitHashes(r.first, l.first)) return null
        val mainCmp = compareLists(r.first, l.first, padZero = true)
        if (mainCmp != 0) return mainCmp
        // A vendor suffix may identify another build or channel rather than an earlier release.
        if ((r.second.isOpaqueChannel() || l.second.isOpaqueChannel()) && r.second != l.second) return null
        // 主版本相等才看预发布：正式版 > 预发布；两边都有则逐标识符比
        return when {
            r.second.isEmpty() && l.second.isEmpty() -> 0
            r.second.isEmpty() -> 1
            l.second.isEmpty() -> -1
            else -> compareLists(r.second, l.second, padZero = false)
        }
    }

    /**
     * 数字基线后的 Git 哈希（如 26.11.caccb78）能辨认构建，无法排序。纯数字尾段仍按版本号处理。
     * 两侧都剔掉哈希段且剩余基线相等才判不可比（26.11.caccb78 vs 26.11.def5678）；
     * 仅一侧带哈希维持原比较（1.2.3.4f2a1b9 vs 1.2.3 仍判有更新，不吞提醒）。
     * 基线段数不等（26.11.0.caccb78 vs 26.11.caccb78）走补零比较得 0——同基线不误报更新。
     */
    private fun differentCommitHashes(remote: List<String>, local: List<String>): Boolean {
        val r = remote.dropTrailingHashes()
        val l = local.dropTrailingHashes()
        if (r == remote || l == local || r.isEmpty() || l.isEmpty() || r.size != l.size) return false
        return r.indices.all { compareSegment(r[it], l[it]) == 0 }
    }

    /** 尾部连续的哈希段（7-64 位十六进制且含字母；纯数字仍是版本号，如 26090410 构建日期）。 */
    private fun List<String>.dropTrailingHashes(): List<String> =
        dropLastWhile { COMMIT_HASH.matches(it) && !it.all(Char::isDigit) }

    /**
     * 拆成主版本和预发布标识，只比较以数字开头的版本。
     * dropPackaging=true 时预发布里的打包装饰词（debug/final/…）剔除——它们不是版本语义。
     */
    private fun parse(version: String, dropPackaging: Boolean): Pair<List<String>, List<String>>? {
        val parts = if (dropPackaging && version.any { it.isWhitespace() }) version.split(WHITESPACE) else emptyList()
        val token = if (parts.size > 1 && parts.drop(1).all { it.lowercase() in PACKAGING_WORDS }) parts.first() else version
        val main = token.substringBefore('-').trim('.', ' ')
        if (main.firstOrNull()?.isDigit() != true || main.any { it.isWhitespace() }) return null
        val pre = token.substringAfter('-', "").trim('.', ' ')
        val mainParts = main.split('.').filter { it.isNotEmpty() }
        val preParts = if (pre.isEmpty()) emptyList() else pre.split('.')
            .filter { it.isNotEmpty() }
            .filter { !dropPackaging || it.lowercase() !in PACKAGING_WORDS }
        return mainParts to preParts
    }

    private fun List<String>.isOpaqueChannel(): Boolean {
        val first = firstOrNull() ?: return false
        return !first.all { it.isDigit() } && !PRERELEASE_STAGE.matches(first)
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
            if (!padZero && (x == null || y == null)) return a.size.compareTo(b.size)
            val cmp = compareSegment(x ?: "0", y ?: "0")
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
                        a is Num && b is Num -> {
                            val length = a.value.length.compareTo(b.value.length)
                            if (length == 0) a.value.compareTo(b.value) else length
                        }
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
            var j = i + 1
            // Debian 的 ~ 独立排序，即便它紧邻 rc 等字母。
            while (segment[i] != '~' && j < segment.length &&
                segment[j] != '~' && segment[j].isDigit() == digit) j++
            tokens += if (digit) {
                Num(segment.substring(i, j).trimStart('0'))
            } else {
                Alpha(segment.substring(i, j))
            }
            i = j
        }
        return tokens
    }

    private sealed interface Token
    private data class Num(val value: String) : Token
    private data class Alpha(val value: String) : Token
}
