package io.candy.tick.data

import io.candy.tick.data.model.RepoSnapshot
import java.time.Duration

/** 自动同步的 UI 信号：进度与结果（首页细线 + 悬浮胶囊消费；手动下拉刷新不接入） */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Running(val done: Int, val total: Int) : SyncStatus
    data class Done(val changed: Int, val failed: Int, val total: Int = 0, val at: Long = System.currentTimeMillis()) : SyncStatus
}

/** 快照「有新活动」判定：pushedAt 推进 >1 分钟，或 release tag 变化（首页圆点与同步结果共用，口径一致） */
object SnapshotDiff {
    fun hasNewActivity(old: RepoSnapshot, new: RepoSnapshot): Boolean {
        val pushAdvanced = old.pushedAt != null && new.pushedAt != null &&
            Duration.between(old.pushedAt, new.pushedAt).toMinutes() > 1
        val tagChanged = old.latestReleaseTag != null && new.latestReleaseTag != null &&
            new.latestReleaseTag != old.latestReleaseTag
        return pushAdvanced || tagChanged
    }
}
