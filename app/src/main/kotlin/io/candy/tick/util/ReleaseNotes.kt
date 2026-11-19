package io.candy.tick.util

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
 * 裸 URL 与 #123 issue 引用）标注 tag="URL" 自定义 span，调用方用 ClickableText
 * 命中开浏览器。自更新弹窗与版本历史共用。
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

    /** GitHub issue/PR 引用 shorthand（#123 → issues/123）；负向后视避开 URL 锚点/C#/HTML 实体 */
    private val issueRef = Regex("(?<![\\w/#&])#([0-9]{1,6})")

    private val brTag = Regex("(?i)<br\\s*/?>")

    fun annotated(
        md: String,
        linkColor: androidx.compose.ui.graphics.Color,
        repoSlug: String? = null, // "owner/repo"：提供时 #123 变为可点 issue 链接
    ): androidx.compose.ui.text.AnnotatedString {
        val emojiMd = emoji.entries.fold(md) { acc, (k, v) -> acc.replace(":$k:", v) }
        val r = Renderer(linkColor, repoSlug)
        r.blocks(parser.parse(emojiMd), indent = "")
        return r.build()
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

    /** 一次 append 一段：段自带当时全部活跃样式（链接/粗体/等宽…），段间天然不重叠 */
    private class Span(
        val start: Int,
        val end: Int,
        val url: String?, // 非空 = 可点链接段
        val bold: Boolean,
        val italic: Boolean,
        val mono: Boolean,
        val strike: Boolean,
    )

    private class Renderer(
        val linkColor: androidx.compose.ui.graphics.Color,
        val repoSlug: String?,
    ) {
        private val sb = StringBuilder()
        private val spans = mutableListOf<Span>()
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
            if (currentUrl != null || bold > 0 || italic > 0 || mono > 0 || strike > 0) {
                spans.add(Span(start, sb.length, currentUrl, bold > 0, italic > 0, mono > 0, strike > 0))
            }
        }

        /** Text 节点：先做 #123 issue 引用切分（代码内与无仓库上下文时不处理） */
        private fun text(t: String) {
            if (repoSlug == null || mono > 0 || currentUrl != null) {
                styled(t)
                return
            }
            var idx = 0
            for (m in issueRef.findAll(t)) {
                styled(t.substring(idx, m.range.first))
                val n = m.groupValues[1]
                withUrl("https://github.com/$repoSlug/issues/$n") { styled("#$n") }
                idx = m.range.last + 1
            }
            styled(t.substring(idx))
        }

        private inline fun withUrl(url: String, block: () -> Unit) {
            val prev = currentUrl
            currentUrl = url
            block()
            currentUrl = prev
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

        fun build(): androidx.compose.ui.text.AnnotatedString {
            while (sb.isNotEmpty() && sb.last() == '\n') sb.deleteCharAt(sb.length - 1)
            val b = androidx.compose.ui.text.AnnotatedString.Builder()
            var pos = 0
            for (s in spans) {
                // 无样式缺口（普通文本/结构字符）：span 只记录有样式区间，这里补齐裸文本
                if (s.start > pos) b.append(sb.substring(pos, s.start))
                val style = androidx.compose.ui.text.SpanStyle(
                    color = if (s.url != null) linkColor else androidx.compose.ui.graphics.Color.Unspecified,
                    textDecoration = if (s.url != null) {
                        androidx.compose.ui.text.style.TextDecoration.Underline
                    } else if (s.strike) {
                        androidx.compose.ui.text.style.TextDecoration.LineThrough
                    } else {
                        null
                    },
                    fontWeight = if (s.bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    fontStyle = if (s.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                    fontFamily = if (s.mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                    // 等宽配 13sp 小号（对齐 MonoTokenStyle）：跟随正文字号的大号等宽观感突兀
                    fontSize = if (s.mono) androidx.compose.ui.unit.TextUnit(13f,
                        androidx.compose.ui.unit.TextUnitType.Sp)
                    else androidx.compose.ui.unit.TextUnit.Unspecified,
                )
                b.pushStyle(style)
                if (s.url != null) b.pushStringAnnotation("URL", s.url)
                b.append(sb.substring(s.start, s.end))
                if (s.url != null) b.pop()
                b.pop()
                pos = s.end
            }
            if (pos < sb.length) b.append(sb.substring(pos))
            return b.toAnnotatedString()
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
                is HtmlBlock -> Unit // HTML 块整块丢弃（<a id> 语言锚点等）
                is TableBlock -> table(node, indent)
                else -> { // 未知块按行内兜底（新语法至少内容不丢）
                    blank()
                    inline(node)
                }
            }
        }

        private fun list(node: Node, indent: String, ordered: Boolean, top: Boolean) {
            if (top) blank() else nl() // 嵌套列表与父项保持单换行，不空行隔断
            var item = node.firstChild
            var i = (node as? OrderedList)?.startNumber ?: 1
            while (item != null) {
                if (item is ListItem) {
                    nl()
                    val marker = if (ordered) "$i. " else "· "
                    raw(indent + marker)
                    listItemChildren(item, indent + "    ")
                }
                item = item.next
                if (ordered) i++
            }
        }

        /** 列表项子块：首段接在 marker 后，后续段落/嵌套列表带缩进 */
        private fun listItemChildren(item: ListItem, childIndent: String) {
            var n = item.firstChild
            var first = true
            while (n != null) {
                when (n) {
                    is Paragraph -> {
                        if (!first) {
                            raw("\n" + childIndent)
                        }
                        inline(n)
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
                    if (node.destination.startsWith("http") || node.destination.startsWith("mailto")) {
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
