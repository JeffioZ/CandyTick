package io.candy.tick.data

import android.content.Context
import io.candy.tick.data.model.AppState
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 磁盘缓存与崩溃日志管理：仓库 APK（apks/{repoId}/）、自身更新 APK（updates/）、分享临时文件（cache/shares/）与崩溃日志。
 * 「清理缓存」只清安装包与临时文件（不动订阅数据）；崩溃日志由专属浮层独立管理；App 启动时回收孤儿目录。
 */
class CacheManager(private val context: Context) {

    internal class ScannedFile(private val file: File) {
        val bytes = file.length()
        private val modifiedAt = file.lastModified()

        fun delete(): Long =
            if (file.lastModified() == modifiedAt && file.length() == bytes && file.delete()) bytes else 0L
    }

    internal class CacheInfo(private val files: List<ScannedFile>) {
        val totalBytes = files.sumOf { it.bytes }

        fun clean(): Long = files.sumOf { it.delete() }
    }

    private val apksRoot: File get() = File(context.filesDir, "apks")
    private val updatesDir: File get() = File(context.filesDir, "updates")
    private val sharesDir: File get() = File(context.cacheDir, "shares")
    private val storeIndexDir: File get() = File(context.cacheDir, "store-index")
    private val crashLogFile: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash.log")

    /** Use the files counted in this scan. Downloads published later belong to the next scan. */
    internal fun scan() = CacheInfo(
        files = sequenceOf(apksRoot, updatesDir, sharesDir, storeIndexDir).flatMap { it.walkTopDown() }
            .filter { it.isFile && !it.isDownloadPart() }.map(::ScannedFile).toList(),
    )

    internal fun crashLogBytes(): Long = crashLogFile.takeIf { it.isFile }?.length() ?: 0L

    internal fun readCrashLog(): String? =
        runCatching { crashLogFile.takeIf { it.isFile && it.length() > 0 }?.readText() }.getOrNull()

    internal fun clearCrashLog(): Boolean =
        runCatching { !crashLogFile.exists() || crashLogFile.delete() }.getOrDefault(false)

    /**
     * Use the restored snapshot to reclaim files left by earlier processes.
     * Current-process downloads may already be running when this scan reaches their directory.
     */
    fun cleanStaleFiles(restored: AppState, corruptReset: Boolean = false) {
        // 上个进程崩溃留下的临时文件没有任务再负责收尾。按进程启动时间区分活跃下载。
        // 状态损坏重置后快照是空的，按它回收等于把用户手里的 APK 全删掉：本进程跳过（corruptReset），
        // 跨进程以 .corrupt 备份的 mtime（=损坏时刻）起 7 天宽限——留出 adb 取回状态与 APK 人工恢复的窗口。
        val corruptBackup = File(context.filesDir, AppStateStore.STATE_FILE + ".corrupt")
        val inCorruptGrace = corruptReset ||
            (corruptBackup.isFile && System.currentTimeMillis() - corruptBackup.lastModified() < CORRUPT_GRACE_MS)
        if (!inCorruptGrace) {
            val startedAt = System.currentTimeMillis() -
                (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())
            val retained = restored.repos.associate { it.id to it.downloadedApk?.fileName }
            apksRoot.listFiles()?.forEach { dir ->
                if (dir.name == "store" && dir.isDirectory) {
                    // 商店下载态仅驻留内存，上个进程遗留的 APK/.part 全部回收
                    dir.listFiles()?.forEach { pkgDir ->
                        pkgDir.listFiles()?.forEach { file ->
                            if (file.isFile && file.lastModified() < startedAt) file.delete()
                        }
                        pkgDir.delete()
                    }
                    return@forEach
                }
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
        }
        cleanExpiredShares(context)
    }

    private fun File.isDownloadPart(): Boolean = name.endsWith(".part") || name.substringAfterLast(".part-", "").toLongOrNull() != null

    companion object {
        /** 损坏重置后 APK 回收的宽限期（DSH 双 review：内存标志只保一个进程，冷启动会照删） */
        private val CORRUPT_GRACE_MS = TimeUnit.DAYS.toMillis(7)

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
