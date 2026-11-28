package io.candy.tick.data.store

import io.candy.tick.data.download.ExpectedHash
import io.candy.tick.data.model.StoreReleaseInfo

/** 本机已装应用快照（实时从 PackageManager 读取，不持久化落盘）。 */
data class LocalInstalledApp(
    val packageName: String,
    val displayName: String,
    val versionCode: Long,
    val versionName: String,
    val isSystemApp: Boolean,
    val targetSdkVersion: Int = 35,
    val signatureMd5: String = "",
    val signatureSha256: String = "",
    val oppoLegacySignatureMd5: String = "",
    val splits: String = "0",
    val installedByXiaomiMarket: Boolean = false,
)

/** 分包 APK（Split APK）中的单个附加安装包。 */
data class StoreDownloadPart(
    val name: String,
    val url: String,
    val sizeBytes: Long = 0L,
    val expectedHash: ExpectedHash? = null,
)

/** 点击下载时现取的下载地址与校验参数（不缓存 URL，防止签名链接过期）。 */
data class StoreDownloadSpec(
    val url: String,
    val sizeBytes: Long,
    val expectedHash: ExpectedHash? = null,
    val headers: Map<String, String> = emptyMap(),
    val extraParts: List<StoreDownloadPart> = emptyList(),
)

enum class StoreQuerySpeed {
    FAST_BATCH,
    SLOW_PER_APP,
    LOCAL_INDEX,
}

/**
 * 单个应用商店适配器契约：
 * - [checkUpdates] 返回有更高 versionCode 的候选更新；
 * - [resolveDownload] 在用户点击下载时现取真实安装包地址。
 */
interface StoreSource {
    val id: String
    val speed: StoreQuerySpeed

    suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo>

    suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec
}
