package io.candy.tick.data.store

import androidx.annotation.StringRes
import io.candy.tick.R
import io.candy.tick.data.download.SignatureVerdict
import io.candy.tick.data.model.StoreAppUpdate
import io.candy.tick.data.model.StorePreferences
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.model.StoreSignatureRecord

private val apkChecksum = Regex("(?:[a-fA-F0-9]{32}|[a-fA-F0-9]{64})")

internal fun String.isIndexStore(): Boolean = this == StoreCatalog.ID_FDROID || this == StoreCatalog.ID_IZZYONDROID

internal fun StoreReleaseInfo.matchesArtifactChecksum(saved: String): Boolean {
    if (!apkChecksum.matches(checksum)) return true
    if (!apkChecksum.matches(saved)) return !storeId.isIndexStore()
    return checksum.equals(saved, ignoreCase = true)
}

data class StoreDescriptor(
    val id: String,
    @param:StringRes val nameRes: Int,
    val speed: StoreQuerySpeed,
    val implemented: Boolean = true,
    val hasMirrorSetting: Boolean = false,
)

/**
 * 商店注册表与候选版本排序策略。
 */
object StoreCatalog {

    const val ID_TENCENT = "tencent"
    const val ID_WANDOUJIA = "wandoujia"
    const val ID_XIAOMI = "xiaomi"
    const val ID_FDROID = "fdroid"
    const val ID_IZZYONDROID = "izzyondroid"
    const val ID_COOLAPK = "coolapk"
    const val ID_VIVO = "vivo"
    const val ID_OPPO = "oppo"
    const val ID_HUAWEI = "huawei"
    const val ID_HONOR = "honor"
    const val ID_SAMSUNG = "samsung"
    const val ID_TAPTAP = "taptap"
    const val ID_APPCHINA = "appchina"

    /** 固定顺序：主流厂商商店 → 综合与社区商店 → 开源商店（开关不重排，保持肌肉记忆）。 */
    val all: List<StoreDescriptor> = listOf(
        StoreDescriptor(ID_XIAOMI, R.string.store_name_xiaomi, StoreQuerySpeed.FAST_BATCH, implemented = true),
        StoreDescriptor(ID_HUAWEI, R.string.store_name_huawei, StoreQuerySpeed.FAST_BATCH, implemented = true),
        StoreDescriptor(ID_HONOR, R.string.store_name_honor, StoreQuerySpeed.FAST_BATCH, implemented = true),
        StoreDescriptor(ID_OPPO, R.string.store_name_oppo, StoreQuerySpeed.FAST_BATCH, implemented = true),
        StoreDescriptor(ID_VIVO, R.string.store_name_vivo, StoreQuerySpeed.FAST_BATCH, implemented = true),
        StoreDescriptor(ID_SAMSUNG, R.string.store_name_samsung, StoreQuerySpeed.FAST_BATCH, implemented = true),
        StoreDescriptor(ID_COOLAPK, R.string.store_name_coolapk, StoreQuerySpeed.SLOW_PER_APP, implemented = true),
        StoreDescriptor(ID_TENCENT, R.string.store_name_tencent, StoreQuerySpeed.SLOW_PER_APP, implemented = true),
        StoreDescriptor(ID_WANDOUJIA, R.string.store_name_wandoujia, StoreQuerySpeed.FAST_BATCH, implemented = true),
        StoreDescriptor(ID_TAPTAP, R.string.store_name_taptap, StoreQuerySpeed.FAST_BATCH, implemented = true),
        StoreDescriptor(ID_APPCHINA, R.string.store_name_appchina, StoreQuerySpeed.SLOW_PER_APP, implemented = true),
        StoreDescriptor(ID_FDROID, R.string.store_name_fdroid, StoreQuerySpeed.LOCAL_INDEX, implemented = true, hasMirrorSetting = true),
        StoreDescriptor(ID_IZZYONDROID, R.string.store_name_izzyondroid, StoreQuerySpeed.LOCAL_INDEX, implemented = true),
    )

