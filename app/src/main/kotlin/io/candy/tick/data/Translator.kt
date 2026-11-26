package io.candy.tick.data

import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 轻量端侧翻译（三级免费通道自动降级 + 进程内缓存）。
 * 1) translate.googleapis.com (client=gtx)
 * 2) translate.google.com (client=at，独立配额池)
 * 3) api.mymemory.translated.net（非 Google 独立兜底）
 * 单通道遇 429 自动冷却 5 分钟，无感切换至下一健康通道。
 */
class Translator {

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    @Volatile
    var lastErrorCode: String? = null
        private set

    @Volatile
    private var gtxCooldownUntilMs: Long = 0L

    @Volatile
    private var atCooldownUntilMs: Long = 0L

    @Volatile
    private var myMemoryCooldownUntilMs: Long = 0L

    private data class CacheKey(val targetLang: String, val text: String)

    private val cache = ConcurrentHashMap<CacheKey, String>()
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** 根据应用内语言设置推导目标语言码 */
    fun resolveTargetLanguage(appLanguage: String): String = when (appLanguage.lowercase(Locale.ROOT)) {
        "zh" -> "zh-CN"
        "en" -> "en"
        else -> {
            val sys = Locale.getDefault().language.lowercase(Locale.ROOT)
            if (sys == "zh") "zh-CN" else sys.ifBlank { "en" }
        }
    }

    fun getCached(text: String, targetLang: String): String? =
        cache[CacheKey(targetLang, text)]

    private fun putCache(key: CacheKey, value: String) {
        if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
        cache[key] = value
    }

