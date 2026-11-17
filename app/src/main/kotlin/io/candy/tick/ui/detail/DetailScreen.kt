package io.candy.tick.ui.detail

import io.candy.tick.util.CountFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.ui.common.AppPickerSheet
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.RelTimeText
import io.candy.tick.ui.common.RepoIconBlock
import io.candy.tick.ui.common.StatusBadge
import io.candy.tick.ui.common.VersionStatusLine
import io.candy.tick.ui.common.relTimeString
import io.candy.tick.ui.common.toImageBitmap
import io.candy.tick.ui.common.sourceErrorText
import io.candy.tick.ui.theme.MonoTokenStyle
import io.candy.tick.data.InstallManager
import io.candy.tick.data.model.statusAt
import top.yukonga.miuix.kmp.basic.Button
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Create
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.basic.Check

@Composable
fun DetailScreen(repoId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as CandyTickApp
    val vm: DetailViewModel = viewModel(key = "detail-$repoId") {
        DetailViewModel(
            repoId = repoId,
            repository = app.container.repoRepository,
            githubApi = app.container.githubApi,
            installManager = app.container.installManager,
            context = app,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val scheme = MiuixTheme.colorScheme
    val pullState = rememberPullToRefreshState()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    var showAppPicker by remember { mutableStateOf(false) }
    var showBranchSheet by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val doneMsg = stringResource(R.string.install_done)
    val failedMsg = stringResource(R.string.install_failed_format)
    val mismatchMsg = stringResource(R.string.install_signature_mismatch)
    val autoLinkedFmt = stringResource(R.string.detail_auto_linked)
    val needPermMsg = stringResource(R.string.detail_need_install_permission)
    val downloadDeletedMsg = stringResource(R.string.detail_download_deleted)
    val unlinkedMsg = stringResource(R.string.detail_unlinked)

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is DetailViewModel.Event.InstallResultEvent -> when (val r = event.result) {
                    is InstallManager.InstallResult.Success -> snackbar.showSnackbar(doneMsg)
                    is InstallManager.InstallResult.Failed -> snackbar.showSnackbar(
                        if (r.signatureMismatch) {
                            mismatchMsg
                        } else {
                            failedMsg.format(r.message ?: "")
                        },
                    )
                    InstallManager.InstallResult.PendingUserAction -> Unit // 系统确认界面已接管
                    InstallManager.InstallResult.NeedsInstallPermission ->
                        snackbar.showSnackbar(needPermMsg)
                }
                is DetailViewModel.Event.AutoLinked -> snackbar.showSnackbar(autoLinkedFmt.format(event.packageName))
                DetailViewModel.Event.DownloadDeleted -> snackbar.showSnackbar(downloadDeletedMsg)
            }
        }
    }

    val repo = state.repo
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
             containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
                SmallTopAppBar(
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    title = "", // 标题左对齐走 navigationIcon 槽位（与首页一致；右侧三个动作图标下居中会被挤偏）
                    navigationIcon = {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = null,
                                    tint = scheme.onBackground,
                                )
                            }
                            Text(
                                text = repo?.let { it.noteName ?: it.repo } ?: stringResource(R.string.detail_title),
                                style = MiuixTheme.textStyles.title4,
                                color = scheme.onBackground,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 180.dp),
                            )
                        }
                    },
                    actions = {
                        // 浏览器打开仓库（安卓规范：顶栏动作图标）
                        IconButton(
                            onClick = {
                                val url = state.snapshot?.htmlUrl ?: repo?.let {
                                    "https://github.com/${it.owner}/${it.repo}"
                                } ?: return@IconButton
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Link,
                                contentDescription = stringResource(R.string.open_in_browser),
                                tint = scheme.onBackground,
                            )
                        }
                        // 切换浏览分支（仅影响提交视图；居中排布）
                        IconButton(onClick = {
                            showBranchSheet = true
                            vm.loadBranches()
                        }) {
                            Icon(
                                imageVector = io.candy.tick.ui.common.GitBranchIcon,
                                contentDescription = stringResource(R.string.branch_picker_title),
                                tint = scheme.onBackground,
                            )
                        }
                        // 移除订阅（destructive → 确认对话框；底部文字按钮已移除）
                        IconButton(onClick = { showRemoveDialog = true }) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = stringResource(R.string.detail_remove_repo),
                                tint = scheme.onBackground,
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
        },
    ) { padding ->
        PullToRefresh(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            pullToRefreshState = pullState,
            topAppBarScrollBehavior = scrollBehavior,
            refreshTexts = listOf(
                stringResource(R.string.refresh_pull_down),
                stringResource(R.string.refresh_release),
                stringResource(R.string.refresh_refreshing),
                stringResource(R.string.refresh_done),
            ),
            modifier = Modifier.padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    HeaderCard(
                        state = state,
                        onChangeLinked = { showAppPicker = true },
                        onUnlink = {
                            scope.launch {
                                vm.setLinkedApp(null)
                                snackbar.showSnackbar(unlinkedMsg)
                            }
                        },
                        onLinkSuggestion = {
                            state.linkSuggestion?.let { (pkg, label) -> vm.setLinkedApp(pkg, newLabel = label) }
                        },
                        onDownloadUpdate = vm::downloadUpdate,
                        onInstallUpdate = { repo?.let(vm::installDownloaded) },
                    )
                }
                state.banner?.let { error ->
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = sourceErrorText(error),
                                style = MiuixTheme.textStyles.footnote1,
                                color = scheme.onErrorContainer,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                // 页签：分段控件（滑块移动动画）
                item {
                    val tabItems = listOf(
                        stringResource(R.string.detail_commits) to DetailViewModel.DetailTab.COMMITS,
                        stringResource(R.string.detail_releases) to DetailViewModel.DetailTab.RELEASES,
                    )
                    val selectedIndex = if (state.tab == DetailViewModel.DetailTab.COMMITS) 0 else 1
                    val segShape = androidx.compose.foundation.shape.RoundedCornerShape(19.dp)
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(segShape)
                            .background(scheme.secondaryVariant)
                            .height(44.dp)
                            .padding(3.dp),
                    ) {
                        androidx.compose.foundation.layout.BoxWithConstraints {
                            val segWidth = maxWidth / tabItems.size
                            val offset by androidx.compose.animation.core.animateDpAsState(
                                targetValue = segWidth * selectedIndex,
                                animationSpec = androidx.compose.animation.core.tween(250),
                                label = "tabThumb",
                            )
                            // 滑块（选中背景块）
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .offset(x = offset)
                                    .width(segWidth)
                                    .height(38.dp)
                                    .clip(segShape)
                                    .background(scheme.surface),
                            )
                            // 标签
                            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                                tabItems.forEachIndexed { index, (label, tab) ->
                                    val isSelected = index == selectedIndex
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clickable(
                                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                indication = null,
                                            ) {
                                                vm.setTab(
                                                    if (index == 0) DetailViewModel.DetailTab.COMMITS
                                                    else DetailViewModel.DetailTab.RELEASES
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) scheme.onSurface
                                            else scheme.onSurfaceContainerVariant,
                                            style = MiuixTheme.textStyles.body2,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.tab == DetailViewModel.DetailTab.COMMITS) {
                    commitList(state = state, onLoadMore = vm::loadCommits)
                } else {
                    releaseList(
                        state = state,
                        onLoadMore = vm::loadReleases,
                        onSelectAsset = vm::selectAsset,
                        onDownload = vm::downloadAsset,
                        onRetry = vm::retryDownload,
                        onInstallDownloaded = { repo?.let(vm::installDownloaded) },
                        onDeleteDownloaded = vm::deleteDownloaded,
                    )
                    if (state.releasesEnd && state.releases.isNotEmpty()) {
                        item {
                            FooterText(text = stringResource(R.string.list_end))
                        }
                    }
                }
            }
        }
    }

        // 安装结果 / 撤销类反馈覆盖层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            SnackbarHost(state = snackbar)
        }
    }

    if (showAppPicker) {
        AppPickerSheet(
            show = true,
            onPick = { pkg, label ->
                vm.setLinkedApp(pkg, newLabel = label)
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false },
        )
    }

    // 分支选择（提交视图浏览分支；限高 60% 屏 + 平滑增高；分支多时给搜索框）
    if (showBranchSheet) {
        top.yukonga.miuix.kmp.window.WindowBottomSheet(
            show = true,
            title = stringResource(R.string.branch_picker_title),
            onDismissRequest = { showBranchSheet = false },
        ) {
            val maxSheetHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.6f
            var query by remember { mutableStateOf("") }
            val current = state.repo?.branch
            val keyword = query.trim()
            val filtered = if (keyword.isEmpty()) state.branches else state.branches.filter {
                it.contains(keyword, ignoreCase = true)
            }
            androidx.compose.foundation.layout.Column(
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
                        vm.setBranch(null)
                        showBranchSheet = false
                    },
                )
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    items(filtered, key = { it }) { name ->
                        BranchRow(name, name == current) {
                            vm.setBranch(name)
                            showBranchSheet = false
                        }
                    }
                    // 已选分支被远端删除（不在列表里）：保留一行选中态，可切回默认
                    current?.takeIf { it !in state.branches && (keyword.isEmpty() || it.contains(keyword, true)) }?.let { name ->
                        item(key = "deleted-$name") {
                            BranchRow(name, true) {
                                vm.setBranch(name)
                                showBranchSheet = false
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
                        onClick = vm::loadBranches,
                    )
                }
            }
        }
    }

    // 移除确认（破坏性操作）
    if (showRemoveDialog && repo != null) {
        top.yukonga.miuix.kmp.window.WindowDialog(
            show = true,
            title = stringResource(R.string.remove_confirm_title),
            onDismissRequest = { showRemoveDialog = false },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.remove_confirm_body,
                        repo!!.noteName ?: "${repo!!.owner}/${repo!!.repo}",
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.weight(1f))
                    top.yukonga.miuix.kmp.basic.TextButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { showRemoveDialog = false },
                    )
                    Button(
                        onClick = {
                            showRemoveDialog = false
                            vm.removeRepo()
                            onBack()
                        },
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
}

/** 卡内分区线：全宽 1dp，配 spacedBy(8) 形成 16dp 呼吸 */
@Composable
private fun SectionDivider() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

@Composable
private fun FooterText(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** 分支选择行：BasicComponent + Check 选中态（与设置页 OptionRow 同款） */
@Composable
private fun BranchRow(label: String, selected: Boolean, onClick: () -> Unit) {
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
private fun HeaderCard(
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
                        .background(scheme.secondaryContainer)
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
                        color = scheme.onBackground,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = MiuixIcons.Add,
                        contentDescription = null,
                        tint = scheme.primaryVariant,
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
