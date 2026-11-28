package io.candy.tick.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Release body（GFM Markdown）→ AnnotatedString 渲染内核。
 *
 * commonmark-java 完整解析（表格/删除线/自动链接扩展）：任何合法 Markdown 都进对应
 * AST 节点，不存在「裸符号漏网」——替代早期逐特性补正则的清理管线（每遇到新模板
 * 结构就要加规则）。块级结构摊平为「文本行 + 分段样式」：标题/粗体→字重，行内代码/
 * 代码块→等宽，列表→· 前缀 + 缩进层级，表格→表头加粗的竖线行；链接（含 autolink
 * 裸 URL 与 issue 引用）保留为可聚焦、可朗读的链接。自更新弹窗与版本历史共用。
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

    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create(),
            ),
        )
        .build()

    /** GitHub 支持本仓库的 #123 和跨仓库的 owner/repo#123。 */
    private val issueRef = Regex("(?<![\\w./#&-])(?:([A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+))?#([1-9][0-9]*)(?![\\w])")
    private val taskMarker = Regex("^\\[([ xX])]\\s+")
    private val detailsBlock = Regex("(?i)^\\s*</?(?:details|summary)\\b")
    private val detailsStart = Regex("(?i)^</?details\\b[^>]*>\\s*")
    private val detailsEnd = Regex("(?i)\\s*</details\\s*>$")
    private val summaryTag = Regex("(?is)^<summary\\b[^>]*>(.*?)</summary\\s*>")

    private val brTag = Regex("(?i)<br\\s*/?>")
    private val shortcode = Regex(":([a-z0-9_]+):")

    /** 超长 body 截断阈值（实测订阅仓库 9k–40k：全量渲染 40k 字符的展开构建达百 ms 级） */
    private const val MAX_BODY_CHARS = 9000

    fun isPreview(md: String): Boolean = md.length > MAX_BODY_CHARS

    /** 行内代码的默认字体：只切等宽，字号随正文。页面里传 [monoStyle] 以对齐全局等宽字阶 */
    private val MonoOnly = TextStyle(fontFamily = FontFamily.Monospace)

    fun annotated(
        md: String,
        linkColor: Color,
        repoSlug: String? = null, // "owner/repo"：提供时 #123 变为可点 issue 链接
        monoStyle: TextStyle = MonoOnly, // 行内代码的字体：与页面其他等宽文本同一定义，不在这里写死字号
    ): AnnotatedString {
        val r = Renderer(linkColor, repoSlug, monoStyle)
        r.blocks(parser.parse(truncate(md)), indent = "")
        return r.build()
    }

    /** 段落边界截断（完整 changelog 永远在 GitHub：卡片里已有跳转链接） */
    private fun truncate(md: String): String {
        if (!isPreview(md)) return md
        val cut = md.lastIndexOf("\n\n", MAX_BODY_CHARS)
        var end = if (cut > MAX_BODY_CHARS / 2) cut else MAX_BODY_CHARS
        if (md[end - 1].isHighSurrogate() && md[end].isLowSurrogate()) end--
        return md.substring(0, end) + "\n\n…"
    }

    /** 节点子树内是否含 CJK 字符（CJK 区块：汉字/扩展/通用标点全兼容范围） */
    private fun containsCJK(node: Node): Boolean {
        var c = node.firstChild
        while (c != null) {
            if (c is Text && c.literal.any { it.code in 0x2E80..0x9FFF || it.code in 0x3000..0x303F || it.code in 0xFF00..0xFFEF }) {
                return true
            }
            if (containsCJK(c)) return true
            c = c.next
        }
        return false
    }

    private class Renderer(
        linkColor: Color,
        val repoSlug: String?,
        private val monoStyle: TextStyle,
    ) {
        private val sb = StringBuilder()
        private val styles = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        private val links = mutableListOf<AnnotatedString.Range<LinkAnnotation.Url>>()
        private val linkStyle = TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            pressedStyle = SpanStyle(background = linkColor.copy(alpha = 0.14f)),
            focusedStyle = SpanStyle(background = linkColor.copy(alpha = 0.14f)),
        )
        private var bold = 0
        private var italic = 0
        private var mono = 0
        private var strike = 0
        private var currentUrl: String? = null // Link 递归期间置位（无嵌套链接）

        // 当前行首缩进（SoftLineBreak 换行后跟进，列表项内软换行不顶格）
        private var lineIndent = ""

        // ── 基础输出 ──

        /** 纯文本段（无任何样式）：分隔符、缩进、换行等结构字符 */
        private fun raw(s: String) {
            sb.append(s)
        }

        /** 内容段：按当前活跃样式集合记 span（无样式时等同 raw） */
        private fun styled(text: String) {
            val start = sb.length
            sb.append(text)
            if (text.isNotEmpty() && (bold > 0 || italic > 0 || mono > 0 || strike > 0)) {
                val style = SpanStyle(
                    fontWeight = if (bold > 0) FontWeight.Bold else null,
                    fontStyle = if (italic > 0) FontStyle.Italic else null,
                    fontFamily = if (mono > 0) monoStyle.fontFamily else null,
                    fontSize = if (mono > 0) monoStyle.fontSize else TextUnit.Unspecified,
                    textDecoration = if (strike > 0) TextDecoration.LineThrough else null,
                )
                styles += AnnotatedString.Range(style, start, sb.length)
            }
        }

        /** Text 节点：先做 #123 issue 引用切分（代码内与无仓库上下文时不处理） */
        private fun text(literal: String) {
            if (currentUrl == literal || mono > 0) {
                styled(literal)
                return
            }
            val t = if (':' in literal) shortcode.replace(literal) { emoji[it.groupValues[1]] ?: it.value } else literal
            if (currentUrl != null || '#' !in t) {
                styled(t)
                return
            }
            var idx = 0
            for (m in issueRef.findAll(t)) {
                val slug = m.groups[1]?.value ?: repoSlug ?: continue
                styled(t.substring(idx, m.range.first))
                withUrl("https://github.com/$slug/issues/${m.groupValues[2]}") { styled(m.value) }
                idx = m.range.last + 1
            }
            styled(t.substring(idx))
        }

        private inline fun withUrl(url: String, block: () -> Unit) {
            val prev = currentUrl
            val start = sb.length
            currentUrl = url
            block()
            currentUrl = prev
            if (sb.length > start) {
                links += AnnotatedString.Range(LinkAnnotation.Url(url, linkStyle), start, sb.length)
            }
        }

        /** 块间距：删尾换行后统一一个空行 */
        private fun blank() {
            while (sb.isNotEmpty() && sb.last() == '\n') sb.deleteCharAt(sb.length - 1)
            if (sb.isNotEmpty()) raw("\n\n")
        }

        /** 块内换行（列表项间/表格行间）：避免重复换行 */
        private fun nl() {
            if (sb.isNotEmpty() && sb.last() != '\n') raw("\n")
        }

        fun build(): AnnotatedString {
            val text = sb.toString().trimEnd('\n')
            return AnnotatedString.Builder(text).apply {
                for ((style, start, end) in styles) {
                    if (start < text.length) addStyle(style, start, minOf(end, text.length))
                }
                for ((link, start, end) in links) {
                    if (start < text.length) addLink(link, start, minOf(end, text.length))
                }
            }.toAnnotatedString()
        }

        // ── 块级遍历 ──

        fun blocks(parent: Node, indent: String) {
            var n = parent.firstChild
            while (n != null) {
                block(n, indent)
                n = n.next
            }
        }

        private fun block(node: Node, indent: String) {
            lineIndent = indent
            when (node) {
                is Paragraph -> {
                    blank()
                    inline(node)
                }
                is Heading -> {
                    blank()
                    bold++
                    inline(node)
                    bold--
                }
                is BulletList -> list(node, indent, ordered = false, top = true)
                is OrderedList -> list(node, indent, ordered = true, top = true)
                is FencedCodeBlock -> codeBlock(node.literal, indent)
                is IndentedCodeBlock -> codeBlock(node.literal, indent)
                is BlockQuote -> { // 引用：内容照常，样式从简（摊平文本不支持左边线）
                    blank()
                    blocks(node, indent)
                }
                is ThematicBreak -> blank() // 分隔线 → 空行分组
                is HtmlBlock -> details(node.literal, indent)
                is TableBlock -> table(node, indent)
                else -> { // 未知块按行内兜底（新语法至少内容不丢）
                    blank()
                    inline(node)
                }
            }
        }

        /** 展平 GitHub 折叠区。仅剥外层标签，里面的列表、链接及围栏代码仍由 Markdown 解析。 */
        private fun details(literal: String, indent: String) {
            var body = literal.trim()
            if (!detailsBlock.containsMatchIn(body)) {
                // Keep readable block HTML without loading images or opening a browser.
                val plain = android.text.Html.fromHtml(body, android.text.Html.FROM_HTML_MODE_COMPACT)
                    .toString().replace("\uFFFC", "").trim()
                if (plain.isNotEmpty()) {
                    blank()
                    text(plain)
                }
                return
            }
            while (detailsStart.containsMatchIn(body)) body = body.replaceFirst(detailsStart, "")
            while (detailsEnd.containsMatchIn(body)) body = body.replace(detailsEnd, "")
            val summary = summaryTag.find(body)
            if (summary != null) {
                bold++
                blocks(parser.parse(summary.groupValues[1]), indent)
                bold--
                body = body.substring(summary.range.last + 1).trim()
            }
            if (body.isNotBlank() && body != literal.trim()) blocks(parser.parse(body), indent)
        }

        private fun list(node: Node, indent: String, ordered: Boolean, top: Boolean) {
            if (top) blank() else nl() // 嵌套列表与父项保持单换行，不空行隔断
            var item = node.firstChild
            var i = (node as? OrderedList)?.markerStartNumber ?: 1
            while (item != null) {
                if (item is ListItem) {
                    nl()
                    val firstParagraph = item.firstChild as? Paragraph
                    val firstText = firstParagraph?.firstChild as? Text
                    val task = firstText?.let { taskMarker.find(it.literal) }
                    val marker = if (ordered) "$i. " else if (task == null) "· " else ""
                    raw(indent + marker)
                    if (task != null) raw(if (task.groupValues[1] == " ") "☐ " else "☑ ")
                    listItemChildren(item, indent + "    ", task?.value?.length ?: 0)
                }
                item = item.next
                if (ordered) i++
            }
        }

        /** 列表项子块：首段接在 marker 后，后续段落/嵌套列表带缩进 */
        private fun listItemChildren(item: ListItem, childIndent: String, skipMarker: Int) {
            var n = item.firstChild
            var first = true
            while (n != null) {
                when (n) {
                    is Paragraph -> {
                        if (!first) {
                            raw("\n" + childIndent)
                        }
                        lineIndent = childIndent
                        if (first && skipMarker > 0) {
                            val label = n.firstChild as Text
                            text(label.literal.drop(skipMarker))
                            var sibling = label.next
                            while (sibling != null) {
                                inlineNode(sibling)
                                sibling = sibling.next
                            }
                        } else {
                            inline(n)
                        }
                    }
                    is BulletList -> list(n, childIndent, ordered = false, top = false)
                    is OrderedList -> list(n, childIndent, ordered = true, top = false)
                    else -> block(n, childIndent)
                }
                first = false
                n = n.next
            }
        }

        private fun codeBlock(literal: String, indent: String) {
            blank()
            val lines = literal.trimEnd('\n').lines()
            for ((idx, line) in lines.withIndex()) {
                if (idx > 0) raw("\n")
                mono++
                styled(indent + line)
                mono--
            }
        }

        private fun table(node: TableBlock, indent: String) {
            blank()
            var section = node.firstChild
            while (section != null) {
                when (section) {
                    is TableHead, is TableBody -> tableRows(section, indent)
                }
                section = section.next
            }
        }

        private fun tableRows(section: Node, indent: String) {
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    nl()
                    raw(indent)
                    var cell = row.firstChild
                    var first = true
                    while (cell != null) {
                        if (cell is TableCell) {
                            if (!first) raw(" | ")
                            if (cell.isHeader) bold++
                            inline(cell)
                            if (cell.isHeader) bold--
                            first = false
                        }
                        cell = cell.next
                    }
                }
                row = row.next
            }
        }

        // ── 行内遍历 ──

        private fun inline(parent: Node) {
            var n = parent.firstChild
            while (n != null) {
                inlineNode(n)
                n = n.next
            }
        }

        private fun inlineNode(node: Node) {
            when (node) {
                is Text -> text(node.literal)
                is Code -> {
                    mono++
                    styled(node.literal)
                    mono--
                }
                is Emphasis -> {
                    // CJK 无真斜体（合成剪切变形观感差）：仅西文斜体，中文按普通文本
                    val italicize = !containsCJK(node)
                    if (italicize) italic++
                    inline(node)
                    if (italicize) italic--
                }
                is StrongEmphasis -> {
                    bold++
                    inline(node)
                    bold--
                }
                is Strikethrough -> {
                    strike++
                    inline(node)
                    strike--
                }
                is Link ->
                    // autolink 裸 URL 也走这里；锚点/相对链接（#english 语言切换行）不标链接
                    if (node.destination.startsWith("https://", ignoreCase = true) ||
                        node.destination.startsWith("http://", ignoreCase = true) ||
                        node.destination.startsWith("mailto:", ignoreCase = true)
                    ) {
                        withUrl(node.destination) { inline(node) }
                    } else {
                        inline(node)
                    }
                is Image -> inline(node) // 图片取 alt（children）
                // GitHub release 渲染口径：软换行=空格（同段续行），行尾双空格的硬换行才断行
                is SoftLineBreak -> raw(" ")
                is HardLineBreak -> raw("\n" + lineIndent)
                is HtmlInline ->
                    if (node.literal.matches(brTag)) {
                        raw("\n" + lineIndent)
                    } // 其余行内标签丢弃
                else -> inline(node) // 未知行内容器兜底
            }
        }
    }
}
