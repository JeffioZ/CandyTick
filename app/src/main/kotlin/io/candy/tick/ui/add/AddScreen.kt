package io.candy.tick.ui.add

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.wrapContentHeight
import io.candy.tick.data.model.statusAt
import io.candy.tick.ui.common.RepoIdentityHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.ui.common.AppLinkRow
import io.candy.tick.ui.common.AppIconResult
import io.candy.tick.ui.common.AppPickerSheet
import io.candy.tick.ui.common.Feedback
import io.candy.tick.ui.common.LinkedRepoAvatar
import io.candy.tick.ui.common.RepoAvatar
import io.candy.tick.ui.common.rememberAppIconResult
import io.candy.tick.ui.common.repoAvatarIcon
import io.candy.tick.ui.common.CenteredScrollToTopBar
import io.candy.tick.ui.common.CompactTextAction
import io.candy.tick.ui.common.FooterText
import io.candy.tick.ui.common.TextFieldActionButton
import io.candy.tick.ui.common.clearTextFieldAction
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.SectionDivider
import io.candy.tick.ui.common.rememberMotionEnabled
import io.candy.tick.ui.common.scrollToTop
import io.candy.tick.ui.common.sourceErrorText
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import io.candy.tick.ui.common.Motion

private data class ResultIcon(val repoId: Long, val packageName: String, val icon: AppIconResult)

