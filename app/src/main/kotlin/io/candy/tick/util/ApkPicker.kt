package io.candy.tick.util

import io.candy.tick.data.model.ApkAsset

/**
 * 多 APK 自动选择（Obtainium 哲学：唯一候选直接选；多候选按文件名 ABI 标记匹配
 * SUPPORTED_ABIS（优先设备 ABI，再找 universal）；无标记无法区分则手选。
 * 自动选择仅为默认选中，完整资产列表始终可见可改选。
 */
object ApkPicker {

    /** ABI 别名表（覆盖变体，取自 Obtainium #2416 实战教训） */
    private val abiPatterns = mapOf(
        "arm64-v8a" to listOf("arm64-v8a", "arm64", "aarch64"),
        "armeabi-v7a" to listOf("armeabi-v7a", "armv7a", "armv7", "arm", "armhf"),
        "x86_64" to listOf("x86_64", "x86-64", "x64", "amd64"),
        "x86" to listOf("x86(?![-_]64)", "i[3-6]86"),
        "universal" to listOf("universal", "all", "multi"),
    ).mapValues { (_, aliases) ->
        Regex("(?<![a-z0-9])(?:${aliases.joinToString("|")})(?![a-z0-9])", RegexOption.IGNORE_CASE)
    }

    /** release 资产中的 Android 安装包候选（.apk/.apks/.xapk/.apkm，与 GitHubApi 过滤口径一致） */
    fun apkCandidates(assets: List<ApkAsset>): List<ApkAsset> =
        assets.filter { isPackageAsset(it.name) }

    fun isPackageAsset(name: String): Boolean = packageExtension(name) != null

    internal fun packageExtension(name: String): String? =
        name.substringAfterLast('.', "").lowercase().takeIf { it in packageExtensions }

    private val packageExtensions = setOf("apk", "apks", "xapk", "apkm")

    /**
     * @return selected = 推荐项（null = 无法自动区分）；auto = 是否自动选中
     */
    fun pick(assets: List<ApkAsset>, supportedAbis: List<String>): Pair<ApkAsset?, Boolean> {
        val candidates = apkCandidates(assets)
        if (candidates.isEmpty()) return null to false
        if (candidates.size == 1) return candidates.first() to true

        // 1) 精确匹配设备 ABI（顺序即优先级）
        for (abi in supportedAbis) {
            val pattern = abiPatterns[abi] ?: continue
            val hit = candidates.firstOrNull { pattern.containsMatchIn(it.name) }
            if (hit != null) return hit to true
        }
        // 2) universal 兜底
        val universal = candidates.firstOrNull { abiPatterns.getValue("universal").containsMatchIn(it.name) }
        if (universal != null) return universal to true
        return null to false // 列表手选
    }
}
