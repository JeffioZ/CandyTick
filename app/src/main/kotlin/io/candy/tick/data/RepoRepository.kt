package io.candy.tick.data

import io.candy.tick.data.model.AppState
import io.candy.tick.data.model.BranchInfo
import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.data.model.RepoSnapshot
import io.candy.tick.data.model.Settings
import io.candy.tick.data.model.TrackedRepo
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 用例编排：刷新 / 增删 / 关联 / 设置。Screen 不直连本层以下（拆分红线） */
class RepoRepository(
    private val store: AppStateStore,
    private val providers: Map<String, SourceProvider>,
) {

    val state: StateFlow<AppState> get() = store.state

    private fun provider(name: String): SourceProvider =
        providers[name] ?: providers.getValue(PROVIDER_GITHUB)

    private fun trackedOrNull(id: Long): TrackedRepo? =
        store.state.value.repos.find { it.id == id }

    // ---- 设置 ----

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

    /**
     * 关联本机应用。标题自动跟随应用名，层级保护：用户自定义备注 > 自动应用名 > 仓库名。
     * autoNote 标记 noteName 的来源——自定义（false）永不被自动值覆盖/清除；
     * 解除关联时自动值随之清空。应用改名由刷新流程按标记更新。
     */
    suspend fun setLinkedPackage(
        id: Long,
        packageName: String?,
        newLabel: String? = null,
    ) = store.update { s ->
        s.copy(
            repos = s.repos.map { repo ->
                if (repo.id != id) {
                    repo
                } else {
                    val custom = repo.noteName != null && !repo.autoNote
                    val note = when {
                        packageName == null ->
                            if (repo.autoNote) null else repo.noteName // 解除：自动值清空，自定义保留
                        custom -> repo.noteName
                        else -> newLabel ?: repo.noteName
                    }
                    repo.copy(
                        linkedPackage = packageName,
                        noteName = note,
                        autoNote = note != null && note == newLabel,
                    )
                }
            },
        )
    }

    /** 备注名直写（自动刷新改名用；custom=false 路径由调用方保证不触自定义） */
    suspend fun setNoteName(id: Long, name: String?, auto: Boolean) = store.update { s ->
        s.copy(repos = s.repos.map { if (it.id == id) it.copy(noteName = name, autoNote = auto) else it })
    }

    /** 「下载保留」记录（安装失败不删，可直接重装） */
    suspend fun setDownloadedApk(id: Long, apk: io.candy.tick.data.model.DownloadedApk?) = store.update { s ->
        s.copy(repos = s.repos.map { if (it.id == id) it.copy(downloadedApk = apk) else it })
    }

    /** 后台同步记录最新 release tag（新版本通知与「可更新」排序的依据） */
    suspend fun setLatestReleaseTag(id: Long, tag: String?) = store.update { s ->
        val old = s.snapshots[id] ?: return@update s
        s.copy(snapshots = s.snapshots + (id to old.copy(latestReleaseTag = tag)))
    }

    /** syncLatestReleaseTag 的结果：Tag = 拿到（可能 null = 无带 APK 资产的 release） */
    sealed interface SyncTagOutcome {
        data class Tag(val tag: String?) : SyncTagOutcome
        data object Unchanged : SyncTagOutcome // 304：tag 维持旧值
        data object Failed : SyncTagOutcome // 拉取失败：旧 tag 保留（不写 null，防漏报）
        data object Missing : SyncTagOutcome // 订阅已不存在
    }

    /**
     * 后台同步取最新 release tag：ETag 条件请求（304 不计配额）+ 失败保旧值。
     * 此前瞬时失败会把 latestReleaseTag 写空——漏报一条新版本通知、清掉「可更新」标识。
     */
    suspend fun syncLatestReleaseTag(id: Long): SyncTagOutcome {
        val tracked = trackedOrNull(id) ?: return SyncTagOutcome.Missing
        val etag = store.state.value.snapshots[id]?.releasesEtag
        return try {
            when (val result = provider(tracked.provider).releasesForSync(tracked.owner, tracked.repo, etag)) {
                SyncReleasesResult.NotModified -> SyncTagOutcome.Unchanged
                is SyncReleasesResult.Page -> {
                    val tag = result.releases.firstOrNull { it.apkAssets.isNotEmpty() }?.tagName
                    store.update { s ->
                        val old = s.snapshots[id] ?: return@update s
                        // tag 为 null（该仓无安装包资产）时保留旧值：宁可不比，不丢基线
                        s.copy(
                            snapshots = s.snapshots + (id to old.copy(
                                latestReleaseTag = tag ?: old.latestReleaseTag,
                                releasesEtag = result.etag,
                            )),
                        )
                    }
                    SyncTagOutcome.Tag(tag)
                }
            }
        } catch (e: SourceException) {
            SyncTagOutcome.Failed
        }
    }

    /** 添加页搜索辅助 */
    suspend fun search(query: String): List<RepoInfo> =
        provider(PROVIDER_GITHUB).search(query)

    /** 添加页信息预览（不落库） */
    suspend fun addPreview(owner: String, repo: String): RepoInfoResult =
        provider(PROVIDER_GITHUB).repoInfo(owner, repo)

    /** 备份：导出 / 合并导入（按 id 去重） */
    fun exportState(): String = store.exportJson()

    suspend fun importState(text: String): Int = store.importJson(text)

    // ---- 刷新（旧数据永不丢；单仓库失败不拖垮列表） ----

    /** 刷新单仓库快照；返回 null = 成功（含 304），非空 = 该仓库的错误 */
    suspend fun refreshRepo(id: Long): SourceError? {
        val tracked = trackedOrNull(id) ?: return null
        val old = store.state.value.snapshots[id]
        val etag = old?.etag
        return try {
            // tip 检查时机：基线缺失（首次/切分支/导入）必须立基线；date 缺失（lastCommitDate
            // 是后补字段，旧版本基线只有 sha）也要拉一次补时间；repoInfo 200 = pushed_at
            // 前进（任意分支的推送都可能）→ 复查浏览分支 tip；304 → 内容未变，tip 也未变
            var tipNeeded = old?.lastCommitSha == null || old?.lastCommitDate == null
            // defaultBranch 缺失（旧版本快照补齐）→ 不带 ETag 强拉一次：一次性 1 配额，
            // 之后 etag 更新恢复 304 免费循环。304 响应体不含字段，无法从 304 补齐
            val needRepoBody = old?.defaultBranch == null
            when (val result = provider(tracked.provider).repoInfo(tracked.owner, tracked.repo, if (needRepoBody) null else etag)) {
                RepoInfoResult.NotModified -> {
                    store.update { s ->
                        val cur = s.snapshots[id] ?: return@update s
                        s.copy(snapshots = s.snapshots + (id to cur.copy(lastSyncAt = Instant.now())))
                    }
                }
                is RepoInfoResult.Fetched -> {
                    tipNeeded = true
                    val info = result.info
                    val renamed = tracked.owner != info.owner || tracked.repo != info.repo
                    // latestReleaseTag / lastCommitSha 是跨刷新的累积状态（并发同步路径维护），
                    // 替换快照时必须保留；读取必须在 update 的 transform 内——否则与
                    // 并发写入会互相用旧值覆盖新值（重复通知竞态的根因）
                    store.update { s ->
                        val cur = s.snapshots[id]
                        s.copy(
                            repos = if (renamed) {
                                s.repos.map { if (it.id == id) it.copy(owner = info.owner, repo = info.repo) else it }
                            } else {
                                s.repos
                            },
                            snapshots = s.snapshots + (id to info.toSnapshot().copy(
                                latestReleaseTag = cur?.latestReleaseTag,
                                lastCommitSha = cur?.lastCommitSha,
                                commitsEtag = cur?.commitsEtag,
                                lastCommitDate = cur?.lastCommitDate,
                                releasesEtag = cur?.releasesEtag, // repoInfo 200 不清 release 条件请求状态（清了下轮就多烧 1 配额）
                                // defaultBranch 取新 info（上游可能改默认分支）
                            )),
                        )
                    }
                }
            }
            if (tipNeeded) {
                // 浏览分支 tip 条件请求：304（浏览分支没动，dev 推进不在此列）→ 免配额
                // 不亮灯；200 → sha 变化经 SnapshotDiff 点亮。失败静默：基线保留下轮重试，
                // 不拖垮 repoInfo 的成功
                val tipBranch = tracked.branch // 发请求时的浏览分支
                // date 缺失 = 旧版本基线补时间：必须无条件拉——带旧 etag 只会 304 空体，
                // 补不到 date；一次 1 配额，date 落库后恢复 304 免费循环
                val tipEtag = if (old?.lastCommitDate == null) null else old?.commitsEtag
                runCatching {
                    when (val tip = provider(tracked.provider).commitTipForSync(tracked.owner, tracked.repo, tracked.branch, tipEtag)) {
                        is SyncCommitTipResult.Tip -> store.update { s ->
                            val cur = s.snapshots[id] ?: return@update s
                            // 请求在途中用户切了分支（setBranch 已清基线）→ 这份旧分支的
                            // tip 作废；写进新分支快照会亮假圆点 + etag 与浏览分支错配多耗配额。
                            // 浏览分支存在 TrackedRepo 上（不在快照），须读 update 内的当前值比对
                            if (s.repos.firstOrNull { it.id == id }?.branch == tipBranch) {
                                s.copy(
                                    snapshots = s.snapshots + (id to cur.copy(
                                        lastCommitSha = tip.sha,
                                        commitsEtag = tip.etag,
                                        lastCommitDate = tip.date,
                                    )),
                                )
                            } else {
                                s
                            }
                        }
                        else -> Unit // 304 / 无能力源：基线保持
                    }
                }
            }
            null
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

    /**
     * 分支列表 + 每分支 tip 提交时间（per-branch，取时间上限 ~20 防超大仓失控）。
     * 曾试过 events 活动流打底的 A+B 混合——实测活动流有投递延迟（main 当天推送
     * 次日才入流），会给新分支标旧时间，宁缺毋假故全部 per-branch。
     * 浏览分支与默认分支优先保时间：字典序截断会把 main 排到 dev/feature 之后。
     * 返回 null = 分支列表拉取失败（调用方显示失败态）；空列表 = 仓库无分支。
     * 结果随 DetailViewModel 缓存，弹层重开不重复请求。
     */
    suspend fun loadBranchInfos(repoId: Long): List<BranchInfo>? {
        val tracked = trackedOrNull(repoId) ?: return null
        val provider = provider(tracked.provider)
        val names = runCatching { provider.branches(tracked.owner, tracked.repo) }
            .onFailure { if (it is CancellationException) throw it } // 退出详情后别把余量请求空跑完
            .getOrNull() ?: return null
        if (names.isEmpty()) return emptyList()
        val old = store.state.value.snapshots[repoId]
        val defaultBranch = old?.defaultBranch
        // GitHub 分支切换器排序：默认分支固定首行，其余字典序（API 返回本身即字典序，
        // 这里只把默认分支提到顶部——大仓里 main 埋在 dev/feature 之后的样子）
        val ordered = if (defaultBranch != null && names.contains(defaultBranch)) {
            listOf(defaultBranch) + names.filter { it != defaultBranch }
        } else {
            names
        }
        val dateNames = ordered.take(20).toSet() + setOfNotNull(tracked.branch, defaultBranch)
        return coroutineScope {
            val gate = Semaphore(6) // 并发闸：二十来个并行请求在弱网互相挤带宽，排队更稳
            ordered.map { name ->
                async {
                    val date = if (name in dateNames) {
                        gate.withPermit {
                            runCatching { provider.branchTipDate(tracked.owner, tracked.repo, name) }
                                .onFailure { if (it is CancellationException) throw it }
                                .getOrNull()
                        }
                    } else {
                        null // 超出上限的分支不显示时间（宁缺毋假）
                    }
                    BranchInfo(name, date)
                }
            }.awaitAll()
        }
    }

    /** 浏览分支（null = 仓库默认分支；仅影响提交视图，状态/版本仍按仓库级） */
    suspend fun setBranch(id: Long, branch: String?) = store.update { s ->
        val old = s.snapshots[id]
        s.copy(
            repos = s.repos.map { if (it.id == id) it.copy(branch = branch) else it },
            // 切分支 = 换了 tip 观测对象：旧分支的指纹不再有意义，清基线让下轮
            // sync 重新立（首次记录不亮灯）。窗口内并发的 sync 读到旧基线 → tip
            // 相同不亮，无副作用
            snapshots = if (old != null) {
                s.snapshots + (id to old.copy(lastCommitSha = null, commitsEtag = null, lastCommitDate = null))
            } else {
                s.snapshots
            },
        )
    }

    /** 置顶 / 取消置顶（null = 取消；时间戳保证置顶组内顺序稳定） */
    suspend fun setPinned(id: Long, pinnedAt: Instant?) = store.update { s ->
        s.copy(repos = s.repos.map { if (it.id == id) it.copy(pinnedAt = pinnedAt) else it })
    }

    // ---- 详情页数据（分页透传，SourceException 由调用方接） ----

    /** 提交列表自动跟随该仓库已选分支 */
    suspend fun commits(repoId: Long, page: Int): List<CommitRecord> {
        val t = trackedOrNull(repoId) ?: return emptyList()
        return provider(t.provider).commits(t.owner, t.repo, page, t.branch)
    }

    suspend fun releases(repoId: Long, page: Int): List<ReleaseRecord> {
        val t = trackedOrNull(repoId) ?: return emptyList()
        // per_page=10：取够多条供调用方过滤（如跳过无 APK 的桌面专用 release）
        return provider(t.provider).releases(t.owner, t.repo, page, perPage = RELEASES_PER_PAGE)
    }

}
