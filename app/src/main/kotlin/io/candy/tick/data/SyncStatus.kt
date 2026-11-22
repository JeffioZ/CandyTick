package io.candy.tick.data

import io.candy.tick.data.model.RepoSnapshot
import io.candy.tick.data.model.ApkPublication

/** 首页只显示已开始执行的自动同步。每轮有独立身份，供页面判断是否已离开过。 */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    class Running : SyncStatus
    data class Done(val run: Running, val changed: Int, val failed: Int, val total: Int = 0) : SyncStatus {
        val allFailed: Boolean get() = total > 0 && failed >= total
    }
}

/**
 * 快照「有新活动」判定：release tag 变化，或浏览分支 tip sha 变化（首页圆点与同步结果共用，口径一致）。
 * pushedAt 判定已退场（2026-11-21）：GitHub 的 pushed_at 统计**任意分支**的推送——
 * dev 分支推进会让它前进而浏览分支毫无动静（InstallerX-Revived 实例：pushed_at
 * 当天更新、main 最新提交停在 9 天前），圆点误亮且详情页无从查证。改为 lastCommitSha
 * （SyncWorker 按浏览分支条件请求维护，304 表示内容未变）。基线缺失（null：首次/切分支/导入）
 * 只记录不亮灯。
 */
object SnapshotDiff {
    fun hasNewActivity(old: RepoSnapshot, new: RepoSnapshot): Boolean =
        (releaseChanged(old, new.currentReleaseTag) && !releaseMovedBack(old.latestApk, new.latestApk)) ||
            commitChanged(old, new.lastCommitSha)

    fun releaseMovedBack(old: ApkPublication?, current: ApkPublication?): Boolean =
        old?.publishedAt != null && current?.publishedAt != null && old.tag != current.tag &&
            current.publishedAt < old.publishedAt

    fun releaseChanged(old: RepoSnapshot, tag: String?): Boolean =
        !old.importedReleaseBaseline && (old.releaseBaselineKnown || old.currentReleaseTag != null) &&
            !tag.isNullOrEmpty() && tag != old.currentReleaseTag

    fun commitChanged(old: RepoSnapshot, sha: String?): Boolean =
        !old.importedCommitBaseline && old.lastCommitSha != null && !sha.isNullOrEmpty() && sha != old.lastCommitSha
}
