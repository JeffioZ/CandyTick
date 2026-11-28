package io.candy.tick.data.store.tencent

import io.candy.tick.data.download.ExpectedHash
import io.candy.tick.data.download.HashAlgorithm
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 应用宝查询协议（端点、请求体与响应解析）。
 */
internal object TencentProtocol {
    const val DETAIL_ENDPOINT = "https://upage.html5.qq.com/wechat-apkinfo"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"

    private val sha256Regex = Regex("^[a-fA-F0-9]{64}$")
    private val md5Regex = Regex("^[a-fA-F0-9]{32}$")

    fun buildRequestBody(packageName: String): String =
        buildJsonObject { put("packagename", packageName) }.toString()

    fun parseDetail(rawJson: String, packageName: String): StoreReleaseInfo? {
        val root = Json.parseToJsonElement(rawJson) as JsonObject
        require(root.str("ret") == "0") { "Tencent detail query failed" }
        val records = root.obj("app_detail_records") ?: error("Missing Tencent app records")
        val record = records.obj(packageName) ?: return null
        val info = record.obj("app_info") ?: error("Missing Tencent app detail")
        require(info.str("package_name").let { it.isBlank() || it == packageName }) { "Wrong Tencent app info" }
        val online = (info["online_status"] as? JsonPrimitive)?.longOrNull
        if (online == 0L) return null
        val apk = record.obj("apk_all_data") ?: error("Missing Tencent APK detail")
        val rawUrl = apk.str("url")
        if (rawUrl.isBlank()) return null
        require(apk.str("package_name") == packageName) { "Wrong Tencent app detail" }
        val url = StoreHttp.upgradeToHttps(rawUrl)
        require(url.isNotBlank()) { "Missing Tencent APK URL" }
        val versionCode = apk.num("version_code")
        require(versionCode > 0L) { "Missing Tencent version" }
        val checksum = apk.str("sha256").takeIf(sha256Regex::matches)
            ?: apk.str("apk_md5").takeIf(md5Regex::matches)
            ?: ""
        val size = apk.num("size_byte")
        return StoreReleaseInfo(
            storeId = StoreCatalog.ID_TENCENT,
            versionCode = versionCode,
            versionName = apk.str("version_name").ifBlank { versionCode.toString() },
            sizeBytes = size,
            exactSizeBytes = size.takeIf { it > 0L },
            changelog = apk.str("feature").trim(),
            appId = apk.num("app_id"),
            downloadUrl = url,
            checksum = checksum,
        )
    }

    fun parseExpectedHash(checksum: String): ExpectedHash? = when {
        sha256Regex.matches(checksum) -> ExpectedHash(HashAlgorithm.SHA256, checksum)
        md5Regex.matches(checksum) -> ExpectedHash(HashAlgorithm.MD5, checksum)
        else -> null
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.num(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
}
