package io.candy.tick.data.store

import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 商店网络请求共享工具：统一超时与协程取消语义，避免每个商店重复写 OkHttp 回调。
 */
internal object StoreHttp {
    private const val TIMEOUT_S = 15L

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(30L, TimeUnit.SECONDS)
        .build()

    /** 协程安全的 runCatching：遇到 CancellationException 立即重抛，避免吞掉协程取消信号。 */
    inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }

    suspend fun <T> execute(
        request: Request,
        httpClient: OkHttpClient = client,
        read: (Response) -> T,
    ): T = suspendCancellableCoroutine { cont ->
        val call = httpClient.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isActive) return
                val err = if (e is InterruptedIOException) SourceError.Timeout else SourceError.NetworkUnreachable
                cont.resumeWith(Result.failure(SourceException(err, e)))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!cont.isActive) {
                    response.close()
                    return
                }
                try {
                    val value = response.use(read)
                    cont.resumeWith(Result.success(value))
                } catch (e: Exception) {
                    cont.resumeWith(Result.failure(e))
                }
            }
        })
    }

    fun upgradeToHttps(raw: String): String = when {
        raw.startsWith("https://", ignoreCase = true) -> raw
        raw.startsWith("http://", ignoreCase = true) -> "https://" + raw.substring(7)
        raw.startsWith("//") -> "https:$raw"
        else -> ""
    }
}
