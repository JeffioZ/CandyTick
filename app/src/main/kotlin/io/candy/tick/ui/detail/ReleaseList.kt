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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
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
import top.yukonga.miuix.kmp.basic.TextButton
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
        state.releasesFailed -> TextButton(
            text = stringResource(R.string.load_failed_retry),
            onClick = onLoadMore,
        )
        !state.releasesEnd && state.releases.isNotEmpty() -> LaunchedEffect(state.releases.size) { onLoadMore() }
        state.releases.isEmpty() -> Text(
            text = stringResource(R.string.detail_no_releases),
            style = MiuixTheme.textStyles.footnote1,
            color = scheme.onBackgroundVariant,
            modifier = Modifier.padding(vertical = 10.dp),
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

                when (val download = state.download) {
                    is DetailViewModel.DownloadState.Active -> {
                        if (download.assetName == selected?.name) {
                            // 进度补间：下载回调跳变 → 300ms 平滑
                            val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = download.progress,
                                animationSpec = androidx.compose.animation.core.tween(300),
                                label = "releaseDl",
                            )
                            LinearProgressIndicator(
                                progress = animatedProgress,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = stringResource(R.string.download_progress_percent, (animatedProgress * 100).toInt()),
                                style = MiuixTheme.textStyles.footnote2,
                                color = scheme.onBackgroundVariant,
                            )
                        }
                    }
                    is DetailViewModel.DownloadState.Failed -> {
                        if (download.assetName == selected?.name) {
                            TextButton(
                                text = stringResource(R.string.download_retry),
                                onClick = { onRetry(release, selected) },
                            )
                        }
                    }
                    else -> Unit
                }

                // 安装入口统一由持久记录 downloadedApk 渲染（会话态 Done 不再画按钮，避免双入口）
                val isDownloadedVersion = state.repo?.downloadedApk?.version == release.tagName
                if (isDownloadedVersion) {
                    // 已下载：安装 + 删除（归属此 Release，替代原头部行）
                    Row(
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
                } else if (selected != null &&
                    (state.download == null || state.download is DetailViewModel.DownloadState.Failed)
                ) {
                    PrimaryButton(
                        text = stringResource(R.string.download_apk),
                        onClick = { onDownload(release, selected) },
                        modifier = Modifier.fillMaxWidth(),
                    )
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