    fun descriptorOf(id: String): StoreDescriptor? = all.firstOrNull { it.id == id }

    fun eligibleCandidates(
        update: StoreAppUpdate,
        prefs: StorePreferences,
        local: LocalInstalledApp? = null,
    ): List<StoreReleaseInfo> {
        if (update.packageName in prefs.excludedPackages) return emptyList()
        val isSystem = local?.isSystemApp ?: update.isSystemApp
        if (isSystem == true && !prefs.includeSystemApps) return emptyList()
        val version = local?.versionCode ?: update.installedVersionCode
        val oem = StoreSystemAppPolicy.deviceOemStoreId()
        val installed = local ?: LocalInstalledApp(
            update.packageName, update.displayName, version, update.installedVersionName, isSystem == true,
        )
        return update.candidates.filter { candidate ->
            candidate.storeId in prefs.enabledStores && candidate.versionCode > version &&
                StoreSystemAppPolicy.isStoreEligibleForApp(installed, candidate.storeId, oem)
        }
    }

    fun actionableCandidate(
        update: StoreAppUpdate,
        prefs: StorePreferences,
        signatures: Map<String, StoreSignatureRecord>,
        local: LocalInstalledApp? = null,
        includeSkipped: Boolean = false,
    ): StoreReleaseInfo? {
        val candidates = eligibleCandidates(update, prefs, local)
        val best = selectBestCandidate(candidates, prefs.enabledStores, prefs.pinnedSources[update.packageName]) { id ->
            val candidate = candidates.first { it.storeId == id }
            StoreUpdateChecker.validSignatureVerdict(
                signatures["${update.packageName}|$id"], local?.signatureSha256 ?: update.installedCertSha256, candidate,
            )
        }
        return best?.takeIf { includeSkipped || it.versionCode > (prefs.skippedVersions[update.packageName] ?: 0L) }
    }

    /**
     * 按默认推荐权重整理已开启渠道：本机品牌商店排第 1，其余按目录固定顺序（主流厂商 → 综合社区 → 开源商店）。
     * 用户未手动拖拽过优先级时调用，避免「后勾的本机商店排在前面第三方商店之后」。
     */
    fun sortByDefaultPriority(
        storeIds: Collection<String>,
        deviceOemStoreId: String? = StoreSystemAppPolicy.deviceOemStoreId(),
    ): List<String> {
        val catalogIndex = all.withIndex().associate { (i, desc) -> desc.id to i }
        return storeIds.distinct().sortedWith(
            compareBy<String> { if (deviceOemStoreId != null && it == deviceOemStoreId) 0 else 1 }
                .thenBy { catalogIndex[it] ?: Int.MAX_VALUE },
        )
    }

    /**
     * 从同一应用的多个商店候选中选出默认推荐项：
     * 1. 排除已知签名不兼容的商店；
     * 2. 若用户固定了来源且该来源有可用候选，优先；
     * 3. 否则按「版本号更高 > 签名已知一致 > 商店优先级靠前」排序。
     */
    fun selectBestCandidate(
        candidates: List<StoreReleaseInfo>,
        enabledOrder: List<String>,
        pinnedStoreId: String? = null,
        verdictOf: (storeId: String) -> SignatureVerdict = { SignatureVerdict.UNKNOWN },
    ): StoreReleaseInfo? {
        val eligible = candidates.filter { verdictOf(it.storeId) != SignatureVerdict.DIFFERENT }
        if (eligible.isEmpty()) return null
        if (!pinnedStoreId.isNullOrBlank()) {
            eligible.firstOrNull { it.storeId == pinnedStoreId }?.let { return it }
        }
        val orderIndex = enabledOrder.withIndex().associate { (i, id) -> id to i }
        return eligible.sortedWith(
            compareByDescending<StoreReleaseInfo> { it.versionCode }
                .thenByDescending { verdictOf(it.storeId) == SignatureVerdict.MATCH }
                .thenBy { orderIndex[it.storeId] ?: Int.MAX_VALUE },
        ).firstOrNull()
    }
}
