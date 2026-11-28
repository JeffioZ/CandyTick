package io.candy.tick.data.store.fdroid

import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.model.FdroidMirror
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreDownloadSpec
import io.candy.tick.data.store.StoreHttp
import io.candy.tick.data.store.StoreQueryResult
import io.candy.tick.data.store.StoreQuerySpeed
import io.candy.tick.data.store.StoreSource
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    private data class IndexRevision(val base: String, val etag: String, val length: Long, val modified: Long)
    private data class ParsedIndex(
        val revision: IndexRevision,
        val installedBuilds: Map<String, Pair<Long, String>>,
        val result: StoreQueryResult,
    )

    private val syncMutex = Mutex()
    private var lastScan: ParsedIndex? = null
    private val indexClient = StoreHttp.client.newBuilder()
        .readTimeout(45L, TimeUnit.SECONDS)
        .callTimeout(90L, TimeUnit.SECONDS)
        .build()

    override suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo> = checkBatch(apps).updates

    override suspend fun checkBatch(apps: List<LocalInstalledApp>): StoreQueryResult {
        if (apps.isEmpty()) return StoreQueryResult(emptyMap())
        val repoBase = FdroidProtocol.repoBaseUrl(id, mirrorProvider())
        val installed = apps.associateBy { it.packageName }
        return syncMutex.withLock { readIndex(repoBase, installed) }
    }

    override suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec {
        val previousBase = FdroidMirror.entries.map { FdroidProtocol.repoBaseUrl(id, it) }
            .firstOrNull { release.downloadUrl.startsWith("$it/") } ?: throw SourceException(SourceError.NotFound)
        val currentBase = FdroidProtocol.repoBaseUrl(id, mirrorProvider())
        return StoreDownloadSpec(
            url = currentBase + release.downloadUrl.removePrefix(previousBase),
            sizeBytes = release.sizeBytes,
            exactSizeBytes = release.exactSizeBytes,
            expectedHash = FdroidProtocol.expectedSha256(release.checksum) ?: throw SourceException(SourceError.NotFound),
            headers = mapOf("User-Agent" to "CandyTick"),
        )
    }

    private suspend fun readIndex(repoBase: String, apps: Map<String, LocalInstalledApp>): StoreQueryResult {
        val dir = File(cacheDir, "store-index/$id").apply { mkdirs() }
        val indexFile = File(dir, "index-v2.json")
        val etagFile = File(dir, "index-v2.etag")
        val urlFile = File(dir, "index-v2.url")
        val sameBase = urlFile.isFile && runCatching { urlFile.readText().trim() == repoBase }.getOrDefault(false)
        val savedEtag = if (sameBase && indexFile.isFile && etagFile.isFile) {
            runCatching { etagFile.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
        } else null

        fun revision(etag: String) = IndexRevision(repoBase, etag, indexFile.length(), indexFile.lastModified())
        fun remember(etag: String?, result: StoreQueryResult): StoreQueryResult {
            lastScan = etag?.takeIf(String::isNotBlank)?.let { tag ->
                ParsedIndex(revision(tag), apps.mapValues { (_, app) -> app.versionCode to app.signatureSha256 }, result)
            }
            return result
        }

        fun scan(file: File): StoreQueryResult {
            val unconfirmed = mutableSetOf<String>()
            val updates = file.inputStream().use { FdroidProtocol.scanUpdates(it, id, repoBase, apps, unconfirmed = unconfirmed) }
            return StoreQueryResult(updates, unconfirmed, error = "InvalidIndexEntry".takeIf { unconfirmed.isNotEmpty() })
        }
        suspend fun fetch(etag: String?): StoreQueryResult? {
            val request = Request.Builder().url(FdroidProtocol.indexUrl(repoBase)).header("User-Agent", "CandyTick")
                .apply { if (etag != null) header("If-None-Match", etag) }.build()
            val part = File.createTempFile("index-v2-", ".part", dir)
            try {
                val download = StoreHttp.execute(request, indexClient) { response ->
                    if (response.code == 304 && etag != null) return@execute null
                    if (!response.isSuccessful) throw SourceException(SourceError.Http(response.code))
                    response.body.byteStream().use { input ->
                        part.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    }
                    response.header("ETag").orEmpty()
                } ?: return null
                val result = scan(part)
                currentCoroutineContext().ensureActive()
                // With no ETag after a crash the next request must fetch a full index.
                if (etagFile.exists()) check(etagFile.delete())
                Files.move(part.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                urlFile.writeText(repoBase)
                if (download.isNotBlank()) etagFile.writeText(download)
                return remember(download, result)
            } finally {
                part.delete()
            }
        }
        fetch(savedEtag)?.let { return it }
        val previous = lastScan
        if (savedEtag != null && previous != null && previous.revision == revision(savedEtag) &&
            apps.all { (pkg, app) -> previous.installedBuilds[pkg] == (app.versionCode to app.signatureSha256) }
        ) {
            val cached = previous.result
            val unconfirmed = cached.unconfirmed.intersect(apps.keys)
            return cached.copy(updates = cached.updates.filterKeys { it in apps }, unconfirmed = unconfirmed,
                error = cached.error.takeIf { unconfirmed.isNotEmpty() })
        }
        return try {
            remember(savedEtag, scan(indexFile))
        } catch (_: Exception) {
            // Old releases could cache a truncated 200 response and then keep receiving 304.
            requireNotNull(fetch(null))
        }
    }
}
