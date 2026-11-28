package io.candy.tick.data.store.coolapk

import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDownloadSpec
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.StoreQuerySpeed
import io.candy.tick.data.store.StoreSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * 酷安适配器：逐包查详情，下载时读取 302 Location 直链并校验 MD5。
 */
class CoolapkStore : StoreSource {
    override val id: String = StoreCatalog.ID_COOLAPK
    override val speed: StoreQuerySpeed = StoreQuerySpeed.SLOW_PER_APP

    private val deviceCode = CoolapkProtocol.randomDeviceCode()
    private val noRedirectClient = StoreHttp.client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo> = coroutineScope {
        val out = mutableMapOf<String, StoreReleaseInfo>()
        for (batch in apps.chunked(CONCURRENCY)) {
            val items = batch.map { local ->
                async {
                    val info = StoreHttp.runCatchingCancellable { fetchDetail(local.packageName) }.getOrNull() ?: return@async null
                    if (info.versionCode > local.versionCode) local.packageName to info else null
                }
            }.awaitAll().filterNotNull()
            out.putAll(items)
        }
        out
    }

    override suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec {
        val fresh = fetchDetail(app.packageName) ?: throw SourceException(SourceError.NotFound)
        val headers = withContext(Dispatchers.Default) { CoolapkProtocol.buildHeaders(deviceCode) }
        val url = "${CoolapkProtocol.API_BASE}/v6/apk/download".toHttpUrl().newBuilder()
            .addQueryParameter("pn", app.packageName)
            .addQueryParameter("aid", fresh.appId.toString())
            .build()
        val req = Request.Builder().url(url).apply { headers.forEach(::header) }.get().build()
        val location = StoreHttp.execute(req, noRedirectClient) { resp ->
            if (resp.code !in 300..399) throw SourceException(SourceError.Http(resp.code))
            val resolved = resp.header("Location")?.let { loc -> resp.request.url.resolve(loc)?.toString() ?: loc }.orEmpty()
            CoolapkProtocol.upgradeUrl(resolved)
        }
        if (location.isBlank()) throw SourceException(SourceError.NotFound)
        return StoreDownloadSpec(
            url = location,
            sizeBytes = fresh.sizeBytes,
            expectedHash = CoolapkProtocol.expectedMd5(fresh.checksum),
            headers = mapOf("User-Agent" to CoolapkProtocol.USER_AGENT),
        )
    }

    private suspend fun fetchDetail(packageName: String): StoreReleaseInfo? {
        val headers = withContext(Dispatchers.Default) { CoolapkProtocol.buildHeaders(deviceCode) }
        val url = "${CoolapkProtocol.API_BASE}/v6/apk/detail".toHttpUrl().newBuilder()
            .addQueryParameter("id", packageName)
            .build()
        val req = Request.Builder().url(url).apply { headers.forEach(::header) }.get().build()
        return StoreHttp.execute(req, noRedirectClient) { resp ->
            if (resp.code == 404) return@execute null
            if (!resp.isSuccessful) throw SourceException(SourceError.Http(resp.code))
            CoolapkProtocol.parseDetail(resp.body.string(), packageName)
        }
    }

    companion object {
        private const val CONCURRENCY = 4
    }
}
