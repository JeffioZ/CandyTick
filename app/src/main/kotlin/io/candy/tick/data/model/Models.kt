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
    // 用户解除过自动关联（冷启动不再重绑）：解除 = 明确「不要绑」，必须持久记忆——
    // 否则杀进程后 VM 重建、autoLinkTried 归零，高置信规则重新命中又绑回（miuix demo 实测）。
    // 手动关联/切换关联 = 重新表态，自动清除本标记
    val autoLinkDismissed: Boolean = false,
    val branch: String? = null, // 浏览分支（可空 = 仓库默认分支；仅影响提交视图）
    val pinnedAt: Instant? = null, // 置顶时间（可空 = 未置顶；置顶组永远排最前）
    val downloadedApk: DownloadedApk? = null, // 「下载保留」重装复用
)

@Serializable
data class DownloadedApk(
    val fileName: String,
    val version: String,
    val packageName: String? = null, // APK 包名（装后广播按此精确匹配清理；旧记录 null 兼容）
    val assetName: String? = null,
) {
    val displayAssetName: String get() = assetName ?: fileName
}

/** 本机发现的活动按发布标签 / 提交 SHA 独立确认。浏览较早的版本不会清除随后到达的更新。 */
@Serializable
data class RepoActivity(
    val unreadReleaseTags: Set<String> = emptySet(),
    val unreadCommitShas: Set<String> = emptySet(),
    val recentlyViewedReleases: List<String> = emptyList(),
    val recentlyViewedCommits: List<String> = emptyList(),
) {
    val hasUnread: Boolean get() = unreadReleaseTags.isNotEmpty() || unreadCommitShas.isNotEmpty()

    fun discoverReleases(tags: List<String>): RepoActivity {
        val newTags = tags.filter { it !in unreadReleaseTags && it !in recentlyViewedReleases }
        return if (newTags.isEmpty()) this else copy(unreadReleaseTags = unreadReleaseTags + newTags)
    }

    fun discoverCommits(shas: List<String>): RepoActivity {
        val newShas = shas.filter { it !in unreadCommitShas && it !in recentlyViewedCommits }
        return if (newShas.isEmpty()) this else copy(unreadCommitShas = unreadCommitShas + newShas)
    }

    fun markRead(
        releaseTags: Set<String>,
        commitShas: Set<String>,
        visibleReleaseHead: String?,
        visibleCommitHead: String?,
    ): RepoActivity {
        val readTags = (if (visibleReleaseHead == null) releaseTags else releaseTags + visibleReleaseHead)
            .intersect(unreadReleaseTags)
        val readShas = (if (visibleCommitHead == null) commitShas else commitShas + visibleCommitHead)
            .intersect(unreadCommitShas)
        // 首页/详情可能先显示新 head，稍后条件请求才更新快照。只记近期的 head 和
        // 已确认的未读事件；浏览过的普通历史条目不进持久状态。
        val recentTags = rememberViewed(recentlyViewedReleases, readTags, visibleReleaseHead)
        val recentShas = rememberViewed(recentlyViewedCommits, readShas, visibleCommitHead)
        if (readTags.isEmpty() && readShas.isEmpty() &&
            recentTags === recentlyViewedReleases && recentShas === recentlyViewedCommits
        ) return this
        return copy(
            unreadReleaseTags = unreadReleaseTags - readTags,
            unreadCommitShas = unreadCommitShas - readShas,
            recentlyViewedReleases = recentTags,
            recentlyViewedCommits = recentShas,
        )
    }

    private fun rememberViewed(previous: List<String>, read: Set<String>, head: String?): List<String> {
        if (read.isEmpty() && (head == null || head in previous)) return previous
        val acknowledged = if (head == null) read else read + head
        return (previous.filterNot { it in acknowledged } + acknowledged).takeLast(32)
    }
}

@Serializable
data class ApkPublication(val tag: String, val publishedAt: Instant?)

/** 仓库快照。发布时间来自 GitHub，不随查看、刷新或确认通知而改变。 */
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
    val latestReleaseTag: String? = null, // 后台已处理通知的发布游标，兼容旧快照。
    // 旧 releasesEtag 对应的快照不含发布时间；换键后下一次请求会自动补齐正文。
    val releasePageEtag: String? = null,
    // 浏览分支的 tip 指纹（「有新提交」的精确信号源）：GitHub pushed_at 统计任意分支
    // 的推送——dev 推进会让它前进而浏览分支毫无动静（InstallerX-Revived 实例），
    // 直接用它判定会误亮且详情页无从查证。null = 基线缺失（首次/切分支/导入），只记录不亮灯
    val lastCommitSha: String? = null,
    val commitsEtag: String? = null, // tip 条件请求（per_page=1；304 无需响应正文）
    // 浏览分支 tip 提交时间（committer date）：首页卡片时间与详情提交首行同源——
    // pushed_at 统计任意分支推送（dev 推进也前进），曾是「首页 x 分钟前 / 详情 2 小时前」
    // 各说各话的根因（miuix/ShizukuX 实测）。null = 基线缺失/无能力源，首页回退 pushedAt
    val lastCommitDate: Instant? = null,
    val defaultBranch: String? = null, // 上游当前默认分支名（跟随语义的显示锚点）
    val pendingTipSync: Boolean = false,
    val importedCommitBaseline: Boolean = false,
    val importedReleaseBaseline: Boolean = false,
    val releaseBaselineKnown: Boolean = false, // 成功查过空列表后，首次发布也算新版本。
    val activity: RepoActivity = RepoActivity(),
    val latestApk: ApkPublication? = null,
) {
    val currentReleaseTag: String? get() = latestApk?.tag ?: latestReleaseTag
}

/** 分支选择器条目：name + 分支 tip 提交时间（纯 per-branch 拉取，上限 ~20） */
data class BranchInfo(
    val name: String,
    val tipDate: Instant?, // 分支最后提交时间（committer date）；null = 查不到（冷分支/失败）
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
    val body: String = "", // 更新说明正文（Markdown 原文，展示层做轻量清理）
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
    val githubToken: String? = null,
    val notificationsEnabled: Boolean = true,
    val selfUpdateCheck: Boolean = true, // 自身更新：自动检查（默认开）
    val selfUpdateAutoDownload: Boolean = false, // 自身更新：自动下载（默认关，全网络——包仅 ~4MB）
    val autoInstallDownload: Boolean = true, // 下载后自动安装：仓库应用 + 自身更新共用（默认开，见设置页说明文案）
    val language: String = "", // 应用内语言（空 = 跟随系统；"zh" / "en"）
    val sortMode: String = "LAST_PUSH", // 首页排序（持久化，重启保留）
) {
    val canAutoDownloadUpdate: Boolean get() = selfUpdateCheck && selfUpdateAutoDownload
}

/** 持久化根：订阅 + 快照 + 设置，单 JSON 文件 */
@Serializable
data class AppState(
    val repos: List<TrackedRepo> = emptyList(),
    val snapshots: Map<Long, RepoSnapshot> = emptyMap(),
    val settings: Settings = Settings(),
    val lastImportAt: Instant? = null, // 旧文件兼容：读取时转为各快照的独立基线。
    val downloadedSelfUpdate: DownloadedApk? = null,
)

/** 维护状态按仓库最近一次推送距今天数及归档状态判定。 */
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
