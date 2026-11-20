package io.candy.tick.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.candy.tick.R
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.ui.theme.MonoTokenStyle
import io.candy.tick.util.VersionCompare
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 本机已装版本（仅关联应用后可查） */
fun localVersionName(context: Context, repo: TrackedRepo): String? =
    repo.linkedPackage?.let { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()
    }

/** 远端 tag 是否严格更新于本机（宁可不比：不可比按无更新处理） */
fun updateAvailable(context: Context, repo: TrackedRepo, remoteTag: String): Boolean {
    val local = localVersionName(context, repo) ?: return false
    return VersionCompare.compare(remoteTag, local)?.let { it > 0 } == true
}

/**
 * 版本状态行：最新 release tag vs 本机已装版本（关联应用时）。
 * 四态文案：有更新 / 一致 / 无法比较 / 仅最新（未关联本机应用）。
 * 首页卡片展开与详情页头部共用，保证两处口径一致。
 */
@Composable
fun VersionStatusLine(repo: TrackedRepo, remoteTag: String, installEpoch: Int = 0) {
    // installEpoch 仅供重组（参数变化破除 skip）：本机版本读 PackageManager 非响应式，
    // 安装完成后需要外部信号强制重算（ON_RESUME 递增）
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val local = localVersionName(context, repo)
    val cmp = if (local != null) VersionCompare.compare(remoteTag, local) else null
    val highlight = cmp != null && cmp > 0
    val text = when {
        local == null -> stringResource(R.string.version_latest_only, remoteTag)
        cmp == null -> stringResource(R.string.version_incomparable_full, remoteTag, local)
        highlight -> stringResource(R.string.version_update_available, remoteTag)
        else -> stringResource(R.string.version_line, remoteTag)
    }
    Text(
        text = text,
        style = MonoTokenStyle,
        color = if (highlight) scheme.primaryVariant else scheme.onBackgroundVariant,
    )
}
