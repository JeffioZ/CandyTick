package io.candy.tick.ui.detail

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.File
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.model.ApkAsset
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.RelTimeText
import io.candy.tick.ui.theme.MonoTokenStyle
import io.candy.tick.util.ApkPicker
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Release 历史 + APK 资产（多 APK 自动推荐 + 手选；下载 → 安装） */
fun LazyListScope.releaseList(
    state: DetailViewModel.UiState,
    onLoadMore: () -> Unit,
    onSelectAsset: (String, ApkAsset) -> Unit,
    onDownload: (ReleaseRecord, ApkAsset) -> Unit,
    onRetry: (ReleaseRecord, ApkAsset) -> Unit,
    onInstallDownloaded: () -> Unit,
    onDeleteDownloaded: () -> Unit,
) {
    items(count = state.releases.size, key = { state.releases[it].tagName + it }) { index ->
        val release = state.releases[index]
        ReleaseCard(
            release = release,
            state = state,
            onSelectAsset = onSelectAsset,
            onDownload = onDownload,
            onRetry = onRetry,
            onInstallDownloaded = onInstallDownloaded,
            onDeleteDownloaded = onDeleteDownloaded,
        )
    }
    item(key = "releases-footer") {
        ReleaseFooter(state = state, onLoadMore = onLoadMore)
    }
}

@Composable
private fun ReleaseFooter(state: DetailViewModel.UiState, onLoadMore: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    when {
        state.releasesLoading -> androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            io.candy.tick.ui.common.LoadingDots(dotSize = 5.dp)
        }
        state.releasesFailed -> io.candy.tick.ui.common.StatusPage(
            icon = top.yukonga.miuix.kmp.icon.MiuixIcons.Close,
            isError = true,
            title = stringResource(R.string.load_failed),
            subtitle = stringResource(R.string.status_error_subtitle),
            actionLabel = stringResource(R.string.retry),
            onAction = onLoadMore,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
        )
        !state.releasesEnd && state.releases.isNotEmpty() -> LaunchedEffect(state.releases.size) { onLoadMore() }
        state.releases.isEmpty() -> io.candy.tick.ui.common.StatusPage(
            icon = top.yukonga.miuix.kmp.icon.MiuixIcons.File,
            title = stringResource(R.string.detail_no_releases),
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
        )
    }
}

@Composable
private fun ReleaseCard(
    release: ReleaseRecord,
    state: DetailViewModel.UiState,
    onSelectAsset: (String, ApkAsset) -> Unit,
    onDownload: (ReleaseRecord, ApkAsset) -> Unit,
    onRetry: (ReleaseRecord, ApkAsset) -> Unit,
    onInstallDownloaded: () -> Unit,
    onDeleteDownloaded: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val candidates = remember(release) { ApkPicker.apkCandidates(release.apkAssets) }
    val (autoPick, isAuto) = remember(release) {
        ApkPicker.pick(release.apkAssets, Build.SUPPORTED_ABIS.toList())
    }
    val selected = state.selectedAssets[release.tagName] ?: autoPick
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = release.tagName,
                    style = MonoTokenStyle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = scheme.primaryVariant,
                )
                Spacer(Modifier.width(10.dp))
                RelTimeText(
                    date = release.publishedAt,
                    color = scheme.onBackgroundVariant,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
            release.name?.takeIf { it.isNotBlank() && it != release.tagName }?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onBackground,
                )
            }

            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.detail_no_apk),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                )
            } else {
                candidates.forEach { asset ->
                    AssetRow(
                        asset = asset,
                        // 仅多候选时才标「推荐」（唯一候选没有选择问题，不啰嗦）
                        recommended = isAuto && candidates.size > 1 && selected?.name == asset.name,
                        selected = selected?.name == asset.name,
                        onSelect = { onSelectAsset(release.tagName, asset) },
                    )
                }

                // 动作区四相（download/progress/failed/downloaded）：AnimatedContent 柔切换，
                // 尺寸渐变消除删除后按钮宽度突变；进度/失败只归属对应 release+asset（双维匹配防串台），
                // 头部入口的下载不在卡片重复显示进度
                val dl = state.download
                val mine = dl != null && dl.releaseKey == release.tagName &&
                    dl.assetName == selected?.name
                val actionPhase = when {
                    state.repo?.downloadedApk?.version == release.tagName -> "downloaded"
                    mine && dl is DetailViewModel.DownloadState.Active && !dl.fromHeader -> "progress"
                    mine && dl is DetailViewModel.DownloadState.Failed -> "failed"
                    else -> "download"
                }
                // 容器尺寸渐变：消除 downloaded→download 行宽突变（AnimatedContent 交叉淡出不动画尺寸）
                androidx.compose.animation.AnimatedContent(
                    targetState = actionPhase,
                    modifier = Modifier.animateContentSize(),
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                            androidx.compose.animation.slideInVertically(
                                animationSpec = androidx.compose.animation.core.tween(180),
                            ) { it / 6 }) togetherWith
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))
                    },
                    label = "releaseAction",
                ) { phase ->
                    when (phase) {
                        "downloaded" -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                PrimaryButton(
                                    text = stringResource(R.string.install_apk),
                                    onClick = onInstallDownloaded,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            top.yukonga.miuix.kmp.basic.IconButton(onClick = onDeleteDownloaded) {
                                top.yukonga.miuix.kmp.basic.Icon(
                                    imageVector = top.yukonga.miuix.kmp.icon.MiuixIcons.Delete,
                                    contentDescription = stringResource(R.string.detail_delete_download),
                                    tint = scheme.onErrorContainer,
                                )
                            }
                        }
                        "progress" -> Column {
                            val active = dl as? DetailViewModel.DownloadState.Active
                            val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = active?.progress ?: 0f,
                                animationSpec = androidx.compose.animation.core.tween(300),
                                label = "releaseDl",
                            )
                            LinearProgressIndicator(
                                progress = animatedProgress,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = stringResource(
                                    R.string.download_progress_percent,
                                    (animatedProgress * 100).toInt(),
                                ),
                                style = MiuixTheme.textStyles.footnote2,
                                color = scheme.onBackgroundVariant,
                            )
                        }
                        "failed" -> Column {
                            val failed = dl as? DetailViewModel.DownloadState.Failed
                            Text(
                                text = stringResource(
                                    R.string.download_failed_reason,
                                    failed?.message ?: stringResource(R.string.err_network),
                                ),
                                style = MiuixTheme.textStyles.footnote2,
                                color = scheme.onErrorContainer,
                            )
                            PrimaryButton(
                                text = stringResource(R.string.retry_download),
                                onClick = { selected?.let { onRetry(release, it) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        else -> PrimaryButton(
                            text = stringResource(R.string.download_apk),
                            onClick = { selected?.let { onDownload(release, it) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetRow(
    asset: ApkAsset,
    recommended: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                top.yukonga.miuix.kmp.basic.Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = "selected",
                    tint = scheme.primaryVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = asset.name,
            style = MonoTokenStyle,
            color = if (selected) scheme.onBackground else scheme.onBackgroundVariant,
            modifier = Modifier.weight(1f),
        )
        if (recommended) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(scheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.asset_recommended),
                    color = scheme.onPrimaryContainer,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
        }
    }
}
