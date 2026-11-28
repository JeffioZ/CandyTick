package io.candy.tick.data.store.oppo

import io.candy.tick.data.download.ExpectedHash
import io.candy.tick.data.download.HashAlgorithm
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDownloadPart
import io.candy.tick.data.store.StoreDownloadSpec
import io.candy.tick.data.store.StoreHttp
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import kotlin.math.abs

/**
 * OPPO 软件商店协议：Protobuf/Protostuff 编解码、双阶段请求头签名（sign / sg）与单包/分包解析。
 */
internal object OppoProtocol {

    const val BASE_URL = "https://api-cn.store.heytapmobi.com"
    const val UPDATE_URL = "$BASE_URL/update/global/v1/check"
    const val CONTENT_TYPE = "application/x2-protostuff; charset=UTF-8"
    const val BATCH_SIZE = 80

    private const val OAK = "cdb09c43063ea6bb"
    private const val SECOND_KEY = "09bdc58acb383220be08f4fe8a43775179"
    private const val OBSCURE_CODE =
        "STORENEWMIICeAIBADANBgkqhkiG9w0BAQEFAASCAmIwggJeAgEAAoGBANYFY/UJGSzhIhpx6YM5KJ9yRHc7YeURxzb9tDvJvMfENHlnP3DtVkOIjERbpsSd76fjtZnMWY60TpGLGyrNkvuV40L15JQhHAo9yURpPQoI0eg3SLFmTEI/MUiPRCwfwYf2deqKKlsmMSysYYHX9JiGzQuWiYZaawxprSuiqDGvAgMBAAECgYEAtQ0QV00gGABISljNMy5aeDBBTSBWG2OjxJhxLRbndZM81OsMFysgC7dq+bUS6ke1YrDWgsoFhRxxTtx/2gDYciGp/c/h0Td5pGw7T9W6zo2xWI5oh1WyTnn0Xj17O9CmOk4fFDpJ6bapL+fyDy7gkEUChJ9+p66WSAlsfUhJ2TECQQD5sFWMGE2IiEuz4fIPaDrNSTHeFQQr/ZpZ7VzB2tcG7GyZRx5YORbZmX1jR7l3H4F98MgqCGs88w6FKnCpxDK3AkEA225CphAcfyiH0ShlZxEXBgIYt3V8nQuc/g2KJtiV6eeFkxmOMHbVTPGkARvt5VoPYEjwPTg43oqTDJVtlWagyQJBAOvEeJLno9aHNExvznyD4/pR4hec6qqLNgMyIYMfHCl6d3UodVvC1HO1/nMPl+4GvuRnxuoBtxj/PTe7AlUbYPMCQQDOkf4sVv58tqslO+I6JNyHy3F5RCELtuMUR6rG5x46FLqqwGQbO8ORq+m5IZHTV/Uhr4h6GXNwDQRh1EpVW0gBAkAp/v3tPI1riz6UuG0I6uf5er26yl5evPyPrjrD299L4Qy/1EIunayC7JYcSGlR01+EDYYgwUkec+QgrRC/NstV"
    private const val CHANNEL = "4101"
    private const val CLIENT_VER_CODE = "122280"
    private const val CLIENT_VER_NAME = "12.22.80"
    private const val PKG_VER = "38"
    private const val STAT_APP_CODE = "210"
    private const val HEX_UPPER = "0123456789ABCDEF"

    private val SECONDARY_KEYS = arrayOf(
        "cab2f5d94d4eea71",
        "3e8d4f1ce5aa6e09",
        "c5ca81b0391663db",
        "517e95007dd031cb",
        "f8730121dba1cf23",
    )
    private val SECONDARY_SALTS = arrayOf(
        "d9275c219e852eb5e60cb5ceccd523b5",
        "6bf7d491e5ab9da2931f91eb4c9a1640",
        "0ca3e6b70c4e0fc4cf53ead33d158e83",
        "464c6045b59a38069a3e585d8bfe3b90",
        "f1184b1998cccddd10aa5b8dd039ef8f",
    )

