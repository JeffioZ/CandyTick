package io.candy.tick.data

import android.content.Context
import io.candy.tick.data.model.AppState
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 磁盘缓存与崩溃日志管理：仓库 APK（apks/{repoId}/）、自身更新 APK（updates/）、分享临时文件（cache/shares/）与崩溃日志。
 * 「清理缓存」只清安装包与临时文件（不动订阅数据）；崩溃日志由专属浮层独立管理；App 启动时回收孤儿目录。
 */
class CacheManager(private val context: Context, private val protectedStorePackages: () -> Set<String> = { emptySet() }) {

    internal class ScannedFile(private val file: File, private val isProtected: () -> Boolean = { false }) {
        val bytes = file.length()
        private val modifiedAt = file.lastModified()

        fun delete(): Long =
            if (!isProtected() && file.lastModified() == modifiedAt && file.length() == bytes && file.delete()) bytes else 0L
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
    internal fun scan(): CacheInfo {
        val storeRoot = File(apksRoot, "store")
        val busy = protectedStorePackages()
        val files = sequenceOf(apksRoot, updatesDir, sharesDir, storeIndexDir).flatMap { it.walkTopDown() }
            .filter { it.isFile && !it.isDownloadPart() }.mapNotNull { file ->
                val pkg = file.parentFile?.takeIf { it.parentFile == storeRoot }?.name
                if (pkg == null) ScannedFile(file)
                else if (pkg in busy) null
                else ScannedFile(file) { pkg in protectedStorePackages() }
            }.toList()
        return CacheInfo(files)
    }

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
        val marker = File(context.noBackupFilesDir, AppStateStore.RECOVERY_MARKER)
        val recoveryTime = marker.takeIf { it.isFile }?.lastModified()
        if (!corruptReset) {
            val startedAt = System.currentTimeMillis() -
                (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())
            fun reclaimable(file: File): Boolean = file.isFile && file.lastModified() < startedAt &&
                (recoveryTime == null || file.isDownloadPart() || file.lastModified() > recoveryTime)
            val retained = restored.repos.associate { it.id to it.downloadedApk?.fileName }
            apksRoot.listFiles()?.forEach { dir ->
                if (dir.name == "store" && dir.isDirectory) {
                    dir.listFiles()?.forEach { pkgDir ->
                        val record = restored.storeState.downloaded[pkgDir.name]
                        // Older records did not describe their splits. Keep their files until explicitly cleared.
                        val names = record?.store?.let { it.splits.toSet() + record.fileName }
                        pkgDir.listFiles()?.forEach { file ->
                            if (reclaimable(file) && pkgDir.name !in protectedStorePackages() &&
                                (record == null || names != null && file.name !in names || file.isDownloadPart())
                            ) file.delete()
                        }
                        pkgDir.delete()
                    }
                    return@forEach
                }
                val id = dir.name.toLongOrNull() ?: return@forEach
                val keep = retained[id]
                dir.listFiles()?.forEach { file ->
                    if (reclaimable(file) && file.name != keep) file.delete()
                }
                if (id !in retained) dir.delete() // Succeeds only when empty.
            }
            updatesDir.listFiles()?.forEach { file ->
                if (reclaimable(file) && file.name != restored.downloadedSelfUpdate?.fileName) {
                    file.delete()
                }
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
