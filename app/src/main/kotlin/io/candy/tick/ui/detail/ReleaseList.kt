package io.candy.tick.ui.detail

import io.candy.tick.data.RepoDownloadState
import io.candy.tick.data.model.DownloadedApk
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import io.candy.tick.ui.common.downloadActionTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.InlineErrorRow
import io.candy.tick.data.model.ApkAsset
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.StatusPage
import io.candy.tick.ui.common.DownloadProgress
import io.candy.tick.ui.common.relTimeString
import io.candy.tick.ui.theme.MonoTokenStyle
import io.candy.tick.util.ApkPicker
import io.candy.tick.util.VersionCompare
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.candy.tick.ui.common.Motion

/** Release 历史 + APK 资产（多 APK 自动推荐 + 手选；下载 → 安装） */
fun LazyListScope.releaseList(
    state: DetailViewModel.UiState,
    releases: List<ReleaseRecord>,
    localTarget: DownloadedApk?,
    notificationTag: String?,
    contentModifier: Modifier,
    localVersion: String?,
    htmlBase: String,
    expandedNotes: Set<String>,
    onToggleNotes: (String) -> Unit,
    showPagingIndicator: Boolean,
    animateRefreshChanges: Boolean,
    onLoadMore: () -> Unit,
    onSelectAsset: (String, ApkAsset) -> Unit,
    onRead: (String) -> Unit,
    onDownload: (ReleaseRecord, ApkAsset) -> Unit,
    onRetry: (ReleaseRecord, ApkAsset) -> Unit,
    onInstallDownloaded: (DownloadedApk) -> Unit,
    onDeleteDownloaded: (DownloadedApk) -> Unit,
    onCancelDownload: () -> Unit,
) {
    if (localTarget != null) {
        item(key = "releases:${localTarget.version}", contentType = "release") {
            Card(modifier = contentModifier.fillMaxWidth()) {
                SavedPackageAction(localTarget) {
                    onRead(it.version)
                    onInstallDownloaded(it)
                }
            }
        }
    }
    items(
        count = releases.size,
        key = { "releases:${releases[it].tagName}" },
        contentType = { "release" },
    ) { index ->
        val release = releases[index]
        ReleaseCard(
            release = release,
            modifier = refreshItemMotion(animateRefreshChanges).then(contentModifier),
            state = state,
            localVersion = localVersion,
            htmlBase = htmlBase,
            fromNotification = release.tagName == notificationTag,
            notesExpanded = release.tagName in expandedNotes,
            onToggleNotes = { onToggleNotes(release.tagName) },
            onRead = { onRead(release.tagName) },
            onSelectAsset = onSelectAsset,
            onDownload = onDownload,
            onRetry = onRetry,
            onInstallDownloaded = {
                onRead(release.tagName)
                onInstallDownloaded(it)
            },
            onDeleteDownloaded = onDeleteDownloaded,
            onCancelDownload = onCancelDownload,
        )
    }
    item(key = "releases-footer") {
        HistoryFooter(
            state = state,
            tab = DetailViewModel.DetailTab.RELEASES,
            displayedCount = releases.size + if (localTarget == null) 0 else 1,
            showPagingIndicator = showPagingIndicator,
            modifier = contentModifier,
            onLoadMore = onLoadMore,
        )
    }
}

