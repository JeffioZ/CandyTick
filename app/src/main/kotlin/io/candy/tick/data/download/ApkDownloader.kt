package io.candy.tick.data.download

import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long,
    val fraction: Float?,
)

/**
 * 通用 APK 流式下载内核（GitHub 仓库、应用商店、自身更新共用）。
 * 负责磁盘空间预检、自定义请求头、协作取消、写盘大小上限及统一错误归类。
 */
class ApkDownloader(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 直接下载 URL 到 [dest]（调用方负责 .part 临时文件与原子重命名）。
     * @return 实际写入字节数
     */
    suspend fun download(
        url: String,
        dest: File,
        expectedSize: Long = 0L,
        headers: Map<String, String> = emptyMap(),
        onProgress: (DownloadProgress) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        try {
            checkDiskSpace(dest, expectedSize)
            val request = Request.Builder()
                .url(url.toHttpUrl())
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            val ctx = coroutineContext
            executeCall(request) { response ->
                if (!response.isSuccessful) {
                    val error = when (response.code) {
                        404 -> SourceError.NotFound
                        429 -> SourceError.RateLimited(null)
                        else -> SourceError.Http(response.code)
                    }
                    throw SourceException(error)
                }
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
                body.byteStream().use { input ->
                    streamToFile(input, dest, total, ctx, onProgress)
                }
            }
        } catch (e: Exception) {
            rethrowMapped(e)
        }
    }

    private suspend fun <T> executeCall(request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    val error = if (e is InterruptedIOException) SourceError.Timeout else SourceError.NetworkUnreachable
                    continuation.resumeWith(Result.failure(SourceException(error, e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    try {
                        val result = response.use(read)
                        continuation.resumeWith(Result.success(result))
                    } catch (e: Exception) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }
            })
        }

    companion object {
        private const val TIMEOUT_S = 15L
        const val DISK_HEADROOM_BYTES = 64L * 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()

        /** 确保目标父目录存在，并在已知包体大小时预留 64 MiB 安全余量。 */
        fun checkDiskSpace(dest: File, expectedSize: Long) {
            dest.parentFile?.mkdirs()
            if (expectedSize > 0 &&
                (dest.parentFile?.usableSpace ?: Long.MAX_VALUE) < expectedSize + DISK_HEADROOM_BYTES
            ) {
                throw SourceException(SourceError.StorageFull)
            }
        }

        /** 将输入流写入文件，支持协程协作取消、2 GiB 上限止损与按百分点节流回调。 */
        fun streamToFile(
            input: InputStream,
            dest: File,
            expectedTotal: Long,
            context: CoroutineContext,
            onProgress: (DownloadProgress) -> Unit = {},
            maxBytes: Long = MAX_DOWNLOAD_BYTES,
        ): Long {
            dest.parentFile?.mkdirs()
            var read = 0L
            dest.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var lastPercent = -1
                var lastUnknownBucket = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    context.ensureActive()
                    output.write(buffer, 0, n)
                    read += n
                    if (read > maxBytes) throw IOException("Download exceeds size limit")
                    if (expectedTotal > 0) {
                        val fraction = (read.toDouble() / expectedTotal).toFloat().coerceIn(0f, 1f)
                        val percent = (fraction * 100).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(DownloadProgress(read, expectedTotal, fraction))
                        }
                    } else {
                        val bucket = read / (256 * 1024)
                        if (bucket != lastUnknownBucket) {
                            lastUnknownBucket = bucket
                            onProgress(DownloadProgress(read, 0L, null))
                        }
                    }
                }
            }
            return read
        }

        fun rethrowMapped(e: Throwable): Nothing = when (e) {
            is CancellationException -> throw e
            is SourceException -> throw e
            is InterruptedIOException -> throw SourceException(SourceError.Timeout, e)
            is IOException -> if (e.message?.contains("ENOSPC") == true) {
                throw SourceException(SourceError.StorageFull, e)
            } else {
                throw SourceException(SourceError.NetworkUnreachable, e)
            }
            else -> throw SourceException(SourceError.NetworkUnreachable, e)
        }
    }
}
