package io.candy.tick.data.store.xiaomi

import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.errorCode
import io.candy.tick.data.model.StoreAppUpdate
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDownloadSpec
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.StoreQueryResult
import io.candy.tick.data.store.StoreQuerySpeed
import io.candy.tick.data.store.StoreSource
import io.candy.tick.data.store.storeCheckErrorCode
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 小米应用商店适配器。批量响应漏掉系统分组时，只复核此前有更新的系统应用。
 */
class XiaomiStore(private val previousUpdates: () -> Map<String, StoreAppUpdate>) : StoreSource {
    override val id: String = StoreCatalog.ID_XIAOMI
    override val speed: StoreQuerySpeed = StoreQuerySpeed.FAST_BATCH

    private val instanceId: String = ByteArray(16).also(Random.Default::nextBytes).joinToString("") { "%02x".format(it) }
    @Volatile
    private var dctx: String = ""

    override suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo> =
        checkBatch(apps).updates

    override suspend fun checkBatch(apps: List<LocalInstalledApp>): StoreQueryResult {
        if (apps.isEmpty()) return StoreQueryResult(emptyMap())
        ensureDctx()
        val first = requestUpdates(apps)
        if (apps.size == 1 || first.hasSystemList) return StoreQueryResult(first.updates)

        val previous = previousUpdates()
        val missing = apps.filter { app ->
            val old = previous[app.packageName]
            app.isSystemApp && app.packageName !in first.recognizedPackages &&
                old != null && old.installedVersionCode == app.versionCode &&
                old.candidates.any { it.storeId == id && it.versionCode > app.versionCode }
        }
        if (missing.isEmpty()) return StoreQueryResult(first.updates)

        val realAnchor = apps.firstOrNull { it.packageName == XiaomiProtocol.MIUI_ANCHOR_PKG }
        val reason = try {
            // The two initial HTTP calls have 30s limits. Leave room in the 90s full-check budget.
            val confirmed = withTimeoutOrNull(12_000L) { requestUpdates(missing, realAnchor) }
            if (confirmed != null) return StoreQueryResult(first.updates + confirmed.updates)
            SourceError.Timeout.errorCode
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.storeCheckErrorCode()
        }
        return StoreQueryResult(first.updates, missing.mapTo(mutableSetOf()) { it.packageName }, error = reason)
    }

    private suspend fun requestUpdates(
        apps: List<LocalInstalledApp>,
        installedAnchor: LocalInstalledApp? = null,
    ): XiaomiProtocol.UpdateResult {
        val fields = XiaomiProtocol.buildUpdateFields(apps, instanceId, dctx, installedAnchor = installedAnchor)
        XiaomiProtocol.signFormForUrl(XiaomiProtocol.UPDATE_URL, fields)
        val body = XiaomiProtocol.encodeForm(fields).toRequestBody(FORM_MEDIA)
        val request = Request.Builder()
            .url(XiaomiProtocol.UPDATE_URL)
            .post(body)
            .build()
        return StoreHttp.execute(request) { response ->
            if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
            XiaomiProtocol.parseUpdateResult(response.body.string(), apps.associateBy { it.packageName })
        }
    }

    override suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec {
        if (release.appId <= 0L) throw SourceException(SourceError.NotFound)
        val primary = fetchDownloadSpec(XiaomiProtocol.buildDownloadMetaUrl(app, release, instanceId), release.versionCode)
        // 小米系统应用走 upgrade 场景常返回空 apks[]，回退到 detail 场景即可拿到完整包体与分包
        val spec = primary ?: fetchDownloadSpec(XiaomiProtocol.buildDetailDownloadMetaUrl(app, release, instanceId), release.versionCode)
            ?: throw SourceException(SourceError.NotFound)
        return if (spec.sizeBytes > 0L || spec.extraParts.isNotEmpty()) spec else spec.copy(sizeBytes = release.sizeBytes)
    }

    private suspend fun ensureDctx() {
        if (dctx.isNotBlank()) return
        StoreHttp.runCatchingCancellable {
            val url = XiaomiProtocol.signUrl("${XiaomiProtocol.MARKET_BASE}/expId?${XiaomiProtocol.encodeForm(XiaomiProtocol.commonParams(instanceId))}")
            val request = Request.Builder().url(url).get().build()
            StoreHttp.execute(request) { response ->
                if (!response.isSuccessful) return@execute
                val text = response.body.string()
                val match = Regex(""""dctx"\s*:\s*"([^"]+)"""").find(text)
                match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { dctx = it }
            }
        }
    }

    private suspend fun fetchDownloadSpec(metaUrl: String, expectedVersion: Long): StoreDownloadSpec? {
        val request = Request.Builder().url(metaUrl).get().build()
        return StoreHttp.execute(request) { response ->
            if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
            XiaomiProtocol.parseDownloadSpec(response.body.string(), headers = DOWNLOAD_HEADERS, expectedVersion = expectedVersion)
        }
    }

    companion object {
        private val FORM_MEDIA = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
        private val DOWNLOAD_HEADERS = mapOf(
            "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 15; Xiaomi)",
        )
    }
}
