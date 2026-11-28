package io.candy.tick.data.store.xiaomi

import android.os.Build
import io.candy.tick.data.download.ExpectedHash
import io.candy.tick.data.download.HashAlgorithm
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDownloadPart
import io.candy.tick.data.store.StoreDownloadSpec
import io.candy.tick.data.store.StoreHttp
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 小米应用商店批量更新协议：签名计算、请求构造与响应解析。
 */
internal object XiaomiProtocol {
    const val MARKET_BASE = "https://app.market.xiaomi.com/apm"
    const val UPDATE_URL = "https://updateinfo.market.xiaomi.com/apm/updateinfo/v2?lo=CN"
    const val DEFAULT_DOWNLOAD_HOST = "https://fgb0.market.xiaomi.com/download/"
    const val MIUI_ANCHOR_PKG = "com.miui.core"

    private const val SALT = "good luck!"
    private const val B64_TABLE = "leDTKhmg4MafVFp73x6djvLiHn2G9XPruARBwS0q1OzNJt8WobZsQcYyEICk5U-_"
    private const val DEFAULT_USE_EXP_ID =
        "2023001,2311551,2398789,2398846,2346056,2075312,2362289,2378691,2403435,2263575,2404589,2333160,2286434,1411227,1978999,2368587,2362073,2059056"
    private val signedKeys = setOf(
        "ad", "appId", "co", "cpuArchitecture", "device", "deviceType", "id", "la", "lo",
        "marketVersion", "model", "_n", "network", "oldApkHash", "os", "packageName",
        "pageRef", "pos", "ref", "refPosition", "resolution", "ro", "sdk", "sid",
        "sourcePackage", "targetVersionCode", "versionCode", "webResVersion",
    )

    private data class XiaomiOsFingerprint(
        val os: String,
        val osV2: String,
        val osBigVersionCode: String,
        val osBigVersionName: String,
        val miuiBigVersionCode: String,
        val miuiBigVersionName: String,
        val region: String,
    )

    private val osFingerprint: XiaomiOsFingerprint by lazy {
        val incremental = Build.VERSION.INCREMENTAL.orEmpty().trim()
        val miOsIncremental = readSystemProp("ro.mi.os.version.incremental")
        val release = Build.VERSION.RELEASE.orEmpty().ifBlank { "15" }
        XiaomiOsFingerprint(
            os = incremental.ifBlank { miOsIncremental }.ifBlank { release },
            osV2 = miOsIncremental.ifBlank { incremental }.ifBlank { release },
            osBigVersionCode = readSystemProp("ro.mi.os.version.code").ifBlank { "3" },
            osBigVersionName = readSystemProp("ro.mi.os.version.name").ifBlank { "OS3.0" },
            miuiBigVersionCode = readSystemProp("ro.miui.ui.version.code").ifBlank { "816" },
            miuiBigVersionName = readSystemProp("ro.miui.ui.version.name").ifBlank { "V816" },
            region = readSystemProp("ro.miui.region").ifBlank { "CN" },
        )
    }

    private fun readSystemProp(key: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val method = cls.getMethod("get", String::class.java, String::class.java)
        (method.invoke(null, key, "") as? String)?.trim().orEmpty()
    }.getOrDefault("")

