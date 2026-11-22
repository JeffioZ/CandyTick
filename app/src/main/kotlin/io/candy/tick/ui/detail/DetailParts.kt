package io.candy.tick.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.InlineErrorRow
import io.candy.tick.ui.common.OwnerRepoLine
import io.candy.tick.ui.common.OptionRow
import io.candy.tick.ui.common.InputLimits
import io.candy.tick.ui.common.SheetScrollToTopTitle
import io.candy.tick.ui.common.TextFieldActionButton
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.DownloadProgress
import io.candy.tick.ui.common.AppLinkRow
import io.candy.tick.ui.common.LinkedRepoAvatar
import io.candy.tick.ui.common.rememberAppIconResult
import io.candy.tick.ui.common.scrollToTop
import io.candy.tick.ui.common.SectionDivider
import io.candy.tick.ui.common.StarCount
import io.candy.tick.ui.common.StatusBadge
import io.candy.tick.ui.common.LinkedVersionSummary
import io.candy.tick.ui.common.relTimeString
import io.candy.tick.ui.common.sourceErrorText
import io.candy.tick.util.CountFormat
import io.candy.tick.util.VersionCompare
import io.candy.tick.data.model.statusAt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import io.candy.tick.ui.common.Motion

// ── 详情页子组件（自 DetailScreen 拆出：头部卡 / 分支弹层 / 移除确认）──

@Composable
internal fun downloadFailureText(failure: RepoDownloadState.Failed?): String {
    val reason = failure?.error?.let { sourceErrorText(it) } ?: failure?.message
    return if (reason.isNullOrBlank()) stringResource(R.string.settings_download_failed)
    else stringResource(R.string.download_failed_reason, reason)
}

/**
 * 分支选择底部弹层：限高 60% 屏 + 平滑增高；分支多时给搜索框。
 * 首行固定「默认分支」，已选分支被远端删除时保留一行选中态可切回。
 */
@Composable
internal fun BranchPickerSheet(
    show: Boolean,
    state: DetailViewModel.UiState,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    onLoadBranches: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val maxSheetHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.6f
    var query by remember { mutableStateOf("") }
    // sheet 关闭即清搜索词：消灭幽灵草稿（与 Token sheet 同一治理原则）
    androidx.compose.runtime.LaunchedEffect(show) {
        if (!show && query.isNotEmpty()) query = ""
    }
    // 打开即聚焦：搜索框仅在分支 >15 个时组合，且分支请求未回时固定延迟会打空
    // （FocusRequester 未挂载 → runCatching 静默失败）；改为列表就绪后请求
    val branchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val branches = state.branches.orEmpty()
    val searchable = branches.size > 15
    androidx.compose.runtime.LaunchedEffect(show, searchable) {
        if (show && searchable) {
            kotlinx.coroutines.delay(100)
            runCatching { branchFocus.requestFocus() }
        }
    }
    // GitHub 式分支切换器：纯分支列表，当前查看的分支打勾——
    // branch=null（跟随默认）时勾打在上游默认分支上；无「Default branch」幻影行
    val defaultBranch = state.snapshot?.defaultBranch
    val currentViewed = state.repo?.branch ?: defaultBranch
    val keyword = query.trim()
    val filtered = if (keyword.isEmpty()) branches else branches.filter {
        it.name.contains(keyword, ignoreCase = true)
    }
    val listReady = state.branches != null && !state.branchesLoading && !state.branchesFailed
    val missingSelection = state.repo?.branch?.takeIf { name ->
        listReady && branches.none { it.name == name } && name.contains(keyword, ignoreCase = true)
    }
    // show 直通（勿用 if 包裹组件）：关闭时组件仍在组合中，库的退场动画才能播放
    val listState = rememberLazyListState()
    top.yukonga.miuix.kmp.window.WindowBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        onDismissFinished = { listState.requestScrollToItem(0) },
        startAction = {
            SheetScrollToTopTitle(
                text = stringResource(R.string.branch_picker_title),
                canScroll = listState.canScrollBackward,
                scrollToTop = listState::scrollToTop,
            )
        },
    ) {
        fun filterBranches(value: String) {
            if (value == query || value.length > InputLimits.SEARCH) return
            query = value
            listState.requestScrollToItem(0)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (searchable) Modifier.height(maxSheetHeight) else Modifier.heightIn(max = maxSheetHeight))
                // 220 表层档显式 spec：默认 spring 时长不受六档体系控制（自家原则：
                // 「默认 spring 不可控且在体系外」）；缩放=0 时 tween 瞬时完成自动退化
                .animateContentSize(androidx.compose.animation.core.tween(Motion.PANEL)),
        ) {
            if (searchable) {
                top.yukonga.miuix.kmp.basic.TextField(
                    value = query,
                    onValueChange = ::filterBranches,
                    label = stringResource(R.string.branch_search_hint),
                    singleLine = true,
                    // 回车收键盘：实时过滤已出结果，Done 不需要别的动作
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { keyboard?.hide() },
                    ),
                    trailingIcon = if (query.isNotEmpty()) {
                        { TextFieldActionButton(onClick = { filterBranches("") }) }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .focusRequester(branchFocus),
                )
            }
            // 搜索无结果：轻量居中提示（与分支/应用弹层同一处理）
            if (listReady && filtered.isEmpty() && missingSelection == null) {
                Text(
                    text = stringResource(if (keyword.isEmpty()) R.string.branch_empty else R.string.branch_no_match),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
                items(filtered, key = { it.name }) { b ->
                    // 点默认分支 = 存 null（保留「跟随默认」语义，上游改名自动跟）
                    OptionRow(b.name, b.name == currentViewed, summary = b.tipDate?.let { relTimeString(it) }) {
                        onSelect(if (b.name == defaultBranch) null else b.name)
                        onDismiss()
                    }
                }
                // 已 pin 的分支被远端删除（不在列表里）：保留一行选中态，可切回。
                // loading 中列表为空 ≠ 「已删」——当前分支会闪现成删除行，等加载完再判
                missingSelection?.let { name ->
                    item(key = "deleted-$name") {
                        OptionRow(name, true) {
                            onSelect(name)
                            onDismiss()
                        }
                    }
                }
            }
            when {
                state.branchesLoading -> androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    io.candy.tick.ui.common.LoadingDots(dotSize = 5.dp)
                }
                state.branchesFailed -> io.candy.tick.ui.common.StatusPage(
                    icon = ImageVector.vectorResource(R.drawable.ic_close),
                    isError = true,
                    compact = true,
                    title = stringResource(R.string.load_failed),
                    subtitle = stringResource(R.string.status_error_subtitle),
                    actionLabel = stringResource(R.string.retry),
                    onAction = onLoadBranches,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                )
            }
        }
    }
}

