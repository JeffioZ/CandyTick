package io.candy.tick.util

/**
 * Release body（Markdown 原文）的展示层轻量清理：
 * 剥标题井号/加粗/列表符/HTML 注释，压空行，GitHub emoji shortcode 转 Unicode；
 * Markdown 链接标注为自定义 span（tag="URL"），由调用方用 ClickableText 命中开浏览器——
 * 与 GitHub 网页「链接文字可点」交互一致。自更新弹窗与版本历史共用。
 */
object ReleaseNotes {

    /** GitHub emoji shortcode → Unicode（release notes 常见集，未命中的保留原样） */
    private val emoji = mapOf(
        "rocket" to "🚀", "bug" to "🐛", "lady_beetle" to "🐞",
        "sparkles" to "✨", "fire" to "🔥", "wrench" to "🔧",
        "zap" to "⚡", "lock" to "🔒", "unlock" to "🔓",
        "seedling" to "🌱", "memo" to "📝", "book" to "📖",
        "books" to "📚", "hammer" to "🔨", "art" to "🎨",
        "globe" to "🌐", "tada" to "🎉", "party" to "🎉",
        "eyes" to "👀", "bulb" to "💡", "package" to "📦",
        "gear" to "⚙️", "check" to "✅", "white_check_mark" to "✅",
        "x" to "❌", "warning" to "⚠️", "arrow_up" to "⬆️",
        "arrow_down" to "⬇️", "chart_with_upwards_trend" to "📈",
        "chart_with_downwards_trend" to "📉", "recycle" to "♻️",
        "wastebasket" to "🗑️", "star" to "⭐", "star2" to "🌟",
        "thumbsup" to "👍", "thumbsdown" to "👎", "heart" to "❤️",
        "iphone" to "📱", "android" to "🤖", "apple" to "🍎",
        "computer" to "💻", "mag" to "🔍", "hourglass" to "⏳",
        "clock" to "🕐", "construction" to "🚧", "shrug" to "🤷",
        "question" to "❓", "exclamation" to "❗", "sun" to "☀️",
        "moon" to "🌙", "cloud" to "☁️", "snowflake" to "❄️",
    )

    // markdown 链接 [文本](url) 或裸 URL——alternation 顺序匹配天然无重叠
    private val anyLink = Regex("\\[([^]]+)]\\((https?://[^)\\s]+)\\)|(?<![\\w)\\]])https?://[\\w./#?&=%+-]+")
    private val mdOnly = Regex("\\[([^]]+)]\\((https?://[^)\\s]+)\\)")

    private fun String.preClean(): String = this
        .let { txt -> emoji.entries.fold(txt) { acc, (k, v) -> acc.replace(":$k:", v) } }
        .replace(Regex("<!--[\\s\\S]*?-->"), "") // HTML 注释（release 模板常见残留）
        .replace(Regex("#{1,6} "), "")
        .replace("**", "")
        .replace(Regex("^[-*] ", RegexOption.MULTILINE), "· ")
        .replace("`", "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** 纯文本版（自更新弹窗等无链接场景）：链接剥成「文本 (URL)」 */
    fun plain(md: String): String = md.preClean().replace(mdOnly, "$1 ($2)")

    /**
     * 链接标注版（更新日志展开区）：[文本](url) 显示文本、薄荷色、
     * 自定义 span（tag=URL, value=url），调用方用 ClickableText 命中后开浏览器。
     * 不用 LinkAnnotation（新 API 在 Miuix Text 包装下实测闪退，经典 span 路径最稳）。
     */
    fun annotated(
        md: String,
        linkColor: androidx.compose.ui.graphics.Color,
    ): androidx.compose.ui.text.AnnotatedString {
        val builder = androidx.compose.ui.text.AnnotatedString.Builder()
        var cursor = 0
        val cleaned = md.preClean()
        for (m in anyLink.findAll(cleaned)) {
            val display: String
            val url: String
            if (m.groupValues[1].isNotEmpty()) {
                // markdown 命中：显示文本部分
                display = m.groupValues[1]
                url = m.groupValues[2]
            } else {
                // 裸 URL 命中（GitHub 自动生成 notes 常见）：整段显示、整段可点
                display = m.value
                url = m.value
            }
            builder.append(cleaned.substring(cursor, m.range.first))
            with(builder) {
                pushStringAnnotation("URL", url)
                // 下划线 + 薄荷色：与「查看完整更新说明」/「如何获取 Token」链接同款语言
                // （色相近时下划线是「可点」的最强信号，全 app 链接统一样式）
                pushStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = linkColor,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    ),
                )
                append(display)
                pop()
                pop()
            }
            cursor = m.range.last + 1
        }
        builder.append(cleaned.substring(cursor))
        return builder.toAnnotatedString()
    }
}
