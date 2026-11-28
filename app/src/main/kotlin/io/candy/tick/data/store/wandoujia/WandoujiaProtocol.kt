package io.candy.tick.data.store.wandoujia

import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreHttp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 豌豆荚批量更新协议：单次 M90+GZIP 报文检查全部已装应用；详情合并接口解析下载地址。
 */
internal object WandoujiaProtocol {
    const val API_BASE = "https://w-api.pp.cn"
    const val CHECK_UPDATE_PATH = "/api/resource.app.checkUpdateV1"
    const val COMBINE_PATH = "/api2/combine"
    const val API_USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 15; CandyTick)"
    const val DOWNLOAD_USER_AGENT =
        "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 5.2; Trident/4.0)"

    private const val CALLER = "secret.wdj.client"
    private const val SALT = "LVJd97AbRtikeYRRhi3ocdwSD"
    private const val SUCCESS_CODE = 2000000L
    private val REQ_KEY = "d861a5214f1849a1".toByteArray()
    private val RESP_KEY = "c84b3226ce0673d4".toByteArray()
    private val htmlBreak = Regex("""<\\?/?br\s*/?>""", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("<[^>]+>")

    fun buildCheckEnvelope(
        apps: List<LocalInstalledApp>,
        identity: String,
        joinedAt: Long,
        requestId: Long,
    ): JsonObject = buildJsonObject {
        put("id", requestId)
        put("client", clientIdentity(identity, joinedAt))
        put("data", buildJsonObject {
            put("flags", 1081)
            put("content", buildJsonArray {
                for (app in apps) {
                    add(buildJsonObject {
                        put("packageName", app.packageName)
                        put("versionName", app.versionName)
                        put("versionCode", app.versionCode)
                        put("signature", "")
                        put("isSystemApp", if (app.isSystemApp) 1 else 0)
                        put("installedTime", 0L)
                        put("RFF", "")
                        put("ZFF", "")
                    })
                }
            })
        })
        put("sign", "")
    }

    fun buildDetailCombineEnvelope(
        appId: Long,
        identity: String,
        joinedAt: Long,
        requestId: Long,
    ): JsonObject {
        val data = buildJsonArray {
            add(buildJsonObject {
                put("service", "resource.app.getDetail")
                put("data", buildJsonObject {
                    put("screenWidth", 1080)
                    put("appId", appId)
                })
            })
        }
        val sign = md5Hex(requestId.toString() + CALLER + data.toString().replace("\\", "") + SALT)
        return buildJsonObject {
            put("id", requestId)
            put("client", clientIdentity(identity, joinedAt))
            put("data", data)
            put("sign", sign)
            put("encrypt", "md5")
        }
    }

    fun parseCheckResponse(rawJson: String, installed: Map<String, LocalInstalledApp>): Map<String, StoreReleaseInfo> {
        val root = runCatching { Json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return emptyMap()
        if (root.obj("state").num("code", -1L) != SUCCESS_CODE) return emptyMap()
        val list = root.obj("data")?.get("content") as? JsonArray ?: return emptyMap()
        val out = mutableMapOf<String, StoreReleaseInfo>()
        for (el in list) {
            val item = el as? JsonObject ?: continue
            val pkg = item.str("packageName")
            val local = installed[pkg] ?: continue
            val parsed = parseAppItem(item) ?: continue
            if (parsed.versionCode > local.versionCode) out[pkg] = parsed
        }
        return out
    }

    fun parseDetailResponse(rawJson: String): StoreReleaseInfo? {
        val root = runCatching { Json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return null
        if (root.obj("state").num("code", -1L) != SUCCESS_CODE) return null
        val services = root["data"] as? JsonArray ?: return null
        val detailSvc = services.firstOrNull { (it as? JsonObject)?.str("service") == "resource.app.getDetail" } as? JsonObject
            ?: return null
        if (detailSvc.obj("state").num("code", -1L) != SUCCESS_CODE) return null
        val appObj = detailSvc.obj("data")?.obj("app") ?: return null
        return parseAppItem(appObj)
    }

    fun encodeM90Request(plainJson: ByteArray, seed: Int = Random.nextInt()): ByteArray {
        val compressed = gzip(plainJson)
        return encodeM90(compressed, REQ_KEY, seed)
    }

    fun decodeM90Response(encrypted: ByteArray): ByteArray = gunzip(decodeM90(encrypted, RESP_KEY))

    internal fun encodeM90(data: ByteArray, key: ByteArray, seed: Int): ByteArray {
        val state = IntArray(8) { key[it].toInt() and 0xff }
        val inc = IntArray(8) { key[it + 8].toInt() and 0xff }
        val rnd = m90RandomVector(seed)
        val out = ByteArray(data.size + 10)
        out[0] = 'm'.code.toByte(); out[1] = '9'.code.toByte(); out[2] = '0'.code.toByte(); out[3] = 31
        for (i in 0 until 4) out[i + 4] = rnd[i].toByte()
        var checksum = 0
        for (i in data.indices) {
            val pos = i % 8
            if (pos == 0) advanceState(state, inc, rnd)
            val p = data[i].toInt() and 0xff
            out[i + 8] = (p xor state[pos]).toByte()
            checksum = checksum xor p
        }
        out[data.size + 8] = (checksum xor state[0]).toByte()
        out[data.size + 9] = (checksum xor state[1]).toByte()
        return out
    }

    internal fun decodeM90(data: ByteArray, key: ByteArray): ByteArray {
        if (data.size < 10 || data[0] != 'm'.code.toByte() || data[1] != '9'.code.toByte() || data[2] != '0'.code.toByte()) {
            throw IllegalArgumentException("Invalid M90 payload")
        }
        val state = IntArray(8) { key[it].toInt() and 0xff }
        val inc = IntArray(8) { key[it + 8].toInt() and 0xff }
        val b0 = data[4].toInt() and 0xff
        val b1 = data[5].toInt() and 0xff
        val b2 = data[6].toInt() and 0xff
        val b3 = data[7].toInt() and 0xff
        val rnd = intArrayOf(b0, b1, b2, b3, (b0 + 87) and 0xff, (b1 + 29) and 0xff, (b2 + 171) and 0xff, (b3 + 148) and 0xff)
        val out = ByteArray(data.size - 10)
        var checksum = 0
        for (i in out.indices) {
            val pos = i % 8
            if (pos == 0) advanceState(state, inc, rnd)
            val p = (data[i + 8].toInt() and 0xff) xor state[pos]
            out[i] = p.toByte()
            checksum = checksum xor p
        }
        val c0 = (checksum xor state[0]) and 0xff
        val c1 = (checksum xor state[1]) and 0xff
        if ((data[data.lastIndex - 1].toInt() and 0xff) != c0 || (data[data.lastIndex].toInt() and 0xff) != c1) {
            throw IllegalArgumentException("M90 checksum mismatch")
        }
        return out
    }

    private fun parseAppItem(item: JsonObject): StoreReleaseInfo? {
        val pkg = item.str("packageName")
        val code = item.num("versionCode")
        if (pkg.isBlank() || code <= 0L) return null
        val url = StoreHttp.upgradeToHttps(item.str("downloadUrl"))
        val appId = item.num("id")
        if (url.isBlank() && appId <= 0L) return null
        val sizeBytes = item.num("totalSize").takeIf { it > 0L } ?: (item.num("size") * 1024L)
        return StoreReleaseInfo(
            storeId = StoreCatalog.ID_WANDOUJIA,
            versionCode = code,
            versionName = item.str("versionName").ifBlank { code.toString() },
            sizeBytes = sizeBytes,
            changelog = stripHtml(item.str("verDesc")),
            appId = appId,
            downloadUrl = url,
        )
    }

    private fun clientIdentity(identity: String, joinedAt: Long): JsonObject = buildJsonObject {
        put("caller", CALLER)
        put("ex", buildJsonObject {
            put("osVersion", 35)
            put("ch", "wap_seo_others_homepage")
            put("productId", 2011)
            put("brand", "generic")
            put("udid", identity)
            put("utdid", identity.take(24))
            put("utoken", "")
            put("joinTime", joinedAt)
            put("aid", identity)
        })
        put("guestMode", true)
        put("versionCode", 803110002)
        put("VName", "8.3.11.2")
        put("puid", "")
        put("uuid", identity)
        put("umid", "")
        put("recognition", "0_0")
        put("androidId", identity.take(16))
    }

    private fun m90RandomVector(seed: Int): IntArray {
        val b0 = seed ushr 24 and 0xff
        val b1 = seed ushr 16 and 0xff
        val b2 = seed ushr 8 and 0xff
        val b3 = seed and 0xff
        return intArrayOf(b0, b1, b2, b3, (b0 + 87) and 0xff, (b1 + 29) and 0xff, (b2 + 171) and 0xff, (b3 + 148) and 0xff)
    }

    private fun advanceState(state: IntArray, inc: IntArray, rnd: IntArray) {
        for (i in 0 until 8) state[i] = (state[i] + inc[i] + rnd[i]) and 0xff
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
        out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun stripHtml(raw: String): String = raw
        .replace(htmlBreak, "\n").replace(htmlTag, "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
    private fun JsonObject?.str(key: String): String = (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject?.num(key: String, default: Long = 0L): Long =
        (this?.get(key) as? JsonPrimitive)?.longOrNull ?: default
}
