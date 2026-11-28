package io.candy.tick.data.store.fdroid

import io.candy.tick.data.download.ExpectedHash
import io.candy.tick.data.download.HashAlgorithm
import io.candy.tick.data.model.FdroidMirror
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import java.io.InputStream
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * F-Droid / IzzyOnDroid index-v2 协议：流式扫描 `packages` 键，只解码本机已装包，避免 20MB+ 索引整树 OOM。
 */
internal object FdroidProtocol {

    private val indexJson = Json { ignoreUnknownKeys = true; useAlternativeNames = false }
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
    @OptIn(ExperimentalSerializationApi::class)
    fun scanUpdates(
        input: InputStream,
        storeId: String,
        repoBase: String,
        installedByPkg: Map<String, LocalInstalledApp>,
        supportedAbis: Set<String> = defaultSupportedAbis(),
        sdkInt: Int = android.os.Build.VERSION.SDK_INT,
        unconfirmed: MutableSet<String> = mutableSetOf(),
    ): Map<String, StoreReleaseInfo> {
        val packages = object : DeserializationStrategy<Map<String, StoreReleaseInfo>> {
            override val descriptor = buildClassSerialDescriptor("InstalledIndexPackages") {
                for (name in installedByPkg.keys) element<JsonElement>(name, isOptional = true)
            }

            override fun deserialize(decoder: Decoder): Map<String, StoreReleaseInfo> = buildMap {
                decoder.decodeStructure(descriptor) {
                    while (true) {
                        val index = decodeElementIndex(descriptor)
                        if (index == CompositeDecoder.DECODE_DONE) break
                        val name = descriptor.getElementName(index)
                        val entry = decodeSerializableElement(descriptor, index, JsonElement.serializer()) as? JsonObject
                        if (entry?.get("versions") !is JsonObject) {
                            unconfirmed += name
                            continue
                        }
                        val local = installedByPkg.getValue(name)
                        var invalidVersion = 0L
                        val best = bestVersion(entry, storeId, repoBase, local.versionCode, supportedAbis, sdkInt, local.signatureSha256) { code ->
                            invalidVersion = maxOf(invalidVersion, code)
                        }
                        if (invalidVersion > (best?.versionCode ?: 0L)) unconfirmed += name
                        if (best != null) put(name, best)
                    }
                }
            }
        }
        val index = object : DeserializationStrategy<Map<String, StoreReleaseInfo>> {
            override val descriptor = buildClassSerialDescriptor("StoreIndexV2") {
                element("packages", packages.descriptor)
            }

            override fun deserialize(decoder: Decoder): Map<String, StoreReleaseInfo> = decoder.decodeStructure(descriptor) {
                var result: Map<String, StoreReleaseInfo>? = null
                while (true) {
                    val field = decodeElementIndex(descriptor)
                    if (field == CompositeDecoder.DECODE_DONE) break
                    require(field == 0 && result == null) { "Invalid F-Droid packages field" }
                    result = decodeSerializableElement(descriptor, field, packages)
                }
                requireNotNull(result) { "Missing F-Droid packages" }
            }
        }
        return indexJson.decodeFromStream(index, input)
    }

    internal fun parseBestVersion(
        packageJson: String,
        storeId: String,
        repoBase: String,
        installedVersionCode: Long,
        supportedAbis: Set<String> = defaultSupportedAbis(),
        sdkInt: Int = android.os.Build.VERSION.SDK_INT,
    ): StoreReleaseInfo? = bestVersion(
        Json.parseToJsonElement(packageJson) as JsonObject, storeId, repoBase, installedVersionCode, supportedAbis, sdkInt,
    )

    private fun bestVersion(
        pkgObj: JsonObject,
        storeId: String,
        repoBase: String,
        installedVersionCode: Long,
        supportedAbis: Set<String>,
        sdkInt: Int,
        installedSigner: String = "",
        onInvalidVersion: (Long) -> Unit = {},
    ): StoreReleaseInfo? {
        val versions = pkgObj["versions"] as? JsonObject ?: error("Missing F-Droid versions")
        val localSigners = installedSigner.split('+').filter(String::isNotBlank)
        var best: StoreReleaseInfo? = null
        for ((_, vEl) in versions) {
            val vObj = vEl as? JsonObject ?: continue
            val manifest = vObj["manifest"] as? JsonObject ?: continue
            val code = (manifest["versionCode"] as? JsonPrimitive)?.longOrNull ?: continue
            if (code <= installedVersionCode) continue
            if (best != null && best.versionCode > code) continue
            val usesSdk = manifest["usesSdk"] as? JsonObject
            if (sdkInt > 0 && usesSdk != null) {
                val minSdk = (usesSdk["minSdkVersion"] as? JsonPrimitive)?.longOrNull
                val maxSdk = (usesSdk["maxSdkVersion"] as? JsonPrimitive)?.longOrNull
                if ((minSdk != null && sdkInt < minSdk) || (maxSdk != null && sdkInt > maxSdk)) continue
            }
            val nativeCode = (manifest["nativecode"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                .orEmpty()
            if (nativeCode.isNotEmpty() && supportedAbis.isNotEmpty() && nativeCode.none { it in supportedAbis }) continue
            val file = vObj["file"] as? JsonObject
            val fileName = (file?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val sha256 = (file?.get("sha256") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (file == null || fileName.isBlank() || !sha256Regex.matches(sha256)) {
                onInvalidVersion(code)
                continue
            }
            val signers = ((manifest["signer"] as? JsonObject)?.get("sha256") as? JsonArray).orEmpty()
                .map { (it as? JsonPrimitive)?.contentOrNull.orEmpty().trim().lowercase() }
                .takeIf { list -> list.all(sha256Regex::matches) }.orEmpty().distinct().sorted()
            // Reproducible builds may publish two differently signed APKs with the same versionCode.
            if (best?.versionCode == code) {
                if (localSigners.isEmpty() || signers != localSigners || best.signerSha256s == localSigners) continue
            }
            val size = (file["size"] as? JsonPrimitive)?.longOrNull ?: 0L
            val verName = (manifest["versionName"] as? JsonPrimitive)?.contentOrNull?.ifBlank { code.toString() }
                ?: code.toString()
            val whatsNew = extractLocalizedText(vObj["whatsNew"] as? JsonObject)
            best = StoreReleaseInfo(
                storeId = storeId,
                versionCode = code,
                versionName = verName,
                sizeBytes = size,
                exactSizeBytes = size.takeIf { it > 0L },
                changelog = whatsNew,
                downloadUrl = repoBase.trimEnd('/') + "/" + fileName.trimStart('/'),
                checksum = sha256,
                signerSha256s = signers,
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
}
