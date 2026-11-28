package io.candy.tick.data.store.samsung

import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreHttp
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 三星应用商店 XML 协议：批量检查更新（getUpdateList / 2389）与下载解析（downloadEx2 / 2311）。
 * 纯 Kotlin 实现，不依赖 Android XmlPullParser，便于 JVM 单元测试覆盖。
 */
internal object SamsungProtocol {

    const val ENDPOINT_BASE = "https://cn-ms.galaxyappstore.com/ods.as"
    const val REQ_UPDATE_LIST = "2389"
    const val REQ_DOWNLOAD = "2311"
    const val BATCH_SIZE = 80

    private const val STDUK_SALT = "kjk3syk6wkj5"
    private const val TX_SALT = "GalaxyApps"
    private val dayFmt = DateTimeFormatter.ofPattern("dd").withZone(ZoneOffset.UTC)
    private val hourFmt = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC)

    fun endpointUrl(reqId: String): String = "$ENDPOINT_BASE?reqId=$reqId&ot=01&ct=B"

    fun computeStduk(installId: String): String = sha256Hex(installId + STDUK_SALT)

    fun computeTransactionId(stduk: String, epochMillis: Long = System.currentTimeMillis()): String {
        val instant = Instant.ofEpochMilli(epochMillis)
        val day = dayFmt.format(instant)
        val hour = hourFmt.format(instant)
        return day + sha256Hex(stduk + hour + TX_SALT).take(7)
    }

    fun buildUpdateXml(
        apps: List<LocalInstalledApp>,
        installId: String,
        epochMillis: Long = System.currentTimeMillis(),
    ): String {
        val stduk = computeStduk(installId)
        val loadApp = apps.joinToString("||") { app ->
            if (app.isSystemApp) "${app.packageName}@@${app.versionCode}@0@@N"
            else "${app.packageName}@@${app.versionCode}@1@N"
        }
        return buildEnvelope(
            reqId = REQ_UPDATE_LIST,
            name = "getUpdateList",
            transactionId = computeTransactionId(stduk, epochMillis),
            params = listOf(
                "loadApp" to loadApp,
                "predeployed" to "0",
                "justForCount" to "N",
                "autoUpdateYN" to "N",
                "imgWidth" to "135",
                "imgHeight" to "135",
                "extuk" to installId,
                "stduk" to stduk,
                "imei" to installId,
            ),
        )
    }

    fun buildDownloadXml(
        app: LocalInstalledApp,
        productId: String,
        installId: String,
        epochMillis: Long = System.currentTimeMillis(),
    ): String {
        val stduk = computeStduk(installId)
        return buildEnvelope(
            reqId = REQ_DOWNLOAD,
            name = "downloadEx2",
            transactionId = computeTransactionId(stduk, epochMillis),
            params = listOf(
                "GUID" to app.packageName,
                "productID" to productId,
                "versionCode" to app.versionCode.toString(),
                "dowloadType" to "new",
                "autoUpdateYN" to "N",
                "deepLinkSource" to "N",
                "predeployed" to if (app.isSystemApp) "1" else "0",
                "resumeYN" to "N",
                "loadType" to if (app.isSystemApp) "0" else "1",
                "extuk" to installId,
                "stduk" to stduk,
                "imei" to installId,
            ),
        )
    }

    private fun buildEnvelope(
        reqId: String,
        name: String,
        transactionId: String,
        params: List<Pair<String, String>>,
    ): String = buildString(512) {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<SamsungProtocol networkType=\"1\" version2=\"4.6.08.11\" lang=\"CHI\" ")
        append("openApiVersion=\"35\" deviceModel=\"SM-S9280\" mcc=\"460\" mnc=\"00\" ")
        append("csc=\"CHC\" odcVersion=\"4.6.08.11\" version=\"7.8\" ")
        append("filter=\"1\" odcType=\"01\" systemId=\"1700000000000\" ")
        append("abiType=\"64\" pd=\"0\" wear=\"0\">")
        append("<request name=\"").append(escapeXml(name))
        append("\" id=\"").append(escapeXml(reqId))
        append("\" numParam=\"").append(params.size)
        append("\" transactionId=\"").append(escapeXml(transactionId)).append("\">")
        for ((k, v) in params) {
            append("<param name=\"").append(escapeXml(k)).append("\">")
            append(escapeXml(v))
            append("</param>")
        }
        append("</request></SamsungProtocol>")
    }

    data class UpdateEntry(
        val release: StoreReleaseInfo,
        val productId: String,
    )

    fun parseUpdateResponse(
        xml: String,
        installedByPkg: Map<String, LocalInstalledApp>,
    ): Map<String, UpdateEntry> {
        val lists = parseListValues(xml)
        val out = LinkedHashMap<String, UpdateEntry>()
        for (values in lists) {
            val pkg = (values["GUID"] ?: values["packageName"]).orEmpty().trim()
            val installed = installedByPkg[pkg] ?: continue
            val versionCode = values["versionCode"]?.toLongOrNull() ?: continue
            if (versionCode <= installed.versionCode) continue
            val productId = values["productID"].orEmpty().trim()
            val versionName = values["version"].orEmpty().trim().ifBlank { versionCode.toString() }
            val size = values["realContentSize"]?.toLongOrNull()
                ?: values["contentSize"]?.toLongOrNull()
                ?: 0L
            val release = StoreReleaseInfo(
                storeId = StoreCatalog.ID_SAMSUNG,
                versionCode = versionCode,
                versionName = versionName,
                sizeBytes = size,
                appId = productId.toLongOrNull() ?: 0L,
            )
            out[pkg] = UpdateEntry(release = release, productId = productId)
        }
        return out
    }

    data class DownloadResult(
        val url: String,
        val sizeBytes: Long,
    )

    fun parseDownloadResponse(xml: String): DownloadResult? {
        val values = parseListValues(xml).firstOrNull() ?: parseFlatValues(xml)
        val rawUrl = values["downLoadURI"] ?: values["downloadURI"] ?: return null
        val url = StoreHttp.upgradeToHttps(rawUrl.trim())
        if (url.isBlank()) return null
        val size = values["fileSize"]?.toLongOrNull()
            ?: values["realContentSize"]?.toLongOrNull()
            ?: 0L
        return DownloadResult(url = url, sizeBytes = size)
    }

    /** 轻量 XML 解析：提取每个 `<list>` 节点下的 `<value name="...">...</value>` 键值对。 */
    internal fun parseListValues(raw: String): List<Map<String, String>> {
        val lists = mutableListOf<Map<String, String>>()
        val listRegex = Regex("<list\\b[^>]*>(.*?)</list>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (match in listRegex.findAll(raw)) {
            val map = parseFlatValues(match.groupValues[1])
            if (map.isNotEmpty()) lists += map
        }
        return lists
    }

    private fun parseFlatValues(fragment: String): Map<String, String> {
        val valueRegex = Regex(
            "<value\\b[^>]*\\bname\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</value>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val map = LinkedHashMap<String, String>()
        for (m in valueRegex.findAll(fragment)) {
            val key = decodeXml(m.groupValues[1])
            if (key !in map) {
                val rawText = m.groupValues[2].trim()
                val unwrapped = if (rawText.startsWith("<![CDATA[") && rawText.endsWith("]]>")) {
                    rawText.substring(9, rawText.length - 3)
                } else {
                    decodeXml(rawText)
                }
                map[key] = unwrapped.trim()
            }
        }
        return map
    }

    private fun escapeXml(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            append(
                when (ch) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> ch
                },
            )
        }
    }

    private fun decodeXml(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
