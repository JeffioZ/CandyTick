package io.candy.tick.data.store.oppo

import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.download.ExpectedHash
import io.candy.tick.data.download.HashAlgorithm
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDownloadSpec
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.StoreQuerySpeed
import io.candy.tick.data.store.StoreSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OPPO 软件商店适配器：批量 Protobuf 查询更新，下载时自动兼容直链与 v2 分包描述。
 */
class OppoStore : StoreSource {
    override val id: String = StoreCatalog.ID_OPPO
    override val speed: StoreQuerySpeed = StoreQuerySpeed.FAST_BATCH

    private val instanceId: String = UUID.randomUUID().toString()
    private val cachedUrls = ConcurrentHashMap<String, String>()

    override suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo> {
        if (apps.isEmpty()) return emptyMap()
        val installedByPkg = apps.associateBy { it.packageName }
        val result = LinkedHashMap<String, StoreReleaseInfo>()
        for (chunk in apps.chunked(OppoProtocol.BATCH_SIZE)) {
            val entries = queryBatch(chunk, installedByPkg)
            for ((pkg, entry) in entries) {
                if (entry.rawUrl.isNotBlank()) cachedUrls[pkg] = entry.rawUrl
                result[pkg] = entry.release
            }
        }
        return result
    }

    override suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec {
        val fresh = StoreHttp.runCatchingCancellable {
            queryBatch(listOf(app), mapOf(app.packageName to app))[app.packageName]
        }.getOrNull()
        val rawUrl = fresh?.rawUrl?.takeIf { it.isNotBlank() }
            ?: cachedUrls[app.packageName]?.takeIf { it.isNotBlank() }
            ?: throw SourceException(SourceError.NotFound)

        if (OppoProtocol.isIncompletePackageUrl(rawUrl)) {
            throw SourceException(SourceError.NotFound)
        }

        if (rawUrl.contains("/download/v2/", ignoreCase = true)) {
            val v2Url = StoreHttp.upgradeToHttps(rawUrl)
            if (v2Url.isBlank()) throw SourceException(SourceError.NotFound)
            val headers = OppoProtocol.buildHeaders("GET", v2Url, instanceId)
            val builder = Request.Builder().url(v2Url).get()
            for ((k, v) in headers) builder.header(k, v)
            val spec = StoreHttp.execute(builder.build()) { response ->
                if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
                OppoProtocol.parseDownloadFileWrap(response.body.bytes())
            } ?: throw SourceException(SourceError.NotFound)
            if (OppoProtocol.isIncompletePackageUrl(spec.url) || spec.extraParts.any { OppoProtocol.isIncompletePackageUrl(it.url) }) {
                throw SourceException(SourceError.NotFound)
            }
            val dlHeaders = OppoProtocol.buildHeaders("GET", spec.url, instanceId)
                .filterKeys { it in CDN_DOWNLOAD_HEADERS }
            return spec.copy(
                sizeBytes = spec.sizeBytes.takeIf { it > 0L } ?: release.sizeBytes,
                headers = dlHeaders,
            )
        }

        val url = StoreHttp.upgradeToHttps(rawUrl)
        if (url.isBlank()) throw SourceException(SourceError.NotFound)
        val md5 = fresh?.release?.checksum?.ifBlank { release.checksum } ?: release.checksum
        val size = fresh?.release?.sizeBytes?.takeIf { it > 0L } ?: release.sizeBytes
        val dlHeaders = OppoProtocol.buildHeaders("GET", url, instanceId)
            .filterKeys { it in DIRECT_DOWNLOAD_HEADERS }
        return StoreDownloadSpec(
            url = url,
            sizeBytes = size,
            expectedHash = md5.takeIf { it.isNotBlank() }?.let { ExpectedHash(HashAlgorithm.MD5, it) },
            headers = dlHeaders,
        )
    }

    private suspend fun queryBatch(
        apps: List<LocalInstalledApp>,
        installedByPkg: Map<String, LocalInstalledApp>,
    ): Map<String, OppoProtocol.UpdateEntry> {
        val bodyBytes = OppoProtocol.encodeUpgradeWrapRequest(apps)
        val headers = OppoProtocol.buildHeaders("POST", OppoProtocol.UPDATE_URL, instanceId)
        val builder = Request.Builder()
            .url(OppoProtocol.UPDATE_URL)
            .post(bodyBytes.toRequestBody(PROTO_MEDIA))
        for ((k, v) in headers) builder.header(k, v)
        return StoreHttp.execute(builder.build()) { response ->
            if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
            OppoProtocol.parseUpgradeResponse(response.body.bytes(), installedByPkg)
        }
    }

    companion object {
        private val PROTO_MEDIA = OppoProtocol.CONTENT_TYPE.toMediaType()
        private val CDN_DOWNLOAD_HEADERS = setOf("User-Agent")
        private val DIRECT_DOWNLOAD_HEADERS = setOf("User-Agent", "oak", "id", "ocs", "appid", "appversion", "pkg-ver", "romver", "t", "sign", "sg")
    }
}
