package io.candy.tick.data.store.taptap

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * TapTap 商店适配器：批量查询游戏与应用更新，下载时现取 APK 直链。
 */
class TapTapStore : StoreSource {
    override val id: String = StoreCatalog.ID_TAPTAP
    override val speed: StoreQuerySpeed = StoreQuerySpeed.FAST_BATCH

    private val uid: String = UUID.randomUUID().toString()
    private val xUa: String = TapTapProtocol.buildXUa(uid)

    override suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo> {
        if (apps.isEmpty()) return emptyMap()
        val installedByPkg = apps.associateBy { it.packageName }
        val result = LinkedHashMap<String, StoreReleaseInfo>()
        for (chunk in apps.chunked(TapTapProtocol.BATCH_SIZE)) {
            val fields = TapTapProtocol.signForm(
                params = mapOf("identifiers" to chunk.joinToString(",") { it.packageName }),
                xUa = xUa,
            )
            val request = Request.Builder()
                .url(TapTapProtocol.buildUrl(TapTapProtocol.BATCH_UPDATE_PATH, xUa))
                .header("User-Agent", TapTapProtocol.USER_AGENT)
                .post(TapTapProtocol.encodeForm(fields).toRequestBody(FORM_MEDIA))
                .build()
            val batch = StoreHttp.execute(request) { response ->
                if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
                TapTapProtocol.parseBatchUpdateResponse(response.body.string(), installedByPkg)
            }
            result.putAll(batch)
        }
        return result
    }

    override suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec {
        if (release.appId <= 0L) throw SourceException(SourceError.NotFound)
        val fields = TapTapProtocol.signForm(
            params = mapOf(
                "node" to uid,
                "referer" to "search",
                "end_point" to "d1",
                "auto_download" to "0",
                "sandbox" to "1",
                "abi" to "arm64-v8a",
                "id" to release.appId.toString(),
                "screen_densities" to "xxhdpi",
            ),
            xUa = xUa,
        )
        val request = Request.Builder()
            .url(TapTapProtocol.buildUrl(TapTapProtocol.APK_DETAIL_PATH, xUa))
            .header("User-Agent", TapTapProtocol.USER_AGENT)
            .post(TapTapProtocol.encodeForm(fields).toRequestBody(FORM_MEDIA))
            .build()
        val info = StoreHttp.execute(request) { response ->
            if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
            TapTapProtocol.parseApkDetailResponse(response.body.string())
        } ?: throw SourceException(SourceError.NotFound)
        val md5 = info.md5.ifBlank { release.checksum }
        return StoreDownloadSpec(
            url = info.url,
            sizeBytes = info.sizeBytes.takeIf { it > 0L } ?: release.sizeBytes,
            expectedHash = md5.takeIf { it.isNotBlank() }?.let { ExpectedHash(HashAlgorithm.MD5, it) },
        )
    }

    companion object {
        private val FORM_MEDIA = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
    }
}
