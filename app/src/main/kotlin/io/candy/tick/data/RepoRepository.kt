package io.candy.tick.data

import io.candy.tick.data.model.AppState
import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.data.model.RepoSnapshot
import io.candy.tick.data.model.Settings
import io.candy.tick.data.model.ThemeMode
import io.candy.tick.data.model.TrackedRepo
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow

/** 用例编排：刷新 / 增删 / 关联 / 设置。Screen 不直连本层以下（拆分红线） */
class RepoRepository(
    private val store: AppStateStore,
    private val providers: Map<String, SourceProvider>,
) {

    val state: StateFlow<AppState> get() = store.state

    private fun provider(name: String): SourceProvider =
        providers[name] ?: providers.getValue(PROVIDER_GITHUB)

    private fun tracked(id: Long): TrackedRepo =
        requireNotNull(store.state.value.repos.find { it.id == id }) { "repo $id not tracked" }

    // ---- 设置 ----

    suspend fun setThemeMode(mode: ThemeMode) =
        updateSettings { it.copy(themeMode = mode) }

    suspend fun updateSettings(transform: (Settings) -> Settings) =
        store.update { it.copy(settings = transform(it.settings)) }

    // ---- 增删 / 关联 ----

    sealed interface AddResult {
        data object Duplicate : AddResult // 已在列表
        data class Added(val repo: TrackedRepo) : AddResult
        data class Failed(val error: SourceError) : AddResult
    }

    /** 添加订阅：拉取信息（顺带完成改名跟随）→ 按 GitHub id 查重 → 落库 */
    suspend fun addRepo(owner: String, repo: String): AddResult = try {
        when (val result = provider(PROVIDER_GITHUB).repoInfo(owner.trim(), repo.trim())) {
            RepoInfoResult.NotModified -> AddResult.Failed(SourceError.Http(304))
            is RepoInfoResult.Fetched -> {
                val info = result.info
                if (store.state.value.repos.any { it.id == info.githubId }) {
                    AddResult.Duplicate
                } else {
                    val tracked = TrackedRepo(
                        id = info.githubId,
                        owner = info.owner,
                        repo = info.repo,
                        addedAt = Instant.now(),
                    )
                    store.update { s ->
                        s.copy(
                            repos = s.repos + tracked,
                            snapshots = s.snapshots + (tracked.id to info.toSnapshot()),
                        )
                    }
                    AddResult.Added(tracked)
                }
            }
        }
    } catch (e: SourceException) {
        AddResult.Failed(e.error)
    }

    /** 乐观删除：调用方负责 Snackbar 撤销（恢复条目 + 快照） */
    suspend fun removeRepo(id: Long) = store.update { s ->
        s.copy(repos = s.repos.filterNot { it.id == id }, snapshots = s.snapshots - id)
    }

    suspend fun restoreRepo(repo: TrackedRepo, snapshot: RepoSnapshot?) =
        store.update { s ->
            s.copy(
                repos = s.repos + repo,
                snapshots = if (snapshot != null) s.snapshots + (repo.id to snapshot) else s.snapshots,
            )
        }

    suspend fun setLinkedPackage(id: Long, packageName: String?) = store.update { s ->
        s.copy(repos = s.repos.map { if (it.id == id) it.copy(linkedPackage = packageName) else it })
    }

    suspend fun setNoteName(id: Long, noteName: String?) = store.update { s ->
        s.copy(repos = s.repos.map { if (it.id == id) it.copy(noteName = noteName) else it })
    }

    // ---- 刷新（旧数据永不丢；单仓库失败不拖垮列表） ----

    /** 刷新单仓库快照；返回 null = 成功（含 304），非空 = 该仓库的错误 */
    suspend fun refreshRepo(id: Long): SourceError? {
        val tracked = tracked(id)
        val etag = store.state.value.snapshots[id]?.etag
        return try {
            when (val result = provider(tracked.provider).repoInfo(tracked.owner, tracked.repo, etag)) {
                RepoInfoResult.NotModified -> {
                    store.update { s ->
                        val old = s.snapshots[id] ?: return@update s
                        s.copy(snapshots = s.snapshots + (id to old.copy(lastSyncAt = Instant.now())))
                    }
                    null
                }
                is RepoInfoResult.Fetched -> {
                    val info = result.info
                    val renamed = tracked.owner != info.owner || tracked.repo != info.repo
                    store.update { s ->
                        s.copy(
                            repos = if (renamed) {
                                s.repos.map { if (it.id == id) it.copy(owner = info.owner, repo = info.repo) else it }
                            } else {
                                s.repos
                            },
                            snapshots = s.snapshots + (id to info.toSnapshot()),
                        )
                    }
                    null
                }
            }
        } catch (e: SourceException) {
            e.error // 快照不动 = 旧数据保留
        }
    }

    /** 并发刷新全部订阅；返回 id → 首个错误（全成功为空 Map） */
    suspend fun refreshAll(): Map<Long, SourceError> = coroutineScope {
        store.state.value.repos.map { tracked ->
            async { tracked.id to refreshRepo(tracked.id) }
        }.awaitAll()
            .filter { it.second != null }
            .associate { it.first to it.second!! }
    }

    // ---- 详情页数据（分页透传，SourceException 由调用方接） ----

    suspend fun commits(repoId: Long, page: Int): List<CommitRecord> {
        val t = tracked(repoId)
        return provider(t.provider).commits(t.owner, t.repo, page)
    }

    suspend fun releases(repoId: Long, page: Int): List<ReleaseRecord> {
        val t = tracked(repoId)
        return provider(t.provider).releases(t.owner, t.repo, page)
    }
}
