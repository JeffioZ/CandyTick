package io.candy.tick.util

/** GitHub 链接解析：首页 / repo / releases 等任意子路径均可 */
object UrlParser {

    private val REPO_PATH = Regex(
        """^(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:/[?#\s].*)?/?$""",
        RegexOption.IGNORE_CASE,
    )

    /** 「owner/repo」裸输入同样接受 */
    private val BARE = Regex("""^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$""")

    /** @return owner/repo 二元组；null = 无法识别 */
    fun parse(input: String): Pair<String, String>? {
        val text = input.trim()
        REPO_PATH.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
        BARE.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
        return null
    }

    fun looksLikeUrl(input: String): Boolean =
        input.trim().let { it.contains("github.com", ignoreCase = true) || it.startsWith("http", true) }
}
