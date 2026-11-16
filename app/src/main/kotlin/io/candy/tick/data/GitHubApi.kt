package io.candy.tick.data

import io.candy.tick.data.model.ApkAsset
import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.data.model.parseInstant
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * GitHub REST 封装 —— 3 个 suspend 函数。
 * OkHttp 只允许出现在本文件（design.md「可升级性」：破坏性变更按文件整体替换）。
 */
class GitHubApi(private val tokenProvider: () -> String?) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS) // 整体超时 15s（四类失败约定）
        .build()

    suspend fun repoInfo(owner: String, repo: String, etag: String? = null): RepoInfoResult =
        withContext(Dispatchers.IO) {
            val response = execute(request(apiUrl("repos", owner, repo), etag))
            response.use {
                checkHttp(it)
                when (it.code) {
                    304 -> RepoInfoResult.NotModified
                    else -> {
                        val gh = json.decodeFromString<GhRepo>(it.body.string())
                        // 改名跟随：301 重定向后的最终 URL 即新 owner/repo
                        val seg = it.request.url.pathSegments
                        val finalOwner = if (seg.size >= 3 && seg[0] == "repos") seg[1] else owner
                        val finalRepo = if (seg.size >= 3 && seg[0] == "repos") seg[2] else repo
                        RepoInfoResult.Fetched(gh.toInfo(finalOwner, finalRepo, it.header("etag")))
                    }
                }
            }
        }

    suspend fun commits(owner: String, repo: String, page: Int = 1): List<CommitRecord> =
        withContext(Dispatchers.IO) {
            val url = apiUrl("repos", owner, repo, "commits")
                .newBuilder()
                .addQueryParameter("per_page", PER_PAGE.toString())
                .addQueryParameter("page", page.toString())
                .build()
            val response = execute(request(url, etag = null))
            response.use {
                checkHttp(it)
                json.decodeFromString<List<GhCommit>>(it.body.string()).map { c ->
                    CommitRecord(
                        sha = c.sha,
                        message = c.commit.message,
                        author = c.commit.author?.name,
                        date = parseInstant(c.commit.author?.date),
                    )
                }
            }
        }

    suspend fun releases(owner: String, repo: String, page: Int = 1): List<ReleaseRecord> =
        withContext(Dispatchers.IO) {
            val url = apiUrl("repos", owner, repo, "releases")
                .newBuilder()
                .addQueryParameter("per_page", PER_PAGE.toString())
                .addQueryParameter("page", page.toString())
                .build()
            val response = execute(request(url, etag = null))
            response.use {
                checkHttp(it)
                json.decodeFromString<List<GhRelease>>(it.body.string()).map { r ->
                    ReleaseRecord(
                        tagName = r.tag_name,
                        name = r.name,
                        publishedAt = parseInstant(r.published_at),
                        apkAssets = r.assets.map { a ->
                            ApkAsset(
                                name = a.name,
                                url = a.browser_download_url,
                                size = a.size,
                            )
                        },
                    )
                }
            }
        }

    // ---- 内部：请求 / 错误映射 ----

    private fun request(url: HttpUrl, etag: String?): Request {
        val builder = Request.Builder().url(url)
        etag?.let { builder.header("If-None-Match", it) }
        tokenProvider()?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        builder.header("Accept", "application/vnd.github+json")
        return builder.build()
    }

    private fun execute(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (e: SocketTimeoutException) {
        throw SourceException(SourceError.Timeout, e)
    } catch (e: InterruptedIOException) { // callTimeout 到点
        throw SourceException(SourceError.Timeout, e)
    } catch (e: IOException) {
        throw SourceException(SourceError.NetworkUnreachable, e)
    }

    private fun checkHttp(response: Response) {
        if (response.code == 304) return
        when {
            response.code == 404 -> throw SourceException(SourceError.NotFound)
            response.code == 403 && response.header("x-ratelimit-remaining") == "0" -> {
                val reset = response.header("x-ratelimit-reset")?.toLongOrNull()
                throw SourceException(
                    SourceError.RateLimited(retryAt = reset?.let(Instant::ofEpochSecond)),
                )
            }
            !response.isSuccessful -> throw SourceException(SourceError.Http(response.code))
        }
    }

    private fun apiUrl(vararg segments: String): HttpUrl =
        BASE_URL.toHttpUrl().newBuilder().apply { segments.forEach(::addPathSegment) }.build()

    private companion object {
        const val BASE_URL = "https://api.github.com"
        const val TIMEOUT_S = 15L
        const val PER_PAGE = 30
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
)

@Serializable
private data class GhPerson(val name: String? = null, val date: String? = null)

@Serializable
private data class GhRelease(
    val tag_name: String = "",
    val name: String? = null,
    val published_at: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
private data class GhAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0,
)
