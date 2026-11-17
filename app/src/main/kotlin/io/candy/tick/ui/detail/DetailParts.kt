package io.candy.tick.ui.detail

import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.RepoIconBlock
import io.candy.tick.ui.common.StatusBadge
import io.candy.tick.ui.common.VersionStatusLine
import io.candy.tick.ui.common.relTimeString
import io.candy.tick.ui.common.toImageBitmap
import io.candy.tick.ui.common.updateAvailable
import io.candy.tick.ui.theme.MonoTokenStyle
import io.candy.tick.util.CountFormat
import io.candy.tick.data.model.statusAt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Create
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ── 详情页子组件（自 DetailScreen 拆出：头部卡 / 分支弹层 / 移除确认）──

/**
 * 分支选择底部弹层：限高 60% 屏 + 平滑增高；分支多时给搜索框。
 * 首行固定「默认分支」，已选分支被远端删除时保留一行选中态可切回。
 */
@Composable
internal fun BranchPickerSheet(
    state: DetailViewModel.UiState,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    onLoadBranches: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val maxSheetHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.6f
    var query by remember { mutableStateOf("") }
    val current = state.repo?.branch
    val keyword = query.trim()
    val filtered = if (keyword.isEmpty()) state.branches else state.branches.filter {
        it.contains(keyword, ignoreCase = true)
    }
    top.yukonga.miuix.kmp.window.WindowBottomSheet(
        show = true,
        title = stringResource(R.string.branch_picker_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .animateContentSize(),
        ) {
            if (state.branches.size > 15) {
                top.yukonga.miuix.kmp.basic.TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.branch_search_hint),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
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
                state.branchesLoading -> Text(
                    text = stringResource(R.string.loading),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                state.branchesFailed -> TextButton(
                    text = stringResource(R.string.branch_load_failed),
                    onClick = onLoadBranches,
                )
            }
        }
    }
}

/** 移除订阅确认（破坏性操作：红色主按钮） */
@Composable
internal fun RemoveRepoDialog(
    repoName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    top.yukonga.miuix.kmp.window.WindowDialog(
        show = true,
        title = stringResource(R.string.remove_confirm_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.remove_confirm_body, repoName),
                style = MiuixTheme.textStyles.body2,
                color = scheme.onBackgroundVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                )
                top.yukonga.miuix.kmp.basic.Button(
                    onClick = onConfirm,
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                        color = scheme.error,
                        contentColor = scheme.onError,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.action_remove),
                        style = MiuixTheme.textStyles.button,
                    )
                }
            }
        }
    }
}

/** 卡内分区线：全宽 1dp，配 spacedBy(8) 形成 16dp 呼吸 */
@Composable
internal fun SectionDivider() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

@Composable
internal fun FooterText(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** 分支选择行：BasicComponent + Check 选中态（与设置页 OptionRow 同款） */
@Composable
internal fun BranchRow(label: String, selected: Boolean, onClick: () -> Unit) {
    top.yukonga.miuix.kmp.basic.BasicComponent(
        modifier = Modifier.fillMaxWidth(),
        title = label,
        endActions = {
            if (selected) {
                Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primaryVariant,
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
) {
    val repo = state.repo ?: return
    val snapshot = state.snapshot
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RepoIconBlock(repo = repo, size = 56.dp, corner = 14.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = repo.noteName ?: repo.repo,
                        style = MiuixTheme.textStyles.title4,
                        color = scheme.onBackground,
                    )
                    Text(
                        text = "${repo.owner}/${repo.repo}",
                        style = MonoTokenStyle,
                        color = scheme.onBackgroundVariant,
                    )
                }
                StatusBadge(status = snapshot?.statusAt() ?: io.candy.tick.data.model.RepoStatus.UNKNOWN)
            }

            snapshot?.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onBackgroundVariant,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.detail_stars, CountFormat.format(snapshot?.stars ?: 0)),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                )
                Text(
                    text = stringResource(R.string.detail_forks, CountFormat.format(snapshot?.forks ?: 0)),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                )
                Text(
                    text = stringResource(R.string.detail_issues, CountFormat.format(snapshot?.openIssues ?: 0)),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                )
                snapshot?.license?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onBackgroundVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }

            snapshot?.pushedAt?.let { pushed ->
                Text(
                    text = stringResource(R.string.detail_last_push, relTimeString(pushed)),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onBackgroundVariant,
                )
            }

            // ── 版本分区：版本状态 + 更新操作成组（因果相邻），与元数据/关联区用分隔线切开 ──
            snapshot?.latestReleaseTag?.let { tag ->
                SectionDivider()
                VersionStatusLine(repo = repo, remoteTag = tag)
            }

            // 更新入口（有新版时头部直接行动）：下载 → 进度 → 安装，不用翻版本列表
            val latestTag = snapshot?.latestReleaseTag
            val hasUpdate = latestTag != null && io.candy.tick.ui.common.updateAvailable(context, repo, latestTag)
            val downloadedMatches = repo.downloadedApk?.version == latestTag
            when (val dl = state.download) {
                is DetailViewModel.DownloadState.Active -> Column {
                    top.yukonga.miuix.kmp.basic.LinearProgressIndicator(
                        progress = dl.progress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.download_progress_percent, (dl.progress * 100).toInt()),
                        style = MiuixTheme.textStyles.footnote2,
                        color = scheme.onBackgroundVariant,
                    )
                }
                else -> when {
                    hasUpdate && downloadedMatches -> PrimaryButton(
                        text = stringResource(R.string.install_apk),
                        onClick = onInstallUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    hasUpdate -> PrimaryButton(
                        text = stringResource(R.string.download_update_action),
                        onClick = onDownloadUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ── 关联分区（始终存在，固定卡底）──
            SectionDivider()
            // 关联本机应用（可修改 / 解除）
            val linkedLabel = repo.linkedPackage?.let { pkg ->
                runCatching {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(pkg, 0),
                    ).toString()
                }.getOrNull() ?: pkg
            }
            if (linkedLabel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val appBitmap = remember(repo.linkedPackage) {
                        repo.linkedPackage?.let { pkg ->
                            runCatching { context.packageManager.getApplicationIcon(pkg).toImageBitmap() }.getOrNull()
                        }
                    }
                    appBitmap?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it,
                            contentDescription = null,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = stringResource(R.string.detail_linked_app, linkedLabel),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onBackgroundVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onChangeLinked) {
                        Icon(
                            imageVector = MiuixIcons.Create,
                            contentDescription = stringResource(R.string.detail_link_change),
                            tint = scheme.onBackgroundVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onUnlink) {
                        Icon(
                            imageVector = MiuixIcons.Blocklist,
                            contentDescription = stringResource(R.string.detail_unlink),
                            tint = scheme.onBackgroundVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else if (state.linkSuggestion != null) {
                // 关联建议：整行点按即关联（胶囊提示条 + 本机应用小图标）
                val suggested = state.linkSuggestion
                val appIcon = remember(suggested?.first) {
                    suggested?.first?.let { pkg ->
                        runCatching { context.packageManager.getApplicationIcon(pkg).toImageBitmap() }.getOrNull()
                    }
                }
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
                        androidx.compose.foundation.Image(
                            bitmap = appIcon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(7.dp)),
                        )
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
                        imageVector = MiuixIcons.Add,
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = onChangeLinked) {
                        Icon(
                            imageVector = MiuixIcons.More,
                            contentDescription = stringResource(R.string.detail_link_app_other),
                            tint = scheme.onBackgroundVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else {
                TextButton(text = stringResource(R.string.detail_link_app), onClick = onChangeLinked)
            }
        }
    }
}
