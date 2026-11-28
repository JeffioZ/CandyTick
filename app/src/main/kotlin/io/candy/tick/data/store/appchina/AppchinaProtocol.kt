package io.candy.tick.data.store.appchina

import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 应用汇网页查询协议：按包名搜索获取 versionCode，下载时从详情页提取直链。
 */
internal object AppchinaProtocol {
    const val WEB_BASE = "https://m.appchina.com"
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val pkgVerRegex = Regex("""package_(\d+)(?:[./]|$)""")
    private val downloadRegex = Regex("""class="normal-down-btn"[^>]*href="([^"]+)"""")
    private val versionMetaRegex = Regex("""最新版本:</span>\s*<span class="fl">([^<]+)</span>""")
    private val changeLogRegex = Regex("""App_Update">\s*<span>([\s\S]*?)</span>""")
    private val sizeRegex = Regex("""([\d,.]+)\s*([KMGT])B?""", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("<[^>]+>")

    fun searchUrl(packageName: String): String = "$WEB_BASE/ajax/search/$packageName/1"

    fun detailUrl(packageName: String): String = "$WEB_BASE/app/$packageName"

    fun parseSearchMatch(rawJson: String, packageName: String): StoreReleaseInfo? {
        val resp = runCatching { json.decodeFromString<SearchResp>(rawJson) }.getOrNull() ?: return null
        val item = resp.list.firstOrNull { it.packageName.equals(packageName, ignoreCase = true) && !it.offShelf }
            ?: return null
        val apkUrl = StoreHttp.upgradeToHttps(item.apkUrl)
        val versionCode = pkgVerRegex.find(apkUrl)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (versionCode <= 0L) return null
        return StoreReleaseInfo(
            storeId = StoreCatalog.ID_APPCHINA,
            versionCode = versionCode,
            versionName = versionCode.toString(),
            sizeBytes = parseSize(item.size),
            downloadUrl = apkUrl,
        )
    }

    fun parseDetailPage(html: String, fallback: StoreReleaseInfo): StoreReleaseInfo {
        val url = downloadRegex.find(html)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?.let(StoreHttp::upgradeToHttps)
            ?.takeIf { it.isNotBlank() } ?: fallback.downloadUrl
        val verName = versionMetaRegex.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: fallback.versionName
        val log = changeLogRegex.find(html)?.groupValues?.get(1)?.replace(htmlTag, "")?.trim().orEmpty()
        return fallback.copy(
            versionName = verName,
            changelog = log.ifBlank { fallback.changelog },
            downloadUrl = url,
        )
    }

    private fun parseSize(raw: String): Long {
        val m = sizeRegex.find(raw.trim()) ?: return 0L
        val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0L
        val mul = when (m.groupValues[2].uppercase()) {
            "K" -> 1024.0
            "M" -> 1024.0 * 1024
            "G" -> 1024.0 * 1024 * 1024
            "T" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (v * mul).toLong()
    }

    @Serializable
    private data class SearchResp(val list: List<SearchItem> = emptyList())

    @Serializable
    private data class SearchItem(
        val packageName: String = "",
        val size: String = "",
        val apkUrl: String = "",
        val offShelf: Boolean = false,
    )
}