    /** 单条文本翻译（命中缓存直接返回；失败返回 null） */
    suspend fun translateText(text: String, targetLang: String): String? {
        if (text.isBlank()) return text
        val key = CacheKey(targetLang, text)
        cache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            safeTranslate(text, targetLang)?.also { putCache(key, it) }
        }
    }

    /** 批量短文本翻译（用于 Commit 列表，多条合并减少请求数；拆分不对齐时自动降级逐条翻） */
    suspend fun translateBatch(texts: List<String>, targetLang: String): Map<String, String> {
        val unique = texts.filter { it.isNotBlank() }.distinct()
        val result = LinkedHashMap<String, String>()
        val missing = ArrayList<String>()
        for (item in unique) {
            val cached = cache[CacheKey(targetLang, item)]
            if (cached != null) result[item] = cached else missing.add(item)
        }
        if (missing.isEmpty()) return result

        withContext(Dispatchers.IO) {
            var idx = 0
            while (idx < missing.size) {
                val chunk = ArrayList<String>()
                var charCount = 0
                while (idx < missing.size) {
                    val next = missing[idx]
                    if (chunk.isNotEmpty() && charCount + next.length > BATCH_MAX_CHARS) break
                    chunk.add(next)
                    charCount += next.length + BATCH_SEPARATOR.length
                    idx++
                }
                if (chunk.size == 1) {
                    val item = chunk[0]
                    safeTranslate(item, targetLang)?.let { translated ->
                        putCache(CacheKey(targetLang, item), translated)
                        result[item] = translated
                    }
                } else {
                    val joined = chunk.joinToString(BATCH_SEPARATOR)
                    val merged = safeTranslate(joined, targetLang)
                    val parts = merged?.split(BATCH_SPLIT_REGEX)
                    if (parts != null && parts.size == chunk.size) {
                        for (i in chunk.indices) {
                            val translated = parts[i].trim()
                            putCache(CacheKey(targetLang, chunk[i]), translated)
                            result[chunk[i]] = translated
                        }
                    } else {
                        for (item in chunk) {
                            safeTranslate(item, targetLang)?.let { translated ->
                                putCache(CacheKey(targetLang, item), translated)
                                result[item] = translated
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    /** Markdown 轻量保结构翻译：保护围栏代码块，按段落分块翻译再拼回 */
    suspend fun translateMarkdown(markdown: String, targetLang: String): String? {
        if (markdown.isBlank()) return markdown
        val key = CacheKey(targetLang, markdown)
        cache[key]?.let { return it }

        return withContext(Dispatchers.IO) {
            val codeBlocks = ArrayList<String>()
            val protected = FENCED_CODE_REGEX.replace(markdown) { match ->
                val token = "⟦CB${codeBlocks.size}⟧"
                codeBlocks.add(match.value)
                token
            }
            val chunks = splitChunks(protected, MARKDOWN_CHUNK_CHARS)
            val translatedChunks = ArrayList<String>(chunks.size)
            for (chunk in chunks) {
                if (chunk.isBlank()) {
                    translatedChunks.add(chunk)
                    continue
                }
                val translated = safeTranslate(chunk, targetLang)
                    ?: return@withContext null
                translatedChunks.add(translated)
            }
            var merged = translatedChunks.joinToString("\n\n")
            codeBlocks.forEachIndexed { index, block ->
                merged = merged.replace("⟦CB$index⟧", block)
            }
            putCache(key, merged)
            merged
        }
    }

    private suspend fun safeTranslate(text: String, targetLang: String): String? {
        val now = System.currentTimeMillis()
        var lastErr: Exception? = null

        // 1) Google gtx
        if (now >= gtxCooldownUntilMs) {
            try {
                return requestGoogleSingle(ENDPOINT_GTX, "gtx", text, targetLang).also { lastErrorCode = null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.code == 429) gtxCooldownUntilMs = System.currentTimeMillis() + COOLDOWN_MS
                lastErr = e
            } catch (e: Exception) {
                lastErr = e
            }
        }

        // 2) Google at（独立配额池）
        if (now >= atCooldownUntilMs) {
            try {
                return requestGoogleSingle(ENDPOINT_AT, "at", text, targetLang).also { lastErrorCode = null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.code == 429) atCooldownUntilMs = System.currentTimeMillis() + COOLDOWN_MS
                lastErr = e
            } catch (e: Exception) {
                lastErr = e
            }
        }

        // 3) MyMemory（非 Google 独立免费兜底）
        if (now >= myMemoryCooldownUntilMs) {
            try {
                return requestMyMemory(text, targetLang).also { lastErrorCode = null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.code == 429) myMemoryCooldownUntilMs = System.currentTimeMillis() + COOLDOWN_MS
                lastErr = e
            } catch (e: Exception) {
                lastErr = e
            }
        }

        lastErrorCode = lastErr?.let(::mapErrorCode) ?: "E-TR-429"
        return null
    }

    private fun mapErrorCode(e: Exception): String = when (e) {
        is HttpStatusException -> "E-TR-${e.code}"
        is InterruptedIOException -> "E-TR-TIME"
        else -> "E-TR-NET"
    }

    private fun splitChunks(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val paragraphs = text.split("\n\n").flatMap { para ->
            if (para.length <= maxChars) {
                listOf(para)
            } else {
                val subChunks = ArrayList<String>()
                val lineBuf = StringBuilder()
                for (rawLine in para.split("\n")) {
                    // 兜底：无换行的超长单行按空格/长度进一步切分，防止超出 MyMemory 500 字符上限
                    val pieces = splitOverlongLine(rawLine, maxChars)
                    for (line in pieces) {
                        if (lineBuf.isNotEmpty() && lineBuf.length + line.length + 1 > maxChars) {
                            subChunks.add(lineBuf.toString())
                            lineBuf.setLength(0)
                        }
                        if (lineBuf.isNotEmpty()) lineBuf.append('\n')
                        lineBuf.append(line)
                    }
                }
                if (lineBuf.isNotEmpty()) subChunks.add(lineBuf.toString())
                subChunks
            }
        }
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        for (paragraph in paragraphs) {
            if (current.isNotEmpty() && current.length + paragraph.length + 2 > maxChars) {
                chunks.add(current.toString())
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(paragraph)
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    private fun splitOverlongLine(line: String, maxChars: Int): List<String> {
        if (line.length <= maxChars) return listOf(line)
        val out = ArrayList<String>()
        var start = 0
        while (start < line.length) {
            val end = (start + maxChars).coerceAtMost(line.length)
            if (end == line.length) {
                out.add(line.substring(start, end))
                break
            }
            val splitAt = line.lastIndexOf(' ', end).takeIf { it > start } ?: end
            out.add(line.substring(start, splitAt))
            start = if (splitAt < line.length && line[splitAt] == ' ') splitAt + 1 else splitAt
        }
        return out
    }

    private suspend fun requestGoogleSingle(
        endpoint: String,
        clientTag: String,
        text: String,
        targetLang: String,
    ): String {
        val body = FormBody.Builder()
            .add("client", clientTag)
            .add("sl", "auto")
            .add("tl", targetLang)
            .add("dt", "t")
            .add("q", text)
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", "Mozilla/5.0")
            .post(body)
            .build()
        val raw = executeRequest(request)
        val root = json.parseToJsonElement(raw).jsonArray
        val segments = root.firstOrNull() as? JsonArray ?: throw IOException("Unexpected response")
        val sb = StringBuilder()
        for (segment in segments) {
            val piece = (segment as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content ?: continue
            sb.append(piece)
        }
        return sb.toString()
    }

    private suspend fun requestMyMemory(text: String, targetLang: String): String {
        val parts = splitChunks(text, MYMEMORY_MAX_CHARS)
        val out = ArrayList<String>(parts.size)
        for (part in parts) {
            if (part.isBlank()) {
                out.add(part)
                continue
            }
            val url = ENDPOINT_MYMEMORY.toHttpUrl().newBuilder()
                .addQueryParameter("q", part)
                .addQueryParameter("langpair", "autodetect|$targetLang")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()
            val raw = executeRequest(request)
            val root = json.parseToJsonElement(raw).jsonObject
            val status = root["responseStatus"]?.jsonPrimitive?.intOrNull ?: 200
            if (status == 429) throw HttpStatusException(429)
            if (status !in 200..299) throw HttpStatusException(status)
            val translated = root["responseData"]?.jsonObject?.get("translatedText")?.jsonPrimitive?.content
                ?: throw IOException("Missing MyMemory translation")
            out.add(translated)
        }
        return out.joinToString("\n\n")
    }

    private suspend fun executeRequest(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { res ->
                            if (!res.isSuccessful) throw HttpStatusException(res.code)
                            val raw = res.body.string()
                            if (continuation.isActive) continuation.resume(raw)
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    private companion object {
        const val ENDPOINT_GTX = "https://translate.googleapis.com/translate_a/single"
        const val ENDPOINT_AT = "https://translate.google.com/translate_a/single"
        const val ENDPOINT_MYMEMORY = "https://api.mymemory.translated.net/get"
        const val TIMEOUT_S = 15L
        const val COOLDOWN_MS = 5L * 60L * 1000L
        const val BATCH_MAX_CHARS = 1500
        const val MARKDOWN_CHUNK_CHARS = 1800
        const val MYMEMORY_MAX_CHARS = 450
        const val MAX_CACHE_ENTRIES = 500
        const val BATCH_SEPARATOR = "\n⟦CT_SEP⟧\n"
        val BATCH_SPLIT_REGEX = Regex("\\s*⟦\\s*CT_SEP\\s*⟧\\s*")
        val FENCED_CODE_REGEX = Regex("```[\\s\\S]*?```")
    }
}
