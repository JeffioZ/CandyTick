package io.candy.tick.data.store

import android.content.res.Resources
import android.os.Build
import android.util.Log
import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.errorCode
import io.candy.tick.data.download.ExpectedHash
import io.candy.tick.data.model.StoreReleaseInfo
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/** 本机已装应用快照（实时从 PackageManager 读取，不持久化落盘）。 */
data class LocalInstalledApp(
    val packageName: String,
    val displayName: String,
    val versionCode: Long,
    val versionName: String,
    val isSystemApp: Boolean,
    val targetSdkVersion: Int = 35,
    val signatureMd5: String = "",
    /** Multi-signer packages use their sorted SHA-256 fingerprints joined by '+'. */
    val signatureSha256: String = "",
    val oppoLegacySignatureMd5: String = "",
    val splits: String = "0",
    val installedByXiaomiMarket: Boolean = false,
)

/** Real device compatibility fields shared by vendor-specific request formats. */
internal object StoreDevice {
    val abis: List<String> get() = Build.SUPPORTED_ABIS.orEmpty().filter(String::isNotBlank)
    val primaryAbi: String get() = abis.firstOrNull().orEmpty()
    val densityDpi: Int get() = runCatching { Resources.getSystem().displayMetrics.densityDpi }.getOrDefault(0)
}

/** 分包 APK（Split APK）中的单个附加安装包。 */
data class StoreDownloadPart(
    val name: String,
    val url: String,
    val sizeBytes: Long = 0L,
    val expectedHash: ExpectedHash? = null,
    val exactSizeBytes: Long? = sizeBytes.takeIf { it > 0L },
)

/** 点击下载时现取的下载地址与校验参数（不缓存 URL，防止签名链接过期）。 */
data class StoreDownloadSpec(
    val url: String,
    val sizeBytes: Long,
    val expectedHash: ExpectedHash? = null,
    val headers: Map<String, String> = emptyMap(),
    val extraParts: List<StoreDownloadPart> = emptyList(),
    val exactSizeBytes: Long? = sizeBytes.takeIf { it > 0L },
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
    val batchSize: Int get() = if (speed == StoreQuerySpeed.SLOW_PER_APP) 1 else Int.MAX_VALUE

    suspend fun checkUpdates(apps: List<LocalInstalledApp>): Map<String, StoreReleaseInfo>

    suspend fun checkBatch(apps: List<LocalInstalledApp>): StoreQueryResult =
        StoreQueryResult(checkUpdates(apps))

    suspend fun resolveDownload(
        app: LocalInstalledApp,
        release: StoreReleaseInfo,
    ): StoreDownloadSpec
}

data class StoreQueryResult(
    val updates: Map<String, StoreReleaseInfo>,
    val unconfirmed: Set<String> = emptySet(),
    val nextPackage: String? = null,
    val error: String? = null,
    val checkedEarlier: Set<String> = emptySet(),
    val confirmedVersions: Map<String, Long> = emptyMap(),
) {
    fun checkedNow(pkg: String): Boolean = pkg !in unconfirmed && pkg !in checkedEarlier
}

internal class StoreResponseException(val code: Long) : Exception("Store response code $code")

internal fun Exception.storeCheckErrorCode(): String = when (this) {
    is SourceException -> error.errorCode
    is StoreResponseException -> "API $code"
    is SerializationException -> "E-DATA"
    else -> javaClass.simpleName
}

/** Keep completed batches when a later batch fails or the source exhausts its time budget. */
internal suspend fun StoreSource.queryWithCoverage(
    apps: List<LocalInstalledApp>,
    timeoutMs: Long,
    startPackage: String? = null,
    confirmedVersions: Map<String, Long> = emptyMap(),
): StoreQueryResult {
    if (apps.isEmpty()) return StoreQueryResult(emptyMap(), emptySet())
    val checkedEarlier = apps.filter { confirmedVersions[it.packageName] == it.versionCode }.mapTo(mutableSetOf()) { it.packageName }
    val remaining = if (checkedEarlier.isEmpty()) apps else apps.filterNot { it.packageName in checkedEarlier }
    val start = if (startPackage == null) -1 else remaining.indexOfFirst { it.packageName == startPackage }
    val ordered = if (start > 0) remaining.drop(start) + remaining.take(start) else remaining
    val batches = ordered.chunked(batchSize.coerceIn(1, apps.size))
    val next = AtomicInteger()
    val updates = mutableMapOf<String, StoreReleaseInfo>()
    val unconfirmed = remaining.mapTo(mutableSetOf()) { it.packageName }
    // Finished failures can be retried after the in-flight or not-yet-started tail.
    val pending = unconfirmed.toMutableSet()
    var firstError: String? = null
    val workers = if (speed == StoreQuerySpeed.SLOW_PER_APP) minOf(4, batches.size) else 1

    val finished = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            repeat(workers) {
                launch {
                    while (isActive) {
                        val batch = batches.getOrNull(next.getAndIncrement()) ?: break
                        val result = try {
                            checkBatch(batch)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            StoreQueryResult(emptyMap(), batch.mapTo(mutableSetOf()) { it.packageName }, error = e.storeCheckErrorCode())
                        }
                        synchronized(updates) {
                            updates.putAll(result.updates)
                            batch.forEach {
                                pending.remove(it.packageName)
                                if (it.packageName !in result.unconfirmed) unconfirmed.remove(it.packageName)
                            }
                            if (firstError == null) firstError = result.error
                        }
                    }
                }
            }
        }
        true
    } == true
    val resume = if (finished) null else batches.getOrNull(next.get())?.firstOrNull()?.packageName
        ?: ordered.firstOrNull { it.packageName in pending }?.packageName
    val reason = firstError ?: if (!finished && unconfirmed.isNotEmpty()) SourceError.Timeout.errorCode else null
    if (reason != null) Log.w("StoreUpdateChecker", "$id check incomplete: $reason (${unconfirmed.size} unconfirmed)")
    val confirmed = if (resume != null || checkedEarlier.isNotEmpty()) {
        apps.filterNot { it.packageName in unconfirmed }.associate { it.packageName to it.versionCode }
    } else emptyMap()
    return StoreQueryResult(updates, unconfirmed, resume, reason, checkedEarlier, confirmed)
}
