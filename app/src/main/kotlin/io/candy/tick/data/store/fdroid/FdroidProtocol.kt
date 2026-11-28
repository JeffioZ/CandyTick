package io.candy.tick.data.store.fdroid

import io.candy.tick.data.download.ExpectedHash
import io.candy.tick.data.download.HashAlgorithm
import io.candy.tick.data.model.FdroidMirror
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * F-Droid / IzzyOnDroid index-v2 协议：流式扫描 `packages` 键，只解码本机已装包，避免 20MB+ 索引整树 OOM。
 */
internal object FdroidProtocol {

    private val sha256Regex = Regex("^[a-fA-F0-9]{64}$")

    private fun defaultSupportedAbis(): Set<String> =
        runCatching { android.os.Build.SUPPORTED_ABIS?.toSet() }.getOrNull().orEmpty()

    fun repoBaseUrl(storeId: String, mirror: FdroidMirror): String = when {
        storeId == "izzyondroid" -> "https://apt.izzysoft.de/fdroid/repo"
        mirror == FdroidMirror.TUNA -> "https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo"
        else -> "https://f-droid.org/repo"
    }

    fun indexUrl(repoBase: String): String = repoBase.trimEnd('/') + "/index-v2.json"

    /**
     * 流式读取 index-v2.json 的 `packages` 映射，仅对 [installedByPkg] 中存在的包解析最新版本。
     */
    fun scanUpdates(
        input: InputStream,
        storeId: String,
        repoBase: String,
        installedByPkg: Map<String, LocalInstalledApp>,
        supportedAbis: Set<String> = defaultSupportedAbis(),
    ): Map<String, StoreReleaseInfo> {
        if (installedByPkg.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, StoreReleaseInfo>()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8), 32 * 1024).use { reader ->
            val scanner = TopLevelPackagesScanner(reader)
            scanner.forEachPackage { pkg, rawSlice ->
                val local = installedByPkg[pkg] ?: return@forEachPackage
                parseBestVersion(rawSlice, storeId, repoBase, local.versionCode, supportedAbis)?.let { out[pkg] = it }
            }
        }
        return out
    }

    internal fun parseBestVersion(
        packageJson: String,
        storeId: String,
        repoBase: String,
        installedVersionCode: Long,
        supportedAbis: Set<String> = defaultSupportedAbis(),
    ): StoreReleaseInfo? {
        val pkgObj = runCatching { Json.parseToJsonElement(packageJson) as? JsonObject }.getOrNull() ?: return null
        val versions = pkgObj["versions"] as? JsonObject ?: return null
        var best: StoreReleaseInfo? = null
        for ((_, vEl) in versions) {
            val vObj = vEl as? JsonObject ?: continue
            val manifest = vObj["manifest"] as? JsonObject ?: continue
            val file = vObj["file"] as? JsonObject ?: continue
            val code = (manifest["versionCode"] as? JsonPrimitive)?.longOrNull ?: continue
            if (code <= installedVersionCode) continue
            if (best != null && best.versionCode >= code) continue
            val nativeCode = (manifest["nativecode"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                .orEmpty()
            if (nativeCode.isNotEmpty() && supportedAbis.isNotEmpty() && nativeCode.none { it in supportedAbis }) continue
            val fileName = (file["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            val sha256 = (file["sha256"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val size = (file["size"] as? JsonPrimitive)?.longOrNull ?: 0L
            val verName = (manifest["versionName"] as? JsonPrimitive)?.contentOrNull?.ifBlank { code.toString() }
                ?: code.toString()
            val whatsNew = extractLocalizedText(vObj["whatsNew"] as? JsonObject)
            best = StoreReleaseInfo(
                storeId = storeId,
                versionCode = code,
                versionName = verName,
                sizeBytes = size,
                changelog = whatsNew,
                downloadUrl = repoBase.trimEnd('/') + "/" + fileName.trimStart('/'),
                checksum = sha256,
            )
        }
        return best
    }

    fun expectedSha256(checksum: String): ExpectedHash? =
        checksum.takeIf { sha256Regex.matches(it) }
            ?.let { ExpectedHash(HashAlgorithm.SHA256, it) }

    private fun extractLocalizedText(obj: JsonObject?): String {
        if (obj == null) return ""
        for (key in listOf("zh-CN", "zh-Hans", "zh", "en-US", "en")) {
            val text = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return obj.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }.orEmpty()
    }

    /** 极简词法扫描器：只定位顶层 `packages` 对象并逐包切片，内存峰值几十 KB。 */
    private class TopLevelPackagesScanner(private val reader: BufferedReader) {
        private var peeked: Int = -2

        fun forEachPackage(action: (String, String) -> Unit) {
            skipWs()
            if (nextChar() != '{'.code) return
            while (true) {
                skipWs()
                when (peekChar()) {
                    '}'.code, -1 -> return
                    ','.code -> { nextChar(); continue }
                }
                val key = readJsonString() ?: return
                skipWs()
                if (nextChar() != ':'.code) return
                skipWs()
                if (key == "packages") {
                    if (nextChar() != '{'.code) return
                    scanPackagesBody(action)
                    return
                } else {
                    skipValue()
                }
            }
        }

        private fun scanPackagesBody(action: (String, String) -> Unit) {
            val sb = StringBuilder(4096)
            while (true) {
                skipWs()
                when (peekChar()) {
                    '}'.code, -1 -> return
                    ','.code -> { nextChar(); continue }
                }
                val pkg = readJsonString() ?: return
                skipWs()
                if (nextChar() != ':'.code) return
                skipWs()
                sb.setLength(0)
                readRawValue(sb)
                if (sb.isNotEmpty()) action(pkg, sb.toString())
            }
        }

        private fun readRawValue(out: StringBuilder) {
            when (peekChar()) {
                '{'.code -> readDelimited(out, '{', '}')
                '['.code -> readDelimited(out, '[', ']')
                '"'.code -> readStringRaw(out)
                else -> {
                    while (true) {
                        val c = peekChar()
                        if (c == -1 || c == ','.code || c == '}'.code || c == ']'.code || c <= ' '.code) break
                        out.append(nextChar().toChar())
                    }
                }
            }
        }

        private fun skipValue() {
            when (peekChar()) {
                '{'.code -> skipDelimited('{', '}')
                '['.code -> skipDelimited('[', ']')
                '"'.code -> readJsonString()
                else -> while (true) {
                    val c = peekChar()
                    if (c == -1 || c == ','.code || c == '}'.code || c == ']'.code || c <= ' '.code) break
                    nextChar()
                }
            }
        }

        private fun readDelimited(out: StringBuilder, open: Char, close: Char) {
            if (nextChar() != open.code) return
            out.append(open)
            var depth = 1
            var inStr = false
            while (depth > 0) {
                val c = nextChar()
                if (c == -1) return
                val ch = c.toChar()
                out.append(ch)
                if (inStr && ch == '\\') {
                    val esc = nextChar()
                    if (esc == -1) return
                    out.append(esc.toChar())
                } else if (ch == '"') inStr = !inStr
                else if (!inStr && ch == open) depth++
                else if (!inStr && ch == close) depth--
            }
        }

        private fun skipDelimited(open: Char, close: Char) {
            if (nextChar() != open.code) return
            var depth = 1
            var inStr = false
            while (depth > 0) {
                val c = nextChar()
                if (c == -1) return
                val ch = c.toChar()
                if (inStr && ch == '\\') {
                    if (nextChar() == -1) return
                } else if (ch == '"') inStr = !inStr
                else if (!inStr && ch == open) depth++
                else if (!inStr && ch == close) depth--
            }
        }

        private fun readStringRaw(out: StringBuilder) {
            if (nextChar() != '"'.code) return
            out.append('"')
            while (true) {
                val c = nextChar()
                if (c == -1) return
                val ch = c.toChar()
                out.append(ch)
                if (ch == '\\') {
                    val esc = nextChar()
                    if (esc == -1) return
                    out.append(esc.toChar())
                } else if (ch == '"') return
            }
        }

        private fun readJsonString(): String? {
            if (nextChar() != '"'.code) return null
            val sb = StringBuilder(64)
            while (true) {
                val c = nextChar()
                if (c == -1) return null
                val ch = c.toChar()
                if (ch == '\\') {
                    val esc = nextChar()
                    if (esc == -1) return null
                    sb.append(esc.toChar())
                } else if (ch == '"') return sb.toString()
                else sb.append(ch)
            }
        }

        private fun skipWs() {
            while (true) {
                val c = peekChar()
                if (c == -1 || c > ' '.code) break
                nextChar()
            }
        }

        private fun peekChar(): Int {
            if (peeked == -2) peeked = reader.read()
            return peeked
        }

        private fun nextChar(): Int {
            if (peeked != -2) {
                val c = peeked
                peeked = -2
                return c
            }
            return reader.read()
        }
    }
}
