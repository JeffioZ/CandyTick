@file:UseSerializers(InstantSerializer::class)

package io.candy.tick.data.model

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
enum class FdroidMirror { OFFICIAL, TUNA }

@Serializable
enum class MeteredDownloadPolicy(val thresholdBytes: Long) {
    ALWAYS_ALLOW(Long.MAX_VALUE),
    OVER_50MB(50L * 1024 * 1024),
    OVER_200MB(200L * 1024 * 1024),
    ALWAYS_ASK(0L);

    fun requiresConfirmation(bytes: Long?): Boolean = when (this) {
        ALWAYS_ALLOW -> false
        ALWAYS_ASK -> true
        OVER_50MB, OVER_200MB -> bytes == null || bytes <= 0L || bytes > thresholdBytes
    }
}

/** 单个商店返回的候选更新版本。 */
@Serializable
data class StoreReleaseInfo(
    val storeId: String,
    val versionCode: Long,
    val versionName: String,
    val sizeBytes: Long = 0L,
    val changelog: String = "",
    val appId: Long = 0L,
    val downloadUrl: String = "",
    val checksum: String = "",
    val signerSha256s: List<String> = emptyList(),
    val exactSizeBytes: Long? = null,
)

/** 单个本机应用的聚合更新记录（只持久化「确实有更新」的应用，避免 JSON 膨胀）。 */
@Serializable
data class StoreAppUpdate(
    val packageName: String,
    val displayName: String,
    val installedVersionCode: Long,
    val installedVersionName: String,
    val candidates: List<StoreReleaseInfo> = emptyList(),
    val checkedAt: Instant = Instant.EPOCH,
    val unread: Boolean = false,
    val isSystemApp: Boolean? = null,
    val installedCertSha256: String = "",
)

/** 按 packageName|storeId 记录签名核对结论；若本机应用重装导致证书变化，自动失效。 */
@Serializable
data class StoreSignatureRecord(
    val verdict: String, // "MATCH" / "DIFFERENT"
    val localCertSha256: String = "",
    val versionCode: Long? = null,
    val checksum: String = "",
)

/** 商店检查状态；旧版本自动停用的记录继续保留，超时后下次从未覆盖的包接着查。 */
@Serializable
data class StoreHealth(
    val consecutiveFailures: Int = 0,
    val autoDisabled: Boolean = false,
    val lastError: String? = null,
    val nextPackage: String? = null,
    val confirmedVersions: Map<String, Long> = emptyMap(),
)

/** 应用商店监控设置（精简开关 + 单应用跳过/固定来源）。 */
@Serializable
data class StorePreferences(
    val enabledStores: List<String> = emptyList(),
    val priorityCustomized: Boolean = false,
    val includeSystemApps: Boolean = false,
    val meteredDownloadPolicy: MeteredDownloadPolicy = MeteredDownloadPolicy.OVER_200MB,
    val hideHomeCardWhenUpToDate: Boolean = true,
    val fdroidMirror: FdroidMirror = FdroidMirror.TUNA,
    val excludedPackages: Set<String> = emptySet(),
    val skippedVersions: Map<String, Long> = emptyMap(),
    val pinnedSources: Map<String, String> = emptyMap(),
) {
    val trackedPackages: Set<String> get() = excludedPackages + skippedVersions.keys + pinnedSources.keys
}

internal fun AppState.withStorePreferenceFor(
    packageName: String,
    observed: Boolean = false,
    transform: (StorePreferences) -> StorePreferences,
): AppState {
    val prefs = transform(settings.storePrefs)
    val previous = storeState.observedPreferencePackages
    val seen = when {
        packageName !in prefs.trackedPackages -> previous - packageName
        observed -> previous + packageName
        else -> previous
    }
    if (prefs == settings.storePrefs && seen == previous) return this
    return copy(settings = settings.copy(storePrefs = prefs), storeState = storeState.copy(observedPreferencePackages = seen))
}

/** 应用商店监控持久状态（合并进单 JSON AppState）。 */
@Serializable
data class StoreState(
    val updates: Map<String, StoreAppUpdate> = emptyMap(),
    val signatures: Map<String, StoreSignatureRecord> = emptyMap(),
    val downloaded: Map<String, DownloadedApk> = emptyMap(),
    val health: Map<String, StoreHealth> = emptyMap(),
    val lastCheckedAt: Instant? = null,
    val lastAttemptAt: Instant? = null,
    val lastCheckComplete: Boolean? = null,
    val lastFailedStoreIds: List<String> = emptyList(),
    val observedPreferencePackages: Set<String> = emptySet(),
) {
    fun signatureKey(packageName: String, storeId: String): String = "$packageName|$storeId"
}
