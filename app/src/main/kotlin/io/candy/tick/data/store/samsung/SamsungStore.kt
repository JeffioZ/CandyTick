package io.candy.tick.data.store.samsung

import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDownloadSpec
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.StoreQuerySpeed
import io.candy.tick.data.store.StoreResponseException
import io.candy.tick.data.store.StoreSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 三星应用商店适配器：批量 XML 检查更新，下载时通过 downloadEx2 现取直链。
 */
class SamsungStore : StoreSource {
    override val id: String = StoreCatalog.ID_SAMSUNG
    override val speed: StoreQuerySpeed = StoreQuerySpeed.FAST_BATCH
    override val batchSize: Int = SamsungProtocol.BATCH_SIZE

    private val installId: String =
        ByteArray(16).also(Random.Default::nextBytes).joinToString("") { "%02x".format(it) }
    private val productIds = ConcurrentHashMap<String, String>()

    override suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo> {
        if (apps.isEmpty()) return emptyMap()
        val installedByPkg = apps.associateBy { it.packageName }
        val result = LinkedHashMap<String, StoreReleaseInfo>()
        for (chunk in apps.chunked(SamsungProtocol.BATCH_SIZE)) {
            val xml = SamsungProtocol.buildUpdateXml(chunk, installId)
            val request = Request.Builder()
                .url(SamsungProtocol.endpointUrl(SamsungProtocol.REQ_UPDATE_LIST))
                .header("Accept", "image/webp")
                .post(xml.toRequestBody(TEXT_MEDIA))
                .build()
            val batch = StoreHttp.execute(request) { response ->
                if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
                SamsungProtocol.parseUpdateResponse(response.body.string(), installedByPkg)
            }
            for ((pkg, entry) in batch) {
                if (entry.productId.isNotBlank()) productIds[pkg] = entry.productId
                result[pkg] = entry.release
            }
        }
        return result
    }

    override suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec {
        val productId = productIds[app.packageName]
            ?: release.appId.takeIf { it > 0L }?.toString()?.padStart(12, '0')
            ?: throw SourceException(SourceError.NotFound)
        val versioned = try {
            fetchDownload(SamsungProtocol.buildDownloadXml(app, productId, installId), release.versionCode)
        } catch (e: StoreResponseException) {
            // ODC can reject the installed-version parameter. Other business errors are authoritative.
            if (e.code != 4002L) throw e
            null
        }
        val resolved = versioned
            ?: fetchDownload(SamsungProtocol.buildDownloadXml(app, productId, installId, includeInstalledVersion = false), release.versionCode)
            ?: throw SourceException(SourceError.NotFound)
        return StoreDownloadSpec(
            url = resolved.url,
            sizeBytes = resolved.sizeBytes.takeIf { it > 0L } ?: release.sizeBytes,
            exactSizeBytes = resolved.exactSizeBytes,
        )
    }

    private suspend fun fetchDownload(xml: String, expectedVersion: Long): SamsungProtocol.DownloadResult? {
        val request = Request.Builder()
            .url(SamsungProtocol.endpointUrl(SamsungProtocol.REQ_DOWNLOAD))
            .header("Accept", "image/webp")
            .post(xml.toRequestBody(TEXT_MEDIA))
            .build()
        return StoreHttp.execute(request) { response ->
            if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
            SamsungProtocol.parseDownloadResponse(response.body.string(), expectedVersion)
        }
    }

    companion object {
        private val TEXT_MEDIA = "text/plain; charset=utf-8".toMediaType()
    }
}
