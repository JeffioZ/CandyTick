package io.candy.tick.data.store.wandoujia

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
import kotlin.random.Random
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 豌豆荚适配器：一次请求批量比对本机全部应用；下载前现取最新详情地址。
 */
class WandoujiaStore : StoreSource {
    override val id: String = StoreCatalog.ID_WANDOUJIA
    override val speed: StoreQuerySpeed = StoreQuerySpeed.FAST_BATCH

    private val identity: String = ByteArray(16).also(Random.Default::nextBytes).joinToString("") { "%02x".format(it) }
    private val joinedAt: Long = System.currentTimeMillis()

    override suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo> {
        if (apps.isEmpty()) return emptyMap()
        val envelope = WandoujiaProtocol.buildCheckEnvelope(apps, identity, joinedAt, nextRequestId())
        val payload = WandoujiaProtocol.encodeM90Request(envelope.toString().toByteArray())
        val request = Request.Builder()
            .url(WandoujiaProtocol.API_BASE + WandoujiaProtocol.CHECK_UPDATE_PATH)
            .header("User-Agent", WandoujiaProtocol.API_USER_AGENT)
            .post(payload.toRequestBody(FORM_MEDIA))
            .build()
        return StoreHttp.execute(request) { response ->
            if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
            val decoded = WandoujiaProtocol.decodeM90Response(response.body.bytes()).decodeToString()
            WandoujiaProtocol.parseCheckResponse(decoded, apps.associateBy { it.packageName })
        }
    }

    override suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec {
        val fresh = if (release.appId > 0L) fetchFreshDownload(release.appId, app.packageName) else null
        val selected = fresh?.takeIf { it.versionCode == release.versionCode && it.downloadUrl.isNotBlank() } ?: release
        val url = selected.downloadUrl
        if (url.isBlank()) throw SourceException(SourceError.NotFound)
        return StoreDownloadSpec(
            url = url,
            sizeBytes = selected.sizeBytes,
            exactSizeBytes = selected.exactSizeBytes,
            expectedHash = selected.checksum.takeIf { it.isNotBlank() }?.let { ExpectedHash(HashAlgorithm.MD5, it) },
            headers = mapOf("User-Agent" to WandoujiaProtocol.DOWNLOAD_USER_AGENT),
        )
    }

    private suspend fun fetchFreshDownload(appId: Long, packageName: String): StoreReleaseInfo? {
        val envelope = WandoujiaProtocol.buildDetailCombineEnvelope(appId, identity, joinedAt, nextRequestId())
        val request = Request.Builder()
            .url(WandoujiaProtocol.API_BASE + WandoujiaProtocol.COMBINE_PATH)
            .header("User-Agent", WandoujiaProtocol.API_USER_AGENT)
            .post(envelope.toString().toRequestBody(JSON_MEDIA))
            .build()
        return StoreHttp.execute(request) { response ->
            if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
            WandoujiaProtocol.parseDetailResponse(response.body.string(), packageName)
        }
    }

    private fun nextRequestId(): Long = System.currentTimeMillis() * 1000L + Random.nextInt(1000)

    companion object {
        private val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
