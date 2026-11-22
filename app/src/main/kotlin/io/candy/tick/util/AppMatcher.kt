package io.candy.tick.util

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import io.candy.tick.data.model.TrackedRepo

/**
 * 本机应用 ↔ 仓库 自动配对（Obtainium 静默体验的本地化实现）。
 * 全程只用 PackageManager 本地查询——零下载、零文件副作用、无残留。
 *
 * 置信度分级（静默自动化只认高置信且全机唯一）：
 * - 高（可自动关联）：标签归一化全等 / 包名尾段全等（带通用词黑名单）/ 包名含 owner.repo 相邻段
 *   （io.github.<owner>.<repo>、com.github.<owner>.<repo> 规范）
 * - 低（仅建议条，用户点一下才生效）：标签包含式匹配
 */
object AppMatcher {

    /** 通用包名尾段黑名单：太泛的词自动绑等于赌 */
    private val GENERIC_SEGMENTS = setOf(
        "app", "apps", "android", "mobile", "client", "core", "common",
        "main", "free", "pro", "lite", "demo", "sample", "release", "debug",
    )

    data class Candidate(val packageName: String, val label: String)

    private val nonAlphanumeric = Regex("[^a-z0-9]")
    private fun norm(s: String) = s.lowercase().replace(nonAlphanumeric, "")

    /** 可启动应用池（launcher intent）：用户可见的应用才可能是关联对象 */
    private fun launcherApps(context: Context): List<Pair<String, String>> = runCatching {
        val pm = context.packageManager
        launcherEntries(context).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .toList()
    }.getOrDefault(emptyList())

    /** 高置信候选：三条规则任一命中；命中多个不同包则放弃（宁可不绑） */
    fun bestAutoMatch(context: Context, owner: String, repo: String): Candidate? {
        val repoN = norm(repo)
        val ownerN = norm(owner)
        if (repoN.isEmpty()) return null
        val hits = launcherApps(context).mapNotNull { (pkg, label) ->
            val lastSeg = pkg.substringAfterLast('.').lowercase()
            val segments = pkg.split('.').map { norm(it) }.filter { it.isNotEmpty() }
            val matched = when {
                norm(label) == repoN -> true // 标签全等
                norm(lastSeg) == repoN && lastSeg !in GENERIC_SEGMENTS && repoN.length >= 4 -> true // 包名尾段全等
                segments.zipWithNext().any { (a, b) -> a == ownerN && b == repoN } -> true // owner.repo 相邻段
                else -> false
            }
            if (matched) Candidate(pkg, label) else null
        }
        return if (hits.size == 1) hits.first() else null
    }

    /** 弱匹配唯一命中（标签包含式）：详情页建议条用，用户确认才生效 */
    fun containmentSuggestion(context: Context, repo: String): Candidate? {
        val repoN = norm(repo)
        if (repoN.length < 3) return null
        val hits = launcherApps(context).mapNotNull { (pkg, label) ->
            val n = norm(label)
            val matched = n.length >= 3 && (n.contains(repoN) || repoN.contains(n))
            if (matched) Candidate(pkg, label) else null
        }
        return if (hits.size == 1) hits.first() else null
    }
}

internal fun launcherEntries(context: Context): Sequence<ResolveInfo> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, 0).asSequence()
        .filterNot { it.activityInfo.packageName == context.packageName }
        .distinctBy { it.activityInfo.packageName }
}

/** PackageManager can return the package or component name when no display label was declared. */
internal fun appDisplayName(packageName: String, label: CharSequence?): String? =
    label?.toString()?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals(packageName, ignoreCase = true) &&
            !it.startsWith("$packageName.", ignoreCase = true) && !it.startsWith("$packageName/", ignoreCase = true)
    }

internal fun installedVersionName(context: Context, packageName: String): String? =
    runCatching { context.packageManager.getPackageInfo(packageName, 0).versionName }
        .getOrNull()?.takeIf { it.isNotBlank() }

internal fun installedVersionName(context: Context, repo: TrackedRepo): String? =
    repo.linkedPackage?.let { installedVersionName(context, it) }?.let { VersionCompare.installedName(it, repo) }

/** 已安装应用的显示名。部分应用只在桌面入口声明名称。 */
fun labelOf(context: Context, packageName: String): String? = runCatching {
    val pm = context.packageManager
    appDisplayName(packageName, pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)))?.let { return@runCatching it }
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
    pm.queryIntentActivities(launcher, 0).firstNotNullOfOrNull { appDisplayName(packageName, it.loadLabel(pm)) }
}.getOrNull()

/** APK 文件的显示名（下载自动关联用；sourceDir 需回填否则 loadLabel 只给包名） */
fun apkLabelOf(context: Context, file: java.io.File): String? = runCatching {
    val pm = context.packageManager
    val info = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return@runCatching null
    info.applicationInfo?.let {
        it.sourceDir = file.absolutePath
        pm.getApplicationLabel(it).toString()
    }
}.getOrNull()
