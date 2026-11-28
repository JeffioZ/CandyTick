package io.candy.tick.data.store.huawei

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
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 华为应用市场适配器：先通过 client.front2 握手拿会话签名，再批量走 client.diffUpgrade2。
 */
class HuaweiStore : StoreSource {
    override val id: String = StoreCatalog.ID_HUAWEI
    override val speed: StoreQuerySpeed = StoreQuerySpeed.FAST_BATCH

    private val deviceId: String = HuaweiProtocol.deriveDeviceId(UUID.randomUUID().toString())
    private val signMutex = Mutex()
    @Volatile
    private var cachedSign: String = ""
    private val cachedUrls = ConcurrentHashMap<String, String>()

    override suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo> {
        if (apps.isEmpty()) return emptyMap()
        val installedByPkg = apps.associateBy { it.packageName }
        val result = LinkedHashMap<String, StoreReleaseInfo>()
        for (chunk in apps.chunked(HuaweiProtocol.BATCH_SIZE)) {
            val entries = queryDiffUpgrade(chunk, installedByPkg)
            for ((pkg, entry) in entries) {
                if (entry.downloadUrl.isNotBlank()) cachedUrls[pkg] = entry.downloadUrl
                result[pkg] = entry.release
            }
        }
        return result
    }

    override suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec {
        // 现查一次单应用以拿到未过期的直链，失败时回退到检查阶段缓存的直链
        val fresh = StoreHttp.runCatchingCancellable {
            queryDiffUpgrade(listOf(app), mapOf(app.packageName to app))[app.packageName]
        }.getOrNull()
        val url = fresh?.downloadUrl?.takeIf { it.isNotBlank() }
            ?: cachedUrls[app.packageName]?.takeIf { it.isNotBlank() }
            ?: throw SourceException(SourceError.NotFound)
        val sha256 = fresh?.release?.checksum?.ifBlank { release.checksum } ?: release.checksum
        val size = fresh?.release?.sizeBytes?.takeIf { it > 0L } ?: release.sizeBytes
        return StoreDownloadSpec(
            url = url,
            sizeBytes = size,
            expectedHash = sha256.takeIf { it.isNotBlank() }?.let { ExpectedHash(HashAlgorithm.SHA256, it) },
        )
    }

    private suspend fun queryDiffUpgrade(
        apps: List<LocalInstalledApp>,
        installedByPkg: Map<String, LocalInstalledApp>,
    ): Map<String, HuaweiProtocol.UpdateEntry> {
        val sign = ensureSessionSign(forceRefresh = false)
        val firstBody = postGzip(HuaweiProtocol.buildDiffUpgradeFields(apps, deviceId, sign))
        if (HuaweiProtocol.isSessionExpired(firstBody)) {
            val refreshed = ensureSessionSign(forceRefresh = true)
            val retryBody = postGzip(HuaweiProtocol.buildDiffUpgradeFields(apps, deviceId, refreshed))
            return HuaweiProtocol.parseDiffUpgradeResponse(retryBody, installedByPkg)
        }
        return HuaweiProtocol.parseDiffUpgradeResponse(firstBody, installedByPkg)
    }

    private suspend fun ensureSessionSign(forceRefresh: Boolean): String = signMutex.withLock {
        if (!forceRefresh && cachedSign.isNotBlank()) return cachedSign
        val body = postGzip(HuaweiProtocol.buildFrontFields(deviceId))
        val sign = HuaweiProtocol.parseSessionSign(body)
            ?: throw SourceException(SourceError.NotFound)
        cachedSign = sign
        return sign
    }

    private suspend fun postGzip(fields: Map<String, String>): String {
        val payload = HuaweiProtocol.gzipEncodeForm(fields)
        val request = Request.Builder()
            .url(HuaweiProtocol.GATEWAY_URL)
            .header("Content-Encoding", "gzip")
            .header("Accept", "application/json")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", HuaweiProtocol.USER_AGENT)
            .header("X-Traffic-Retry", "0")
            .post(payload.toRequestBody(GZIP_MEDIA))
            .build()
        return StoreHttp.execute(request) { response ->
            if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
            val raw = response.body.bytes()
            // 华为接口即使收到 Accept-Encoding: identity 仍可能直接返回 GZIP 压缩体，需按魔数自动解压
            val decoded = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                try {
                    GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
                } catch (e: IOException) {
                    throw SourceException(SourceError.NetworkUnreachable, e)
                }
            } else {
                raw
            }
            decoded.toString(Charsets.UTF_8)
        }
    }

    companion object {
        private val GZIP_MEDIA = "application/x-gzip".toMediaType()
    }
}
