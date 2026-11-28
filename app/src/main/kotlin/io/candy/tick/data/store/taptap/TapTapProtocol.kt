package io.candy.tick.data.store.taptap

import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreHttp
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * TapTap 接口协议：签名表单请求、批量版本比对与安装包详情解析。
 * 纯 Kotlin 实现，便于 JVM 单元测试覆盖。
 */
internal object TapTapProtocol {

    const val BASE_URL = "https://api.taptapdada.com"
    const val BATCH_UPDATE_PATH = "/app/v1/mini-multi-get"
    const val APK_DETAIL_PATH = "/apk/v1/detail"
    const val USER_AGENT = "TapTap/2.96.12-rel#100100 (com.taptap; build:296121001; Android 16) Okhttp/3.12.1"
    const val BATCH_SIZE = 50

    private const val SIGN_SECRET = "PeCkE6Fu0B10Vm9BKfPfANwCUAn5POcs"
    private const val NONCE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun buildXUa(uid: String): String =
        "V=1&PN=TapTap&VN=2.96.12-rel.100100&VN_CODE=296121001&LOC=CN&LANG=zh_CN" +
            "&CH=organic-direct_index&UID=$uid&NT=1&SR=1080x2400&DEB=Android&DEM=CandyTick&OSV=16"

    fun buildUrl(path: String, xUa: String): String =
        "$BASE_URL$path?X-UA=${URLEncoder.encode(xUa, "UTF-8")}"

    fun randomNonce(length: Int = 5): String = buildString(length) {
        repeat(length) { append(NONCE_CHARS[Random.nextInt(NONCE_CHARS.length)]) }
    }

    fun signForm(
        params: Map<String, String>,
        xUa: String,
        epochSeconds: Long = System.currentTimeMillis() / 1000L,
        nonce: String = randomNonce(),
    ): LinkedHashMap<String, String> {
        val fields = linkedMapOf<String, String>()
        fields.putAll(params)
        fields["time"] = epochSeconds.toString()
        fields["nonce"] = nonce
        // X-UA 必须与其他字段一起参与 ASCII 字典排序（大写 'X' 排在小写键名前）
        val signPayload = (fields.entries.map { "${it.key}=${it.value}" } + "X-UA=$xUa")
            .sorted()
            .joinToString("&") + SIGN_SECRET
        fields["sign"] = md5Hex(signPayload)
        return fields
    }

    fun encodeForm(fields: Map<String, String>): String = fields.entries.joinToString("&") { (k, v) ->
        "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
    }

    fun parseBatchUpdateResponse(
        body: String,
        installedByPkg: Map<String, LocalInstalledApp>,
    ): Map<String, StoreReleaseInfo> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyMap()
        val data = root["data"] as? JsonObject ?: return emptyMap()
        val list = data["list"] as? JsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, StoreReleaseInfo>()
        for (item in list) {
            val obj = item as? JsonObject ?: continue
            val pkg = obj.str("identifier")
            val installed = installedByPkg[pkg] ?: continue
            val download = obj["download"] as? JsonObject ?: continue
            val apk = download["apk"] as? JsonObject ?: continue
            val apkId = download.long("apk_id")
            val versionCode = apk.long("version_code")
            if (apkId <= 0L || versionCode <= installed.versionCode) continue
            val versionName = apk.str("version_name").ifBlank { versionCode.toString() }
            val size = apk.long("size")
            val md5 = apk.str("md5").lowercase().takeIf { it.length == 32 }.orEmpty()
            val changelog = (obj["whatsnew"] as? JsonObject)?.str("text").orEmpty()
            out[pkg] = StoreReleaseInfo(
                storeId = StoreCatalog.ID_TAPTAP,
                versionCode = versionCode,
                versionName = versionName,
                sizeBytes = size,
                changelog = changelog,
                appId = apkId,
                checksum = md5,
            )
        }
        return out
    }

    data class ApkDownloadInfo(
        val url: String,
        val sizeBytes: Long,
        val md5: String,
    )

    fun parseApkDetailResponse(body: String): ApkDownloadInfo? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val data = root["data"] as? JsonObject ?: return null
        val apk = data["apk"] as? JsonObject ?: return null
        val url = StoreHttp.upgradeToHttps(apk.str("download"))
        if (url.isBlank()) return null
        return ApkDownloadInfo(
            url = url,
            sizeBytes = apk.long("size"),
            md5 = apk.str("md5").lowercase().takeIf { it.length == 32 }.orEmpty(),
        )
    }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L
}
