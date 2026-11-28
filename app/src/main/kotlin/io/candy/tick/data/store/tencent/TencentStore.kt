package io.candy.tick.data.store.tencent

import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDownloadSpec
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.StoreQuerySpeed
import io.candy.tick.data.store.StoreSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 应用宝适配器：逐包查询（并发 4），每次点击下载时现取最新下载地址与校验和。
 */
class TencentStore : StoreSource {
    override val id: String = StoreCatalog.ID_TENCENT
    override val speed: StoreQuerySpeed = StoreQuerySpeed.SLOW_PER_APP

    override suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo> = buildMap {
        for (local in apps) {
            val release = fetchDetail(local.packageName) ?: continue
            if (release.versionCode > local.versionCode) put(local.packageName, release)
        }
    }

    override suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec {
        val fresh = fetchDetail(app.packageName) ?: throw SourceException(SourceError.NotFound)
        if (fresh.versionCode != release.versionCode) throw SourceException(SourceError.NotFound)
        if (fresh.downloadUrl.isBlank()) throw SourceException(SourceError.NotFound)
        return StoreDownloadSpec(
            url = fresh.downloadUrl,
            sizeBytes = fresh.sizeBytes,
            exactSizeBytes = fresh.exactSizeBytes,
            expectedHash = TencentProtocol.parseExpectedHash(fresh.checksum),
            headers = mapOf("User-Agent" to TencentProtocol.USER_AGENT),
        )
    }

    private suspend fun fetchDetail(packageName: String): StoreReleaseInfo? {
        val body = TencentProtocol.buildRequestBody(packageName).toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(TencentProtocol.DETAIL_ENDPOINT)
            .header("User-Agent", TencentProtocol.USER_AGENT)
            .post(body)
            .build()
        return StoreHttp.execute(request) { response ->
            if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
            TencentProtocol.parseDetail(response.body.string(), packageName)
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
