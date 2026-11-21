package io.candy.tick.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.OwnerRepoLine
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.RepoIconBlock
import io.candy.tick.ui.common.SectionDivider
import io.candy.tick.ui.common.StarCount
import io.candy.tick.ui.common.StatusBadge
import io.candy.tick.ui.common.VersionStatusLine
import io.candy.tick.ui.common.relTimeString
import io.candy.tick.ui.common.toImageBitmap
import io.candy.tick.ui.common.updateAvailable
import io.candy.tick.util.CountFormat
import io.candy.tick.data.model.statusAt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector

// ── 详情页子组件（自 DetailScreen 拆出：头部卡 / 分支弹层 / 移除确认）──

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
    androidx.compose.runtime.LaunchedEffect(show, state.branches.size > 15) {
        if (show && state.branches.size > 15) {
            kotlinx.coroutines.delay(100)
            runCatching { branchFocus.requestFocus() }
        }
    }
    val current = state.repo?.branch
    val keyword = query.trim()
    val filtered = if (keyword.isEmpty()) state.branches else state.branches.filter {
        it.contains(keyword, ignoreCase = true)
    }
    // show 直通（勿用 if 包裹组件）：关闭时组件仍在组合中，库的退场动画才能播放
    top.yukonga.miuix.kmp.window.WindowBottomSheet(
        show = show,
        title = stringResource(R.string.branch_picker_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                // 220 表层档显式 spec：默认 spring 时长不受六档体系控制（自家原则：
                // 「默认 spring 不可控且在体系外」）；缩放=0 时 tween 瞬时完成自动退化
                .animateContentSize(androidx.compose.animation.core.tween(220)),
        ) {
            if (state.branches.size > 15) {
                top.yukonga.miuix.kmp.basic.TextField(
                    value = query,
                    onValueChange = { query = it },
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
                        {
                            io.candy.tick.ui.common.PressIconButton(
                                onClick = { query = "" },
                                contentDescription = stringResource(R.string.clear_input),
                                // 44dp 行内档 + 右让 4dp：染色圆四向 8dp 均衡
                                size = 44.dp,
                                modifier = Modifier.padding(end = 4.dp),
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_close),
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onBackgroundVariant,
                                )
                            }
                        }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .focusRequester(branchFocus),
                )
            }
            BranchRow(
                label = stringResource(R.string.branch_default),
                selected = current == null,
                onClick = {
                    onSelect(null)
                    onDismiss()
                },
            )
            // 搜索无结果：轻量居中提示（与分支/应用弹层同一处理）
            if (keyword.isNotEmpty() && filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.branch_no_match),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(filtered, key = { it }) { name ->
                    BranchRow(name, name == current) {
                        onSelect(name)
                        onDismiss()
                    }
                }
                // 已选分支被远端删除（不在列表里）：保留一行选中态，可切回默认
                current?.takeIf { it !in state.branches && (keyword.isEmpty() || it.contains(keyword, true)) }?.let { name ->
                    item(key = "deleted-$name") {
                        BranchRow(name, true) {
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

/** 分支选择行：BasicComponent + Check 选中态（与设置页 OptionRow 同款） */
@Composable
internal fun BranchRow(label: String, selected: Boolean, onClick: () -> Unit) {
    top.yukonga.miuix.kmp.basic.BasicComponent(
        // selected 语义与 OptionRow 同源：✓ 图标纯视觉，读屏需播报选中态
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected },
        title = label,
        endActions = {
            if (selected) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primaryVariant,
                    modifier = Modifier.size(20.dp), // intrinsic 26dp，统一选中标记 20dp
                )
            }
        },
        onClick = onClick,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp,
        ),
    )
}

