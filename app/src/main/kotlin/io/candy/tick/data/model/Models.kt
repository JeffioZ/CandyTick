@file:UseSerializers(InstantSerializer::class)

package io.candy.tick.data.model

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** 主题三档（design.md 深浅模式：默认跟随系统，随设置持久化） */
@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 同步频率（默认「每次打开应用」；hours 仅用于周期模式排程） */
@Serializable
enum class SyncFrequency(val hours: Int) {
    ON_OPEN(0),
    EVERY_6_HOURS(6),
    EVERY_12_HOURS(12),
    DAILY(24),
}

/** 订阅条目（GitHub 数字 id 做主键，改名跟随后 id 不变） */
@Serializable
data class TrackedRepo(
    val id: Long,
    val owner: String,
    val repo: String,
    val provider: String = "github",
    val addedAt: Instant,
    val noteName: String? = null, // 备注名（可空；autoNote=true 时为关联应用名，随改名刷新）
    val autoNote: Boolean = false, // noteName 是否自动来自关联应用（自定义备注永不覆盖）
    val linkedPackage: String? = null, // 关联本机应用包名（可空）
    val branch: String? = null, // 浏览分支（可空 = 仓库默认分支；仅影响提交视图）
    val pinnedAt: Instant? = null, // 置顶时间（可空 = 未置顶；置顶组永远排最前）
    val downloadedApk: DownloadedApk? = null, // 「下载保留」重装复用
)

@Serializable
data class DownloadedApk(val fileName: String, val version: String)

/** 仓库快照（ETag 随快照存储，条件请求 304 不计配额；latestReleaseTag 供新版本通知与「可更新」排序） */
@Serializable
data class RepoSnapshot(
    val stars: Int,
    val forks: Int,
    val openIssues: Int,
    val pushedAt: Instant?,
    val archived: Boolean,
    val license: String?,
    val description: String?,
    val ownerAvatar: String?,
    val htmlUrl: String,
    val lastSyncAt: Instant,
    val etag: String?,
    val latestReleaseTag: String? = null,
)

@Serializable
data class CommitRecord(
    val sha: String,
    val message: String,
    val author: String?,
    val date: Instant?,
)

@Serializable
data class ReleaseRecord(
    val tagName: String,
    val name: String?,
    val publishedAt: Instant?,
    val apkAssets: List<ApkAsset> = emptyList(),
)

@Serializable
data class ApkAsset(
    val name: String,
    val url: String,
    val size: Long = 0,
)

@Serializable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val syncFrequency: SyncFrequency = SyncFrequency.ON_OPEN,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val githubToken: String? = null,
    val notificationsEnabled: Boolean = true,
    val selfUpdateCheck: Boolean = true, // 自身更新：自动检查（默认开）
    val selfUpdateAutoDownload: Boolean = false, // 自身更新：自动下载（默认关，仅 Wi-Fi）
    val autoInstallDownload: Boolean = true, // 仓库应用：下载完成自动弹出安装确认（默认开）
    val language: String = "", // 应用内语言（空 = 跟随系统；"zh" / "en"）
    val sortMode: String = "LAST_PUSH", // 首页排序（持久化，重启保留）
)

/** 持久化根：订阅 + 快照 + 设置，单 JSON 文件 */
@Serializable
data class AppState(
    val repos: List<TrackedRepo> = emptyList(),
    val snapshots: Map<Long, RepoSnapshot> = emptyMap(),
    val settings: Settings = Settings(),
    val lastImportAt: Instant? = null, // 导入基线：早于它的快照属备份数据，首次本地同步不标「有更新」
)

/** 生命体征（判定规则：默认分支最新提交距今天数 + archived） */
enum class RepoStatus { ACTIVE, MODERATE, LOW, ABANDONED, ARCHIVED, UNKNOWN }

fun RepoSnapshot.statusAt(now: Instant = Instant.now()): RepoStatus = when {
    archived -> RepoStatus.ARCHIVED
    pushedAt == null -> RepoStatus.UNKNOWN
    else -> java.time.Duration.between(pushedAt, now).toDays().let { days ->
        when {
            days <= 30 -> RepoStatus.ACTIVE
            days <= 180 -> RepoStatus.MODERATE
            days <= 365 -> RepoStatus.LOW
            else -> RepoStatus.ABANDONED
        }
    }
}

/** ISO-8601 文本 ↔ Instant（原生 java.time，不引库） */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant =
        parseInstant(decoder.decodeString()) ?: Instant.EPOCH

    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(value.toString())
}

/** 宽松解析：优先 Instant（Z 结尾），兜底带偏移的 OffsetDateTime */
fun parseInstant(source: String?): Instant? = source?.let {
    runCatching { Instant.parse(it) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
}