    fun buildHeaders(
        method: String,
        url: String,
        instanceId: String,
        timestamp: Long = System.currentTimeMillis(),
    ): Map<String, String> {
        val uri = runCatching { URI(url) }.getOrNull()
        val path = uri?.path.orEmpty()
        val query = uri?.query.orEmpty()
        val openId = deriveOpenId(instanceId)
        val base = "oppo/PKB110/35/15/V15.0.0/$STAT_APP_CODE"
        val userAgent = formUrlEncode("$base/$CHANNEL/$CLIENT_VER_CODE/$CLIENT_VER_NAME")
        val ocs = formUrlEncode("$base/PKB110_15.0.0(CN01)/$CLIENT_VER_CODE")
        val signInput = ocs + timestamp + openId + path + query
        val sign = computeSign(signInput)
        val encodedPathQuery = formUrlEncode(path + query)
        val sgInput = (encodedPathQuery + CONTENT_TYPE + method.lowercase() + openId + timestamp).lowercase()
        val sg = computeSecondarySign(sgInput, sign)
        return linkedMapOf(
            "Accept" to CONTENT_TYPE,
            "Content-Type" to CONTENT_TYPE,
            "User-Agent" to userAgent,
            "oak" to OAK,
            "id" to openId,
            "ocs" to ocs,
            "appid" to "oppo#20171#CN",
            "appversion" to CLIENT_VER_NAME,
            "pkg-ver" to PKG_VER,
            "romver" to PKG_VER,
            "t" to timestamp.toString(),
            "sign" to sign,
            "sg" to sg,
        )
    }

    internal fun computeSign(input: String): String {
        val key2 = (OAK + SECOND_KEY.substring(18, 34) + SECOND_KEY.substring(2, 18)).toByteArray(Charsets.UTF_8)
        val payload = key2 + input.toByteArray(Charsets.UTF_8) +
            (input.length + 48).toString().toByteArray(Charsets.UTF_8) +
            OBSCURE_CODE.toByteArray(Charsets.UTF_8)
        return md5(payload).toHexLower()
    }

    internal fun computeSecondarySign(input: String, sign: String): String {
        val first = input + OAK + sign
        val firstBytes = (first + first.toByteArray(Charsets.UTF_8).size).toByteArray(Charsets.UTF_8)
        val checksum = md5(firstBytes).sumOf { abs(it.toInt()) % 10 }
        val digit = checksum % 10
        val idx = digit % SECONDARY_KEYS.size
        val second = input + SECONDARY_KEYS[idx] + SECONDARY_SALTS[idx]
        val secondBytes = (second + second.toByteArray(Charsets.UTF_8).size).toByteArray(Charsets.UTF_8)
        val digest = if (digit < 5) md5(secondBytes) else sha1(secondBytes)
        return digest.toHexLower()
    }

    fun encodeUpgradeWrapRequest(apps: List<LocalInstalledApp>): ByteArray {
        val out = ByteArrayOutputStream()
        for (app in apps) {
            val item = ByteArrayOutputStream()
            writeString(item, 127, "com.heytap.cdo.global.update.domain.UpgradeReqV2")
            writeString(item, 1, app.packageName)
            writeVarintField(item, 2, app.versionCode)
            writeString(item, 3, "0")
            writeVarintField(item, 4, 0L)
            writeVarintField(item, 6, app.targetSdkVersion.toLong())
            val legacySign = app.oppoLegacySignatureMd5.ifBlank { app.signatureMd5 }
            if (legacySign.isNotBlank()) {
                writeString(item, 7, legacySign)
            }
            if (app.signatureMd5.isNotBlank()) {
                writeString(item, 9, app.signatureMd5)
            }
            writeString(item, 10, "")
            writeString(item, 11, if (app.isSystemApp) "PRELOAD" else "")
            writeVarintField(item, 12, 0L)
            writeVarintField(item, 13, 0L)
            writeBytes(out, 1, item.toByteArray())
        }
        writeVarintField(out, 3, 0L)
        writeVarintField(out, 5, 0L)
        return out.toByteArray()
    }

    fun isIncompletePackageUrl(url: String): Boolean =
        url.contains("incfs", ignoreCase = true) || url.substringBefore('?').endsWith(".dm", ignoreCase = true)

