package io.candy.tick.data.store.appchina

import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDownloadSpec
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.StoreQuerySpeed
import io.candy.tick.data.store.StoreSource
import okhttp3.Request

/**
 * 应用汇适配器：逐包查询（并发 4），下载前解析详情页最新直链。
 */
class AppchinaStore : StoreSource {
    override val id: String = StoreCatalog.ID_APPCHINA
    override val speed: StoreQuerySpeed = StoreQuerySpeed.SLOW_PER_APP

    override suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo> = buildMap {
        for (local in apps) {
            val release = queryPackage(local.packageName) ?: continue
            if (release.versionCode > local.versionCode) put(local.packageName, release)
        }
    }

    override suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec {
        val req = Request.Builder()
            .url(AppchinaProtocol.detailUrl(app.packageName))
            .header("User-Agent", AppchinaProtocol.USER_AGENT)
            .header("Referer", AppchinaProtocol.WEB_BASE + "/")
            .get()
            .build()
        val fresh = StoreHttp.runCatchingCancellable {
            StoreHttp.execute(req) { resp ->
                if (!resp.isSuccessful) throw SourceException(SourceError.Http(resp.code))
                AppchinaProtocol.parseDetailPage(resp.body.string(), release)
            }
        }.getOrDefault(release)
        val url = fresh.downloadUrl.takeIf { it.isNotBlank() } ?: throw SourceException(SourceError.NotFound)
        return StoreDownloadSpec(
            url = url,
            sizeBytes = fresh.sizeBytes,
            exactSizeBytes = null,
            headers = mapOf(
                "User-Agent" to AppchinaProtocol.USER_AGENT,
                "Referer" to AppchinaProtocol.WEB_BASE + "/",
            ),
        )
    }

    private suspend fun queryPackage(packageName: String): StoreReleaseInfo? {
        val req = Request.Builder()
            .url(AppchinaProtocol.searchUrl(packageName))
            .header("User-Agent", AppchinaProtocol.USER_AGENT)
            .header("Referer", AppchinaProtocol.WEB_BASE + "/")
            .header("Accept", "application/json")
            .get()
            .build()
        return StoreHttp.execute(req) { resp ->
            if (resp.code == 404) return@execute null
            if (!resp.isSuccessful) throw SourceException(SourceError.Http(resp.code))
            AppchinaProtocol.parseSearchMatch(resp.body.string(), packageName)
        }
    }
}
