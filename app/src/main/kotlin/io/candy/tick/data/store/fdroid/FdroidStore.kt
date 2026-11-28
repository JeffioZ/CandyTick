package io.candy.tick.data.store.fdroid

import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.model.FdroidMirror
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreDownloadSpec
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.StoreQuerySpeed
import io.candy.tick.data.store.StoreSource
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request

/**
 * F-Droid / IzzyOnDroid 适配器：
 * 下载并按 ETag 缓存 index-v2.json 到私有缓存目录，流式扫描本机已装包。
 */
class FdroidStore(
    override val id: String,
    private val cacheDir: File,
    private val mirrorProvider: () -> FdroidMirror = { FdroidMirror.TUNA },
) : StoreSource {
    override val speed: StoreQuerySpeed = StoreQuerySpeed.LOCAL_INDEX

    private val syncMutex = Mutex()
    private val indexClient = StoreHttp.client.newBuilder()
        .readTimeout(45L, TimeUnit.SECONDS)
        .callTimeout(90L, TimeUnit.SECONDS)
        .build()

    override suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo> {
        if (apps.isEmpty()) return emptyMap()
        val repoBase = FdroidProtocol.repoBaseUrl(id, mirrorProvider())
        val indexFile = syncIndexFile(repoBase)
        return indexFile.inputStream().use { input ->
            FdroidProtocol.scanUpdates(input, id, repoBase, apps.associateBy { it.packageName })
        }
    }

    override suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec {
        if (release.downloadUrl.isBlank()) throw SourceException(SourceError.NotFound)
        return StoreDownloadSpec(
            url = release.downloadUrl,
            sizeBytes = release.sizeBytes,
            expectedHash = FdroidProtocol.expectedSha256(release.checksum),
            headers = mapOf("User-Agent" to "CandyTick"),
        )
    }

    private suspend fun syncIndexFile(repoBase: String): File = syncMutex.withLock {
        val dir = File(cacheDir, "store-index/$id").apply { mkdirs() }
        val indexFile = File(dir, "index-v2.json")
        val etagFile = File(dir, "index-v2.etag")
        val urlFile = File(dir, "index-v2.url")
        val sameBase = urlFile.isFile && runCatching { urlFile.readText().trim() == repoBase }.getOrDefault(false)
        val savedEtag = if (sameBase && indexFile.isFile && etagFile.isFile) {
            runCatching { etagFile.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
        } else null

        val req = Request.Builder()
            .url(FdroidProtocol.indexUrl(repoBase))
            .header("User-Agent", "CandyTick")
            .apply { if (savedEtag != null) header("If-None-Match", savedEtag) }
            .get()
            .build()

        val part = File.createTempFile("index-v2-", ".part", dir)
        try {
            StoreHttp.execute(req, indexClient) { response ->
                if (response.code == 304 && indexFile.isFile) return@execute
                if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
                response.body.byteStream().use { input ->
                    part.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                if (!part.renameTo(indexFile)) {
                    part.copyTo(indexFile, overwrite = true)
                    part.delete()
                }
                val newEtag = response.header("ETag")?.takeIf { it.isNotBlank() }
                if (newEtag != null) {
                    runCatching { etagFile.writeText(newEtag) }
                } else {
                    runCatching { etagFile.delete() }
                }
                runCatching { urlFile.writeText(repoBase) }
            }
        } finally {
            if (part.exists()) part.delete()
        }
        indexFile
    }
}
