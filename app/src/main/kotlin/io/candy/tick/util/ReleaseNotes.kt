package io.candy.tick.util

/**
 * Release body（Markdown 原文）的展示层轻量清理：
 * 剥标题井号/加粗/列表符，压空行——纯文本卡片展示（自更新弹窗与版本历史共用）。
 */
object ReleaseNotes {

    fun plain(md: String): String = md
        .replace(Regex("#{1,6} "), "")
        .replace("**", "")
        .replace(Regex("^[-*] ", RegexOption.MULTILINE), "· ")
        .replace(Regex("\\[[^]]+]\\(([^)]+)\\)"), "$1") // [文本](链接) → 保留链接
        .replace("`", "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
