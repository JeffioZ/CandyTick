package io.candy.tick.data.store.honor

import io.candy.tick.data.download.ExpectedHash
import io.candy.tick.data.download.HashAlgorithm
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDevice
import io.candy.tick.data.store.StoreDownloadPart
import io.candy.tick.data.store.StoreDownloadSpec
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.StoreResponseException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 荣耀应用市场协议：HmacSHA256 请求签名、批量更新检查与单包/分包下载解析。
 */
internal object HonorProtocol {

    const val BASE_URL = "https://appmarket-api-drcn.hispace.hihonorcloud.com"
    const val UPDATE_PATH = "/api/system/updater/v1/check/app/update"
    const val DETAIL_PATH = "/api/market/app/v3/detail/bynames/query"
    const val DISPATCH_PATH = "/downloaddispatch/download/appmarket/getUriList"
    const val CLIENT_PACKAGE = "com.hihonor.appmarket"
    const val APK_VER = "160107301"
    const val APK_VER_NAME = "16.1.7.301"
    const val BATCH_SIZE = 100

    private const val REQUEST_KEY_B64 = "OTNZNTk0a0JPVGtDNkEyOWdlcWRTQkxTNXUxU1RwQVE="
    private val requestKey: ByteArray = Base64.getDecoder().decode(REQUEST_KEY_B64)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val sha256Regex = Regex("^[a-f0-9]{64}$")
    private val zeroSha256 = "0".repeat(64)