    private val metadataPrefixes = listOf(
        "$BASE_URL/download/v2/",
        "https://api-store-gl.heytapmobile.com/download/v2/",
        "https://api-store-gl.heytapmobile.com/download/overseas/v2/",
    )
    private val imageExtension = Regex("\\.(?:png|jpe?g|webp|gif)$", RegexOption.IGNORE_CASE)

    fun isDownloadMetadataUrl(url: String): Boolean {
        val https = StoreHttp.upgradeToHttps(url)
        return metadataPrefixes.any { https.startsWith(it, ignoreCase = true) }
    }

    data class UpdateEntry(
        val release: StoreReleaseInfo,
        val rawUrl: String,
    )

    fun parseUpgradeResponse(
        bytes: ByteArray,
        installedByPkg: Map<String, LocalInstalledApp>,
    ): Map<String, UpdateEntry> {
        val out = LinkedHashMap<String, UpdateEntry>()
        fun walk(msg: ProtoMsg, depth: Int = 0) {
            if (depth > 64) return
            val pkg = msg.string(7).trim()
            val verCode = msg.long(9)
            if (pkg.isNotBlank() && verCode > 0L) {
                val installed = installedByPkg[pkg]
                if (installed != null && verCode > installed.versionCode) {
                    val verName = msg.string(8).trim().ifBlank { verCode.toString() }
                    val size = msg.long(10)
                    val md5 = msg.string(12).trim().lowercase().takeIf { it.length == 32 }.orEmpty()
                    val url = msg.string(22).trim()
                    if (url.isBlank() || isIncompletePackageUrl(url)) return
                    if (verCode <= (out[pkg]?.release?.versionCode ?: 0L)) return
                    val plainApk = !isDownloadMetadataUrl(url) &&
                        url.substringBefore('?').endsWith(".apk", ignoreCase = true)
                    val changelog = msg.string(102).trim().takeUnless {
                        StoreHttp.upgradeToHttps(it).isNotEmpty() && imageExtension.containsMatchIn(it.substringBefore('?'))
                    }.orEmpty()
                    val release = StoreReleaseInfo(
                        storeId = StoreCatalog.ID_OPPO,
                        versionCode = verCode,
                        versionName = verName,
                        sizeBytes = size,
                        exactSizeBytes = size.takeIf { plainApk && it > 0L },
                        changelog = changelog,
                        appId = msg.long(1),
                        checksum = md5,
                    )
                    out[pkg] = UpdateEntry(release = release, rawUrl = url)
                    return
                }
            }
            for (field in msg.fields) {
                if (field.wireType == 2 && field.raw != null && field.raw.size >= 4) {
                    walk(parseProto(field.raw), depth + 1)
                }
            }
        }
        walk(parseProto(bytes, strict = true))
        return out
    }

    fun parseDownloadFileWrap(bytes: ByteArray, expectedVersion: Long? = null): StoreDownloadSpec? {
        val root = parseProto(bytes, strict = true)
        if (root.first(1)?.varint != 200L) return null
        if (expectedVersion != null && root.long(2) > 0L && root.long(2) != expectedVersion) return null
        val entries = root.all(3).map { field ->
            val file = field.raw?.let { parseProto(it, strict = true) } ?: return null
            val cdnUrl = StoreHttp.upgradeToHttps(file.string(7).trim())
            val fallbackUrl = StoreHttp.upgradeToHttps(file.string(8).trim())
            val url = cdnUrl.ifBlank { fallbackUrl }
            if (url.isBlank()) return null
            val splitName = file.string(2).trim()
            val md5 = file.string(5).trim().lowercase().takeIf { it.length == 32 }
            val size = file.long(10).takeIf { it > 0L } ?: file.long(9)
            val isBase = file.first(4)?.varint?.let { it == 0L }
                ?: (splitName.isBlank() || splitName.equals("base", true) || splitName.equals("base.apk", true))
            DownloadFileItem(isBase = isBase, splitName = splitName, url = url, size = size, md5 = md5)
        }
        if (entries.isEmpty()) return null
        val base = entries.singleOrNull { it.isBase } ?: return null
        val splits = entries.filter { it !== base }.mapIndexed { idx, item ->
            val rawName = item.splitName.ifBlank { "split_${idx + 1}" }
            val fileName = if (rawName.endsWith(".apk", ignoreCase = true)) rawName else "$rawName.apk"
            StoreDownloadPart(
                name = fileName,
                url = item.url,
                sizeBytes = item.size,
                expectedHash = item.md5?.let { ExpectedHash(HashAlgorithm.MD5, it) },
            )
        }
        return StoreDownloadSpec(
            url = base.url,
            sizeBytes = base.size,
            expectedHash = base.md5?.let { ExpectedHash(HashAlgorithm.MD5, it) },
            extraParts = splits,
        )
    }