    fun commonParams(instanceId: String, dctx: String = ""): MutableMap<String, String> {
        val fp = osFingerprint
        val abis = runCatching { Build.SUPPORTED_ABIS?.joinToString(",") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "arm64-v8a"
        return linkedMapOf(
            "co" to (Locale.getDefault().country.ifBlank { "CN" }),
            "la" to (Locale.getDefault().language.ifBlank { "zh" }),
            "lo" to fp.region,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "androidVersion" to Build.VERSION.RELEASE.orEmpty().ifBlank { "15" },
            "os" to fp.os,
            "osV2" to fp.osV2,
            "osBigVersionCode" to fp.osBigVersionCode,
            "osBigVersionName" to fp.osBigVersionName,
            "miuiBigVersionCode" to fp.miuiBigVersionCode,
            "miuiBigVersionName" to fp.miuiBigVersionName,
            "model" to Build.MODEL.orEmpty().ifBlank { "24129PN74C" },
            "device" to Build.DEVICE.orEmpty().ifBlank { "dada" },
            "deviceType" to "0",
            "cpuArchitecture" to abis,
            "resolution" to "1080*2400",
            "marketVersion" to "4005012",
            "network" to "unknown",
            "ro" to "unknown",
            "newUser" to "false",
            "clientFlag" to "2",
            "isMiuiLite" to "false",
            "supportBundle" to "1",
            "supportSmallApk" to "true",
            "supportPatchVer" to "0,1,2,3",
            "useExpId" to DEFAULT_USE_EXP_ID,
            "instance_id" to instanceId,
            "clientId" to instanceId,
        ).apply {
            if (dctx.isNotBlank()) put("dctx", dctx)
        }
    }

    fun buildUpdateFields(
        apps: List<LocalInstalledApp>,
        instanceId: String,
        dctx: String = "",
        nowMillis: Long = System.currentTimeMillis(),
    ): MutableMap<String, String> {
        // 小米 updateinfo/v2 需要列表包含 com.miui.core 锚点才会完整返回 miuiApp 系统组件更新
        val withAnchor = if (apps.any { it.packageName == MIUI_ANCHOR_PKG }) {
            apps
        } else {
            apps + LocalInstalledApp(MIUI_ANCHOR_PKG, MIUI_ANCHOR_PKG, 0L, "0", isSystemApp = true)
        }
        val sorted = withAnchor.sortedBy { it.packageName }
        return commonParams(instanceId, dctx).apply {
            put("apkSource", sorted.joinToString(",") { "0" })
            put("autoUpdateEnabled", "false")
            put("background", "false")
            put("downloadRestriction", "1")
            put("downloadRestrictionMode", "0")
            put("invalidSystemPackageHash", "null")
            put("privacyCompliance", "true")
            put("rankTypeV2", "true")
            put("session_id", instanceId + nowMillis)
            put("showUnfitnessApp", "true")
            put("splits", sorted.joinToString(",") { it.splits.ifBlank { "0" } })
            put("packageName", sorted.joinToString(",") { it.packageName })
            put("versionCode", sorted.joinToString(",") { it.versionCode.toString() })
            put("oldApkHash", sorted.joinToString(",") { "0" })
            put("installedByMarket", sorted.joinToString(",") { if (it.installedByXiaomiMarket) "1" else "0" })
            put("ref", "update")
        }
    }

    fun buildDownloadMetaUrl(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
        instanceId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val params = commonParams(instanceId).apply {
            put("ad", "0")
            put("appId", release.appId.toString())
            put("autoUpdateEnabled", "false")
            put("bundleType", "main")
            put("downloadGrantType", "0")
            put("oldApkHash", "0")
            put("pName", app.packageName)
            put("pageRef", "com.miui.home")
            put("ref", "upgrade")
            put("refPosition", "0")
            put("sourcePackage", "com.miui.home")
            put("taskStartTime", nowMillis.toString())
            put("useCache", "false")
            put("versionCode", app.versionCode.toString())
            put("versionName", app.versionName)
            put("targetVersionCode", release.versionCode.toString())
            put("session_id", instanceId + nowMillis)
        }
        val raw = "$MARKET_BASE/download/${release.appId}?${encodeForm(params)}"
        return signUrl(raw, nowMillis)
    }

    fun buildDetailDownloadMetaUrl(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
        instanceId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val params = commonParams(instanceId).apply {
            put("ad", "0")
            put("appId", release.appId.toString())
            put("bundleType", "main")
            put("callerPackage", "com.xiaomi.market")
            put("callerSignature", "88daa889de21a80bca64464243c9ede6")
            put("downloadGrantType", "0")
            put("downloadImmediately", "false")
            put("launchWhenInstalled", "false")
            put("entrance", "detail")
            put("pageTag", "detail")
            put("keyword", app.displayName)
            put("pName", app.packageName)
            put("packageName", app.packageName)
            put("pageRef", "com.miui.home")
            put("originalPageRef", "com.miui.home")
            put("pos", "detailInstallBtn")
            put("ref", "detail")
            put("refPosition", "0")
            put("refs", "input-searchResult-detail/${release.appId}")
            put("releaseType", "0")
            put("sid", "${instanceId}default")
            put("senderPackageName", "com.xiaomi.market")
            put("sourcePackage", "com.xiaomi.market")
            put("supportCompressType", "1")
            put("supportModifyUrl", "true")
            put("supportSdm", "true")
            put("supportSpeedInstall", "true")
            put("supportCloudProfile", "true")
            put("supportCloudVerify", "true")
            put("isLightToFull", "false")
            put("useCache", "false")
            put("previousFromRef", "detailInstallBtn")
            put("versionCode", (app.versionCode - 1L).coerceAtLeast(1L).toString())
        }
        val raw = "$MARKET_BASE/download/${release.appId}?${encodeForm(params)}"
        return signUrl(raw, nowMillis)
    }

    fun signFormForUrl(url: String, fields: MutableMap<String, String>, nowMillis: Long = System.currentTimeMillis()) {
        val nonce = "${nowMillis}_${Random.nextInt(1000)}"
        val sep = if ('?' in url) "&" else "?"
        val sigUrl = if (fields.isEmpty()) url else "$url$sep${encodeForm(fields)}"
        fields["_n"] = nonce
        fields["_s"] = computeSignature(sigUrl, nonce)
        fields["_v"] = "1"
    }

    fun signUrl(baseUrl: String, nowMillis: Long = System.currentTimeMillis()): String {
        val nonce = "${nowMillis}_${Random.nextInt(1000)}"
        val sig = computeSignature(baseUrl, nonce)
        val sep = if ('?' in baseUrl) "&" else "?"
        return "$baseUrl${sep}_n=${urlEnc(nonce)}&_s=${urlEnc(sig)}&_v=1"
    }

    fun encodeForm(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (k, v) -> "${urlEnc(k)}=${urlEnc(v)}" }

    fun parseUpdateResponse(rawJson: String, installed: Map<String, LocalInstalledApp>): Map<String, StoreReleaseInfo> {
        val root = runCatching { Json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, StoreReleaseInfo>()
        for (arrayKey in listOf("listApp", "miuiApp")) {
            val arr = root[arrayKey] as? JsonArray ?: continue
            for (el in arr) {
                val obj = el as? JsonObject ?: continue
                val pkg = obj.str("packageName")
                val local = installed[pkg] ?: continue
                val code = obj.num("versionCode")
                val appId = obj.num("appId")
                if (pkg.isBlank() || code <= local.versionCode || appId <= 0L) continue
                val existing = out[pkg]
                if (existing != null && existing.versionCode >= code) continue
                out[pkg] = StoreReleaseInfo(
                    storeId = StoreCatalog.ID_XIAOMI,
                    versionCode = code,
                    versionName = obj.str("versionName").ifBlank { code.toString() },
                    sizeBytes = obj.num("apkSize"),
                    changelog = obj.str("changeLog").trim(),
                    appId = appId,
                )
            }
        }
        return out
    }

    fun parseDownloadUrl(rawJson: String): Pair<String, Long>? =
        parseDownloadSpec(rawJson)?.let { it.url to it.sizeBytes }

    /** 解析小米单包或 Split APK 多分包下载描述（自动保证 base 在主位置、其余 split 进 extraParts）。 */
    fun parseDownloadSpec(rawJson: String, headers: Map<String, String> = emptyMap()): StoreDownloadSpec? {
        val root = runCatching { Json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return null
        val apks = root["apks"] as? JsonArray ?: return null
        val host = ((root["hosts"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: root.str("host").ifBlank { DEFAULT_DOWNLOAD_HOST }

        data class RawPart(val isBase: Boolean, val name: String, val url: String, val size: Long, val md5: String?)
        val parts = apks.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val apkPath = obj.str("apk").ifBlank { obj.str("url") }
            if (apkPath.isBlank()) return@mapNotNull null
            val full = if (apkPath.startsWith("http", ignoreCase = true)) apkPath else host.trimEnd('/') + "/" + apkPath.trimStart('/')
            val url = StoreHttp.upgradeToHttps(full)
            if (url.isBlank()) return@mapNotNull null
            val type = obj.str("type")
            val isBase = type.equals("base", ignoreCase = true) || type.isBlank()
            val name = obj.str("name").ifBlank { apkPath.substringAfterLast('/') }
            val size = obj.num("size")
            val md5 = obj.str("apkHash").ifBlank { obj.str("md5") }.lowercase().takeIf { it.length == 32 }
            RawPart(isBase = isBase, name = name, url = url, size = size, md5 = md5)
        }
        if (parts.isEmpty()) return null
        val base = parts.firstOrNull { it.isBase } ?: parts.first()
        val splits = parts.filter { it !== base }.mapIndexed { idx, p ->
            StoreDownloadPart(
                name = p.name.ifBlank { "split_${idx + 1}.apk" },
                url = p.url,
                sizeBytes = p.size,
                expectedHash = p.md5?.let { ExpectedHash(HashAlgorithm.MD5, it) },
            )
        }
        val baseSize = base.size.takeIf { it > 0L } ?: if (splits.isEmpty()) root.num("apkSize") else 0L
        return StoreDownloadSpec(
            url = base.url,
            sizeBytes = baseSize,
            expectedHash = base.md5?.let { ExpectedHash(HashAlgorithm.MD5, it) },
            headers = headers,
            extraParts = splits,
        )
    }

    internal fun computeSignature(url: String, nonce: String): String {
        val ts = nonce.substringBefore('_').toLong()
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val arranged = arrangeForSign(decoded, nonce)
        val algo = when ((ts % 4).toInt()) {
            0 -> "HmacMD5"
            1 -> "HmacSHA256"
            2 -> "HmacSHA1"
            else -> "HmacSHA384"
        }
        val mac = Mac.getInstance(algo).apply {
            init(SecretKeySpec((SALT + nonce).toByteArray(), algo))
        }
        return customBase64(mac.doFinal(arranged.toByteArray()))
    }

    private fun arrangeForSign(decodedUrl: String, nonce: String): String {
        val qIdx = decodedUrl.indexOf('?')
        val pathPart = if (qIdx >= 0) decodedUrl.substring(0, qIdx) else decodedUrl
        val query = if (qIdx >= 0 && qIdx + 1 < decodedUrl.length) decodedUrl.substring(qIdx + 1) else ""
        val sb = StringBuilder()
        var slashes = 0
        var rev = true
        for (ch in pathPart) {
            if (ch == '/' && rev) {
                slashes++
                if (slashes == 3) {
                    sb.append('\n').append(ch); rev = false
                } else sb.insert(0, ch)
            } else if (rev) sb.insert(0, ch) else sb.append(ch)
        }
        sb.append('\n')
        val params = query.split('&')
            .filter { it.isNotEmpty() && !it.startsWith("_n=") && !it.startsWith("_s=") && !it.startsWith("_v=") }
            .toMutableList()
        params.add("_n=$nonce")
        val plist = buildString {
            params.forEachIndexed { idx, p ->
                val k = p.substringBefore('=')
                if (k in signedKeys) {
                    append(k)
                    if (idx != params.lastIndex) append(';')
                }
            }
        }
        params.add("_p=$plist")
        params.sortWith { a, b -> a.compareTo(b, ignoreCase = true) }
        for ((idx, param) in params.withIndex()) {
            val k = param.substringBefore('=')
            val keep = if ('=' in param) k in signedKeys else true
            if (!keep) {
                if (idx == params.lastIndex && sb.endsWith("=")) sb.deleteCharAt(sb.lastIndex)
                continue
            }
            val segs = param.split('=')
            sb.append(segs.first())
            for (i in 1 until segs.size) {
                sb.append('&').append(reverseChars(segs[i]))
            }
            if (idx != params.lastIndex) sb.append('=')
        }
        val lf = sb.indexOf('\n')
        return if (lf >= 0) sb.substring(lf + 1) else sb.toString()
    }

    private fun reverseChars(s: String): String {
        val out = CharArray(s.length)
        for (i in s.indices) out[s.length - 1 - i] = s[i]
        return out.concatToString()
    }

    private fun customBase64(bytes: ByteArray): String = buildString {
        var i = 0
        while (i + 2 < bytes.size) {
            val b1 = bytes[i].toInt() and 0xff
            val b2 = bytes[i + 1].toInt() and 0xff
            val b3 = bytes[i + 2].toInt() and 0xff
            append(B64_TABLE[(b1 shr 2) and 0x3f])
            append(B64_TABLE[((b1 shl 4) or (b2 shr 4)) and 0x3f])
            append(B64_TABLE[((b2 shl 2) or (b3 shr 6)) and 0x3f])
            append(B64_TABLE[b3 and 0x3f])
            i += 3
        }
        val rem = bytes.size - i
        if (rem == 1) {
            val b1 = bytes[i].toInt() and 0xff
            append(B64_TABLE[(b1 shr 2) and 0x3f])
            append(B64_TABLE[(b1 shl 4) and 0x3f])
        } else if (rem == 2) {
            val b1 = bytes[i].toInt() and 0xff
            val b2 = bytes[i + 1].toInt() and 0xff
            append(B64_TABLE[(b1 shr 2) and 0x3f])
            append(B64_TABLE[((b1 shl 4) or (b2 shr 4)) and 0x3f])
            append(B64_TABLE[(b2 shl 2) and 0x3f])
        }
    }

    private fun urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.num(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
}
