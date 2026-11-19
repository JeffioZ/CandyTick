package io.candy.tick.ui.detail

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Release 历史 + APK 资产（多 APK 自动推荐 + 手选；下载 → 安装） */
fun LazyListScope.releaseList(
    state: DetailViewModel.UiState,
    localVersion: String?,
    htmlBase: String,
    onLoadMore: () -> Unit,
    onSelectAsset: (String, ApkAsset) -> Unit,
    onDownload: (ReleaseRecord, ApkAsset) -> Unit,
    onRetry: (ReleaseRecord, ApkAsset) -> Unit,
    onInstallDownloaded: () -> Unit,
    onDeleteDownloaded: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    items(count = state.releases.size, key = { state.releases[it].tagName + it }) { index ->
        val release = state.releases[index]
        // tab 切换/分页加载时卡片淡入（150 微反馈档，与首页/提交列表同语言）
        val itemMod = Modifier.fillMaxWidth().animateItem(
            fadeInSpec = androidx.compose.animation.core.tween(150),
            fadeOutSpec = androidx.compose.animation.core.tween(150),
        )
        ReleaseCard(
            modifier = itemMod,
            release = release,
            state = state,
            localVersion = localVersion,
            htmlBase = htmlBase,
            onSelectAsset = onSelectAsset,
            onDownload = onDownload,
            onRetry = onRetry,
            onInstallDownloaded = onInstallDownloaded,
            onDeleteDownloaded = onDeleteDownloaded,
            onCancelDownload = onCancelDownload,
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
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            io.candy.tick.ui.common.LoadingDots(dotSize = 5.dp)
        }
        state.releasesFailed -> io.candy.tick.ui.common.StatusPage(
            icon = ImageVector.vectorResource(R.drawable.ic_close),
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
            icon = ImageVector.vectorResource(R.drawable.ic_file),
            title = stringResource(R.string.detail_no_releases),
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
        )
        // 到底且有内容：终点提示（与 CommitFooter 对称，替代原先挂在 Screen 层的外置 if）
        else -> io.candy.tick.ui.common.FooterText(text = stringResource(R.string.list_end))
    }
}

