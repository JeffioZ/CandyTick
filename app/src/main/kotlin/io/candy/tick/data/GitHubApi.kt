package io.candy.tick.data

import io.candy.tick.data.model.ApkAsset
import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.data.model.parseInstant
import io.candy.tick.util.ApkPicker
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * GitHub REST 请求、条件缓存和安装包流式下载。
 */
class GitHubApi(private val tokenProvider: () -> String?) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS) // 整体超时 15s（四类失败约定）
        .build()
    private val downloadClient = client.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()

    /** Token 被 GitHub 拒绝（401）过：已自动降级匿名，设置页提示用户清理 */
    private val rejected = MutableStateFlow(false)
    val tokenRejected: StateFlow<Boolean> = rejected.asStateFlow()

    /** 换新 Token 验证通过后清除降级标记 */
    fun clearTokenRejected() {
        rejected.value = false
    }

    suspend fun repoInfo(owner: String, repo: String, etag: String? = null): RepoInfoResult =
        withContext(Dispatchers.IO) {
            try {
                readAuthenticated(request(apiUrl("repos", owner, repo), etag)) {
                    checkHttp(it)
                    when (it.code) {
                        304 -> RepoInfoResult.NotModified
                        else -> {
                            val gh = json.decodeFromString<GhRepo>(it.body.string())
                            RepoInfoResult.Fetched(gh.toInfo(gh.owner.login, gh.name, it.header("etag")))
                        }
                    }
                }
            } catch (e: Exception) {
                rethrowMapped(e)
            }
        }

    suspend fun commits(owner: String, repo: String, page: Int = 1, sha: String? = null): List<CommitRecord> =
        withContext(Dispatchers.IO) {
            try {
                val url = apiUrl("repos", owner, repo, "commits")
                    .newBuilder()
                    .addQueryParameter("per_page", COMMITS_PER_PAGE.toString())
                    .addQueryParameter("page", page.toString())
                    .apply { if (sha != null) addQueryParameter("sha", sha) }
                    .build()
                readAuthenticated(request(url, etag = null)) {
                    if (it.isEmptyRepo()) return@readAuthenticated emptyList()
                    checkHttp(it)
                    json.decodeFromString<List<GhCommit>>(it.body.string()).map { c ->
                        CommitRecord(
                            sha = c.sha,
                            message = c.commit.message,
                            author = c.commit.author?.name,
                            // committer date 优先：落枝时间才是「该分支最后有动静」的真源
                            // （rebase/squash 会改写 committer），与 tip 快照/分支行同口径，
                            // 保证首页卡片时间与详情提交首行精确一致
                            date = parseInstant(c.commit.committer?.date ?: c.commit.author?.date),
                        )
                    }
                }
            } catch (e: Exception) {
                rethrowMapped(e)
            }
        }

    suspend fun branches(owner: String, repo: String, page: Int): BranchPage =
        withContext(Dispatchers.IO) {
            try {
                val url = apiUrl("repos", owner, repo, "branches")
                    .newBuilder()
                    .addQueryParameter("per_page", "100")
                    .addQueryParameter("page", page.toString())
                    .build()
                readAuthenticated(request(url, etag = null)) {
                    checkHttp(it)
                    BranchPage(
                        names = json.decodeFromString<List<GhBranch>>(it.body.string()).map { branch -> branch.name },
                        hasNext = it.header("Link")?.contains("rel=\"next\"") == true,
                    )
                }
            } catch (e: Exception) {
                rethrowMapped(e)
            }
        }

    suspend fun releases(owner: String, repo: String, page: Int = 1, perPage: Int = RELEASES_PER_PAGE): List<ReleaseRecord> =
        withContext(Dispatchers.IO) {
            try {
                val url = apiUrl("repos", owner, repo, "releases")
                    .newBuilder()
                    .addQueryParameter("per_page", perPage.toString())
                    .addQueryParameter("page", page.toString())
                    .build()
                readAuthenticated(request(url, etag = null)) {
                    checkHttp(it)
                    json.decodeFromString<List<GhRelease>>(it.body.string()).map(::toReleaseRecord)
                }
            } catch (e: Exception) {
                rethrowMapped(e)
            }
        }

    suspend fun releaseByTag(owner: String, repo: String, tag: String): ReleaseRecord =
        withContext(Dispatchers.IO) {
            try {
                val url = apiUrl("repos", owner, repo, "releases", "tags", tag)
                readAuthenticated(request(url, etag = null)) {
                    checkHttp(it)
                    toReleaseRecord(json.decodeFromString<GhRelease>(it.body.string()))
                }
            } catch (e: Exception) {
                rethrowMapped(e)
            }
        }

    /**
     * 后台同步 release 列表使用 ETag。304 无需传输正文，已认证请求还可节省主配额。
     */
    suspend fun releasesForSync(owner: String, repo: String, etag: String? = null): SyncReleasesResult =
        withContext(Dispatchers.IO) {
            try {
                val url = apiUrl("repos", owner, repo, "releases")
                    .newBuilder()
                    .addQueryParameter("per_page", RELEASES_PER_PAGE.toString())
                    .build()
                readAuthenticated(request(url, etag)) {
                    when (it.code) {
                        304 -> SyncReleasesResult.NotModified
                        else -> {
                            checkHttp(it)
                            SyncReleasesResult.Page(
                                releases = json.decodeFromString<List<GhRelease>>(it.body.string()).map(::toReleaseRecord),
                                etag = it.header("etag"),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                rethrowMapped(e)
            }
        }

    /**
     * 后台同步专用：浏览分支 tip sha（per_page=1 条件请求）。
     * 「有新提交」的精确信号源：pushed_at 统计任意分支的推送（dev 推进会让它前进而
     * 浏览分支毫无动静），直接用它判定会误亮。branch = null 即默认分支。
     */
    suspend fun commitsTip(owner: String, repo: String, branch: String?, etag: String? = null): SyncCommitTipResult =
        withContext(Dispatchers.IO) {
            try {
                val url = apiUrl("repos", owner, repo, "commits")
                    .newBuilder()
                    .addQueryParameter("per_page", "1")
                    .apply { branch?.let { addQueryParameter("sha", it) } }
                    .build()
                readAuthenticated(request(url, etag)) {
                    when (it.code) {
                        304 -> SyncCommitTipResult.NotModified
                        409 -> if (it.isEmptyRepo()) SyncCommitTipResult.Tip("", it.header("etag"))
                            else throw SourceException(SourceError.Http(409))
                        else -> {
                            checkHttp(it)
                            val tip = json.decodeFromString<List<GhCommit>>(it.body.string()).firstOrNull()
                            SyncCommitTipResult.Tip(
                                sha = tip?.sha ?: "",
                                etag = it.header("etag"),
                                // 顺手带回落快照（首页卡片时间源）；committer date 与提交行/分支行同口径
                                date = tip?.let { c -> parseInstant(c.commit.committer?.date ?: c.commit.author?.date) },
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                rethrowMapped(e)
            }
        }

    /** 分支 tip 提交时间（per_page=1，committer date 优先）：per-branch 补齐用 */
    suspend fun branchTipDate(owner: String, repo: String, branch: String): Instant? =
        withContext(Dispatchers.IO) {
            try {
                val url = apiUrl("repos", owner, repo, "commits")
                    .newBuilder()
                    .addQueryParameter("per_page", "1")
                    .addQueryParameter("sha", branch)
                    .build()
                readAuthenticated(request(url, etag = null)) {
                    checkHttp(it)
                    json.decodeFromString<List<GhCommit>>(it.body.string()).firstOrNull()
                        ?.let { c -> parseInstant(c.commit.committer?.date ?: c.commit.author?.date) }
                }
            } catch (e: Exception) {
                rethrowMapped(e)
            }
        }

    /** 搜索辅助（添加页；Search API） */
    suspend fun search(query: String): RepoSearchPage = withContext(Dispatchers.IO) {
        try {
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addPathSegment("search")
                .addPathSegment("repositories")
                .addQueryParameter("q", query.trim())
                .addQueryParameter("per_page", "20")
                .build()
            readAuthenticated(request(url, etag = null)) {
                checkHttp(it)
                val page = json.decodeFromString<GhSearch>(it.body.string())
                RepoSearchPage(
                    repositories = page.items.map { gh -> gh.toInfo(gh.owner.login, gh.name, etag = null) },
                    isComplete = !page.incomplete_results && page.items.size == page.total_count,
                )
            }
        } catch (e: Exception) {
            rethrowMapped(e)
        }
    }

    /** APK 下载：流式写文件 + 进度回调（下载保留语义由调用方管理文件生命周期） */
    suspend fun download(asset: ApkAsset, dest: File, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            try {
                dest.parentFile?.mkdirs()
                val endpoint = asset.url.toHttpUrl()
                val request = Request.Builder().url(endpoint).apply {
                    if (endpoint.isHttps && endpoint.host == "api.github.com" && endpoint.port == 443) {
                        addToken()
                        header("Accept", "application/octet-stream")
                    }
                }.build()
                val downloadContext = coroutineContext
                readAuthenticated(request, downloadClient) { response ->
                    checkHttp(response)
                    val body = response.body
                    val total = body.contentLength().takeIf { it > 0 } ?: asset.size
                    body.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read = 0L
                            var lastPercent = -1
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                // 协作取消：阻塞读不响应协程 cancel，循环里显式检查——
                                // 否则被取消的旧任务会继续写盘（与新会话双写同文件）
                                downloadContext.ensureActive()
                                output.write(buffer, 0, n)
                                read += n
                                if (total > 0) {
                                    val progress = (read.toDouble() / total).toFloat().coerceIn(0f, 1f)
                                    val percent = (progress * 100).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress(progress)
                                    }
                                }
                            }
                        }
                    }
                }
                dest
            } catch (e: Exception) {
                // 磁盘满等写盘失败此前裸抛 IOException，把 UI 协程直接带崩
                rethrowMapped(e)
            }
        }

    /** Token 测试连接：200 ✓ / 401 ✗ / 网络类异常上抛 */
    suspend fun validateToken(token: String): Boolean = withContext(Dispatchers.IO) {
        val url = BASE_URL.toHttpUrl().newBuilder().addPathSegment("user").build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/vnd.github+json")
            .build()
        readResponse(request, client) {
            when (it.code) {
                200 -> true
                401 -> false
                else -> throw SourceException(SourceError.Http(it.code))
            }
        }
    }

    // ---- 内部：请求 / 错误映射 ----

    /**
     * 非 HTTP 异常统一收敛为 SourceException：此前 JSON 解析失败（SerializationException）、
     * 下载写盘失败（IOException，磁盘满）等会裸抛——下拉刷新直接崩、worker 静默失败。
     * 按「拿不到有效数据」归网络类（四类失败口径内，UI 有既有文案）。
     */
    private fun rethrowMapped(e: Throwable): Nothing = when (e) {
        is kotlinx.coroutines.CancellationException -> throw e
        is SourceException -> throw e
        is InterruptedIOException -> throw SourceException(SourceError.Timeout, e)
        else -> throw SourceException(SourceError.NetworkUnreachable, e)
    }

    private fun request(url: HttpUrl, etag: String?): Request {
        val builder = Request.Builder().url(url)
        etag?.let { builder.header("If-None-Match", it) }
        builder.addToken()
        builder.header("Accept", "application/vnd.github+json")
        return builder.build()
    }

    private fun Request.Builder.addToken() {
        if (!rejected.value) tokenProvider()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }

    /** 在 OkHttp 调度器中读取完整响应，取消协程时同时中断排队、建连和读包。 */
    private suspend fun <T> readResponse(request: Request, client: OkHttpClient, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val error = if (e is InterruptedIOException) SourceError.Timeout else SourceError.NetworkUnreachable
                    continuation.resumeWith(Result.failure(SourceException(error, e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use(read)
                        continuation.resumeWith(Result.success(result))
                    } catch (e: Exception) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }
            })
        }

    private class RejectedToken : Exception()

    private suspend fun <T> readAuthenticated(
        request: Request,
        httpClient: OkHttpClient = client,
        read: (Response) -> T,
    ): T = try {
        readResponse(request, httpClient) {
            if (it.code == 401 && request.header("Authorization") != null) throw RejectedToken()
            read(it)
        }
    } catch (_: RejectedToken) {
        if (request.header("Authorization") == "Bearer ${tokenProvider()}") rejected.value = true
        readResponse(request.newBuilder().removeHeader("Authorization").build(), httpClient, read)
    }

    private fun checkHttp(response: Response) {
        if (response.code == 304) return
        when {
            response.code == 404 -> throw SourceException(SourceError.NotFound)
            // 429（主/次限流通用信号）：Retry-After 秒数换恢复时间
            response.code == 429 -> throw SourceException(
                SourceError.RateLimited(
                    response.header("retry-after")?.toLongOrNull()?.let(Instant.now()::plusSeconds),
                ),
            )
            response.code == 403 && response.header("x-ratelimit-remaining") == "0" -> {
                val reset = response.header("x-ratelimit-reset")?.toLongOrNull()
                throw SourceException(
                    SourceError.RateLimited(retryAt = reset?.let(Instant::ofEpochSecond)),
                )
            }
            // 二级限流：403 带 Retry-After 但无 remaining 头——此前落「服务异常(403)」
            response.code == 403 && response.header("retry-after") != null -> throw SourceException(
                SourceError.RateLimited(
                    response.header("retry-after")?.toLongOrNull()?.let(Instant.now()::plusSeconds),
                ),
            )
            !response.isSuccessful -> throw SourceException(SourceError.Http(response.code))
        }
    }

    private fun Response.isEmptyRepo(): Boolean =
        code == 409 && peekBody(1024).string().contains("Git Repository is empty", ignoreCase = true)

    private fun apiUrl(vararg segments: String): HttpUrl =
        BASE_URL.toHttpUrl().newBuilder().apply { segments.forEach(::addPathSegment) }.build()

    /** GhRelease → ReleaseRecord（releases / releasesForSync 共用） */
    private fun toReleaseRecord(r: GhRelease) = ReleaseRecord(
        tagName = r.tag_name,
        name = r.name,
        publishedAt = parseInstant(r.published_at),
        body = r.body ?: "",
        // 只收 Android 安装包格式：.apk（标准）/ .apks（split）/ .xapk / .apkm
        // 桌面资产（AppImage/msi/zip）混入会污染版本 tag 选取和下载候选列表；
        // .aab 不含（需 bundletool 转换，不可直接安装）
        apkAssets = r.assets
            .filter { a -> ApkPicker.isPackageAsset(a.name) && a.name == File(a.name).name }
            .map { a ->
                ApkAsset(
                    name = a.name,
                    url = if (tokenProvider().isNullOrBlank() || rejected.value) a.browser_download_url
                        else a.url ?: a.browser_download_url,
                    size = a.size,
                )
            },
    )

    private companion object {
        const val BASE_URL = "https://api.github.com"
        const val TIMEOUT_S = 15L
    }
}

// ---- GitHub DTO（仅本文件可见；容错解析，字段缺失不崩） ----

@Serializable
private data class GhRepo(
    val id: Long = 0,
    val name: String = "",
    val html_url: String = "",
    val description: String? = null,
    val archived: Boolean = false,
    val pushed_at: String? = null,
    val default_branch: String? = null,
    val stargazers_count: Int = 0,
    val forks_count: Int = 0,
    val open_issues_count: Int = 0,
    val owner: GhOwner = GhOwner(),
    val license: GhLicense? = null,
) {
    fun toInfo(owner: String, repo: String, etag: String?) = RepoInfo(
        githubId = id,
        owner = owner,
        repo = repo,
        stars = stargazers_count,
        forks = forks_count,
        openIssues = open_issues_count,
        pushedAt = parseInstant(pushed_at),
        archived = archived,
        license = license?.spdx_id,
        description = description,
        ownerAvatar = this.owner.avatar_url,
        htmlUrl = html_url,
        etag = etag,
        defaultBranch = default_branch,
    )
}

@Serializable
private data class GhOwner(val login: String = "", val avatar_url: String? = null)

@Serializable
private data class GhLicense(val spdx_id: String? = null)

@Serializable
private data class GhCommit(
    val sha: String = "",
    val commit: GhCommitDetail = GhCommitDetail(),
)

@Serializable
private data class GhCommitDetail(
    val message: String = "",
    val author: GhPerson? = null,
    val committer: GhPerson? = null,
)

@Serializable
private data class GhBranch(val name: String = "")

@Serializable
private data class GhPerson(val name: String? = null, val date: String? = null)

@Serializable
private data class GhRelease(
    val body: String? = null,
    val tag_name: String = "",
    val name: String? = null,
    val published_at: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
private data class GhAsset(
    val name: String = "",
    val url: String? = null,
    val browser_download_url: String = "",
    val size: Long = 0,
)

@Serializable
private data class GhSearch(
    val total_count: Int = 0,
    val incomplete_results: Boolean = true,
    val items: List<GhRepo> = emptyList(),
)
