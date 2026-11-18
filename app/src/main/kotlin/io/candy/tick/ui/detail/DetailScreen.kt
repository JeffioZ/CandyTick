package io.candy.tick.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.ui.common.AppPickerSheet
import io.candy.tick.ui.common.sourceErrorText
import io.candy.tick.data.InstallManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Delete

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
    // 瞬时反馈走全局 FeedbackHost
    val doneMsg = stringResource(R.string.install_done)
    val failedMsg = stringResource(R.string.install_failed_format)
    val mismatchMsg = stringResource(R.string.install_signature_mismatch)
    val autoLinkedFmt = stringResource(R.string.detail_auto_linked)
    val needPermMsg = stringResource(R.string.detail_need_install_permission)

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is DetailViewModel.Event.InstallResultEvent -> when (val r = event.result) {
                    is InstallManager.InstallResult.Success -> io.candy.tick.ui.common.Feedback.show(doneMsg)
                    is InstallManager.InstallResult.Failed -> io.candy.tick.ui.common.Feedback.show(
                        text = if (r.signatureMismatch) mismatchMsg else failedMsg.format(r.message ?: ""),
                        isError = true,
                    )
                    InstallManager.InstallResult.PendingUserAction -> Unit // 系统确认界面已接管
                    InstallManager.InstallResult.NeedsInstallPermission ->
                        io.candy.tick.ui.common.Feedback.show(text = needPermMsg, isError = true)
                }
                is DetailViewModel.Event.AutoLinked -> io.candy.tick.ui.common.Feedback.show(autoLinkedFmt.format(event.packageName))
                // DownloadDeleted 不出胶囊：动作区四相动画当场可见，复述即噪音
                DetailViewModel.Event.DownloadDeleted -> Unit
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
            color = scheme.primaryVariant, // 薄荷主色：与全应用活动色统一
            // 只在刷新中显示两个字：36dp 文字区是库的固定结构，用它让间距有目的
            refreshTexts = listOf("", "", stringResource(R.string.refresh_refreshing), ""),
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
                                // 不出胶囊：头卡关联行当场消失，视觉已确认
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
                                animationSpec = androidx.compose.animation.core.tween(220),
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
                }
            }
        }
    }

    }

    AppPickerSheet(
        show = showAppPicker,
        onPick = { pkg, label ->
            vm.setLinkedApp(pkg, newLabel = label)
            showAppPicker = false
        },
        onDismiss = { showAppPicker = false },
    )

    // 分支选择弹层（提交视图浏览分支）：show 直通，退场有动效
    BranchPickerSheet(
        show = showBranchSheet,
        state = state,
        onSelect = {
            vm.setBranch(it)
            showBranchSheet = false
        },
        onDismiss = { showBranchSheet = false },
        onLoadBranches = vm::loadBranches,
    )

    // 移除确认（破坏性操作）：show 直通，退场有动效
    RemoveRepoDialog(
        show = showRemoveDialog && repo != null,
        repoName = repo?.let { it.noteName ?: "${it.owner}/${it.repo}" } ?: "",
        onConfirm = {
            showRemoveDialog = false
            vm.removeRepo()
            onBack()
        },
        onDismiss = { showRemoveDialog = false },
    )
}