@Composable
private fun ReleaseCard(
    release: ReleaseRecord,
    modifier: Modifier,
    state: DetailViewModel.UiState,
    localVersion: String?,
    htmlBase: String,
    fromNotification: Boolean,
    notesExpanded: Boolean,
    onToggleNotes: () -> Unit,
    onRead: () -> Unit,
    onSelectAsset: (String, ApkAsset) -> Unit,
    onDownload: (ReleaseRecord, ApkAsset) -> Unit,
    onRetry: (ReleaseRecord, ApkAsset) -> Unit,
    onInstallDownloaded: (DownloadedApk) -> Unit,
    onDeleteDownloaded: (DownloadedApk) -> Unit,
    onCancelDownload: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val candidates = remember(release) { ApkPicker.apkCandidates(release.apkAssets) }
    val (autoPick, isAuto) = remember(release) {
        ApkPicker.pick(release.apkAssets, Build.SUPPORTED_ABIS.toList())
    }
    val saved = state.repo?.downloadedApk?.takeIf { it.version == release.tagName }
    val releaseTransfer = state.download?.takeIf { it.releaseKey == release.tagName }
    val preferredName = state.selectedAssets[release.tagName]?.name ?: releaseTransfer?.assetName ?: saved?.displayAssetName
    val selected = candidates.find { it.name == preferredName } ?: autoPick
    // 更新日志展开态：body 有内容才可展开（chevron 提示）；
    // 展开集合提升到页面级（滚出屏/跨页签保持，且标题可一键全收起）
    val hasNotes = release.body.isNotBlank()
    val cardInset = 6.dp
    val sectionGap = 4.dp
    Card(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = cardInset),
            verticalArrangement = Arrangement.spacedBy(sectionGap),
        ) {
            ReleaseHeader(
                release = release,
                hasPackages = candidates.isNotEmpty(),
                hasNotes = hasNotes,
                current = localVersion != null && VersionCompare.matchesTag(release.tagName, localVersion),
                expanded = notesExpanded,
                onToggleNotes = {
                    onRead()
                    onToggleNotes()
                },
            )
            if (fromNotification) {
                Text(
                    text = stringResource(R.string.detail_release_from_notification),
                    style = MiuixTheme.textStyles.footnote2,
                    color = scheme.primaryVariant,
                )
            }

            // 更新日志展开区（220 展开档与首页卡一致）：纯文本限高可滚 + GitHub 链接
            if (hasNotes) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = notesExpanded,
                    enter = androidx.compose.animation.expandVertically(
                        animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                    ) + androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                    ),
                    exit = androidx.compose.animation.shrinkVertically(
                        animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                    ) + androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                    ),
                ) {
                    // 图层隔离（graphicsLayer 空参 = 强制独立层）：展开动画逐帧改的是
                    // 裁剪/尺寸，文本等重内容缓存在自身层里，每帧只重绘裁剪区，
                    // 不拖整卡重录显示列表——长日志展开偶发掉帧的主缓解
                    Column(
                        modifier = Modifier.graphicsLayer(),
                    ) {
                        io.candy.tick.ui.common.ReleaseNotesBody(
                            markdown = release.body,
                            repoSlug = state.repo?.let { "${it.owner}/${it.repo}" },
                            repoUrl = htmlBase,
                            tag = release.tagName,
                            maxHeight = 260.dp,
                            spaceAfter = when (candidates.size) {
                                0 -> cardInset
                                1 -> sectionGap + AssetRowInset
                                else -> sectionGap
                            },
                        )
                    }
                }
            }
            if (saved != null && candidates.none { it.name == saved.displayAssetName }) {
                SavedPackageAction(saved, onInstallDownloaded)
            }
            if (candidates.isNotEmpty()) {
                val multiple = candidates.size > 1
                Column(if (multiple) Modifier.selectableGroup() else Modifier) {
                    candidates.forEach { asset ->
                        AssetRow(
                            asset = asset,
                            recommended = multiple && isAuto && autoPick?.name == asset.name,
                            selectable = multiple,
                            selected = selected?.name == asset.name,
                            onSelect = {
                                onRead()
                                onSelectAsset(release.tagName, asset)
                            },
                        )
                    }
                }

                // 动作区四相（download/progress/failed/downloaded）：AnimatedContent 柔切换，
                // 尺寸渐变消除删除后按钮宽度突变；进度/失败只归属对应 release+asset（双维匹配防串台），
                // 头部入口的下载不在卡片重复显示进度
                val mine = releaseTransfer != null && releaseTransfer.assetName == selected?.name
                val downloaded = saved?.takeIf { it.displayAssetName == selected?.name }
                val actionPhase = when {
                    releaseTransfer is RepoDownloadState.Active && !releaseTransfer.fromHeader -> "progress"
                    downloaded != null -> "downloaded"
                    mine && releaseTransfer is RepoDownloadState.Failed -> "failed"
                    else -> "download"
                }
                // 容器尺寸单源化：外挂 animateContentSize 与 AnimatedContent 内置默认 spring
                // SizeTransform 双层打架 = 切相卡顿根源（取消时高度变化发闷）；收敛为显式
                // SizeTransform 180 与 fade 同拍；clip=false 容许过渡期两相叠影，由 fade 收尾
                androidx.compose.animation.AnimatedContent(
                    targetState = actionPhase to releaseTransfer,
                    contentKey = { it.first },
                    transitionSpec = { downloadActionTransition() },
                    label = "releaseAction",
                ) { (phase, transfer) ->
                    val current = phase == actionPhase
                    when (phase) {
                        "downloaded" -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            // 16：安装钮→删除圆钮墨迹距 = 16+4(内缩) = 20，圆钮墨迹→卡缘 = 4+16(卡衬) = 20，
                            // 两侧等距（此前 8+4=12 vs 20 不对称，真机反馈）；按钮宽度由 weight 吸收 -8dp
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                PrimaryButton(
                                    text = stringResource(R.string.install_apk),
                                    onClick = { downloaded?.let(onInstallDownloaded) },
                                    enabled = current,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            // 48dp 圆形染色反馈：Miuix IconButton 无按压多态、44dp 实机偏难点
                            // （用户反馈「按两次才按到」→升 48 并加按压多态）；
                            // 红色保留——删除已下载安装包是破坏性动作（destructive-emphasis）
                            io.candy.tick.ui.common.PressIconButton(
                                onClick = { downloaded?.let(onDeleteDownloaded) },
                                enabled = current,
                                contentDescription = stringResource(R.string.detail_delete_download),
                            ) {
                                top.yukonga.miuix.kmp.basic.Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_delete),
                                    contentDescription = null,
                                    tint = scheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        "progress" -> {
                            val active = transfer as RepoDownloadState.Active
                            DownloadProgress(
                                progress = active.progress,
                                sizeBytes = candidates.find { it.name == active.assetName }?.size ?: 0L,
                                onCancel = onCancelDownload,
                                cancelEnabled = current,
                            )
                        }
                        "failed" -> InlineErrorRow(
                            message = downloadFailureText(transfer as? RepoDownloadState.Failed),
                            onRetry = { selected?.let { onRetry(release, it) } },
                            retryEnabled = current,
                        )
                        else -> {
                            val sel = selected
                            if (sel != null) {
                                PrimaryButton(
                                    text = stringResource(R.string.download_apk),
                                    onClick = { onDownload(release, sel) },
                                    enabled = current && state.download !is RepoDownloadState.Active,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                // 无默认选中（多候选无法自动区分）：提示先选，不给死按钮
                                Text(
                                    text = stringResource(R.string.detail_select_asset_hint),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.onBackgroundVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedPackageAction(record: DownloadedApk, onInstall: (DownloadedApk) -> Unit) {
    StatusPage(
        title = stringResource(R.string.detail_saved_package, record.version),
        subtitle = record.displayAssetName,
        actionLabel = stringResource(R.string.install_apk),
        onAction = { onInstall(record) },
        compact = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

@Composable
private fun ReleaseHeader(
    release: ReleaseRecord,
    hasPackages: Boolean,
    hasNotes: Boolean,
    current: Boolean,
    expanded: Boolean,
    onToggleNotes: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val name = release.name?.trim()?.takeIf {
        it.isNotEmpty() && !VersionCompare.displayVersion(it).equals(VersionCompare.displayVersion(release.tagName), ignoreCase = true)
    }
    val published = release.publishedAt?.let { relTimeString(it) }
    val noPackage = if (hasPackages) null else stringResource(R.string.detail_no_apk)
    val details = buildAnnotatedString {
        if (name != null) withStyle(MonoTokenStyle.toSpanStyle()) { append(release.tagName) }
        if (published != null) {
            if (length > 0) append(" · ")
            append(published)
        }
        if (noPackage != null) {
            if (length > 0) append(" · ")
            append(noPackage)
        }
    }
    val expandAction = if (hasNotes) {
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(
            role = Role.Button,
            onClickLabel = stringResource(if (expanded) R.string.collapse_details else R.string.expand_details),
            onClick = onToggleNotes,
        ).heightIn(min = 48.dp)
    } else Modifier
    Column(
        // 按压块内 8dp 对称内衬（与首页卡片展开行同款）：文字与 chevron 都不贴色块边缘
        Modifier.fillMaxWidth().then(expandAction).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = name ?: release.tagName,
            style = if (name == null) MonoTokenStyle.copy(fontWeight = FontWeight.Bold)
                else MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
            color = if (name == null) scheme.primaryVariant else scheme.onBackground,
        )
        if (details.isNotEmpty() || current || hasNotes) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (details.isNotEmpty()) {
                        Text(details, style = MiuixTheme.textStyles.footnote2, color = scheme.onBackgroundVariant)
                    }
                    if (current) {
                        Text(
                            text = stringResource(R.string.release_current),
                            style = MiuixTheme.textStyles.footnote2,
                            color = scheme.onBackgroundVariant,
                            modifier = Modifier.background(scheme.secondaryVariant, CircleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                if (hasNotes) {
                    Icon(
                        imageVector = ImageVector.vectorResource(
                            if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                        ),
                        contentDescription = null,
                        tint = scheme.onBackgroundVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private val AssetRowInset = 4.dp

@Composable
private fun AssetRow(
    asset: ApkAsset,
    recommended: Boolean,
    selectable: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val selection = if (selectable) {
        val (pressSrc, pressColor) = io.candy.tick.ui.common.rememberRowPressTint()
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(pressColor, RoundedCornerShape(10.dp))
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                interactionSource = pressSrc,
                indication = null,
                onClick = onSelect,
            )
            .heightIn(min = 48.dp)
    } else Modifier
    Row(
        modifier = Modifier.fillMaxWidth().then(selection).padding(vertical = AssetRowInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            // 单选框（Material 3 / HyperOS 单选惯例）：圆环恒定 + 圆心实心点淡入缩放——
            // 两态同族（都是圆），只动圆心差异部分。此前 20dp 裸勾叠在环上，笔画横穿
            // 圆环、形状重心跳（用户实测「略有点丑」）
            val radioTint by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) scheme.primaryVariant
                else scheme.onBackgroundVariant.copy(alpha = 0.45f),
                animationSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                label = "radioTint",
            )
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_radio_off),
                    contentDescription = null, // 纯视觉：选中态由行级语义播报
                    tint = radioTint,
                    modifier = Modifier.size(20.dp),
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = selected,
                    enter = androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(Motion.MICRO),
                    ) + androidx.compose.animation.scaleIn(
                        initialScale = 0.6f,
                        animationSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                    ),
                    exit = androidx.compose.animation.fadeOut(
                        androidx.compose.animation.core.tween(Motion.MICRO),
                    ) + androidx.compose.animation.scaleOut(
                        targetScale = 0.6f,
                        animationSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                    ),
                ) {
                    // 圆心实心点 8dp：环内径 ~15dp（r=10 stroke2 @20dp），占比约一半——
                    // 与 M3 radio 的点/环比例同档
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(scheme.primaryVariant),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        // 名称 = 行主体：恒主色（选中态由 radio 承担）；体积 = 次级元信息（footnote2 +
        // 次级色），8dp 间隙分隔——此前名称/体积同色无间隙，长名换行时糊成一片
        Text(
            text = asset.name,
            style = MonoTokenStyle,
            color = scheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        // 文件大小（有值时）：大文件「要不要下/等多久」的决策信息，此前只在下载中出现。
        // 名称前 8dp = 与长名（占满行宽）的最小间隙；名称后 8dp = 与「推荐」胶囊的间隙
        // （weight 槽的富余不可依赖，长名占满时两个间隙都得显式给）
        asset.size.takeIf { it > 0 }?.let { size ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = io.candy.tick.util.CountFormat.formatBytes(size),
                style = MiuixTheme.textStyles.footnote2,
                color = scheme.onBackgroundVariant,
            )
        }
        // 推荐胶囊：瞬时显隐——任何动画（宽度/淡出占位）都会引起文件名布局跳变（抖动感），
        // 视觉柔和不抖的职责交给 ✓ 的 Crossfade 与文字色渐变，标签一次到位
        if (recommended) {
            Spacer(Modifier.width(8.dp))
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
