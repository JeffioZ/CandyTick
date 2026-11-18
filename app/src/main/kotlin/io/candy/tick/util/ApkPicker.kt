package io.candy.tick.util

import io.candy.tick.data.model.ApkAsset

/**
 * 多 APK 自动选择（Obtainium 哲学：唯一候选直接选；多候选按文件名 ABI 标记匹配
 * SUPPORTED_ABIS（精确 → universal → 任意支持 ABI）；无标记无法区分则手选。
 * 自动选择仅为默认选中，完整资产列表始终可见可改选。
 */
object ApkPicker {

    /** ABI 别名表（覆盖变体，取自 Obtainium #2416 实战教训） */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "arm64-v8a" to listOf("arm64-v8a", "arm64", "aarch64"),
        "armeabi-v7a" to listOf("armeabi-v7a", "armv7", "arm", "armhf"),
        "x86_64" to listOf("x86_64", "x64", "amd64"),
        "universal" to listOf("universal", "all", "multi"),
    )

    /** release 资产中的 Android 安装包候选（.apk/.apks/.xapk/.apkm，与 GitHubApi 过滤口径一致） */
    fun apkCandidates(assets: List<ApkAsset>): List<ApkAsset> =
        assets.filter { a ->
            a.name.endsWith(".apk", true) ||
                a.name.endsWith(".apks", true) ||
                a.name.endsWith(".xapk", true) ||
                a.name.endsWith(".apkm", true)
        }

    /**
     * @return selected = 推荐项（null = 无法自动区分）；auto = 是否自动选中
     */
    fun pick(assets: List<ApkAsset>, supportedAbis: List<String>): Pair<ApkAsset?, Boolean> {
        val candidates = apkCandidates(assets)
        if (candidates.isEmpty()) return null to false
        if (candidates.size == 1) return candidates.first() to true

        // 1) 精确匹配设备 ABI（顺序即优先级）
        for (abi in supportedAbis) {
            val aliases = ALIASES[abi] ?: continue
            val hit = candidates.firstOrNull { apk ->
                aliases.any { apk.name.contains(it, ignoreCase = true) }
            }
            if (hit != null) return hit to true
        }
        // 2) universal 兜底
        val universal = candidates.firstOrNull { apk ->
            ALIASES.getValue("universal").any { apk.name.contains(it, ignoreCase = true) }
        }
        if (universal != null) return universal to true
        // 3) 任意支持的 ABI（不在前序命中说明标记混乱，尽力一搏）
        for (abi in supportedAbis) {
            val hit = candidates.firstOrNull { apk -> apk.name.contains(abi, ignoreCase = true) }
            if (hit != null) return hit to true
        }
        return null to false // 列表手选
    }
}
