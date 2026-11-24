package io.candy.tick.util

/** GitHub 链接解析：首页 / repo / releases 等任意子路径均可 */
object UrlParser {

    private val REPO_PATH = Regex(
        """(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:[/?#][^\s]*)?""",
        RegexOption.IGNORE_CASE,
    )

    /** 「owner/repo」裸输入同样接受 */
    private val BARE = Regex("""^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$""")

    /** @return owner/repo 二元组；null = 无法识别 */
    fun parse(input: String): Pair<String, String>? {
        val text = input.trim()
        val match = REPO_PATH.matchEntire(text) ?: BARE.matchEntire(text)?.takeUnless { looksLikeUrl(text) } ?: return null
        val (owner, rawRepo) = match.destructured
        // 克隆地址带 .git 后缀，API 路径里没有它（带上会 404，界面误报「不存在或已私有」）；
        // GitHub 仓库名本身不能以 .git 结尾，剥掉不会改写真实仓库名
        val repo = if (rawRepo.endsWith(".git", ignoreCase = true)) rawRepo.dropLast(4) else rawRepo
        if (owner == "." || owner == ".." || repo.isEmpty() || repo == "." || repo == "..") return null
        return owner to repo
    }

    fun looksLikeUrl(input: String): Boolean {
        val text = input.trim()
        val domain = text.substringBefore('/')
        return domain.equals("github.com", ignoreCase = true) || domain.equals("www.github.com", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true)
    }
}
