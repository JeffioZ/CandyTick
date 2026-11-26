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
    /** 携带 ETag 条件请求；304 时返回 NotModified。 */
    suspend fun repoInfo(owner: String, repo: String, etag: String? = null): RepoInfoResult

    suspend fun commits(owner: String, repo: String, page: Int = 1, sha: String? = null): List<CommitRecord>

    suspend fun releases(owner: String, repo: String, page: Int = 1, perPage: Int = RELEASES_PER_PAGE): List<ReleaseRecord>

    suspend fun releaseByTag(owner: String, repo: String, tag: String): ReleaseRecord? = null

    /**
     * 后台同步 release 列表可使用 ETag，默认实现回退为普通分页请求。
     */
    suspend fun releasesForSync(owner: String, repo: String, etag: String? = null): SyncReleasesResult =
        SyncReleasesResult.Page(releases(owner, repo), etag = null)

    /**
     * 后台同步专用：浏览分支 tip sha（per_page=1 条件请求）。
     * 「有新提交」的精确信号源——pushed_at 统计任意分支的推送，直接用它会误亮。
     * branch = null 即默认分支（与详情页提交列表的口径一致）。无对应能力的源返回 null。
     */
    suspend fun commitTipForSync(owner: String, repo: String, branch: String?, etag: String? = null): SyncCommitTipResult? = null

    /** 分支列表（提交视图的分支切换；无对应能力的源返回空） */
    suspend fun branches(owner: String, repo: String, page: Int): BranchPage = BranchPage(emptyList(), false)

    /** 分支 tip 提交时间（per-branch 补齐用；分支不存在/无提交返回 null） */
    suspend fun branchTipDate(owner: String, repo: String, branch: String): Instant? = null

    /** 搜索（添加页辅助；无对应能力的源返回空） */
    suspend fun search(query: String): RepoSearchPage = RepoSearchPage()
}

data class RepoSearchPage(val repositories: List<RepoInfo> = emptyList(), val isComplete: Boolean = false)

data class BranchPage(val names: List<String>, val hasNext: Boolean)

/** 拉取到的仓库信息（改名跟随后的最终 owner/repo 在此），用于预览和持久化快照。 */
data class RepoInfo(
    val githubId: Long,
    val owner: String,
    val repo: String,
    val stars: Int,
    val pushedAt: Instant?,
    val archived: Boolean,
    val description: String?,
    val etag: String?,
    val defaultBranch: String?, // 上游当前默认分支名（分支选择器打勾用）
    val forks: Int = 0,
    val openIssues: Int = 0,
    val license: String? = null,
    val ownerAvatar: String? = null,
    val htmlUrl: String = "",
) {
    fun toSnapshot() = RepoSnapshot(
        stars = stars,
        pushedAt = pushedAt,
        archived = archived,
        description = description,
        lastSyncAt = Instant.now(),
        etag = etag,
        defaultBranch = defaultBranch,
        forks = forks,
        openIssues = openIssues,
        license = license,
        ownerAvatar = ownerAvatar,
        htmlUrl = htmlUrl,
    )
}

sealed interface RepoInfoResult {
    data object NotModified : RepoInfoResult
    data class Fetched(val info: RepoInfo) : RepoInfoResult
}

/** 后台同步的 release 拉取结果：304 = 内容未变，tag 维持旧值。 */
sealed interface SyncReleasesResult {
    data class Page(val releases: List<ReleaseRecord>, val etag: String?) : SyncReleasesResult
    data object NotModified : SyncReleasesResult
}

/** 后台同步的分支 tip 拉取结果：Tip.sha 空串 = 分支存在但无提交（立为基线） */
sealed interface SyncCommitTipResult {
    data class Tip(val sha: String, val etag: String?, val date: Instant? = null) : SyncCommitTipResult
    data object NotModified : SyncCommitTipResult
}

/** 失败分类（design.md「状态与反馈」；UI 分文案，错误可行动） */
sealed interface SourceError {
    data object NetworkUnreachable : SourceError
    data object Timeout : SourceError
    data object NotFound : SourceError // 不存在或已私有
    data object TokenRejected : SourceError // 401 后匿名重试仍 404：Token 无效/无权，私有仓不再误报「不存在」
    data class RateLimited(val retryAt: Instant?) : SourceError // 403 + 恢复时间
    data class Http(val code: Int) : SourceError // 其余 HTTP 错误
    // 只出现在下载写盘：剩余空间放不下安装包。此前归入「网络不可达」，会诱导用户去排查网络
    data object StorageFull : SourceError
}

// message 补上错误描述：此前为 null，日志里只剩 cause 链，定位不到是哪种源错误
class SourceException(val error: SourceError, cause: Throwable? = null) : Exception(error.toString(), cause)

/** 「仓库不可访问」判定：只有源明确说「没了/无权」才算；网络/超时/限流是瞬时失败，
 *  不该把徽章翻成不可访问（首页卡片与详情页据此同口径，快照状态继续表达活跃度） */
val SourceError.isInaccessible: Boolean
    get() = this is SourceError.NotFound || this is SourceError.TokenRejected

/** 简短诊断错误码（用于失败提示后缀，方便截图定位根因） */
val SourceError.errorCode: String
    get() = when (this) {
        SourceError.NetworkUnreachable -> "E-NET"
        SourceError.Timeout -> "E-TIME"
        SourceError.NotFound -> "HTTP 404"
        SourceError.TokenRejected -> "HTTP 401"
        is SourceError.RateLimited -> "HTTP 403"
        is SourceError.Http -> "HTTP $code"
        SourceError.StorageFull -> "E-DISK"
    }

/** GitHub 实现：纯委托，OkHttp 细节收敛在 GitHubApi.kt */
class GitHubProvider(private val api: GitHubApi) : SourceProvider {
    override suspend fun repoInfo(owner: String, repo: String, etag: String?) =
        api.repoInfo(owner, repo, etag)

    override suspend fun commits(owner: String, repo: String, page: Int, sha: String?) =
        api.commits(owner, repo, page, sha)

    override suspend fun releases(owner: String, repo: String, page: Int, perPage: Int) =
        api.releases(owner, repo, page, perPage)

    override suspend fun releaseByTag(owner: String, repo: String, tag: String) =
        api.releaseByTag(owner, repo, tag)

    override suspend fun releasesForSync(owner: String, repo: String, etag: String?) =
        api.releasesForSync(owner, repo, etag)

    override suspend fun commitTipForSync(owner: String, repo: String, branch: String?, etag: String?) =
        api.commitsTip(owner, repo, branch, etag)

    override suspend fun branches(owner: String, repo: String, page: Int) = api.branches(owner, repo, page)

    override suspend fun branchTipDate(owner: String, repo: String, branch: String) =
        api.branchTipDate(owner, repo, branch)

    override suspend fun search(query: String) = api.search(query)
}
