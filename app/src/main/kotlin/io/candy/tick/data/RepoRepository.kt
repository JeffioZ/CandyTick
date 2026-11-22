package io.candy.tick.data

import io.candy.tick.data.model.AppState
import io.candy.tick.data.model.ApkPublication
import io.candy.tick.data.model.BranchInfo
import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.DownloadedApk
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.data.model.RepoActivity
import io.candy.tick.data.model.RepoSnapshot
import io.candy.tick.data.model.Settings
import io.candy.tick.data.model.TrackedRepo
import java.time.Instant
import java.io.File
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
    private val filesDir: File,
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
            is RepoInfoResult.Fetched -> addRepo(result.info)
        }
    } catch (e: SourceException) {
        AddResult.Failed(e.error)
    }

    /** 用户确认的预览已含完整快照，订阅与关联一次写入。 */
    suspend fun addRepo(info: RepoInfo, linkedPackage: String? = null, linkedLabel: String? = null): AddResult {
        val tracked = TrackedRepo(
            id = info.githubId, owner = info.owner, repo = info.repo, addedAt = Instant.now(),
            linkedPackage = linkedPackage,
            noteName = linkedLabel,
            autoNote = linkedLabel != null,
        )
        var outcome: AddResult = AddResult.Duplicate
        store.update { s ->
            if (s.repos.any { it.id == tracked.id }) s else {
                outcome = AddResult.Added(tracked)
                s.copy(
                    repos = s.repos + tracked,
                    snapshots = s.snapshots + (tracked.id to info.toSnapshot()),
                )
            }
        }
        return outcome
    }

    /** 乐观删除：调用方负责 Snackbar 撤销（恢复条目 + 快照） */
    suspend fun removeRepo(id: Long) = store.update { s ->
        s.copy(repos = s.repos.filterNot { it.id == id }, snapshots = s.snapshots - id)
    }

    suspend fun restoreRepo(repo: TrackedRepo, snapshot: RepoSnapshot?) =
        store.update { s ->
            if (s.repos.any { it.id == repo.id }) return@update s
            s.copy(
                repos = s.repos + repo,
                snapshots = if (snapshot != null) s.snapshots + (repo.id to snapshot) else s.snapshots,
            )
        }

    suspend fun markActivityRead(
        id: Long,
        releaseTags: Set<String> = emptySet(),
        commitShas: Set<String> = emptySet(),
        visibleReleaseHead: String? = null,
        visibleCommitHead: String? = null,
    ) = store.update { s ->
        val snapshot = s.snapshots[id] ?: return@update s
        val activity = snapshot.activity.markRead(releaseTags, commitShas, visibleReleaseHead, visibleCommitHead)
        if (activity === snapshot.activity) s else s.copy(snapshots = s.snapshots + (id to snapshot.copy(activity = activity)))
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
        autoClear: Boolean = false, // 系统清除（关联应用已卸载）：不动 dismissed 记忆，重装后可自动重绑
        expectedPackage: String? = null,
        onlyIfUnlinked: Boolean = false,
    ): Boolean {
        var changed = false
        store.update { s ->
            val repo = s.repos.firstOrNull { it.id == id } ?: return@update s
            if (expectedPackage != null && repo.linkedPackage != expectedPackage) return@update s
            if (onlyIfUnlinked && (repo.linkedPackage != null || repo.autoLinkDismissed)) return@update s
            val custom = repo.noteName != null && !repo.autoNote
            val note = when {
                custom -> repo.noteName
                packageName == null -> null
                else -> newLabel ?: repo.noteName?.takeIf { packageName == repo.linkedPackage }
            }
            val next = repo.copy(
                linkedPackage = packageName,
                autoLinkDismissed = if (autoClear) repo.autoLinkDismissed else packageName == null,
                noteName = note,
                autoNote = !custom && note != null,
            )
            changed = next != repo
            if (changed) s.copy(repos = s.repos.map { if (it.id == id) next else it }) else s
        }
        return changed
    }

    /** Apply an app-label lookup only to the link and note it was requested for. */
    suspend fun refreshAppLabel(expected: TrackedRepo, label: String) = store.update { s ->
        val repo = s.repos.firstOrNull { it.id == expected.id } ?: return@update s
        if (repo.addedAt != expected.addedAt || repo.linkedPackage != expected.linkedPackage ||
            repo.noteName != expected.noteName || repo.autoNote != expected.autoNote
        ) return@update s
        s.copy(repos = s.repos.map { if (it.id == repo.id) it.copy(noteName = label, autoNote = true) else it })
    }

    /** 「下载保留」记录（安装失败不删，可直接重装） */
    suspend fun setDownloadedApk(id: Long, apk: DownloadedApk?) = store.update { s ->
        s.copy(repos = s.repos.map { if (it.id == id) it.copy(downloadedApk = apk) else it })
    }

    suspend fun clearDownloadedApkIfMatches(id: Long, expected: DownloadedApk) = store.update { s ->
        s.copy(repos = s.repos.map {
            if (it.id == id && it.downloadedApk == expected) it.copy(downloadedApk = null) else it
        })
    }

    suspend fun clearMissingDownloads() = store.update { state ->
        state.copy(repos = state.repos.map { repo ->
            val apk = repo.downloadedApk
            if (apk != null && !File(filesDir, "apks/${repo.id}/${apk.fileName}").isFile) {
                repo.copy(downloadedApk = null)
            } else repo
        })
    }

    /** syncLatestReleaseTag 的结果：Tag = 拿到（可能 null = 无带 APK 资产的 release） */
    sealed interface SyncTagOutcome {
        data class Tag(val tag: String?, val changed: Boolean) : SyncTagOutcome
        data object Unchanged : SyncTagOutcome // 304：tag 维持旧值
        data class Failed(val error: SourceError) : SyncTagOutcome
        data object Missing : SyncTagOutcome // 订阅已不存在
    }

    /**
     * 后台同步取最新 release tag：ETag 条件请求（304 无需响应正文）+ 失败保旧值。
     * 此前瞬时失败会把 latestReleaseTag 写空——漏报一条新版本通知、清掉「可更新」标识。
     */
    suspend fun syncLatestReleaseTag(id: Long, forNotification: Boolean = true): SyncTagOutcome {
        val tracked = trackedOrNull(id) ?: return SyncTagOutcome.Missing
        val baseline = store.state.value.snapshots[id] ?: return SyncTagOutcome.Missing
        return try {
            val result = provider(tracked.provider).releasesForSync(tracked.owner, tracked.repo, baseline.releasePageEtag)
            recordReleasePage(tracked, baseline, result, forNotification)
        } catch (e: SourceException) {
            SyncTagOutcome.Failed(e.error)
        }
    }

    private suspend fun recordReleasePage(
        tracked: TrackedRepo, baseline: RepoSnapshot, result: SyncReleasesResult, forNotification: Boolean,
    ): SyncTagOutcome {
        val page = result as? SyncReleasesResult.Page
        val releases = page?.releases.orEmpty().filter { it.apkAssets.isNotEmpty() && it.tagName.isNotEmpty() }
        val latest = releases.firstOrNull()
        val tags = releases.map { it.tagName }
        val id = tracked.id
        var outcome: SyncTagOutcome = SyncTagOutcome.Missing
        store.update { s ->
            val old = s.snapshots[id] ?: return@update s
            if (s.repos.none { it.id == id && it.addedAt == tracked.addedAt }) return@update s
            val moved = old.currentReleaseTag != baseline.currentReleaseTag || old.releasePageEtag != baseline.releasePageEtag
            // 并发翻到下一版后，迟到的响应只有明确包含该锚点且有更新条目时才能推进。
            val acceptPage = page != null && (!moved || tags.indexOf(old.currentReleaseTag) > 0)
            // A short first page covers the entire history, including releases without an APK.
            // On a full page the previously observed APK might still be on page two.
            val completePage = acceptPage && page.releases.size < RELEASES_PER_PAGE
            val publication = when {
                acceptPage && latest != null -> ApkPublication(
                    latest.tagName, latest.publishedAt ?: old.latestApk?.takeIf { it.tag == latest.tagName }?.publishedAt,
                )
                completePage -> null
                else -> old.latestApk
            }
            val tag = publication?.tag ?: old.latestReleaseTag.takeUnless { completePage }
            val known = !old.importedReleaseBaseline && (old.releaseBaselineKnown || old.latestReleaseTag != null)
            val movedBack = SnapshotDiff.releaseMovedBack(old.latestApk, publication)
            val stillPublished = if (completePage && old.activity.unreadReleaseTags.any { it !in tags }) {
                old.activity.copy(unreadReleaseTags = old.activity.unreadReleaseTags.intersect(tags.toSet()))
            } else old.activity
            val activity = if (acceptPage && !movedBack && SnapshotDiff.releaseChanged(old, tag)) {
                stillPublished.discoverReleases(tags.newerThan(old.currentReleaseTag))
            } else stillPublished
            // 前台先发现新发布时保留通知游标。Worker 收到 304 也能处理它，已经看过则静默。
            val notify = forNotification && known && !movedBack && tag != null && tag != old.latestReleaseTag &&
                tag in activity.unreadReleaseTags
            val next = old.copy(
                latestApk = publication,
                // 已发布较早的版本重新成为首项时同步通知游标，随后 304 也不会把它当成新发布。
                latestReleaseTag = if (forNotification || !known || movedBack || tag == null) tag else old.latestReleaseTag,
                releasePageEtag = if (acceptPage) page.etag else old.releasePageEtag,
                releaseBaselineKnown = true, importedReleaseBaseline = false,
                activity = activity,
            )
            outcome = if (!acceptPage && !notify) SyncTagOutcome.Unchanged else SyncTagOutcome.Tag(tag, notify)
            if (next == old) s else s.copy(snapshots = s.snapshots + (id to next))
        }
        return outcome
    }

    /** 添加页搜索辅助 */
    suspend fun search(query: String): RepoSearchPage =
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
            var tipNeeded = old?.lastCommitSha == null ||
                (old.lastCommitSha.isNotEmpty() && old.lastCommitDate == null) || old.pendingTipSync
            // defaultBranch 缺失（旧版本快照补齐）→ 不带 ETag 强拉一次：一次性 1 配额，
            // 之后 etag 更新恢复 条件请求。304 响应体不含字段，无法从 304 补齐
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
                    if (info.githubId != id) return SourceError.NotFound
                    val renamed = tracked.owner != info.owner || tracked.repo != info.repo
                    // latestReleaseTag / lastCommitSha 是跨刷新的累积状态（并发同步路径维护），
                    // 替换快照时必须保留；读取必须在 update 的 transform 内——否则与
                    // 并发写入会互相用旧值覆盖新值（重复通知竞态的根因）
                    store.update { s ->
                        if (s.repos.none { it.id == id && it.addedAt == tracked.addedAt }) return@update s
                        val cur = s.snapshots[id]
                        s.copy(
                            repos = if (renamed) {
                                s.repos.map { if (it.id == id) it.copy(owner = info.owner, repo = info.repo) else it }
                            } else {
                                s.repos
                            },
                            snapshots = s.snapshots + (id to info.toSnapshot().copy(
                                latestReleaseTag = cur?.latestReleaseTag,
                                latestApk = cur?.latestApk,
                                lastCommitSha = cur?.lastCommitSha,
                                commitsEtag = cur?.commitsEtag,
                                lastCommitDate = cur?.lastCommitDate,
                                releasePageEtag = cur?.releasePageEtag,
                                pendingTipSync = true,
                                importedCommitBaseline = cur?.importedCommitBaseline ?: false,
                                importedReleaseBaseline = cur?.importedReleaseBaseline ?: false,
                                releaseBaselineKnown = cur?.releaseBaselineKnown ?: false,
                                activity = cur?.activity ?: RepoActivity(),
                                // defaultBranch 取新 info（上游可能改默认分支）
                            )),
                        )
                    }
                }
            }
            if (tipNeeded) {
                // 浏览分支 tip 条件请求：304（浏览分支没动，dev 推进不在此列）→ 沿用缓存
                // 不亮灯；200 → sha 变化经 SnapshotDiff 点亮。失败静默：基线保留下轮重试，
                // 不拖垮 repoInfo 的成功
                val tipBranch = tracked.branch // 发请求时的浏览分支
                // date 缺失 = 旧版本基线补时间：必须无条件拉——带旧 etag 只会 304 空体，
                // 补不到 date；一次 1 配额，date 落库后恢复 条件请求
                val tipEtag = if (old?.lastCommitDate == null) null else old.commitsEtag
                try {
                    val tip = provider(tracked.provider).commitTipForSync(tracked.owner, tracked.repo, tipBranch, tipEtag)
                    store.update { s ->
                        val cur = s.snapshots[id] ?: return@update s
                        val currentRepo = s.repos.firstOrNull { it.id == id } ?: return@update s
                        if (currentRepo.branch != tipBranch || currentRepo.addedAt != tracked.addedAt) return@update s
                        if (cur.lastCommitSha != old?.lastCommitSha || cur.commitsEtag != old?.commitsEtag) return@update s
                        val next = when (tip) {
                            is SyncCommitTipResult.Tip -> cur.copy(
                                lastCommitSha = tip.sha,
                                commitsEtag = tip.etag,
                                lastCommitDate = tip.date,
                                pendingTipSync = false,
                                importedCommitBaseline = false,
                                activity = if (SnapshotDiff.commitChanged(cur, tip.sha)) {
                                    cur.activity.discoverCommits(listOf(tip.sha))
                                } else cur.activity,
                            )
                            else -> cur.copy(pendingTipSync = false, importedCommitBaseline = false)
                        }
                        s.copy(snapshots = s.snapshots + (id to next))
                    }
                } catch (_: SourceException) {
                    // 仓库信息已同步，分支 tip 保留旧基线。
                }
            }
            null
        } catch (e: SourceException) {
            e.error // 快照不动 = 旧数据保留
        }
    }

    /** 并发刷新全部订阅；返回 id → 首个错误（全成功为空 Map） */
    suspend fun refreshAll(refreshingCardIds: Set<Long> = emptySet()): Map<Long, SourceError> = coroutineScope {
        store.state.value.repos.map { tracked ->
            async {
                val infoError = refreshRepo(tracked.id)
                // 已展开卡片会取版本历史第一页，那次请求同时刷新发布元数据。
                val releaseError = if (infoError == null && tracked.id !in refreshingCardIds) {
                    (syncLatestReleaseTag(tracked.id, forNotification = false) as? SyncTagOutcome.Failed)?.error
                } else null
                tracked.id to (infoError ?: releaseError)
            }
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
        val tracked = s.repos.firstOrNull { it.id == id } ?: return@update s
        if (tracked.branch == branch) return@update s
        val old = s.snapshots[id]
        s.copy(
            repos = s.repos.map { if (it.id == id) it.copy(branch = branch) else it },
            // 切分支 = 换了 tip 观测对象：旧分支的指纹不再有意义，清基线让下轮
            // sync 重新立（首次记录不亮灯）。窗口内并发的 sync 读到旧基线 → tip
            // 相同不亮，无副作用
            snapshots = if (old != null) {
                s.snapshots + (id to old.copy(
                    lastCommitSha = null, commitsEtag = null, lastCommitDate = null,
                    activity = old.activity.copy(unreadCommitShas = emptySet(), recentlyViewedCommits = emptyList()),
                ))
            } else {
                s.snapshots
            },
        )
    }

    /** 置顶 / 取消置顶（null = 取消；时间戳保证置顶组内顺序稳定） */
    suspend fun togglePinned(id: Long) = store.update { s ->
        s.copy(repos = s.repos.map { repo ->
            if (repo.id != id) repo else repo.copy(pinnedAt = if (repo.pinnedAt == null) Instant.now() else null)
        })
    }

    // ---- 详情页数据（利用已取到的第一页登记活动，SourceException 由调用方接） ----

    /** 展开卡按已同步的 SHA 读取预览，避免迟到的响应覆盖新分支头。 */
    suspend fun previewCommitsAt(repoId: Long, head: String): List<CommitRecord> {
        if (head.isEmpty()) return emptyList()
        val tracked = trackedOrNull(repoId) ?: return emptyList()
        return provider(tracked.provider).commits(tracked.owner, tracked.repo, page = 1, sha = head)
    }

    /** 提交列表自动跟随该仓库已选分支 */
    suspend fun commits(repoId: Long, page: Int): List<CommitRecord> {
        val t = trackedOrNull(repoId) ?: return emptyList()
        val baseline = store.state.value.snapshots[repoId]
        val commits = provider(t.provider).commits(t.owner, t.repo, page, t.branch)
        val head = commits.firstOrNull()?.sha
        if (page == 1 && baseline != null && head != null) {
            val shas = commits.map { it.sha }.newerThan(baseline.lastCommitSha)
            store.update { s ->
                val cur = s.snapshots[repoId] ?: return@update s
                val tracked = s.repos.firstOrNull { it.id == repoId } ?: return@update s
                if (tracked.addedAt != t.addedAt || tracked.branch != t.branch ||
                    (cur.lastCommitSha != baseline.lastCommitSha && cur.lastCommitSha != head)
                ) return@update s
                val activity = if (SnapshotDiff.commitChanged(baseline, head)) cur.activity.discoverCommits(shas) else cur.activity
                val next = cur.copy(
                    lastCommitSha = head,
                    lastCommitDate = commits.first().date ?: cur.lastCommitDate.takeIf { cur.lastCommitSha == head },
                    commitsEtag = cur.commitsEtag.takeIf { cur.lastCommitSha == head },
                    importedCommitBaseline = false, pendingTipSync = false,
                    activity = activity,
                )
                if (next == cur) s else s.copy(snapshots = s.snapshots + (repoId to next))
            }
        }
        return commits
    }

    suspend fun releases(repoId: Long, page: Int): List<ReleaseRecord> {
        val t = trackedOrNull(repoId) ?: return emptyList()
        val source = provider(t.provider)
        if (page != 1) return source.releases(t.owner, t.repo, page, perPage = RELEASES_PER_PAGE)
        val baseline = store.state.value.snapshots[repoId]
        // 同一个第一页响应既给界面列表，也给后续条件请求提供 ETag。
        val result = source.releasesForSync(t.owner, t.repo, etag = null)
        if (result is SyncReleasesResult.Page) {
            if (baseline != null) recordReleasePage(t, baseline, result, forNotification = false)
            return result.releases
        }
        return source.releases(t.owner, t.repo, page, perPage = RELEASES_PER_PAGE)
    }

    suspend fun releaseByTag(repoId: Long, tag: String): ReleaseRecord? {
        val t = trackedOrNull(repoId) ?: return null
        return provider(t.provider).releaseByTag(t.owner, t.repo, tag)
    }

}

/** 空仓库后的第一页全部为新增；找不到上次的锚点时，仅能确定最新一项发生了变化。 */
private fun List<String>.newerThan(previous: String?): List<String> {
    if (previous.isNullOrEmpty()) return this
    val index = indexOf(previous)
    return if (index >= 0) take(index) else take(1)
}