/** 移除订阅确认（破坏性操作：红色主按钮） */
@Composable
internal fun RemoveRepoDialog(
    show: Boolean,
    repoName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    // 底部浮层容器：与选项浮层同款动效
    top.yukonga.miuix.kmp.window.WindowBottomSheet(
        show = show,
        title = stringResource(R.string.remove_confirm_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.remove_confirm_body, repoName),
                style = MiuixTheme.textStyles.body2,
                color = scheme.onBackgroundVariant,
            )
            // 与导入弹层同构：主上副下、全宽竖排（dialog 式右对齐小按钮不属于 sheet 体系）
            top.yukonga.miuix.kmp.basic.Button(
                onClick = onConfirm,
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                    color = scheme.error,
                    contentColor = scheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.action_remove),
                    style = MiuixTheme.textStyles.button,
                )
            }
            io.candy.tick.ui.common.SecondaryButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private data class LinkRow(val packageName: String, val label: String, val suggested: Boolean)
private data class HeaderApp(val icon: ImageBitmap?, val version: String?)

@Composable
internal fun HeaderCard(
    state: DetailViewModel.UiState,
    localVersion: String?,
    onChangeLinked: () -> Unit,
    onUnlink: () -> Unit,
    onLinkSuggestion: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onCancelDownload: () -> Unit = {},
) {
    val repo = state.repo ?: return
    val snapshot = state.snapshot
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val linked = remember(context, repo.linkedPackage, repo.autoNote, repo.noteName, state.installEpoch) {
        repo.linkedPackage?.let {
            val label = repo.noteName.takeIf { repo.autoNote } ?: io.candy.tick.util.labelOf(context, it) ?: it
            LinkRow(it, label, false)
        }
    }
    val link = linked ?: state.linkSuggestion?.let { LinkRow(it.first, it.second, true) }
    val iconResult = rememberAppIconResult(link?.packageName)
    // Keep the previous avatar and local version together while another app's icon is being read.
    val headerApp by produceState(HeaderApp(null, localVersion), linked?.packageName, localVersion, iconResult) {
        if (linked == null || iconResult != null) {
            value = HeaderApp(if (linked != null) iconResult?.bitmap else null, localVersion)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        // 双层间距体系：跨组 8dp（Spacer）、组内 6dp——头卡多组累加，组距收紧一档防松散
        Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinkedRepoAvatar(repo.owner, headerApp.icon)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = repo.noteName ?: repo.repo,
                            style = MiuixTheme.textStyles.title4,
                            color = scheme.onBackground,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // star 挪名称旁（GitHub 档案页同位）：唯一保留的人气信号；
                        // 名称 weight+ellipsis——截断时 star 恒可见（截断优先级）。
                        // 无快照（刚添加未同步）隐藏：此时显示「★0」是假数据
                        if (snapshot != null) {
                            Spacer(Modifier.width(8.dp))
                            StarCount(count = CountFormat.format(snapshot.stars))
                        }
                    }
                    OwnerRepoLine(owner = repo.owner, repo = repo.repo)
                }
                StatusBadge(status = snapshot?.statusAt() ?: io.candy.tick.data.model.RepoStatus.UNKNOWN)
            }

            // ── 信息组（描述；无描述时跨组 8dp 不叠加）——统计行与推送时间已分别
            // 迁往名称旁（star）与首页折叠卡，此处只承载描述 ──
            snapshot?.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onBackgroundVariant,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // 统计行已删（Fork/Issue 是档案页惯性搬运，健康度由活跃徽章承担；
            // star 挪名称旁，push 时间由首页折叠卡表达）

            // ── 版本分区（有 tag 时显示分隔线 + 版本行 + 操作按钮）──
            snapshot?.currentReleaseTag?.let { tag ->
                Spacer(Modifier.height(8.dp))
                SectionDivider()
                Spacer(Modifier.height(6.dp))
                val displayedVersion = headerApp.version
                val comparison = remember(tag, displayedVersion) {
                    displayedVersion?.let { VersionCompare.compare(tag, it) }
                }
                LinkedVersionSummary(tag, displayedVersion, comparison)
                val hasUpdate = comparison != null && comparison > 0
                val downloadedMatches = repo.downloadedApk?.version == tag
                val updatePhase = when {
                    state.download is RepoDownloadState.Active &&
                        state.download.fromHeader -> "progress"
                    // 失败相：此前头部无 failed 分支——失败回落"下载"按钮复原 + 错误条只在
                    // 版本 tab 的卡里（停留提交 tab 时不可见）→ 感知"点了没反应"
                    hasUpdate && state.download is RepoDownloadState.Failed &&
                        state.download.fromHeader && state.download.releaseKey == tag -> "failed"
                    hasUpdate && downloadedMatches -> "install"
                    hasUpdate -> "download"
                    else -> null // idle 不渲染（零占位）
                }
                androidx.compose.animation.AnimatedContent(
                    targetState = updatePhase to state.download,
                    modifier = Modifier.fillMaxWidth(),
                    contentKey = { it.first },
                    transitionSpec = {
                        ((androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.PHASE)) +
                            androidx.compose.animation.slideInVertically(
                                animationSpec = androidx.compose.animation.core.tween(Motion.PHASE),
                            ) { it / 6 }) togetherWith
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.PHASE)))
                            // 尺寸动画显式 180 同拍：AnimatedContent 默认 SizeTransform 是
                            // spring 暗桩，与外挂尺寸动画双层打架=切相卡顿根源（单源化）
                            .using(
                                androidx.compose.animation.SizeTransform(clip = true) { _, _ ->
                                    androidx.compose.animation.core.tween(Motion.PHASE)
                                },
                            )
                    },
                    label = "updatePhase",
                ) { (phase, transfer) ->
                    if (phase == null) return@AnimatedContent
                    val actionCurrent = phase == updatePhase && displayedVersion == localVersion
                    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        when (phase) {
                            "failed" -> InlineErrorRow(
                                message = downloadFailureText(transfer as? RepoDownloadState.Failed),
                                onRetry = onDownloadUpdate,
                                retryEnabled = actionCurrent,
                            )
                            "progress" -> DownloadProgress(
                                progress = (transfer as RepoDownloadState.Active).progress,
                                onCancel = onCancelDownload,
                            )
                            "install" -> PrimaryButton(
                                text = stringResource(R.string.install_apk),
                                onClick = onInstallUpdate,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = actionCurrent,
                            )
                            "download" -> PrimaryButton(
                                text = stringResource(R.string.download_update_action),
                                onClick = onDownloadUpdate,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = actionCurrent && state.download !is RepoDownloadState.Active,
                            )
                        }
                    }
                }
            }

            // ── 关联分区（始终存在，固定卡底）；前置 8dp 与上方版本区呼吸对称 ──
            Spacer(Modifier.height(8.dp))
            SectionDivider()
            LinkedAppRow(link, iconResult?.bitmap, onChangeLinked, onLinkSuggestion, onUnlink)
        }
    }
}

@Composable
private fun LinkedAppRow(
    link: LinkRow?,
    appIcon: ImageBitmap?,
    onChangeLinked: () -> Unit,
    onLinkSuggestion: () -> Unit,
    onUnlink: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val suggested = link?.suggested == true
    val linked = link != null && !suggested
    AppLinkRow(
        appIcon = appIcon,
        onClick = when {
            linked -> null
            suggested -> onLinkSuggestion
            else -> onChangeLinked
        },
        modifier = Modifier.padding(vertical = 8.dp),
        suggested = suggested,
        onChangeApp = if (link == null) null else onChangeLinked,
        onUnlink = if (linked) onUnlink else null,
    ) {
        Text(
            text = when {
                link == null -> stringResource(R.string.detail_link_app)
                suggested -> stringResource(R.string.detail_auto_link_hint, link.label)
                else -> stringResource(R.string.detail_linked_app, link.label)
            },
            style = MiuixTheme.textStyles.footnote1,
            color = if (suggested) scheme.onPrimaryContainer else scheme.onBackgroundVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
