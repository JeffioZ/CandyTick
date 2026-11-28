package io.candy.tick.data.store.huawei

import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDevice
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.StoreResponseException
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 华为应用市场协议：GZIP 压缩表单请求、会话握手（client.front2）与批量更新检查（client.diffUpgrade2）。
 */
internal object HuaweiProtocol {

    const val GATEWAY_URL = "https://store-drcn.hispace.dbankcloud.com/hwmarket/api/clientApi"
    const val USER_AGENT = "Android/1.0"
    const val BATCH_SIZE = 50

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val sha256Regex = Regex("^[a-f0-9]{64}$")

    fun deriveDeviceId(seed: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun buildFrontFields(
        deviceId: String,
        epochMillis: Long = System.currentTimeMillis(),
    ): Map<String, String> = baseFields(
        method = "client.front2",
        ver = "1.1",
        sign = "",
        deviceId = deviceId,
        epochMillis = epochMillis,
    )

    fun buildDiffUpgradeFields(
        apps: List<LocalInstalledApp>,
        deviceId: String,
        sessionSign: String,
        epochMillis: Long = System.currentTimeMillis(),
    ): Map<String, String> {
        val paramsJson = buildJsonObject {
            put(
                "params",
                buildJsonArray {
                    for (app in apps) {
                        add(
                            buildJsonObject {
                                put("package", app.packageName)
                                put("versionCode", app.versionCode)
                                put("oldVersion", app.versionName.ifBlank { app.versionCode.toString() })
                                put("targetSdkVersion", app.targetSdkVersion)
                                put("fSha2", "")
                                put("appBits", systemBits)
                                put("pkgMode", 0)
                                put("isPre", if (app.isSystemApp) 2 else 0)
                                put("maple", 0)
                                put("installationFree", 0)
                                put("shellApkVer", 0)
                            },
                        )
                    }
                },
            )
        }.toString()

        val fields = baseFields(
            method = "client.diffUpgrade2",
            ver = "1.2",
            sign = sessionSign,
            deviceId = deviceId,
            epochMillis = epochMillis,
        ).toMutableMap()
        fields["json"] = paramsJson
        fields["supportDiffTypes"] = "[0]"
        fields["maxMem"] = "4194304"
        fields["isWlanIdle"] = "0"
        fields["isFetchAllGxxApps"] = "0"
        fields["installCheck"] = "0"
        fields["isFullUpgrade"] = "0"
        return fields
    }

    fun gzipEncodeForm(fields: Map<String, String>): ByteArray {
        val form = fields.entries.sortedBy { it.key }.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(form.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    fun parseSessionSign(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return root.str("sign").takeIf { it.isNotBlank() }
    }

    fun isSessionExpired(body: String): Boolean {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return false
        val code = root.int("rtnCode")
        return code == -401 || code == 1001 || code == 401
    }

    data class UpdateEntry(
        val release: StoreReleaseInfo,
        val downloadUrl: String,
    )

    fun parseDiffUpgradeResponse(
        body: String,
        installedByPkg: Map<String, LocalInstalledApp>,
    ): Map<String, UpdateEntry> {
        val root = json.parseToJsonElement(body).jsonObject
        val code = root["rtnCode"]?.let { requireNotNull((it as? JsonPrimitive)?.longOrNull) { "Invalid Huawei result code" } }
        if (code != null && code != 0L) throw StoreResponseException(code)
        val list = root["list"]
        val nonRecommended = root["notRcmList"]
        require((list == null || list is JsonArray || list == JsonNull) &&
            (nonRecommended == null || nonRecommended is JsonArray || nonRecommended == JsonNull)) {
            "Invalid Huawei update lists"
        }
        require(list is JsonArray || nonRecommended is JsonArray || (code == 0L &&
            (list == JsonNull || nonRecommended == JsonNull || root["toUpdateAppInfoList"] is JsonArray ||
                root["allGxxApps"] is JsonArray || root["sharedLibs"] is JsonArray ||
                root["updatePolicyText"] is JsonPrimitive))) { "Missing Huawei update response" }
        val items = buildList {
            (root["list"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(::add) }
            (root["notRcmList"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(::add) }
        }
        val out = LinkedHashMap<String, UpdateEntry>()
        for (obj in items) {
            val pkg = obj.str("package").ifBlank { obj.str("packageName") }
            val installed = installedByPkg[pkg] ?: continue
            val versionCode = obj.long("versionCode")
            if (versionCode <= installed.versionCode) continue
            val versionName = obj.str("version").ifBlank { obj.str("appVersionName") }.ifBlank { obj.str("versionName") }.ifBlank { versionCode.toString() }
            val fullUrl = obj.str("fullDownUrl")
            val isDiff = obj.int("isDiff") != 0 || obj.long("diffSize") > 0L
            if (obj.int("btnDisable") != 0 || obj.int("packingType") != 0 || obj.long("bundleSize") > 0L) continue
            val size = obj.long("fullSize").takeIf { it > 0L } ?: if (isDiff) 0L else obj.long("size")
            val url = StoreHttp.upgradeToHttps(fullUrl.ifBlank { if (isDiff) "" else obj.str("downurl") })
            if (url.isBlank()) continue
            val sha256 = obj.str("sha256").lowercase().takeIf { sha256Regex.matches(it) }.orEmpty()
            val signerSha256s = (obj["sSha2"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() }
                ?.filter { sha256Regex.matches(it) }
                .orEmpty()
            val changelog = obj.str("newFeatures")
            val release = StoreReleaseInfo(
                storeId = StoreCatalog.ID_HUAWEI,
                versionCode = versionCode,
                versionName = versionName,
                sizeBytes = size,
                exactSizeBytes = (if (fullUrl.isBlank()) size else obj.long("fullSize")).takeIf { it > 0L },
                changelog = changelog,
                checksum = sha256,
                signerSha256s = signerSha256s,
            )
            out.putIfAbsent(pkg, UpdateEntry(release = release, downloadUrl = url))
        }
        return out
    }

    private fun baseFields(
        method: String,
        ver: String,
        sign: String,
        deviceId: String,
        epochMillis: Long,
    ): Map<String, String> = linkedMapOf(
        "method" to method,
        "ver" to ver,
        "clientPackage" to "com.huawei.appmarket",
        "clientVersionCode" to "160602300",
        "packageName" to "com.huawei.appmarket",
        "thirdPartyPkg" to "com.huawei.appmarket",
        "version" to "16.6.2.300",
        "versionCode" to "160602300",
        "channelId" to "background",
        "cno" to "4010001",
        "code" to "0200",
        "locale" to "zh_CN",
        "manufacturer" to "HUAWEI",
        "brand" to "HUAWEI",
        "phoneType" to "NOH-AN00",
        "firmwareVersion" to "12",
        "osBrand" to "emui",
        "sysBits" to systemBits.toString(),
        "accountZone" to "CN",
        "lastPhyZone" to "CN",
        "needServiceZone" to "0",
        "zone" to "1",
        "serviceType" to "0",
        "net" to "1",
        "runMode" to "2",
        "recommendSwitch" to "1",
        "adSwitch" to "0",
        "agCapability" to "1111",
        "deviceSpecParams" to buildJsonObject {
            put("abis", StoreDevice.abis.joinToString(","))
            put("dpi", StoreDevice.densityDpi.toString())
            put("preferLan", "zh")
        }.toString(),
        "deviceId" to deviceId,
        "deviceIdType" to "9",
        "deviceIdRealType" to "4",
        "oaidTrack" to "-2",
        "ts" to epochMillis.toString(),
    ).apply {
        if (sign.isNotBlank()) put("sign", sign)
    }

    private val systemBits: Int get() = if (android.os.Build.SUPPORTED_64_BIT_ABIS?.isEmpty() == true) 1 else 2

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L
}