@Composable
fun AddScreen(
    onClose: () -> Unit,
    onAdded: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as CandyTickApp
    val vm: AddViewModel = viewModel { AddViewModel(app.container.repoRepository, app) }
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedIcon by remember(state.results) { mutableStateOf<ResultIcon?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val iconRevision by produceState(0, lifecycle) {
        var firstStart = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (firstStart) firstStart = false else value++
        }
    }
    val activePreviewId = (state.preview as? AddViewModel.PreviewState.Loaded)?.info?.githubId
    var showAppPicker by remember(activePreviewId) { mutableStateOf(false) }
    val showPreview = state.preview !is AddViewModel.PreviewState.Idle
    val overlay = updateTransition(state.takeIf { showPreview }, label = "repoPreview")
    val resultsCovered = showPreview || overlay.currentState != null || overlay.isRunning
    val resultsScroll = if (state.results.repositories.isNotEmpty()) rememberLazyListState() else null
    var previewScroll by remember { mutableStateOf<ScrollState?>(null) }
    val scope = rememberCoroutineScope()
    val animateScroll = rememberMotionEnabled()
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var initialFocusPending by rememberSaveable { mutableStateOf(true) }
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(showPreview) {
        if (showPreview) {
            initialFocusPending = false
            keyboardController?.hide()
        } else if (initialFocusPending) {
            initialFocusPending = false
            kotlinx.coroutines.delay(100)
            runCatching { inputFocus.requestFocus() }
        }
    }

    fun goBack() {
        if (showAppPicker) {
            showAppPicker = false
            return
        }
        val current = vm.state.value
        if (current.preview != AddViewModel.PreviewState.Idle) {
            vm.dismissPreview()
            if (current.results.repositories.isEmpty()) {
                inputFocus.requestFocus()
                keyboardController?.show()
            }
        } else if (!resultsCovered) {
            onClose()
        }
    }
    androidx.activity.compose.BackHandler(enabled = resultsCovered, onBack = ::goBack)

    // 仓库已写入但页面已离开本预览（取消窗口竞态）时的兜底提示：组合外取词
    val addedToast = stringResource(R.string.toast_subscription_added)
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is AddViewModel.Event.Added -> {
                    val current = vm.state.value
                    val preview = current.preview as? AddViewModel.PreviewState.Loaded
                    if (current.adding && preview?.info?.githubId == event.repoId) {
                        onAdded(event.repoId)
                    } else if (!current.duplicate) {
                        // 仓库已写入但页面已离开本预览（取消窗口竞态）：至少告知结果；
                        // duplicate 路径（本就已订阅）不提示，避免「已添加」误导
                        Feedback.show(addedToast)
                    }
                }
            }
        }
    }

    Scaffold(
        // 键盘弹出时整页收缩到 ime 上方：居中的 loading/引导不被键盘盖住
        modifier = Modifier.imePadding(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            CenteredScrollToTopBar(
                title = stringResource(R.string.add_repo),
                onBack = ::goBack,
                onTitleClick = {
                    scope.launch {
                        if (showPreview) previewScroll?.scrollToTop(animateScroll)
                        else resultsScroll?.scrollToTop(animateScroll)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            TextField(
                value = state.input,
                onValueChange = { if (it.length <= io.candy.tick.ui.common.InputLimits.SEARCH) vm.onInput(it) },
                label = stringResource(R.string.add_input_hint),
                // 单行：IME 确定显示「搜索」而非换行；长链接也不会被折行截断
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submit() }),
                // 非空清空钮：粘贴长链接后想换目标，逐字删除太苦
                trailingIcon = if (state.input.isNotEmpty()) {
                    { TextFieldActionButton(onClick = clearTextFieldAction(inputFocus) { vm.onInput("") }) }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inputFocus),
            )
            if (state.parseError) {
                io.candy.tick.ui.common.InlineErrorRow(
                    message = stringResource(R.string.add_parse_error),
                    onRetry = null,
                )
            }

            // ── 状态区：底层（搜索各态）+ 预览浮层叠放 ──
            // 预览是「二级浮层」：不透明底盖在结果列表上方，返回即揭走——
            // 列表零重建（返回不卡顿、滚动位置原样），item 出现动画只在真·新搜索时播
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                Box(
                    modifier = if (resultsCovered) Modifier.clearAndSetSemantics { } else Modifier,
                ) {
                    SearchContent(state, resultsScroll, iconRevision, onRetry = vm::submit) { info, icon ->
                        if (!resultsCovered) {
                            selectedIcon = icon
                            vm.pickResult(info)
                        }
                    }
                }

                val duration = if (animateScroll) Motion.MICRO else 0
                val offset = with(LocalDensity.current) { 8.dp.roundToPx() }
                // Keep the outgoing preview, including its CTA, until the results are revealed.
                overlay.AnimatedContent(
                    contentKey = { it != null },
                    transitionSpec = {
                        if (targetState == null) {
                            val exit = fadeOut(tween(duration)) + slideOutVertically(tween(duration)) { offset }
                            (EnterTransition.None togetherWith exit).using(null)
                        } else {
                            val enter = fadeIn(tween(duration)) + slideInVertically(tween(duration)) { offset }
                            (enter togetherWith ExitTransition.None).using(null)
                        }
                    },
                    modifier = Modifier.matchParentSize(),
                ) { shown ->
                    if (shown == null) return@AnimatedContent
                    val scroll = rememberScrollState()
                    DisposableEffect(scroll) {
                        previewScroll = scroll
                        onDispose { previewScroll = null }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MiuixTheme.colorScheme.background)
                            .then(if (showPreview) Modifier else Modifier.clearAndSetSemantics { })
                            .verticalScroll(scroll),
                    ) {
                        if (shown.results.repositories.isNotEmpty()) {
                            BackToResultsButton(onClick = ::goBack)
                        }
                        val phaseDuration = if (animateScroll) Motion.PHASE else 0
                        AnimatedContent(
                            targetState = shown.preview,
                            // Background metadata and local app matching update the same card in place.
                            contentKey = { if (it is AddViewModel.PreviewState.Loaded) it.info.githubId else it },
                            transitionSpec = {
                                (fadeIn(tween(phaseDuration)) togetherWith fadeOut(tween(phaseDuration)))
                                    .using(SizeTransform { _, _ -> tween(phaseDuration) })
                            },
                            label = "previewPhase",
                        ) { phase ->
                            when (phase) {
                                AddViewModel.PreviewState.Loading -> PreviewSkeletonCard()
                                is AddViewModel.PreviewState.Failed -> io.candy.tick.ui.common.StatusPage(
                                    isError = true,
                                    title = stringResource(R.string.add_preview_failed),
                                    subtitle = sourceErrorText(phase.error),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = {
                                        val current = vm.state.value
                                        if (current.input == shown.input && current.preview == phase) vm.submit()
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                )
                                is AddViewModel.PreviewState.Loaded -> PreviewCard(
                                    preview = phase,
                                    state = shown,
                                    resultIcon = selectedIcon,
                                    iconRevision = iconRevision,
                                    onLinkApp = {
                                        if (phase.info.githubId == activePreviewId && !state.adding) showAppPicker = true
                                    },
                                    onConfirm = { vm.confirmAdd(phase.info.githubId) },
                                )
                                AddViewModel.PreviewState.Idle -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    AppPickerSheet(
        show = showAppPicker,
        onPick = { pkg, label ->
            if (showAppPicker && activePreviewId != null) vm.setLinkedApp(activePreviewId, pkg, label)
            showAppPicker = false
        },
        onDismiss = { showAppPicker = false },
    )
}

@Composable
private fun SearchContent(
    state: AddViewModel.UiState,
    resultsScroll: LazyListState?,
    iconRevision: Int,
    onRetry: () -> Unit,
    onPick: (io.candy.tick.data.RepoInfo, ResultIcon?) -> Unit,
) {
    when {
        state.searching -> androidx.compose.foundation.layout.Box(
            // 搜索等待：dots 居中于结果区（避免左上小点+大片空白的失衡构图）
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            io.candy.tick.ui.common.LoadingDots(dotSize = 5.dp)
        }
        // 搜索失败：StatusPage 居中（与详情页提交/版本加载失败同款同构——
        // 同为「内容区加载失败」类别）——此前左上小红字+重试钮+大片空白的
        // 失衡构图与其他态（居中 dots/居中空态）语言不统一
        state.searchError != null -> io.candy.tick.ui.common.StatusPage(
            isError = true,
            title = stringResource(R.string.add_search_failed),
            subtitle = sourceErrorText(state.searchError),
            // 失败要有出路（skill error-recovery）：重试走同一条提交路径
            actionLabel = stringResource(R.string.retry),
            onAction = onRetry,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).wrapContentHeight().padding(vertical = 16.dp),
        )
        resultsScroll != null -> {
            val motion = rememberMotionEnabled()
            val appear = remember(resultsScroll) {
                androidx.compose.animation.core.MutableTransitionState(!motion).apply { targetState = true }
            }
            val offset = with(LocalDensity.current) { 8.dp.roundToPx() }
            val duration = if (motion) Motion.PHASE else 0
            androidx.compose.animation.AnimatedVisibility(
                visibleState = appear,
                enter = fadeIn(tween(duration)) + slideInVertically(tween(duration)) { offset },
            ) {
                LazyColumn(
                    state = resultsScroll,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.results.repositories, key = { it.githubId }) { info ->
                        val pkg = state.resultPackages[info.githubId]
                        val icon = rememberAppIconResult(pkg, refreshKey = iconRevision)
                        io.candy.tick.ui.common.PressableCard(
                            onClick = {
                                val selected = if (pkg != null && icon?.bitmap != null) {
                                    ResultIcon(info.githubId, pkg, icon)
                                } else null
                                onPick(info, selected)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                RepoIdentityHeader(owner = info.owner, repo = info.repo, stars = info.stars) {
                                    RepoAvatar(info.owner, repoAvatarIcon(pkg, icon), 48.dp, 12.dp)
                                }
                                if (!info.description.isNullOrBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = info.description,
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    item(key = "search-end") {
                        val label = if (state.results.isComplete) R.plurals.footer_search_complete else R.plurals.footer_search_results
                        FooterText(label, state.results.repositories.size)
                    }
                }
            }
        }
        state.searched -> io.candy.tick.ui.common.StatusPage(
            // 无结果空态（搜索中/失败分支已在前排除）
            icon = ImageVector.vectorResource(R.drawable.ic_search),
            title = stringResource(R.string.add_search_empty),
            subtitle = stringResource(R.string.add_search_empty_hint),
            modifier = Modifier
                .fillMaxSize().verticalScroll(rememberScrollState()).wrapContentHeight().padding(vertical = 16.dp),
        )
        state.parseError -> Unit
        else -> io.candy.tick.ui.common.StatusPage(
            icon = ImageVector.vectorResource(R.drawable.ic_link),
            compact = true,
            title = stringResource(R.string.add_guide_title),
            subtitle = stringResource(R.string.add_guide_formats),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).wrapContentHeight().padding(vertical = 16.dp),
        )
    }
}

@Composable
private fun BackToResultsButton(onClick: () -> Unit) {
    CompactTextAction(
        onClick = onClick,
        // Match the gap below the input while keeping this space inside the tap target.
        hitAreaPadding = PaddingValues(bottom = 12.dp),
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_back),
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primaryVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.add_back_to_results),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primaryVariant,
        )
    }
}

@Composable
private fun PreviewSkeletonCard() {
    // URL 加载时沿用实际关联行和按钮的尺寸；简介只预留一行。
    val block = MiuixTheme.colorScheme.dividerLine
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(block),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxWidth(0.6f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(block),
                    )
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxWidth(0.4f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(block),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth(0.75f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(block),
            )
            Spacer(Modifier.height(8.dp))
            SectionDivider()
            Spacer(Modifier.height(8.dp))
            AppLinkRow(appIcon = null, onClick = null, iconPending = true) {
                Box(Modifier.width(96.dp).height(12.dp).background(block, RoundedCornerShape(4.dp)))
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ButtonDefaults.MinHeight)
                    .squircleBackground(color = block, cornerRadius = ButtonDefaults.CornerRadius)
                    .padding(ButtonDefaults.InsideMargin)
                    .clearAndSetSemantics { },
            ) {
                // 空行随按钮字号缩放，骨架不提供操作。
                Text(text = "", style = MiuixTheme.textStyles.button)
            }
        }
    }
}

@Composable
private fun PreviewCard(
    preview: AddViewModel.PreviewState.Loaded,
    state: AddViewModel.UiState,
    resultIcon: ResultIcon?,
    iconRevision: Int,
    onLinkApp: () -> Unit,
    onConfirm: () -> Unit,
) {
    val info = preview.info
    val scheme = MiuixTheme.colorScheme
    // Reuse the visible row's bitmap only for that same repository and local app.
    val initialIcon = resultIcon?.takeIf { it.repoId == info.githubId && it.packageName == preview.linkedPackage }?.icon
    val icon = rememberAppIconResult(preview.linkedPackage, initial = initialIcon, refreshKey = iconRevision)
    Card(modifier = Modifier.fillMaxWidth()) {
        // 与详情页头卡同一语言：身份区 → 信息组 → 分区线 → 关联/操作区；跨组 8（同档）
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            RepoIdentityHeader(
                owner = info.owner,
                repo = info.repo,
                stars = info.stars,
                badge = { io.candy.tick.ui.common.StatusBadge(info.toSnapshot().statusAt()) },
            ) {
                LinkedRepoAvatar(info.owner, repoAvatarIcon(preview.linkedPackage, icon, preview.matchingApp))
            }

            // 描述与详情头卡同款：body2 灰字 + 3 行截断（长描述不撑爆卡）
            if (!info.description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = info.description,
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onBackgroundVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            SectionDivider()
            Spacer(Modifier.height(8.dp))
            if (preview.linkedPackage != null) {
                AppLinkRow(
                    appIcon = icon?.bitmap,
                    iconPending = icon == null,
                    onClick = null,
                    onChangeApp = onLinkApp,
                ) {
                    val label = preview.linkedLabel?.let { stringResource(R.string.detail_linked_app, it) }
                        ?: stringResource(R.string.linked_app_pending)
                    androidx.compose.animation.Crossfade(
                        targetState = label,
                        animationSpec = androidx.compose.animation.core.tween(Motion.PHASE),
                        label = "previewLinkedName",
                    ) { name ->
                        Text(
                            text = name,
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onBackgroundVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                AppLinkRow(appIcon = null, onClick = onLinkApp, iconPending = preview.matchingApp) {
                    Text(
                        text = stringResource(R.string.detail_link_app),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onBackgroundVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = stringResource(if (state.duplicate) R.string.add_open_existing else R.string.add_confirm),
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                busyText = when {
                    !state.adding -> null
                    preview.matchingApp -> stringResource(R.string.add_matching_app)
                    else -> stringResource(R.string.add_saving)
                },
            )
        }
    }
}
