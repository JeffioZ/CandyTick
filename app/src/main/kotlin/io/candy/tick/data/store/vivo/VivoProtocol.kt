package io.candy.tick.data.store.vivo

import android.os.Build
import io.candy.tick.data.download.ExpectedHash
import io.candy.tick.data.download.HashAlgorithm
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreHttp
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * vivo 应用商店批量更新协议：单次最多 100 个包，返回新版与 302 下载入口。
 */
internal object VivoProtocol {
    const val UPDATE_URL = "https://update.appstore.vivo.com.cn/port/packages_update/"
    const val MAX_BATCH = 100
    private const val APP_VERSION = "61510"
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
        val root = runCatching { Json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return emptyMap()
        val list = root["value"] as? JsonArray ?: return emptyMap()
        val out = mutableMapOf<String, StoreReleaseInfo>()
        for (el in list) {
            val item = el as? JsonObject ?: continue
            val pkg = item.str("package_name").trim()
            val local = installed[pkg] ?: continue
            val code = item.num("version_code")
            if (pkg.isBlank() || code <= local.versionCode) continue
            val url = StoreHttp.upgradeToHttps(item.str("download_url"))
            if (url.isBlank()) continue
            val md5 = item.str("md5").ifBlank { item.str("originalMd5") }.lowercase()
                .takeIf(md5Regex::matches).orEmpty()
            out[pkg] = StoreReleaseInfo(
                storeId = StoreCatalog.ID_VIVO,
                versionCode = code,
                versionName = item.str("version_name").ifBlank { code.toString() },
                sizeBytes = item.num("size") * 1024L,
                changelog = item.str("update_des").trim(),
                appId = item.num("id"),
                downloadUrl = url,
                checksum = md5,
            )
        }
        return out
    }

    fun expectedMd5(checksum: String): ExpectedHash? =
        checksum.takeIf(md5Regex::matches)?.let { ExpectedHash(HashAlgorithm.MD5, it) }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.num(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
}
