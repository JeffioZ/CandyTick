package io.candy.tick.data.store.samsung

import android.os.Build
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.StoreResponseException
import java.io.StringReader
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * 三星应用商店 XML 协议：批量检查更新（getUpdateList / 2389）与下载解析（downloadEx2 / 2311）。
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
        includeInstalledVersion: Boolean = true,
    ): String {
        val stduk = computeStduk(installId)
        return buildEnvelope(
            reqId = REQ_DOWNLOAD,
            name = "downloadEx2",
            transactionId = computeTransactionId(stduk, epochMillis),
            params = listOfNotNull(
                "GUID" to app.packageName,
                "productID" to productId,
                if (includeInstalledVersion) "versionCode" to app.versionCode.toString() else null,
                "dowloadType" to "new",
                "autoUpdateYN" to "N",
                "deepLinkSource" to "N",
                "predeployed" to if (includeInstalledVersion && app.isSystemApp) "1" else "0",
                "resumeYN" to "N",
                if (includeInstalledVersion) "loadType" to (if (app.isSystemApp) "0" else "1") else null,
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
        val sdk = Build.VERSION.SDK_INT.takeIf { it > 0 } ?: 35
        val abi = if (Build.SUPPORTED_64_BIT_ABIS?.isEmpty() == true) 32 else 64
        append("openApiVersion=\"$sdk\" deviceModel=\"SM-S9280\" mcc=\"460\" mnc=\"00\" ")
        append("csc=\"CHC\" odcVersion=\"4.6.08.11\" version=\"7.8\" ")
        append("filter=\"1\" odcType=\"01\" systemId=\"1700000000000\" ")
        append("abiType=\"$abi\" pd=\"0\" wear=\"0\">")
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
        val out = LinkedHashMap<String, UpdateEntry>()
        for (values in responseRows(xml)) {
            val pkg = (values["GUID"] ?: values["packageName"]).orEmpty().trim()
            val installed = installedByPkg[pkg] ?: continue
            val versionCode = values["versionCode"]?.toLongOrNull() ?: continue
            if (versionCode <= installed.versionCode) continue
            val productId = values["productID"].orEmpty().trim()
            val versionName = values["version"].orEmpty().trim().ifBlank { versionCode.toString() }
            val exactSize = (values["realContentSize"] ?: values["realContentsSize"])?.toLongOrNull()?.takeIf { it > 0L }
            val size = exactSize ?: (values["contentSize"] ?: values["contentsSize"])?.toLongOrNull() ?: 0L
            val release = StoreReleaseInfo(
                storeId = StoreCatalog.ID_SAMSUNG,
                versionCode = versionCode,
                versionName = versionName,
                sizeBytes = size,
                exactSizeBytes = exactSize,
                appId = productId.toLongOrNull() ?: 0L,
            )
            out[pkg] = UpdateEntry(release = release, productId = productId)
        }
        return out
    }

    data class DownloadResult(
        val url: String,
        val sizeBytes: Long,
        val exactSizeBytes: Long?,
    )

    fun parseDownloadResponse(xml: String, expectedVersion: Long? = null): DownloadResult? {
        val values = responseRows(xml).firstOrNull() ?: return null
        val version = values["versionCode"]?.toLongOrNull()
        if (version != null && version > 0L && expectedVersion != null && version != expectedVersion) return null
        val rawUrl = values["downLoadURI"] ?: return null
        val url = StoreHttp.upgradeToHttps(rawUrl.trim())
        if (url.isBlank()) return null
        val exactSize = (values["fileSize"] ?: values["realContentSize"] ?: values["realContentsSize"])
            ?.toLongOrNull()?.takeIf { it > 0L }
        val size = exactSize ?: (values["contentsSize"] ?: values["installSize"])?.toLongOrNull() ?: 0L
        return DownloadResult(url = url, sizeBytes = size, exactSizeBytes = exactSize)
    }

    private fun responseRows(xml: String): List<Map<String, String>> {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "Unexpected Galaxy Store document type" }
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml))).documentElement
        val response = if (root.tagName.substringAfter(':').equals("response", true)) root else root.elements("response").firstOrNull()
        requireNotNull(response) { "Missing Galaxy Store response" }
        val code = requireNotNull(response.attr("returnCode").toLongOrNull()) { "Invalid Galaxy Store result code" }
        if (code != 0L) {
            val error = response.elements("errorString").firstOrNull()
            throw StoreResponseException(error?.attr("errorCode")?.toLongOrNull() ?: code)
        }
        return response.elements("list").map(::namedValues).toList().ifEmpty { listOf(namedValues(response)) }
    }

    private fun Element.elements(tag: String): Sequence<Element> {
        val nodes = getElementsByTagName("*")
        return (0 until nodes.length).asSequence().map { nodes.item(it) as Element }
            .filter { it.tagName.substringAfter(':').equals(tag, true) }
    }

    private fun Element.attr(name: String): String {
        if (hasAttribute(name)) return getAttribute(name)
        for (i in 0 until attributes.length) {
            val attr = attributes.item(i)
            if (attr.nodeName.equals(name, true)) return attr.nodeValue
        }
        return ""
    }

    private fun namedValues(record: Element): Map<String, String> {
        val fields = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        for (value in record.elements("value")) {
            fields.putIfAbsent(value.attr("name"), value.textContent.trim())
        }
        return fields
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

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
