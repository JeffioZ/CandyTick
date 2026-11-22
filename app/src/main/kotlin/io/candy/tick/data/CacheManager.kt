package io.candy.tick.data

import android.content.Context
import io.candy.tick.data.model.AppState
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 磁盘缓存管理：仓库 APK（apks/{repoId}/）、自身更新 APK（updates/）、崩溃日志。
 * 「清理缓存」设置页的数据层；App 启动时回收孤儿目录（仓库已删除但 APK 残留）。
 */
class CacheManager(private val context: Context) {

    internal class ScannedFile(private val file: File) {
        val bytes = file.length()
        private val modifiedAt = file.lastModified()

        fun delete(): Long =
            if (file.lastModified() == modifiedAt && file.length() == bytes && file.delete()) bytes else 0L
    }

    internal class CacheInfo(private val packages: List<ScannedFile>, private val log: ScannedFile?) {
        val apkBytes = packages.sumOf { it.bytes }
        val crashLogBytes = log?.bytes ?: 0L

        fun clean(apks: Boolean, crashLog: Boolean): Long {
            val packageBytes = if (apks) packages.sumOf { it.delete() } else 0L
            val logBytes = if (crashLog) log?.delete() ?: 0L else 0L
            return packageBytes + logBytes
        }
    }

    private val apksRoot: File get() = File(context.filesDir, "apks")
    private val updatesDir: File get() = File(context.filesDir, "updates")
    private val crashLogFile: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash.log")

    /** Use the files counted in this scan. Downloads published later belong to the next scan. */
    internal fun scan() = CacheInfo(
        packages = sequenceOf(apksRoot, updatesDir).flatMap { it.walkTopDown() }
            .filter { it.isFile && !it.isDownloadPart() }.map(::ScannedFile).toList(),
        log = crashLogFile.takeIf { it.isFile }?.let(::ScannedFile),
    )

    /**
     * Use the restored snapshot to reclaim files left by earlier processes.
     * Current-process downloads may already be running when this scan reaches their directory.
     */
    fun cleanStaleFiles(restored: AppState) {
        // 上个进程崩溃留下的临时文件没有任务再负责收尾。按进程启动时间区分活跃下载。
        val startedAt = System.currentTimeMillis() -
            (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())
        val retained = restored.repos.associate { it.id to it.downloadedApk?.fileName }
        apksRoot.listFiles()?.forEach { dir ->
            val id = dir.name.toLongOrNull() ?: return@forEach
            val keep = retained[id]
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name != keep && file.lastModified() < startedAt) file.delete()
            }
            if (id !in retained) dir.delete() // Succeeds only when empty.
        }
        updatesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != restored.downloadedSelfUpdate?.fileName && file.lastModified() < startedAt) {
                file.delete()
            }
        }
        cleanExpiredShares(context)
    }

    private fun File.isDownloadPart(): Boolean = name.endsWith(".part") || name.substringAfterLast(".part-", "").toLongOrNull() != null

    companion object {
        private fun cleanExpiredShares(context: Context) {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            File(context.cacheDir, "shares").listFiles()?.forEach {
                if (it.isFile && it.lastModified() < cutoff) it.delete()
            }
        }

        internal fun createShareFile(context: Context, backup: Boolean): File {
            val dir = File(context.cacheDir, "shares").apply { mkdirs() }
            cleanExpiredShares(context)
            return if (backup) File.createTempFile("CandyTick-backup-", ".json", dir)
                else File.createTempFile("CandyTick-crash-", ".txt", dir)
        }
    }
}