    fun sign(path: String, timestamp: Long, traceId: String, body: String): String {
        val headerJson = "{\"market-timestamp\":\"$timestamp\",\"traceId\":\"$traceId\"}"
        val msg = (path + headerJson + body).toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256").digest(msg)
        val signKey = ByteArray(requestKey.size + sha.size)
        System.arraycopy(requestKey, 0, signKey, 0, requestKey.size)
        System.arraycopy(sha, 0, signKey, requestKey.size, sha.size)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signKey, "HmacSHA256"))
        return mac.doFinal(msg).toHexLower()
    }

    fun buildHeaders(
        instanceId: String,
        timestamp: Long,
        traceId: String,
        signature: String,
    ): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 15; PGS-AN00 Build/HONORPGS-AN00) $CLIENT_PACKAGE/$APK_VER",
        "x-uuid" to instanceId,
        "apkVer" to APK_VER,
        "flavor" to "product",
        "No-ECDH" to "1",
        "traceId" to traceId,
        "areaId" to "CN",
        "udid" to sha256Hex("honor-udid:$instanceId"),
        "androidId" to sha256Hex("honor-android:$instanceId").take(16),
        "uid" to "",
        "model" to "PGS-AN00",
        "sysVersion" to "9.0.0",
        "magicSysVersion" to "MagicOS 9.0",
        "androidVersion" to "15",
        "launchType" to "-1",
        "caller" to CLIENT_PACKAGE,
        "language" to "zh-CN",
        "userType" to "-1",
        "market-timestamp" to timestamp.toString(),
        "market-package-name" to CLIENT_PACKAGE,
        "market-sign-type" to "HmacSHA256",
        "market-sign-value" to signature,
    )

    fun buildUpdateBody(
        apps: List<LocalInstalledApp>,
        instanceId: String,
        timestamp: Long,
    ): String = buildJsonObject {
        put("fr", "ry000")
        put(
            "appLst",
            buildJsonArray {
                for (app in apps) {
                    add(
                        buildJsonObject {
                            put("pName", app.packageName)
                            put("ver", app.versionCode)
                            put("versionName", app.versionName.ifBlank { app.versionCode.toString() })
                            put("sha256", "")
                            putJsonArray("sha256List") {}
                            put("identifier", zeroSha256)
                            put("installSourcePkg", "")
                            putJsonArray("resourceIdentifierList") {}
                            putJsonArray("bundleList") {}
                        },
                    )
                }
            },
        )
        put("tInfo", buildTerminalInfo(instanceId, timestamp))
    }.toString()

    fun buildDetailBody(
        packageName: String,
        instanceId: String,
        timestamp: Long,
    ): String = buildJsonObject {
        putJsonArray("pNames") { add(packageName) }
        put("tInfo", buildTerminalInfo(instanceId, timestamp))
    }.toString()

    fun parseUpdateResponse(
        body: String,
        installedByPkg: Map<String, LocalInstalledApp>,
    ): Map<String, StoreReleaseInfo> {
        val root = json.parseToJsonElement(body).jsonObject
        val errorCode = root["errorCode"]?.let { requireNotNull((it as? JsonPrimitive)?.longOrNull) { "Invalid Honor result code" } }
        if (errorCode != null && errorCode != 0L) throw StoreResponseException(errorCode)
        val data = root["data"] as? JsonObject
        val list = root["appLst"] ?: data?.get("appLst") ?: root["apps"] ?: data?.get("apps")
        if (list == null || list == JsonNull) {
            require(errorCode == 0L) { "Missing Honor update list" }
            return emptyMap()
        }
        require(list is JsonArray) { "Invalid Honor update list" }
        val out = LinkedHashMap<String, StoreReleaseInfo>()
        for (item in list) {
            val obj = item as? JsonObject ?: continue
            val pkg = obj.str("pName")
            val installed = installedByPkg[pkg] ?: continue
            val versionCode = obj.releaseVersionCode()
            if (versionCode <= installed.versionCode) continue
            val versionName = obj.str("verName").ifBlank { obj.str("versionName") }.ifBlank { versionCode.toString() }
            val size = obj.long("fileSize").takeIf { it > 0L } ?: obj.long("size")
            val sha256 = obj.baseApkHash().orEmpty()
            val changelog = obj.str("verUptDes").ifBlank { obj.str("newFeatures") }
            out[pkg] = StoreReleaseInfo(
                storeId = StoreCatalog.ID_HONOR,
                versionCode = versionCode,
                versionName = versionName,
                sizeBytes = size,
                changelog = changelog,
                checksum = sha256,
            )
        }
        return out
    }

    data class DetailApkCandidate(
        val isBase: Boolean,
        val directUrl: String,
        val metaPath: String,
        val sizeBytes: Long,
        val sha256: String?,
    )

    fun buildDispatchBody(
        packageName: String,
        metaPath: String,
        sizeBytes: Long,
        instanceId: String,
        timestamp: Long,
    ): String = buildJsonObject {
        put("biz", "AppmarketUpdateDownload")
        putJsonObject("downloadObject") {
            put("name", packageName)
            put("path", metaPath)
            put("size", sizeBytes)
        }
        putJsonObject("deviceInfo") {
            put("deviceId", "")
            put("ip", "127.0.0.1")
            put("accept", "http")
        }
        putJsonObject("scene") { put("code", "") }
        put("tInfo", buildTerminalInfo(instanceId, timestamp))
    }.toString()

    fun parseDispatchHttpsUrl(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val data = root["data"]
        val list = when (data) {
            is JsonObject -> data["uriList"] as? JsonArray
            is JsonArray -> data
            else -> null
        } ?: return null
        for (el in list) {
            val raw = when (el) {
                is JsonPrimitive -> el.contentOrNull.orEmpty()
                is JsonObject -> el.str("link").ifBlank { el.str("uri") }.ifBlank { el.str("url") }
                else -> ""
            }
            val url = StoreHttp.upgradeToHttps(raw.trim())
            if (url.startsWith("https://", ignoreCase = true)) return url
        }
        return null
    }

    fun parseDetailCandidates(body: String, packageName: String, expectedVersion: Long? = null): List<DetailApkCandidate> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val data = root["data"] as? JsonObject ?: root
        val apps = (data["apps"] as? JsonArray) ?: (data["appLst"] as? JsonArray) ?: return emptyList()
        val appObj = apps.mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("pName").equals(packageName, ignoreCase = true) }
            ?: return emptyList()

        val version = appObj.releaseVersionCode()
        if (expectedVersion != null && version > 0L && expectedVersion != version) return emptyList()
        val apks = (appObj["apks"] as? JsonArray).orEmpty()
        if (apks.isNotEmpty()) {
            val parsed = apks.mapIndexed { index, raw ->
                val entry = raw as? JsonObject ?: return emptyList()
                val type = entry.str("bundle_type").ifBlank { entry.str("bundleType") }
                val isBase = type.equals("base", ignoreCase = true) || type.toIntOrNull() == 0 || (index == 0 && type.isEmpty())
                val rawUrl = entry.str("downUrl").ifBlank { entry.str("url") }
                val url = StoreHttp.upgradeToHttps(if (isBase && rawUrl.isBlank()) appObj.str("downUrl") else rawUrl)
                val metaPath = entry.str("downloadUrlMetaPath").ifBlank { if (isBase) appObj.str("downloadUrlMetaPath") else "" }
                if (url.isBlank() && metaPath.isBlank()) return emptyList()
                val size = entry.long("fileSize").takeIf { it > 0L } ?: entry.long("size").takeIf { it > 0L }
                    ?: if (apks.size == 1 && isBase) appObj.long("fileSize") else 0L
                val partHash = entry.sha256("fileIdentifier")
                val sha = if (isBase) appObj.sha256("apkIdentifier") ?: partHash ?: appObj.sha256("fileIdentifier") else partHash
                DetailApkCandidate(
                    isBase = isBase,
                    directUrl = url,
                    metaPath = metaPath,
                    sizeBytes = size,
                    sha256 = sha,
                )
            }
            if (parsed.isNotEmpty()) return parsed
        }

        val directUrl = StoreHttp.upgradeToHttps(appObj.str("downUrl"))
        val metaPath = appObj.str("downloadUrlMetaPath")
        if (directUrl.isBlank() && metaPath.isBlank()) return emptyList()
        val size = appObj.long("fileSize").takeIf { it > 0L } ?: appObj.long("size")
        val sha = appObj.baseApkHash()
        return listOf(
            DetailApkCandidate(
                isBase = true,
                directUrl = directUrl,
                metaPath = metaPath,
                sizeBytes = size,
                sha256 = sha,
            ),
        )
    }

    fun parseDetailDownload(body: String, packageName: String): StoreDownloadSpec? {
        val candidates = parseDetailCandidates(body, packageName)
        if (candidates.isEmpty() || candidates.any { it.directUrl.isBlank() }) return null
        val base = candidates.singleOrNull { it.isBase } ?: return null
        val splits = candidates.filter { it !== base }.mapIndexed { idx, c ->
            StoreDownloadPart(
                name = "split_${idx + 1}.apk",
                url = c.directUrl,
                sizeBytes = c.sizeBytes,
                expectedHash = c.sha256?.let { ExpectedHash(HashAlgorithm.SHA256, it) },
            )
        }
        return StoreDownloadSpec(
            url = base.directUrl,
            sizeBytes = base.sizeBytes,
            expectedHash = base.sha256?.let { ExpectedHash(HashAlgorithm.SHA256, it) },
            extraParts = splits,
        )
    }

    fun randomTraceId(): String = ByteArray(16).also(Random.Default::nextBytes).toHexLower()

    private fun buildTerminalInfo(instanceId: String, timestamp: Long): JsonObject {
        val randomHex = sha256Hex("honor-random:$instanceId")
        val randomUuid = "${randomHex.take(8)}-${randomHex.substring(8, 12)}-${randomHex.substring(12, 16)}-" +
            "${randomHex.substring(16, 20)}-${randomHex.substring(20, 32)}"
        return buildJsonObject {
            put("accessToken", "")
            put("activationFlag", -1)
            put("ageLimit", 3)
            put("androidId", sha256Hex("honor-android:$instanceId").take(16))
            put("androidApiVersion", android.os.Build.VERSION.SDK_INT.toString())
            put("apkVer", APK_VER.toInt())
            put("apkVerName", APK_VER_NAME)
            put("carrier", "Unknown")
            put("chId", "HONOR_01")
            putJsonObject("compatibilityVar") {
                put("cpuAbi", StoreDevice.primaryAbi)
                put("bintranslatorEnable", -1)
                put("cpuAbilist", StoreDevice.abis.joinToString(","))
                put("hasHCore", 1)
            }
            putJsonArray("compressScenes") { add(1) }
            put("connectionType", 2)
            put("cpu", StoreDevice.primaryAbi)
            put("deliveryCountry", "cn")
            put("deviceMode", 2)
            put("dpi", StoreDevice.densityDpi.toString())
            put("hman", "HONOR")
            put("htype", "PGS-AN00")
            put("isDark", false)
            put("isDemoType", 0)
            put("isH5", 0)
            put("isMonkey", 0)
            put("isParallelSpace", 1)
            put("isPersonalRecommend", true)
            put("isRecommend", false)
            put("kidsMode", false)
            put("language", "zh-CN")
            put("magicVersion", "9.0.0")
            put("marketType", 0)
            put("miniGameInstall", 0)
            put("netType", 3)
            put("os", 0)
            put("osVer", android.os.Build.VERSION.RELEASE.orEmpty())
            put("pName", CLIENT_PACKAGE)
            put("patchSdkVersion", 0)
            put("randomId", randomUuid)
            put("requestSource", 1)
            put("resolution", "1080x2400")
            put("source", "1")
            put("spreadModelName", "")
            put("subTerminalType", "1_1")
            put("supportGms", 0)
            put("telecomOper", "")
            put("terminalType", 1)
            put("timestamp", timestamp)
            put("udid", sha256Hex("honor-udid:$instanceId"))
            put("userId", "")
            put("userType", "-1")
            put("oaid", "")
        }
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).toHexLower()

    private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

    private fun JsonObject.sha256(key: String): String? = str(key).lowercase().takeIf(sha256Regex::matches)

    private fun JsonObject.baseApkHash(): String? =
        sha256("apkIdentifier") ?: sha256("fileIdentifier")

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L

    private fun JsonObject.releaseVersionCode(): Long =
        long("verCode").takeIf { it > 0L } ?: long("ver").takeIf { it > 0L } ?: long("versionCode")
}