    private data class DownloadFileItem(
        val isBase: Boolean,
        val splitName: String,
        val url: String,
        val size: Long,
        val md5: String?,
    )

    private data class ProtoField(
        val number: Int,
        val wireType: Int,
        val raw: ByteArray? = null,
        val varint: Long? = null,
    )

    private class ProtoMsg(val fields: List<ProtoField>) {
        fun first(number: Int): ProtoField? = fields.firstOrNull { it.number == number }
        fun all(number: Int): List<ProtoField> = fields.filter { it.number == number }
        fun long(number: Int): Long = first(number)?.varint ?: 0L
        fun string(number: Int): String = first(number)?.raw?.decodeToString().orEmpty()
    }

    private fun parseProto(bytes: ByteArray, strict: Boolean = false): ProtoMsg {
        fun invalid(): ProtoMsg {
            require(!strict) { "Invalid OPPO protobuf message" }
            return ProtoMsg(emptyList())
        }
        val fields = ArrayList<ProtoField>()
        var offset = 0
        while (offset < bytes.size) {
            val (key, next1) = readVarint(bytes, offset) ?: return invalid()
            offset = next1
            val number = (key ushr 3).toInt()
            val wireType = (key and 7L).toInt()
            if (number <= 0) return invalid()
            when (wireType) {
                0 -> {
                    val (v, next2) = readVarint(bytes, offset) ?: return invalid()
                    offset = next2
                    fields += ProtoField(number, wireType, varint = v)
                }
                1 -> {
                    if (offset + 8 > bytes.size) return invalid()
                    offset += 8
                }
                2 -> {
                    val (len, next2) = readVarint(bytes, offset) ?: return invalid()
                    offset = next2
                    if (len < 0L || len > bytes.size - offset) return invalid()
                    val end = offset + len.toInt()
                    fields += ProtoField(number, wireType, raw = bytes.copyOfRange(offset, end))
                    offset = end
                }
                5 -> {
                    if (offset + 4 > bytes.size) return invalid()
                    offset += 4
                }
                else -> return invalid()
            }
        }
        return ProtoMsg(fields)
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var idx = start
        while (idx < bytes.size && shift < 64) {
            val b = bytes[idx++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result to idx
            shift += 7
        }
        return null
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
        writeVarint(out, ((fieldNumber shl 3) or 0).toLong())
        writeVarint(out, value)
    }

    private fun writeBytes(out: ByteArrayOutputStream, fieldNumber: Int, data: ByteArray) {
        writeVarint(out, ((fieldNumber shl 3) or 2).toLong())
        writeVarint(out, data.size.toLong())
        out.write(data)
    }

    private fun writeString(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        writeBytes(out, fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    private fun deriveOpenId(seed: String): String {
        fun part(kind: String): String =
            sha256("candytick/oppo-open-id/v1/$kind\u0000$seed".toByteArray(Charsets.UTF_8)).toHexLower()
        return "/${part("guid")}/${part("ouid")}/${part("duid")}"
    }

    private fun formUrlEncode(value: String): String {
        val out = StringBuilder(value.length)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val code = b.toInt() and 0xFF
            when {
                code == 0x20 -> out.append('+')
                code in 'a'.code..'z'.code ||
                    code in 'A'.code..'Z'.code ||
                    code in '0'.code..'9'.code ||
                    code == '-'.code || code == '.'.code ||
                    code == '*'.code || code == '_'.code -> out.append(code.toChar())
                else -> out.append('%').append(HEX_UPPER[code ushr 4]).append(HEX_UPPER[code and 0x0F])
            }
        }
        return out.toString()
    }

    private fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)
    private fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
}
