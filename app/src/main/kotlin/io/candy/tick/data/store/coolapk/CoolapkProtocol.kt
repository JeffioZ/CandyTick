package io.candy.tick.data.store.coolapk

import io.candy.tick.data.download.ExpectedHash
import io.candy.tick.data.download.HashAlgorithm
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreHttp
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.mindrot.jbcrypt.BCrypt

/**
 * 酷安 v6 协议：v2 令牌头生成与详情解析（只使用随机虚拟设备标识，不读取真实硬件信息）。
 */
internal object CoolapkProtocol {
    const val API_BASE = "https://api2.coolapk.com"
    const val USER_AGENT =
        "Dalvik/2.1.0 (Linux; U; Android 9; MI 8 SE MIUI/9.5.9) (#Build; Xiaomi; MI 8 SE; PKQ1.181121.001; 9) +CoolMarket/12.4.2-2208241-universal"

    private val md5Regex = Regex("^[a-fA-F0-9]{32}$")
    private val htmlBreak = Regex("(?i)<br\\s*/?>|</p>|</div>|</li>")
    private val htmlTag = Regex("<[^>]*>")

    fun randomDeviceCode(): String {
        val aid = ByteArray(16).also(Random.Default::nextBytes).joinToString("") { "%02X".format(it) }
        val mac = List(6) { "%02x".format(Random.nextInt(256)) }.joinToString(":")
        return b64("$aid; ; ; $mac; Google; Google; Pixel 5a; SQ1D.220105.007")
    }

    fun buildHeaders(deviceCode: String, epochSeconds: Long = System.currentTimeMillis() / 1000L): Map<String, String> {
        val ts = epochSeconds.toString()
        val token = "token://com.coolapk.market/dcf01e569c1e3db93a3d0fcf191a622c?${md5Hex(ts)}\$${md5Hex(deviceCode)}&com.coolapk.market"
        val salt = "\$2a\$10\$${b64(ts).take(14)}/${md5Hex(token).take(6)}u"
        val hash = BCrypt.hashpw(md5Hex(b64(token)), salt)
        return mapOf(
            "User-Agent" to USER_AGENT,
            "X-App-Id" to "com.coolapk.market",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Sdk-Int" to "30",
            "X-App-Mode" to "universal",
            "X-App-Channel" to "coolapk",
            "X-Sdk-Locale" to "zh-CN",
            "X-App-Version" to "12.4.2",
            "X-Api-Supported" to "2208241",
            "X-App-Code" to "2208241",
            "X-Api-Version" to "12",
            "X-App-Device" to deviceCode,
            "X-Dark-Mode" to "0",
            "X-App-Token" to "v2${b64(hash.replaceFirst("\$2a\$", "\$2y\$"))}",
        )
    }

    fun parseDetail(rawJson: String, expectedPackage: String): StoreReleaseInfo? {
        val root = runCatching { Json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return null
        if (root.num("status", 0L) < 0L) return null
        val data = root["data"] as? JsonObject ?: return null
        val pkg = data.str("apkname").ifBlank { data.str("packageName") }
        val appId = data.num("id").takeIf { it > 0L } ?: data.num("entityId")
        if (!pkg.equals(expectedPackage, ignoreCase = true) || appId <= 0L) return null
        if ((data["is_download_app"] as? JsonPrimitive)?.longOrNull == 0L) return null
        val code = data.num("apkversioncode")
        if (code <= 0L) return null
        val md5 = data.str("apkmd5").takeIf(md5Regex::matches).orEmpty()
        return StoreReleaseInfo(
            storeId = StoreCatalog.ID_COOLAPK,
            versionCode = code,
            versionName = data.str("apkversionname").ifBlank { code.toString() },
            sizeBytes = data.num("apklength"),
            changelog = plainText(data.str("changelog")),
            appId = appId,
            checksum = md5,
        )
    }

    fun expectedMd5(checksum: String): ExpectedHash? =
        checksum.takeIf(md5Regex::matches)?.let { ExpectedHash(HashAlgorithm.MD5, it) }

    fun upgradeUrl(raw: String): String = StoreHttp.upgradeToHttps(raw)

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())
    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun plainText(raw: String): String = raw
        .replace(htmlBreak, "\n").replace(htmlTag, "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.num(key: String, default: Long = 0L): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: default
}