@Composable
internal fun HeaderCard(
    state: DetailViewModel.UiState,
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
    Card(modifier = Modifier.fillMaxWidth()) {
        // 双层间距体系：跨组 8dp（Spacer）、组内 6dp——头卡多组累加，组距收紧一档防松散
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RepoIconBlock(repo = repo, size = 56.dp, corner = 14.dp)
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

            // ── 信息组（描述 + 统计+推送时间，组内 6dp；无描述时跨组 8dp 不叠加）──
            Spacer(Modifier.height(8.dp))
            snapshot?.description?.takeIf { it.isNotBlank() }?.let {
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
            snapshot?.latestReleaseTag?.let { tag ->
                Spacer(Modifier.height(8.dp))
                SectionDivider()
                Spacer(Modifier.height(6.dp))
                VersionStatusLine(repo = repo, remoteTag = tag, installEpoch = state.installEpoch)

                // 更新入口（有新版时头部直接行动）：下载 → 进度 → 安装
                // PM 查询必须 remember：HeaderCard 随下载进度 tick 高频重组，
                // 裸读 getPackageInfo = 每帧 Binder IPC（key 含 installEpoch，装完返回仍会重算）
                val hasUpdate = remember(repo, tag, state.installEpoch) {
                    io.candy.tick.ui.common.updateAvailable(context, repo, tag)
                }
                val downloadedMatches = repo.downloadedApk?.version == tag
                val updatePhase = when {
                    state.download is RepoDownloadState.Active &&
                        state.download.fromHeader -> "progress"
                    // 失败相：此前头部无 failed 分支——失败回落"下载"按钮复原 + 错误条只在
                    // 版本 tab 的卡里（停留提交 tab 时不可见）→ 感知"点了没反应"
                    // Failed 无 fromHeader 字段：releaseKey==最新 tag 即视为头部入口的失败
                    state.download is RepoDownloadState.Failed &&
                        state.download.releaseKey == tag -> "failed"
                    hasUpdate && downloadedMatches -> "install"
                    hasUpdate -> "download"
                    else -> null // idle 不渲染（零占位）
                }
                if (updatePhase != null) {
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.animation.AnimatedContent(
                        targetState = updatePhase,
                        transitionSpec = {
                            ((androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                                androidx.compose.animation.slideInVertically(
                                    animationSpec = androidx.compose.animation.core.tween(180),
                                ) { it / 6 }) togetherWith
                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)))
                                // 尺寸动画显式 180 同拍：AnimatedContent 默认 SizeTransform 是
                                // spring 暗桩，与外挂尺寸动画双层打架=切相卡顿根源（单源化）
                                .using(
                                    androidx.compose.animation.SizeTransform(clip = false) { _, _ ->
                                        androidx.compose.animation.core.tween(180)
                                    },
                                )
                        },
                        label = "updatePhase",
                    ) { phase ->
                        when (phase) {
                            "failed" -> androidx.compose.foundation.layout.Row(
                                // 与版本卡 failed 相同款行内错误条：errorContainer 底 +
                                // 具体原因 + 重试链（skill: error-recovery，出路紧邻错误）
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(scheme.errorContainer)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                top.yukonga.miuix.kmp.basic.Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_info),
                                    contentDescription = null,
                                    tint = scheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.download_failed_reason,
                                        (state.download as? RepoDownloadState.Failed)?.let { f ->
                                            f.error?.let { io.candy.tick.ui.common.sourceErrorText(it) } ?: f.message
                                        } ?: stringResource(R.string.err_network),
                                    ),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(8.dp))
                                // 重试链按压多态：8dp 圆角染色（可点信号，与全 App 反馈语言同源）
                                val (retrySrc, retryTint) = io.candy.tick.ui.common.rememberRowPressTint()
                                Text(
                                    text = stringResource(R.string.retry),
                                    style = MiuixTheme.textStyles.footnote1.copy(
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    ),
                                    color = scheme.onErrorContainer,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(retryTint, RoundedCornerShape(8.dp))
                                        .clickable(
                                            interactionSource = retrySrc,
                                            indication = null,
                                            onClick = onDownloadUpdate,
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            "progress" -> Column {
                                val active = state.download as? RepoDownloadState.Active
                                val p by androidx.compose.animation.core.animateFloatAsState(
                                    targetValue = active?.progress ?: 0f,
                                    animationSpec = androidx.compose.animation.core.tween(300),
                                    label = "headerDl",
                                )
                                top.yukonga.miuix.kmp.basic.LinearProgressIndicator(
                                    progress = p,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.download_progress_percent, (p * 100).toInt()),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = scheme.onBackgroundVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // 取消（与 ReleaseCard 进度同款文字链；按压染色多态）
                                    val (cancelSrc, cancelTint) = io.candy.tick.ui.common.rememberRowPressTint()
                                    Text(
                                        text = stringResource(R.string.cancel),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = scheme.primaryVariant,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(cancelTint, RoundedCornerShape(8.dp))
                                            .clickable(
                                                interactionSource = cancelSrc,
                                                indication = null,
                                                onClick = onCancelDownload,
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            "install" -> PrimaryButton(
                                text = stringResource(R.string.install_apk),
                                onClick = onInstallUpdate,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            "download" -> PrimaryButton(
                                text = stringResource(R.string.download_update_action),
                                onClick = onDownloadUpdate,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // ── 关联分区（始终存在，固定卡底）；前置 8dp 与上方版本区呼吸对称 ──
            Spacer(Modifier.height(8.dp))
            SectionDivider()
            // 关联区紧凑：4 + 32dp 行高——与卡底间距收窄（此前 8+36 视觉上下皆空 18）
            Spacer(Modifier.height(4.dp))
            // 关联本机应用（可修改 / 解除）
            val linkedLabel = repo.linkedPackage?.let {
                io.candy.tick.util.labelOf(context, it) ?: it
            }
            // 关联区三态柔切换（180 转场档）：关联/解除/建议出现不再硬跳
            androidx.compose.animation.AnimatedContent(
                targetState = when {
                    linkedLabel != null -> "linked"
                    state.linkSuggestion != null -> "suggest"
                    else -> "none"
                },
                transitionSpec = {
                    ((androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                        androidx.compose.animation.slideInVertically(
                            animationSpec = androidx.compose.animation.core.tween(180),
                        ) { it / 8 }) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)))
                        .using(
                            androidx.compose.animation.SizeTransform(clip = false) { _, _ ->
                                androidx.compose.animation.core.tween(180)
                            },
                        )
                },
                label = "linkPhase",
            ) { phase ->
                Column {
            if (phase == "linked" && linkedLabel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val appBitmap = io.candy.tick.ui.common.rememberAppIcon(repo.linkedPackage)
                    appBitmap?.let {
                        io.candy.tick.ui.common.AppIconImage(bitmap = it, size = 22.dp, corner = 6.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = stringResource(R.string.detail_linked_app, linkedLabel),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onBackgroundVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // 动作图标对：44dp 行内紧凑档（Miuix IconButton 无按压多态，
                    // 换 PressIconButton 圆形染色反馈；图标 14dp 与 footnote 协调）
                    io.candy.tick.ui.common.PressIconButton(
                        onClick = onChangeLinked,
                        contentDescription = stringResource(R.string.detail_link_change),
                        size = 44.dp,
                    ) {
                        Icon(
                            // Link 锁链 =「关联」本命语义（换关联 = 重新建链）
                            imageVector = ImageVector.vectorResource(R.drawable.ic_link),
                            contentDescription = null,
                            tint = scheme.onBackgroundVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    // 相邻动作钮的触控缝（skill: touch-spacing；染色圆已内缩仍有余量）
                    Spacer(Modifier.width(4.dp))
                    io.candy.tick.ui.common.PressIconButton(
                        onClick = onUnlink,
                        contentDescription = stringResource(R.string.detail_unlink),
                        size = 44.dp,
                    ) {
                        Icon(
                            // unlink 断链 = 解除关联（minus 减号不表意）
                            imageVector = ImageVector.vectorResource(R.drawable.ic_unlink),
                            contentDescription = null,
                            tint = scheme.onBackgroundVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            } else if (phase == "suggest" && state.linkSuggestion != null) {
                // 关联建议：整行点按即关联（胶囊提示条 + 本机应用小图标）
                val suggested = state.linkSuggestion
                val appIcon = io.candy.tick.ui.common.rememberAppIcon(suggested?.first)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        // 薄荷 CTA 底（tertiaryContainer）：点击关联的行动语义，深浅两模式边界清晰
                        .background(scheme.tertiaryContainer)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        ) { onLinkSuggestion() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (appIcon != null) {
                        io.candy.tick.ui.common.AppIconImage(bitmap = appIcon, size = 26.dp, corner = 7.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.detail_auto_link_hint, suggested.second),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_add),
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    // 「其他应用」入口：44dp/14dp——与 linked 态动作钮同规格
                    io.candy.tick.ui.common.PressIconButton(
                        onClick = onChangeLinked,
                        contentDescription = stringResource(R.string.detail_link_app_other),
                        size = 44.dp,
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_more),
                            contentDescription = null,
                            tint = scheme.onBackgroundVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            } else {
                // 未关联（也无建议）：选项行式入口——link 图标前缀 + chevron 尾缀表可点
                // （skill: input-affordance），无底色不抢 suggest 态的薄荷行动语义
                // （skill: primary-action 次要操作从属）；与 linked 态同一行语言
                val (pressSrc, pressColor) = io.candy.tick.ui.common.rememberRowPressTint()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(pressColor, RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = pressSrc,
                            indication = null,
                            onClick = onChangeLinked,
                        )
                        // v10 计入命中：行高 ~40dp 达标（触控缓冲带不越出关联区）
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_link),
                        contentDescription = null,
                        tint = scheme.onBackgroundVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.detail_link_app),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onBackgroundVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = scheme.onBackgroundVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
                }
            }
        }
    }
}
