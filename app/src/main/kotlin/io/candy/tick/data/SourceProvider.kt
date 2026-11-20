package io.candy.tick.data

import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.RepoSnapshot
import io.candy.tick.data.model.ReleaseRecord
import java.time.Instant

const val PROVIDER_GITHUB = "github"

/** 列表分页大小（单源：请求参数与 UI 层终止判定共用）：提交 30 / release 10（取够过滤无 APK 资产的 release） */
const val COMMITS_PER_PAGE = 30
const val RELEASES_PER_PAGE = 10

/** 信息源抽象（V1 仅 GitHub；预留源扩展位） */
interface SourceProvider {
    val name: String

    /** 携带 ETag 条件请求；304 时返回 NotModified（不计配额） */
    suspend fun repoInfo(owner: String, repo: String, etag: String? = null): RepoInfoResult

    suspend fun commits(owner: String, repo: String, page: Int = 1, sha: String? = null): List<CommitRecord>

    suspend fun releases(owner: String, repo: String, page: Int = 1, perPage: Int = RELEASES_PER_PAGE): List<ReleaseRecord>

    /** 分支列表（提交视图的分支切换；无对应能力的源返回空） */
    suspend fun branches(owner: String, repo: String): List<String> = emptyList()

    /** 搜索（添加页辅助；无对应能力的源返回空） */
    suspend fun search(query: String): List<RepoInfo> = emptyList()
}

/** 拉取到的仓库信息（改名跟随后的最终 owner/repo 在此） */
data class RepoInfo(
    val githubId: Long,
    val owner: String,
    val repo: String,
    val stars: Int,
    val forks: Int,
    val openIssues: Int,
    val pushedAt: Instant?,
    val archived: Boolean,
    val license: String?,
    val description: String?,
    val ownerAvatar: String?,
    val htmlUrl: String,
    val etag: String?,
) {
    fun toSnapshot() = RepoSnapshot(
        stars = stars,
        forks = forks,
        openIssues = openIssues,
        pushedAt = pushedAt,
        archived = archived,
        license = license,
        description = description,
        ownerAvatar = ownerAvatar,
        htmlUrl = htmlUrl,
        lastSyncAt = Instant.now(),
        etag = etag,
    )
}

sealed interface RepoInfoResult {
    data object NotModified : RepoInfoResult
    data class Fetched(val info: RepoInfo) : RepoInfoResult
}

/** 四类失败（design.md「状态与反馈」；UI 分文案，错误可行动） */
sealed interface SourceError {
    data object NetworkUnreachable : SourceError
    data object Timeout : SourceError
    data object NotFound : SourceError // 不存在或已私有
    data class RateLimited(val retryAt: Instant?) : SourceError // 403 + 恢复时间
    data class Http(val code: Int) : SourceError // 其余 HTTP 错误
}

class SourceException(val error: SourceError, cause: Throwable? = null) : Exception(cause)

/** GitHub 实现：纯委托，OkHttp 细节收敛在 GitHubApi.kt */
class GitHubProvider(private val api: GitHubApi) : SourceProvider {
    override val name = PROVIDER_GITHUB

    override suspend fun repoInfo(owner: String, repo: String, etag: String?) =
        api.repoInfo(owner, repo, etag)

    override suspend fun commits(owner: String, repo: String, page: Int, sha: String?) =
        api.commits(owner, repo, page, sha)

    override suspend fun releases(owner: String, repo: String, page: Int, perPage: Int) =
        api.releases(owner, repo, page, perPage)

    override suspend fun branches(owner: String, repo: String) = api.branches(owner, repo)

    override suspend fun search(query: String) = api.search(query)
}
