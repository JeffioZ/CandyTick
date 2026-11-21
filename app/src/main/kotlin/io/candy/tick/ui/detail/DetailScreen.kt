package io.candy.tick.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.ui.common.AppPickerSheet
import io.candy.tick.ui.common.ErrorBanner
import io.candy.tick.data.InstallManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import io.candy.tick.ui.common.Motion

@Composable
fun DetailScreen(repoId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as CandyTickApp
    val vm: DetailViewModel = viewModel(key = "detail-$repoId") {
        DetailViewModel(
            repoId = repoId,
            repository = app.container.repoRepository,
            installManager = app.container.installManager,
            downloadHost = app.container.repoDownloadHost,
            context = app,
        )
    }
    // 从安装器返回（ON_RESUME）：本机版本可能已变（装完新 APK），通知 VM 重算版本对比
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.onAppResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
    // 列表状态提升：点标题回顶部需要驱动滚动动画（真实滚动让折叠顶栏自动展开）
    val detailListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // 瞬时反馈走全局 FeedbackHost
    val doneMsg = stringResource(R.string.install_done)
    val failedMsg = stringResource(R.string.install_failed_format)
    val mismatchMsg = stringResource(R.string.install_signature_mismatch)
    val autoLinkedFmt = stringResource(R.string.detail_auto_linked)
    val needPermMsg = stringResource(R.string.detail_need_install_permission)
    val openFailedMsg = stringResource(R.string.open_failed)
    // busy = 静默/手动刷新进行中：顶栏动作钮整组让位给波点（actions 槽）的信号源；
    // 声明在 Screen 级——topBar（actions）与内容区两个作用域都要读
    val busy = state.refreshing || state.commitsReloading || state.releasesReloading

    // 每次进入静默刷新一轮：DetailViewModel 同 key 跨进入保留（navigation3 复用），
    // init 的加载不会重跑——上次访问的数据会顶着「缓存」出现。silent = 不点亮刷新头：
    // 首次进入区域加载点已是唯一且正确的信号（头部圆环 + 区域点同时转是实测「突兀」
    // 的来源）；有缓存的重进内容原地保留、静默校准。失败出横幅
    LaunchedEffect(Unit) { vm.refresh(silent = true) }

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
                    InstallManager.InstallResult.Cancelled -> Unit // 用户取消≠失败（design.md:180）
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
                        // 槽位 wrap-content 但约束 = 顶栏全宽（miuix TopAppBarLayout measure
                        // minWidth 归零）：用 BoxWithConstraints 自算标题 cap。loading 已改走
                        // 顶栏下缘细线（bottomContent，M3 appbar-progress 模式），标题预算
                        // 全额恢复——此前 36dp 固定槽把长名压出提前省略号（用户不喜欢）
                        androidx.compose.foundation.layout.BoxWithConstraints {
                            // actions 预留 = 最多 3×44dp 钮 + 2×8dp 缝（分支钮只在提交页签出现，
                            // 取最大档）；再加 back 48 + 呼吸缝 8
                            val titleMax = (maxWidth - 48.dp - 8.dp - 148.dp)
                                .coerceIn(120.dp, 180.dp)
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                io.candy.tick.ui.common.PressIconButton(
                                    onClick = onBack,
                                    // 图标旁无文字的控件必须有可访问名称（icon-button a11y）
                                    contentDescription = stringResource(R.string.back),
                                ) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_back),
                                        contentDescription = null,
                                        tint = scheme.onBackground,
                                    )
                                }
                                // 后退钮与标题的呼吸缝：48dp 热区/40dp 染色圆之外再留 8dp
                                // （skill: touch-spacing——此前圆贴标题文案）
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = repo?.let { it.noteName ?: it.repo } ?: stringResource(R.string.detail_title),
                                    style = MiuixTheme.textStyles.title4,
                                    color = scheme.onBackground,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .widthIn(max = titleMax)
                                        // 点标题回顶部（GitHub app 惯例单击，双击有判定延迟且不可发现）：
                                        // 无 ripple，滚动动画本身即因果反馈；前置 padding 扩热区至 ~48dp
                                        .padding(vertical = 12.dp)
                                        .clickable(
                                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                            indication = null,
                                        ) { scope.launch { detailListState.animateScrollToItem(0) } },
                                )
                            }
                        }
                    },
                    // 静默刷新反馈：2dp 薄荷线改走「覆盖式」——画在内容层顶部、盖在顶栏
                    // 与内容的既有空隙（13dp）头 2dp 上，零高度影响（bottomContent 版会
                    // 撑高顶栏 2dp 并让指示器配平偏 2dp）。silent 进入/刷新不点亮刷新头，
                    // 这里是唯一信号
                    actions = {
                        // busy 时动作钮整组让位给波点（用户提案，垃圾桶位）：按钮淡出收起 →
                        // 波点在右缘淡入展开 → 完成后反向；收展都锚定右缘（End），与垃圾桶位
                        // 无缝交接。busy ~1s 内动作暂不可用（刷新为短事务，可接受）
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !busy,
                            enter = androidx.compose.animation.fadeIn(
                                androidx.compose.animation.core.tween(Motion.MICRO),
                            ) + androidx.compose.animation.expandHorizontally(
                                animationSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                                expandFrom = androidx.compose.ui.Alignment.End,
                            ),
                            exit = androidx.compose.animation.fadeOut(
                                androidx.compose.animation.core.tween(Motion.MICRO),
                            ) + androidx.compose.animation.shrinkHorizontally(
                                animationSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                                shrinkTowards = androidx.compose.ui.Alignment.End,
                            ),
                        ) {
                            androidx.compose.foundation.layout.Row {
                                // 浏览器打开仓库（安卓规范：顶栏动作图标）
                                io.candy.tick.ui.common.PressIconButton(
                                    onClick = {
                                        val url = state.snapshot?.htmlUrl ?: repo?.let {
                                            "https://github.com/${it.owner}/${it.repo}"
                                        } ?: return@PressIconButton
                                        runCatching {
                                            context.startActivity(
                                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }.onFailure { io.candy.tick.ui.common.Feedback.show(text = openFailedMsg, isError = true) }
                                    },
                                    contentDescription = stringResource(R.string.open_in_browser),
                                ) {
                                    // globe =「打开网页」本命语义；此前 external-link 观感近「分享」
                                    // （skill icons：glyph 按用途选，带文字的 LinkText 保留 external-link）
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_globe),
                                        contentDescription = null,
                                        tint = scheme.onBackground,
                                    )
                                }
                                // 切换浏览分支（仅提交视图有分支语义；Release tab 隐藏，避免点了无效果的控件）
                                // 宽度+透明度过渡：tab 切换时图标个数不再突跳
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = state.tab == DetailViewModel.DetailTab.COMMITS,
                                    enter = androidx.compose.animation.fadeIn(
                                        androidx.compose.animation.core.tween(Motion.PHASE),
                                    ) + androidx.compose.animation.expandHorizontally(
                                        animationSpec = androidx.compose.animation.core.tween(Motion.PHASE),
                                    ),
                                    exit = androidx.compose.animation.fadeOut(
                                        androidx.compose.animation.core.tween(Motion.PHASE),
                                    ) + androidx.compose.animation.shrinkHorizontally(
                                        animationSpec = androidx.compose.animation.core.tween(Motion.PHASE),
                                    ),
                                ) {
                                    io.candy.tick.ui.common.PressIconButton(
                                        onClick = {
                                            showBranchSheet = true
                                            vm.loadBranches()
                                        },
                                        contentDescription = stringResource(R.string.branch_picker_title),
                                    ) {
                                        Icon(
                                            imageVector = ImageVector.vectorResource(R.drawable.ic_git_branch),
                                            contentDescription = null,
                                            tint = scheme.onBackground,
                                        )
                                    }
                                }
                                // 移除订阅（destructive → 确认对话框；底部文字按钮已移除）
                                io.candy.tick.ui.common.PressIconButton(
                                    onClick = { showRemoveDialog = true },
                                    contentDescription = stringResource(R.string.detail_remove_repo),
                                ) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_delete),
                                        contentDescription = null,
                                        tint = scheme.onBackground,
                                    )
                                }
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = busy,
                            enter = androidx.compose.animation.fadeIn(
                                androidx.compose.animation.core.tween(Motion.MICRO),
                            ) + androidx.compose.animation.expandHorizontally(
                                animationSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                                expandFrom = androidx.compose.ui.Alignment.End,
                            ),
                            exit = androidx.compose.animation.fadeOut(
                                androidx.compose.animation.core.tween(Motion.MICRO),
                            ) + androidx.compose.animation.shrinkHorizontally(
                                animationSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                                shrinkTowards = androidx.compose.ui.Alignment.End,
                            ),
                        ) {
                            // 主界面同款波点（loading 动画语言全 App 统一）
                            io.candy.tick.ui.common.LoadingDots(color = scheme.primaryVariant)
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
        },
    ) { padding ->
        io.candy.tick.ui.common.CandyPullToRefresh(
            isRefreshing = state.refreshing,
            onRefresh = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress) // 松手触发确认：与首页同款
                vm.refresh()
            },
            pullToRefreshState = pullState,
            topAppBarScrollBehavior = scrollBehavior,
            modifier = Modifier.padding(padding),
        ) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = detailListState,
                modifier = Modifier.fillMaxSize(),
                // 底部 32：全 App 滚动终止留白统一档（footer 元素上下间距见 FooterText v8 + 此 32）
                // top 13 = 4 基线 + 9：四页顶栏间距审计后与首页同口径（基线 4、刷新补偿 9；
                // 此前 10 基线是页间不一致的来源）
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    // 头卡组（头卡 + 错误横幅）：banner 隐藏时 0 高度收在组内——
                    // 若作独立 item，spacedBy 会在其前后各记一次，tab 上方凭空多 10dp
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
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
                            onCancelDownload = vm::cancelDownload,
                        )
                        // 错误横幅出现/消失过渡（220 表层档）：刷新失败/恢复不再硬跳
                        androidx.compose.animation.AnimatedVisibility(
                            visible = state.banner != null,
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
                            state.banner?.let { error ->
                                // 共用失败横幅（errorContainer 底 + 重试钮，与首页同构）；
                                // 用户可不刷新只关提示（dismissBanner 由此接线）
                                ErrorBanner(error = error, onRetry = vm::refresh, onDismiss = vm::dismissBanner)
                            }
                        }
                    }
                }
                // 页签：分段控件 v2。miuix 官方 TabRow 实测不适合本场景——未选中项 1dp 描边盒 +
                // 满高滑块板为多标签滚动设计，两枚大页签下观感粗糙（用户实测），弃用回到手搓版，
                // 并修掉手搓版的两处老问题：① contentPadding 16 之内又加 16 横向 padding 的双层
                // 内缩（「比上下卡片窄」的根源）→ 与卡片精确同宽；② tween(Motion.PANEL) 匀速 →
                // Motion.SlideSpring 阻尼收尾（与 LazyRow 滚动落位同族，miuix demo 阻尼感的来源）
                item {
                    val selectedIndex = if (state.tab == DetailViewModel.DetailTab.COMMITS) 0 else 1
                    // 外圆角 16dp = miuix Card 默认（HeaderCard 同源）——页签与卡片同宽后
                    // 圆角必须同源，否则相邻两圆直接对撞（用户实测「圆角又不一样了」）
                    val segShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    // 选中滑块圆角 = 外圆角 − 内缩（16−3=13）：同心圆角规则——滑块与
                    // 外容器同半径时转角曲率对不上，留楔形缝/外凸感
                    val segThumbShape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp)
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 上下对称：上=top8+item距10=18，下=bottom8+item距10=18
                            // （contentPadding top 作用在头卡上方，与此处无关）
                            .padding(top = 8.dp, bottom = 8.dp)
                            .clip(segShape)
                            .background(scheme.secondaryVariant)
                            .height(44.dp)
                            .padding(3.dp),
                    ) {
                        androidx.compose.foundation.layout.BoxWithConstraints {
                            val segWidth = maxWidth / 2
                            val offset by androidx.compose.animation.core.animateDpAsState(
                                targetValue = segWidth * selectedIndex,
                                animationSpec = io.candy.tick.ui.common.Motion.SlideSpring,
                                label = "tabThumb",
                            )
                            // 滑块（选中背景块）
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .offset(x = offset)
                                    .width(segWidth)
                                    .height(38.dp)
                                    .clip(segThumbShape)
                                    .background(scheme.surface),
                            )
                            // 图标前缀（Lucide 单源）：提交=commit 节点、版本=tag，色随文字
                            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                                listOf(
                                    R.drawable.ic_git_commit to stringResource(R.string.detail_commits),
                                    R.drawable.ic_tag to stringResource(R.string.detail_releases),
                                ).forEachIndexed { index, (iconRes, label) ->
                                    val isSelected = index == selectedIndex
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clickable(
                                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                indication = null,
                                            ) {
                                                // 页签切换轻触感：滑块 spring 移动的物理确认
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                vm.setTab(
                                                    if (index == 0) DetailViewModel.DetailTab.COMMITS
                                                    else DetailViewModel.DetailTab.RELEASES
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        androidx.compose.foundation.layout.Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // 未选中档 onBackgroundVariant：tertiary 在 secondaryVariant
                                            // 底上仅 2.75:1（深 3.25:1）不达 AA；实测 5.47:1 / 6.45:1
                                            top.yukonga.miuix.kmp.basic.Icon(
                                                imageVector = ImageVector.vectorResource(iconRes),
                                                contentDescription = null,
                                                tint = if (isSelected) scheme.onSurface
                                                else scheme.onBackgroundVariant,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                text = label,
                                                color = if (isSelected) scheme.onSurface
                                                else scheme.onBackgroundVariant,
                                                style = MiuixTheme.textStyles.body2,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // 提交跳转基址 + 本机已装版本（Screen 层算一次：列表卡不重复查 pm）
                val htmlBase = state.snapshot?.htmlUrl?.substringBefore("/tree/")
                    ?: "https://github.com/${repo?.owner}/${repo?.repo}"
                val localVersion = repo?.linkedPackage?.let { pkg ->
                    runCatching {
                        context.packageManager.getPackageInfo(pkg, 0).versionName
                    }.getOrNull()
                }
                if (state.tab == DetailViewModel.DetailTab.COMMITS) {
                    commitList(state = state, htmlBase = htmlBase, onLoadMore = vm::loadCommits)
                } else {
                    releaseList(
                        state = state,
                        localVersion = localVersion,
                        htmlBase = htmlBase,
                        onLoadMore = vm::loadReleases,
                        onSelectAsset = vm::selectAsset,
                        onDownload = vm::downloadAsset,
                        onRetry = vm::retryDownload,
                        onInstallDownloaded = { repo?.let(vm::installDownloaded) },
                        onDeleteDownloaded = vm::deleteDownloaded,
                        onCancelDownload = vm::cancelDownload,
                    )
                }
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
            // 破坏性确认时刻：轻触感（视觉红按钮之外的物理确认）
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showRemoveDialog = false
            vm.removeRepo()
            onBack()
        },
        onDismiss = { showRemoveDialog = false },
    )
}
