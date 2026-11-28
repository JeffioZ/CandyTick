package io.candy.tick.data.store

import android.os.Build

/**
 * 系统应用与更新渠道的匹配策略：
 * 防止开启「检查系统应用」后，其他手机品牌的商店对通用系统包名（如 com.android.email）误报不兼容更新。
 */
internal object StoreSystemAppPolicy {

    val OEM_STORE_IDS: Set<String> = setOf(
        StoreCatalog.ID_XIAOMI,
        StoreCatalog.ID_OPPO,
        StoreCatalog.ID_VIVO,
        StoreCatalog.ID_HUAWEI,
        StoreCatalog.ID_HONOR,
        StoreCatalog.ID_SAMSUNG,
    )

    private val AOSP_SYSTEM_PREFIXES = listOf(
        "android.",
        "com.android.",
    )

    /**
     * 识别当前设备品牌对应的自家官方商店 ID；
     * 若检测不到品牌，或属于非上述 6 大厂商（如 Pixel、Sony、类原生等），返回 null。
     */
    fun deviceOemStoreId(
        brand: String = Build.BRAND.orEmpty(),
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
    ): String? {
        mapBrandToken(brand)?.let { return it }
        return mapBrandToken(manufacturer)
    }

    private fun mapBrandToken(raw: String): String? {
        val token = raw.trim().lowercase()
        if (token.isEmpty()) return null
        return when {
            token in setOf("xiaomi", "redmi", "poco", "blackshark") -> StoreCatalog.ID_XIAOMI
            token in setOf("oppo", "oneplus", "realme", "heytap", "oplus") -> StoreCatalog.ID_OPPO
            token in setOf("vivo", "iqoo") -> StoreCatalog.ID_VIVO
            token == "honor" -> StoreCatalog.ID_HONOR
            token == "huawei" -> StoreCatalog.ID_HUAWEI
            token == "samsung" -> StoreCatalog.ID_SAMSUNG
            else -> null
        }
    }

    /**
     * 判断某个本机已装应用是否允许由 [storeId] 查询或采纳更新（保持克制，不误伤普通可卸载应用）：
     * 1. AOSP 通用系统包名（android.* / com.android.*，如部分 ROM 已移至可卸载分区的 com.android.email）：
     *    无论 FLAG_SYSTEM 真假，一律仅允许本机品牌商店查询，拦截其他厂商及第三方商店；
     * 2. 普通非系统应用（含米家等厂商常规 App 与可卸载应用）：全部放行；
     * 3. 其余系统应用 + 厂商商店（小米/OPPO/vivo/华为/荣耀/三星）：
     *    仅当该商店是本机品牌的官方商店时放行；检测不到品牌或非 6 大厂商时全部拦截；
     * 4. 其余系统应用 + 第三方商店（应用宝/豌豆荚/酷安/TapTap/F-Droid 等）：放行。
     */
    fun isAospSystemPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return AOSP_SYSTEM_PREFIXES.any { pkg.startsWith(it) }
    }

    fun isStoreEligibleForApp(
        app: LocalInstalledApp,
        storeId: String,
        deviceOemStoreId: String? = deviceOemStoreId(),
    ): Boolean {
        if (isAospSystemPackage(app.packageName)) {
            return storeId in OEM_STORE_IDS && deviceOemStoreId != null && storeId == deviceOemStoreId
        }
        if (!app.isSystemApp) return true
        if (storeId in OEM_STORE_IDS) {
            return deviceOemStoreId != null && storeId == deviceOemStoreId
        }
        return true
    }
}