@Composable
private fun ReleaseCard(
    modifier: Modifier = Modifier,
    release: ReleaseRecord,
    state: DetailViewModel.UiState,
    localVersion: String?,
    htmlBase: String,
    onSelectAsset: (String, ApkAsset) -> Unit,
    onDownload: (ReleaseRecord, ApkAsset) -> Unit,
    onRetry: (ReleaseRecord, ApkAsset) -> Unit,
    onInstallDownloaded: () -> Unit,
    onDeleteDownloaded: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val candidates = remember(release) { ApkPicker.apkCandidates(release.apkAssets) }
    val (autoPick, isAuto) = remember(release) {
        ApkPicker.pick(release.apkAssets, Build.SUPPORTED_ABIS.toList())
    }
    val selected = state.selectedAssets[release.tagName] ?: autoPick
    // 更新日志展开态：body 有内容才可展开（chevron 提示）
    val hasNotes = release.body.isNotBlank()
    // rememberSaveable：列表项滚出屏销毁后回来保持展开态（remember 会丢）
    var notesExpanded by androidx.compose.runtime.saveable.rememberSaveable(release.tagName) { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (hasNotes) {
                    Modifier
                        .fillMaxWidth()
                        // clip 在 clickable 前：按压色块圆角化（与首页触发行同款 10dp）
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { notesExpanded = !notesExpanded }
                } else {
                    Modifier.fillMaxWidth()
                },
            ) {
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
                Spacer(Modifier.weight(1f))
                // 「当前」徽章：tag 规范化后等于本机已装版本（去 v/V 前缀；
                // 花式 tag 不匹配则不显示——宁缺勿错）。中性色：与推荐的薄荷（建议）区分，这是事实
                if (localVersion != null && release.tagName.removePrefix("v").removePrefix("V") == localVersion) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(scheme.secondaryVariant)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.release_current),
                            color = scheme.onBackgroundVariant,
                            style = MiuixTheme.textStyles.footnote2,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                // 更新日志 chevron：有 body 时提示 tag 行可展开
                if (hasNotes) {
                    androidx.compose.animation.Crossfade(
                        targetState = notesExpanded,
                        animationSpec = androidx.compose.animation.core.tween(150),
                        label = "notesChevron",
                    ) { exp ->
                        Icon(
                            imageVector = if (exp) {
                                ImageVector.vectorResource(R.drawable.ic_chevron_up)
                            } else {
                                ImageVector.vectorResource(R.drawable.ic_chevron_down)
                            },
                            contentDescription = null,
                            tint = scheme.onBackgroundVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // 更新日志展开区（220 展开档与首页卡一致）：纯文本限高可滚 + GitHub 链接
            if (hasNotes) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = notesExpanded,
                    enter = androidx.compose.animation.expandVertically(
                        animationSpec = androidx.compose.animation.core.tween(220),
                    ) + androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(220),
                    ),
                    exit = androidx.compose.animation.shrinkVertically(
                        animationSpec = androidx.compose.animation.core.tween(220),
                    ) + androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(220),
                    ),
                ) {
                    Column {
                        val linkCtx = androidx.compose.ui.platform.LocalContext.current
                        // ClickableText + 自定义 URL span：经典稳定路径（LinkAnnotation 在
                        // Miuix Text 包装下闪退），命中 span 即开浏览器
                        val noteStyle = MiuixTheme.textStyles.body2.copy(color = scheme.onBackgroundVariant)
                        // remember：长 body 的正则解析+AnnotatedString 构建只跑一次
                        // （此前每次重组都重建，展开动画期间每帧重算——长日志卡顿主因）
                        // repoSlug 供 #123 issue 引用转可点链接
                        val repoSlug = state.repo?.let { "${it.owner}/${it.repo}" }
                        val notesText = remember(release.tagName, release.body, scheme.primaryVariant, repoSlug) {
                            io.candy.tick.util.ReleaseNotes.annotated(
                                md = release.body,
                                linkColor = scheme.primaryVariant,
                                repoSlug = repoSlug,
                            )
                        }
                        androidx.compose.foundation.text.ClickableText(
                            text = notesText,
                            style = noteStyle,
                            modifier = Modifier
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        ) { offset ->
                            notesText.getStringAnnotations("URL", offset, offset)
                                .firstOrNull()?.item?.let { url ->
                                    runCatching {
                                        linkCtx.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(url),
                                            ),
                                        )
                                    }
                                }
                        }
                        Spacer(Modifier.height(6.dp))
                        // 尾部跳转：GitHub 该 release 页——与 Token 帮助链接同款（图标+下划线+薄荷）
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_external_link),
                                contentDescription = null,
                                tint = scheme.primaryVariant,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.release_view_on_github),
                                style = MiuixTheme.textStyles.footnote1.copy(
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                ),
                                color = scheme.primaryVariant,
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        runCatching {
                                            ctx.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse("$htmlBase/releases/tag/${release.tagName}"),
                                                ),
                                            )
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            release.name?.takeIf { it.isNotBlank() && it != release.tagName }?.let {
                // 与提交消息 body1 同级：都是人写给人看的内容主体（此前 body2 与镜像卡差异）
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.body1,
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
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_delete),
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(
                                        R.string.download_progress_percent,
                                        (animatedProgress * 100).toInt(),
                                    ) + (
                                        // 大小参考（asset 带 size 时）：大文件不再「不知要等多久」
                                        selected?.takeIf { it.size > 0 }?.let { a ->
                                            val totalMb = a.size / (1024f * 1024f)
                                            " · %.1f/%.0f MB".format(totalMb * animatedProgress, totalMb)
                                        } ?: ""
                                        ),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = scheme.onBackgroundVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                // 取消：文字链而非 X 图标（「取消」语义直白；点击后回「下载」相即反馈）
                                Text(
                                    text = stringResource(R.string.cancel),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.primaryVariant,
                                    modifier = Modifier
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = onCancelDownload,
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                        "failed" -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                // 行内错误条（行内色块家族统一参数：圆角12/icon16/gap8/padding h12 v10）：
                                // 与 progress 相「状态 + 右侧行动链」对仗；错误与重试同条
                                // （skill: error-recovery，恢复路径紧邻错误；全宽大按钮改轻量链）
                                .clip(RoundedCornerShape(12.dp))
                                .background(scheme.errorContainer)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            val failed = dl as? DetailViewModel.DownloadState.Failed
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_info),
                                contentDescription = null,
                                tint = scheme.onErrorContainer,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(
                                    R.string.download_failed_reason,
                                    // 结构化错误本地化优先，原始 message 兜底（英文技术串也比空白强）
                                    failed?.let { f ->
                                        f.error?.let { io.candy.tick.ui.common.sourceErrorText(it) }
                                            ?: f.message
                                    } ?: stringResource(R.string.err_network),
                                ),
                                style = MiuixTheme.textStyles.footnote1,
                                color = scheme.onErrorContainer,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            // 重试链：下划线表可点（红底上保持条内单色）
                            Text(
                                text = stringResource(R.string.retry),
                                style = MiuixTheme.textStyles.footnote1.copy(
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                ),
                                color = scheme.onErrorContainer,
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { selected?.let { onRetry(release, it) } },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        else -> if (selected != null) {
                            PrimaryButton(
                                text = stringResource(R.string.download_apk),
                                onClick = { onDownload(release, selected!!) },
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
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 单选框（Windows 风格）：圆圈恒定 + 勾按需淡入淡出——两图形大部分重叠，
            // 整体交叉切换会让圆圈先消失再出现（闪烁感），只动差异部分（勾）才稳
            val radioTint by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) scheme.primaryVariant
                else scheme.onBackgroundVariant.copy(alpha = 0.45f),
                animationSpec = androidx.compose.animation.core.tween(150),
                label = "radioTint",
            )
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_radio_off),
                    contentDescription = if (selected) "selected" else null,
                    tint = radioTint,
                    modifier = Modifier.size(20.dp),
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = selected,
                    enter = androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(120),
                    ),
                    exit = androidx.compose.animation.fadeOut(
                        androidx.compose.animation.core.tween(120),
                    ),
                ) {
                    // 只叠勾（circle-check 的差异部分），圆圈底层恒定不动
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = scheme.primaryVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        val nameColor by androidx.compose.animation.animateColorAsState(
            targetValue = if (selected) scheme.onBackground else scheme.onBackgroundVariant,
            animationSpec = androidx.compose.animation.core.tween(150),
            label = "assetName",
        )
        Text(
            text = asset.name,
            style = MonoTokenStyle,
            color = nameColor,
            modifier = Modifier.weight(1f),
        )
        // 推荐胶囊：瞬时显隐——任何动画（宽度/淡出占位）都会引起文件名布局跳变（抖动感），
        // 视觉柔和不抖的职责交给 ✓ 的 Crossfade 与文字色渐变，标签一次到位
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
