package io.candy.tick.data

import android.content.Context
import java.io.File

/**
 * 磁盘缓存管理：仓库 APK（apks/{repoId}/）、自身更新 APK（updates/）、崩溃日志。
 * 「清理缓存」设置页的数据层；App 启动时回收孤儿目录（仓库已删除但 APK 残留）。
 */
class CacheManager(private val context: Context) {

    data class CacheInfo(
        val repoApkBytes: Long,
        val updateApkBytes: Long,
        val crashLogBytes: Long,
    ) {
        val totalBytes: Long get() = repoApkBytes + updateApkBytes + crashLogBytes
    }

    private val apksRoot: File get() = File(context.filesDir, "apks")
    private val updatesDir: File get() = File(context.filesDir, "updates")
    private val crashLogFile: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash.log")

    /** 实时扫描各类缓存大小（进入清理浮层时调用） */
    fun scan(): CacheInfo = CacheInfo(
        repoApkBytes = dirSize(apksRoot),
        updateApkBytes = dirSize(updatesDir),
        crashLogBytes = crashLogFile.takeIf { it.exists() }?.length() ?: 0L,
    )

    /**
     * 清理选中的缓存类别。返回实际释放的字节数。
     * 调用方负责：清后逐仓库 setDownloadedApk(id, null) + SelfUpdater 状态重置。
     */
    fun clean(repoApks: Boolean, updateApks: Boolean, crashLog: Boolean): Long {
        var freed = 0L
        if (repoApks) freed += deleteDir(apksRoot)
        if (updateApks) freed += deleteDir(updatesDir)
        if (crashLog && crashLogFile.exists()) {
            freed += crashLogFile.length()
            crashLogFile.delete()
        }
        return freed
    }

    /**
     * App 启动时回收孤儿：apks/ 下无对应订阅的 repoId 目录（仓库已删但 APK 残留）。
     * 冷启动时 Snackbar 早已消失，不影响撤销安全。
     */
    fun cleanOrphanApks(subscribedIds: Set<Long>) {
        val root = apksRoot
        if (!root.isDirectory) return
        root.listFiles()?.forEach { dir ->
            val dirId = dir.name.toLongOrNull()
            if (dirId != null && dirId !in subscribedIds) {
                dir.deleteRecursively()
            }
        }
    }

    private fun dirSize(dir: File): Long =
        if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    private fun deleteDir(dir: File): Long {
        val size = dirSize(dir)
        dir.deleteRecursively()
        return size
    }
}
