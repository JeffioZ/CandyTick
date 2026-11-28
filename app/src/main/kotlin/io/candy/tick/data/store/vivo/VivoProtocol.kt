package io.candy.tick.data.store.vivo

import android.os.Build
import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.download.ExpectedHash
import io.candy.tick.data.download.HashAlgorithm
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.matchesArtifactChecksum
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * vivo 应用商店批量更新协议：单次最多 100 个包，返回新版与 302 下载入口。
 */
internal object VivoProtocol {
    const val UPDATE_URL = "https://update.appstore.vivo.com.cn/port/packages_update/"
    const val DETAIL_URL = "https://main.appstore.vivo.com.cn/port/packageList/"
    const val MAX_BATCH = 100
    const val APP_VERSION = "61510"
    private val md5Regex = Regex("^[a-fA-F0-9]{32}$")

    fun userAgent(): String =
        "Dalvik/2.1.0 (Linux; U; Android ${Build.VERSION.RELEASE.ifBlank { "15" }}; ${Build.MODEL.ifBlank { "V2309A" }})"

    fun buildUpdateForm(apps: List<LocalInstalledApp>): String {
        val packages = apps.joinToString(",") { "${it.packageName}|${it.versionCode.coerceAtLeast(0L)}|0" }
        val fields = linkedMapOf(
            "packages" to packages,
            "querySource" to "2",
            "dbversion" to "0",
            "downgrade" to "0",
            "n" to "0",
            "suggest64Bit" to "1",
            "gpInstalled" to "false",
            "gpLogin" to "false",
            "secondInstallList" to "",
            "patch_sup" to "2",
            "cpuInfo" to (Build.SUPPORTED_ABIS.joinToString(",").ifBlank { "arm64-v8a" }),
            "app_version" to APP_VERSION,
            "appversion" to APP_VERSION,
            "build_number" to APP_VERSION,
            "model" to (Build.MODEL.ifBlank { "V2309A" }),
            "deviceType" to (Build.DEVICE.ifBlank { "PD2309" }),
            "av" to Build.VERSION.SDK_INT.toString(),
            "an" to (Build.VERSION.RELEASE.ifBlank { "15" }),
            "android_version" to Build.VERSION.SDK_INT.toString(),
            "android_name" to (Build.VERSION.RELEASE.ifBlank { "15" }),
            "sys_build_id" to (Build.ID.ifBlank { "UP1A.231005.007" }),
            "density" to "3.0",
            "screensize" to "1080_2400",
            "mfr" to "vivo",
            "pictype" to "webp",
            "cs" to "0",
        )
        return fields.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    fun parseUpdateResponse(rawJson: String, installed: Map<String, LocalInstalledApp>): Map<String, StoreReleaseInfo> {
        val root = Json.parseToJsonElement(rawJson) as JsonObject
        val status = root["code"] ?: root["retcode"]
        require(status == null || (status as? JsonPrimitive)?.longOrNull == 0L) { "vivo update request failed" }
        val list = root["value"]
        if (list == null || list == JsonNull) {
            require(status != null) { "Missing vivo update list" }
            return emptyMap()
        }
        require(list is JsonArray) { "Invalid vivo update list" }
        val out = mutableMapOf<String, StoreReleaseInfo>()
        for (el in list) {
            val item = el as? JsonObject ?: continue
            val pkg = item.str("package_name").trim()
            val local = installed[pkg] ?: continue
            if (item.num("problemLevel") >= 1L) continue
            val code = item.num("version_code")
            if (code <= local.versionCode || code <= (out[pkg]?.versionCode ?: 0L)) continue
            val url = StoreHttp.upgradeToHttps(item.str("download_url"))
            val appId = item.num("id")
            if (url.isBlank() && appId <= 0L) continue
            val md5 = item.str("md5").ifBlank { item.str("originalMd5") }.lowercase()
                .takeIf(md5Regex::matches).orEmpty()
            out[pkg] = StoreReleaseInfo(
                storeId = StoreCatalog.ID_VIVO,
                versionCode = code,
                versionName = item.str("version_name").ifBlank { code.toString() },
                sizeBytes = item.num("size") * 1024L,
                changelog = item.str("update_des").trim(),
                appId = appId,
                downloadUrl = url,
                checksum = md5,
            )
        }
        return out
    }

    fun downloadDetail(body: String, packageName: String, release: StoreReleaseInfo): StoreReleaseInfo {
        val root = Json.parseToJsonElement(body) as JsonObject
        val code = (root["code"] ?: root["retcode"]) as? JsonPrimitive
        if (code?.longOrNull != 0L) throw SourceException(SourceError.NotFound)
        // The update endpoint can serve preinstalled apps that are absent from packageList.
        val value = root["value"]
        if (value == null || value == JsonNull) return release
        val items = (value as JsonObject)["appInfoList"]
        if (items == null || items == JsonNull) return release
        val item = (items as JsonArray).filterIsInstance<JsonObject>().firstOrNull { it.num("id") == release.appId } ?: return release
        val pkg = item.str("package_name")
        val version = item.num("version_code")
        if (item.num("problemLevel") >= 1L || (pkg.isNotBlank() && pkg != packageName) ||
            (version > 0L && version != release.versionCode)) throw SourceException(SourceError.NotFound)
        val url = StoreHttp.upgradeToHttps(item.str("download_url")).ifBlank { throw SourceException(SourceError.NotFound) }
        val md5 = item.str("md5").lowercase().takeIf(md5Regex::matches) ?: release.checksum
        if (!release.matchesArtifactChecksum(md5)) throw SourceException(SourceError.NotFound)
        return release.copy(downloadUrl = url, checksum = md5)
    }

    fun expectedMd5(checksum: String): ExpectedHash? =
        checksum.takeIf(md5Regex::matches)?.let { ExpectedHash(HashAlgorithm.MD5, it) }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.num(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
}
