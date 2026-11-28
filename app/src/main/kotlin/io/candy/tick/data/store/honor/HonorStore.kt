package io.candy.tick.data.store.honor

import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.download.ExpectedHash
import io.candy.tick.data.download.HashAlgorithm
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDownloadPart
import io.candy.tick.data.store.StoreDownloadSpec
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.StoreQuerySpeed
import io.candy.tick.data.store.StoreSource
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 荣耀应用市场适配器：批量检查更新，下载时按包名查询详情（自动支持单包与 Split APK）。
 */
class HonorStore : StoreSource {
    override val id: String = StoreCatalog.ID_HONOR
    override val speed: StoreQuerySpeed = StoreQuerySpeed.FAST_BATCH
    override val batchSize: Int = HonorProtocol.BATCH_SIZE

    private val instanceId: String = UUID.randomUUID().toString()

    override suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo> {
        if (apps.isEmpty()) return emptyMap()
        val installedByPkg = apps.associateBy { it.packageName }
        val result = LinkedHashMap<String, StoreReleaseInfo>()
        for (chunk in apps.chunked(HonorProtocol.BATCH_SIZE)) {
            val timestamp = System.currentTimeMillis()
            val body = HonorProtocol.buildUpdateBody(chunk, instanceId, timestamp)
            val responseText = postSigned(HonorProtocol.UPDATE_PATH, body, timestamp)
            result.putAll(HonorProtocol.parseUpdateResponse(responseText, installedByPkg))
        }
        return result
    }

    override suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec {
        val timestamp = System.currentTimeMillis()
        val body = HonorProtocol.buildDetailBody(app.packageName, instanceId, timestamp)
        val responseText = postSigned(HonorProtocol.DETAIL_PATH, body, timestamp)
        val candidates = HonorProtocol.parseDetailCandidates(responseText, app.packageName, release.versionCode)
        if (candidates.isEmpty()) throw SourceException(SourceError.NotFound)

        val resolved = candidates.mapNotNull { cand ->
            val url = if (cand.metaPath.isNotBlank()) {
                resolveDispatchUrl(app.packageName, cand.metaPath, cand.sizeBytes)
                    ?: cand.directUrl.takeIf { it.isNotBlank() }
            } else {
                cand.directUrl.takeIf { it.isNotBlank() }
            }
            if (url.isNullOrBlank()) null else cand to url
        }
        if (resolved.isEmpty() || (candidates.size > 1 && resolved.size != candidates.size)) {
            throw SourceException(SourceError.NotFound)
        }
        val base = resolved.singleOrNull { it.first.isBase }
            ?: throw SourceException(SourceError.NotFound)
        val splits = resolved.filter { it !== base }.mapIndexed { idx, (cand, url) ->
            StoreDownloadPart(
                name = "split_${idx + 1}.apk",
                url = url,
                sizeBytes = cand.sizeBytes,
                expectedHash = cand.sha256?.let { ExpectedHash(HashAlgorithm.SHA256, it) },
            )
        }
        return StoreDownloadSpec(
            url = base.second,
            sizeBytes = base.first.sizeBytes.takeIf { it > 0L } ?: release.sizeBytes,
            expectedHash = (base.first.sha256 ?: release.checksum.takeIf { it.isNotBlank() })
                ?.let { ExpectedHash(HashAlgorithm.SHA256, it) },
            extraParts = splits,
            exactSizeBytes = base.first.sizeBytes.takeIf { it > 0L },
        )
    }

    private suspend fun resolveDispatchUrl(packageName: String, metaPath: String, sizeBytes: Long): String? =
        StoreHttp.runCatchingCancellable {
            val ts = System.currentTimeMillis()
            val dispatchBody = HonorProtocol.buildDispatchBody(packageName, metaPath, sizeBytes, instanceId, ts)
            val resp = postSigned(HonorProtocol.DISPATCH_PATH, dispatchBody, ts)
            HonorProtocol.parseDispatchHttpsUrl(resp)
        }.getOrNull()

    private suspend fun postSigned(path: String, body: String, timestamp: Long): String {
        val traceId = HonorProtocol.randomTraceId()
        val signature = HonorProtocol.sign(path, timestamp, traceId, body)
        val headers = HonorProtocol.buildHeaders(instanceId, timestamp, traceId, signature)
        val builder = Request.Builder()
            .url(HonorProtocol.BASE_URL + path)
            .post(body.toRequestBody(JSON_MEDIA))
        for ((k, v) in headers) builder.header(k, v)
        return StoreHttp.execute(builder.build()) { response ->
            if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
            response.body.string()
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
