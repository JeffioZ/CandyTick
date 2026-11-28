package io.candy.tick.data.store.vivo

import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDownloadSpec
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.StoreQuerySpeed
import io.candy.tick.data.store.StoreSource
import java.io.IOException
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * vivo 应用商店适配器：批量查询（每批 100 包），下载前跟随 302 并升级为 HTTPS 直链。
 */
class VivoStore : StoreSource {
    override val id: String = StoreCatalog.ID_VIVO
    override val speed: StoreQuerySpeed = StoreQuerySpeed.FAST_BATCH
    override val batchSize: Int = VivoProtocol.MAX_BATCH

    private val noRedirectClient = StoreHttp.client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo> {
        if (apps.isEmpty()) return emptyMap()
        val installed = apps.associateBy { it.packageName }
        val out = mutableMapOf<String, StoreReleaseInfo>()
        for (chunk in apps.chunked(VivoProtocol.MAX_BATCH)) {
            val form = VivoProtocol.buildUpdateForm(chunk)
            val req = Request.Builder()
                .url(VivoProtocol.UPDATE_URL)
                .header("User-Agent", VivoProtocol.userAgent())
                .post(form.toRequestBody(FORM_MEDIA))
                .build()
            val batch = StoreHttp.execute(req) { response ->
                if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
                VivoProtocol.parseUpdateResponse(response.body.string(), installed)
            }
            out.putAll(batch)
        }
        return out
    }

    override suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec {
        val target = if (release.appId > 0L) {
            val form = FormBody.Builder()
                .add("idListStr", release.appId.toString())
                .add("content_complete", "1")
                .add("needUpCmt", "1")
                .add("app_version", VivoProtocol.APP_VERSION)
                .build()
            val request = Request.Builder().url(VivoProtocol.DETAIL_URL)
                .header("User-Agent", VivoProtocol.userAgent()).post(form).build()
            try {
                StoreHttp.execute(request) { response ->
                    if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
                    VivoProtocol.downloadDetail(response.body.string(), app.packageName, release)
                }
            } catch (e: SourceException) {
                val error = e.error
                val temporary = error == SourceError.Timeout || error == SourceError.NetworkUnreachable ||
                    (error is SourceError.Http && error.code in 500..599)
                if (!temporary || release.downloadUrl.isBlank()) throw e
                release
            } catch (e: IOException) {
                // A disconnect while reading the detail body arrives after OkHttp's onResponse.
                if (release.downloadUrl.isBlank()) throw e
                release
            }
        } else release
        val rawUrl = target.downloadUrl.takeIf { it.isNotBlank() } ?: throw SourceException(SourceError.NotFound)
        val resolvedUrl = resolveHttpsRedirect(rawUrl)
        return StoreDownloadSpec(
            url = resolvedUrl,
            sizeBytes = release.sizeBytes,
            exactSizeBytes = null,
            expectedHash = VivoProtocol.expectedMd5(target.checksum),
            headers = mapOf("User-Agent" to VivoProtocol.userAgent()),
        )
    }

    private suspend fun resolveHttpsRedirect(startUrl: String): String {
        var current = startUrl
        repeat(5) {
            val req = Request.Builder().url(current).header("User-Agent", VivoProtocol.userAgent()).head().build()
            val next = StoreHttp.runCatchingCancellable {
                StoreHttp.execute(req, noRedirectClient) { resp ->
                    if (resp.code in REDIRECT_CODES) {
                        resp.header("Location")
                            ?.let { loc -> resp.request.url.resolve(loc)?.toString() ?: loc }
                            ?.let(StoreHttp::upgradeToHttps)
                            ?.takeIf { it.isNotBlank() }
                    } else null
                }
            }.getOrNull() ?: return current
            current = next
        }
        return current
    }

    companion object {
        private val FORM_MEDIA = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
