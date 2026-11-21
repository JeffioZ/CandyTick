package io.candy.tick.data

import io.candy.tick.data.model.RepoSnapshot

/** 自动同步的 UI 信号：进度与结果（首页细线 + 悬浮胶囊消费；手动下拉刷新不接入） */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Running(val done: Int, val total: Int) : SyncStatus
    data class Done(val changed: Int, val failed: Int, val total: Int = 0, val at: Long = System.currentTimeMillis()) : SyncStatus
}

/**
 * 快照「有新活动」判定：release tag 变化，或浏览分支 tip sha 变化（首页圆点与同步结果共用，口径一致）。
 * pushedAt 判定已退场（2026-11-21）：GitHub 的 pushed_at 统计**任意分支**的推送——
 * dev 分支推进会让它前进而浏览分支毫无动静（InstallerX-Revived 实例：pushed_at
 * 当天更新、main 最新提交停在 9 天前），圆点误亮且详情页无从查证。改为 lastCommitSha
 * （SyncWorker 按浏览分支条件请求维护，304 免配额）。基线缺失（null：首次/切分支/导入）
 * 只记录不亮灯。
 */
object SnapshotDiff {
    fun hasNewActivity(old: RepoSnapshot, new: RepoSnapshot): Boolean {
        val tagChanged = old.latestReleaseTag != null && new.latestReleaseTag != null &&
            new.latestReleaseTag != old.latestReleaseTag
        val commitChanged = old.lastCommitSha != null && new.lastCommitSha != null &&
            new.lastCommitSha != old.lastCommitSha
        return tagChanged || commitChanged
    }
}
