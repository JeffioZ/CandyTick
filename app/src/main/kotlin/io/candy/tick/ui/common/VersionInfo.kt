package io.candy.tick.ui.common

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    }?.takeIf { it.isNotBlank() }?.let { VersionCompare.installedName(it, repo) }

/**
 * 首页展开卡显示紧凑的版本摘要，详情页将发布版本和本机状态分别展示。
 */
@Composable
fun VersionStatusLine(repo: TrackedRepo, remoteTag: String, installEpoch: Int = 0, modifier: Modifier = Modifier) {
    // installEpoch 仅供重组（参数变化破除 skip）：本机版本读 PackageManager 非响应式，
    // 安装完成后需要外部信号强制重算（ON_RESUME 递增）
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    // PM 查询记住：本组件在首页展开卡与详情头卡随同步/下载进度高频重组，
    // 裸读 getPackageInfo = 每帧 Binder IPC（installEpoch 是 ON_RESUME 装完重算信号）
    val local = remember(context, repo, installEpoch) { localVersionName(context, repo) }
    val cmp = if (local != null) VersionCompare.compare(remoteTag, local) else null
    val highlight = cmp != null && cmp > 0
    val text = when {
        local == null -> stringResource(R.string.version_latest_only, remoteTag)
        cmp == null -> stringResource(R.string.version_incomparable_full, remoteTag, local)
        highlight -> stringResource(R.string.version_update_available, remoteTag)
        cmp < 0 -> stringResource(R.string.version_installed_ahead, local, remoteTag)
        else -> stringResource(R.string.version_line, remoteTag)
    }
    Text(
        text = text,
        modifier = modifier,
        style = MiuixTheme.textStyles.footnote1,
        color = if (highlight) scheme.primaryVariant else scheme.onBackgroundVariant,
    )
}

/** Both values share a column even when a long version wraps or the system font size changes. */
@Composable
internal fun LinkedVersionSummary(remoteTag: String, local: String?, comparison: Int?) {
    val releaseLabel = stringResource(R.string.version_release_label)
    val installedLabel = stringResource(R.string.version_installed_label)
    val labelStyle = MiuixTheme.textStyles.footnote1
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelWidth = remember(measurer, density, releaseLabel, installedLabel, labelStyle) {
        with(density) {
            maxOf(
                measurer.measure(releaseLabel, labelStyle).size.width,
                measurer.measure(installedLabel, labelStyle).size.width,
            ).toDp()
        }
    }
    val duration = if (rememberMotionEnabled()) Motion.PHASE else 0
    Column(Modifier.fillMaxWidth().animateContentSize(tween(duration))) {
        VersionRow(releaseLabel, remoteTag, labelWidth)
        AnimatedContent(
            targetState = local to comparison,
            transitionSpec = {
                val enter = fadeIn(tween(duration)) + slideInVertically(tween(duration)) { it / 8 }
                (enter togetherWith fadeOut(tween(duration / 2))).using(null)
            },
            label = "linkedVersion",
        ) { (version, order) ->
            if (version != null) {
                val status = when {
                    order == null -> null
                    order > 0 -> stringResource(R.string.card_updatable)
                    order < 0 -> stringResource(R.string.version_local_newer)
                    else -> stringResource(R.string.version_local_current)
                }
                VersionRow(
                    installedLabel, version, labelWidth, modifier = Modifier.padding(top = 4.dp),
                    status = status, updatable = order != null && order > 0,
                )
            }
        }
    }
}

@Composable
private fun VersionRow(
    label: String, version: String, labelWidth: Dp, modifier: Modifier = Modifier,
    status: String? = null, updatable: Boolean = false,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label, modifier = Modifier.width(labelWidth).alignByBaseline(), softWrap = false,
            style = MiuixTheme.textStyles.footnote1, color = scheme.onBackgroundVariant,
        )
        FlowRow(
            modifier = Modifier.weight(1f).alignByBaseline(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = version,
                modifier = Modifier.alignByBaseline(),
                style = MonoTokenStyle,
                color = scheme.onBackground,
            )
            if (status != null) {
                Text(
                    text = status,
                    modifier = Modifier.alignByBaseline(),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (updatable) scheme.primaryVariant else scheme.onBackgroundVariant,
                )
            }
        }
    }
}
