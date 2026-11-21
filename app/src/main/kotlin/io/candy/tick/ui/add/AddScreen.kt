package io.candy.tick.ui.add

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.candy.tick.data.model.statusAt
import io.candy.tick.util.CountFormat
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.ui.common.AppLinkRow
import io.candy.tick.ui.common.AppPickerSheet
import io.candy.tick.ui.common.CenteredScrollToTopBar
import io.candy.tick.ui.common.CompactTextAction
import io.candy.tick.ui.common.FooterText
import io.candy.tick.ui.common.TextFieldActionButton
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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import io.candy.tick.ui.common.Motion

@Composable
fun AddScreen(
    onClose: () -> Unit,
    onAdded: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as CandyTickApp
    val vm: AddViewModel = viewModel { AddViewModel(app.container.repoRepository, app) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showAppPicker by remember { mutableStateOf(false) }
    val showPreview = state.preview !is AddViewModel.PreviewState.Idle
    val resultsScroll = if (state.results.repositories.isNotEmpty()) rememberLazyListState() else null
    var previewScroll by remember { mutableStateOf<ScrollState?>(null) }
    val scope = rememberCoroutineScope()
    val animateScroll = rememberMotionEnabled()
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // 进页即聚焦：本页唯一目的就是输入（等一帧布局稳定，防未挂载崩溃）
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        runCatching { inputFocus.requestFocus() }
    }

    // 预览态的系统返回 = 揭走预览浮层（与「返回搜索结果」按钮同语义），而非退出添加页；
    // URL 直粘路径同样先回输入框——与浮层退场动画（下移+淡出）一致
    androidx.activity.compose.BackHandler(
        enabled = state.preview !is AddViewModel.PreviewState.Idle,
    ) { vm.dismissPreview() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is AddViewModel.Event.Added -> onAdded(event.repoId)
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
                onBack = if (showPreview) vm::dismissPreview else onClose,
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
                    { TextFieldActionButton(onClick = { vm.onInput("") }) }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inputFocus),
            )
            if (state.parseError) {
                // 报错容器化（errorContainer 底 + onErrorContainer 字）：此前 onErrorContainer
                // 裸用作前景色，深浅两模式各有一处对比度不保真
                Text(
                    text = stringResource(R.string.add_parse_error),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.errorContainer)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            // ── 状态区：底层（搜索各态）+ 预览浮层叠放 ──
            // 预览是「二级浮层」：不透明底盖在结果列表上方，返回即揭走——
            // 列表零重建（返回不卡顿、滚动位置原样），item 出现动画只在真·新搜索时播
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                // 结果揭示批次号：新搜索（列表重建）与「返回搜索结果」各自触发一轮入场动画；
                // 返回时不重建列表（零测量成本），仅重置 item 动画——与新搜索出结果的观感一致
                var resultsReveal by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                androidx.compose.foundation.layout.Box(
                    modifier = if (showPreview) Modifier.clearAndSetSemantics { } else Modifier,
                ) {
                    SearchContent(state, resultsReveal, resultsScroll, onRetry = vm::submit, onPick = {
                        keyboardController?.hide()
                        vm.pickResult(it)
                    })
                }

                // 预览浮层：背景=页面底色完整遮挡底层；内容超高可滚（键盘弹出兜底）
                androidx.compose.animation.AnimatedVisibility(
                    visible = showPreview,
                    enter = androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(Motion.MICRO),
                    ) + androidx.compose.animation.slideInVertically(
                        animationSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                    ) { it / 16 },
                    // 退场=沿进场原路退回（下移+淡出）：返回动作有方向感；
                    // 列表静止显现（零重建保滚动位），揭走本身即返回动画
                    exit = androidx.compose.animation.fadeOut(
                        androidx.compose.animation.core.tween(Motion.MICRO),
                    ) + androidx.compose.animation.slideOutVertically(
                        animationSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                    ) { it / 8 },
                    modifier = Modifier.matchParentSize(),
                ) {
                    // The overlay owns its offset through its exit animation; the next preview starts at the top.
                    val scroll = rememberScrollState()
                    DisposableEffect(scroll) {
                        previewScroll = scroll
                        onDispose { previewScroll = null }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MiuixTheme.colorScheme.background)
                            .verticalScroll(scroll),
                    ) {
                        if (state.results.repositories.isNotEmpty()) {
                            BackToResultsButton {
                                resultsReveal++
                                vm.dismissPreview()
                            }
                        }
                        // 浮层内三态（骨架/卡/错误）柔切换；Loaded 不再叠出现动画
                        // （浮层自身 fade+slide 已承担进场，双层叠加反而迟滞）；
                        // SizeTransform 显式 180：三态高度不同，默认 spring 是尺寸暗桩
                        androidx.compose.animation.AnimatedContent(
                            targetState = previewKeyOf(state.preview),
                            transitionSpec = {
                                ((androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.PHASE))) togetherWith
                                    (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.PHASE))))
                                    .using(
                                        androidx.compose.animation.SizeTransform(clip = false) { _, _ ->
                                            androidx.compose.animation.core.tween(Motion.PHASE)
                                        },
                                    )
                            },
                            label = "previewPhase",
                        ) { key ->
                            when (key) {
                                PreviewKey.Loading -> PreviewSkeletonCard()
                                is PreviewKey.Failed -> io.candy.tick.ui.common.StatusPage(
                                    icon = ImageVector.vectorResource(R.drawable.ic_close),
                                    isError = true,
                                    title = sourceErrorText(key.error),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = vm::retryPreview,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                )
                                is PreviewKey.Loaded -> (state.preview as? AddViewModel.PreviewState.Loaded)?.let { loaded ->
                                    PreviewCard(
                                        preview = loaded,
                                        state = state,
                                        onLinkApp = { showAppPicker = true },
                                        onConfirm = vm::confirmAdd,
                                    )
                                }
                                PreviewKey.Idle -> Unit
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
            vm.setLinkedApp(pkg, label)
            showAppPicker = false
        },
        onDismiss = { showAppPicker = false },
    )
}

@Composable
private fun SearchContent(
    state: AddViewModel.UiState,
    resultsReveal: Int,
    resultsScroll: LazyListState?,
    onRetry: () -> Unit,
    onPick: (io.candy.tick.data.RepoInfo) -> Unit,
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
        state.searchFailed -> io.candy.tick.ui.common.StatusPage(
            icon = ImageVector.vectorResource(R.drawable.ic_close),
            isError = true,
            title = stringResource(R.string.add_search_failed),
            subtitle = stringResource(R.string.status_error_subtitle),
            // 失败要有出路（skill error-recovery）：重试走同一条提交路径
            actionLabel = stringResource(R.string.retry),
            onAction = onRetry,
            modifier = Modifier.fillMaxSize(),
        )
        resultsScroll != null -> LazyColumn(
            state = resultsScroll,
            modifier = Modifier.fillMaxSize(),
            // 底部 32：全 App 滚动终止留白统一档（此前无 bottom，footer 贴底）
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.results.repositories, key = { it.githubId }) { info ->
                // 与首页 RepoCard 同款语言：头像 + title4 名称 + body2 路径 + 统计
                // 出现动画 fade+上滑 220（纯 fade 感知弱；新搜索全新 key 全卡组合整批播放）
                val cardAppear = remember(info.githubId, resultsReveal) {
                    androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visibleState = cardAppear,
                    enter = androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(Motion.PANEL),
                    ) + androidx.compose.animation.slideInVertically(
                        animationSpec = androidx.compose.animation.core.tween(Motion.PANEL),
                    ) { it / 10 },
                ) {
                    io.candy.tick.ui.common.PressableCard(
                        onClick = {
                            // 进入预览=输入阶段结束：收键盘，整屏让位预览卡
                            // （skill: focus-not-obscured，预览按钮不被键盘遮挡）
                            onPick(info)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                                fadeOutSpec = androidx.compose.animation.core.tween(Motion.MICRO),
                            ),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                io.candy.tick.ui.common.RepoIconBlock(
                                    owner = info.owner,
                                    linkedPackage = null,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    androidx.compose.foundation.layout.Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = info.repo,
                                            style = MiuixTheme.textStyles.title4,
                                            color = MiuixTheme.colorScheme.onBackground,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        io.candy.tick.ui.common.StarCount(count = CountFormat.format(info.stars))
                                    }
                                    io.candy.tick.ui.common.OwnerRepoLine(owner = info.owner, repo = info.repo)
                                }
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
            }
            item(key = "search-end") {
                val label = if (state.results.isComplete) R.plurals.footer_search_complete else R.plurals.footer_search_results
                FooterText(label, state.results.repositories.size)
            }
        }
        state.searched -> io.candy.tick.ui.common.StatusPage(
            // 无结果空态（搜索中/失败分支已在前排除）
            icon = ImageVector.vectorResource(R.drawable.ic_search),
            compact = true,
            title = stringResource(R.string.add_search_empty),
            subtitle = stringResource(R.string.add_search_empty_hint),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
        )
        else -> Column(
            // 初次进入的输入引导：说清两种输入方式（新用户不知道能粘链接/裸 owner/repo）
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            top.yukonga.miuix.kmp.basic.Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_link),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.add_guide_title),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.add_guide_formats),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
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
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
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
            AppLinkRow(appIcon = null, onClick = null) {
                Box(Modifier.width(96.dp).height(12.dp).background(block, RoundedCornerShape(4.dp)))
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ButtonDefaults.MinHeight)
                    .background(block, RoundedCornerShape(ButtonDefaults.CornerRadius))
                    .padding(ButtonDefaults.InsideMargin)
                    .clearAndSetSemantics { },
            ) {
                // 空行随按钮字号缩放，骨架不提供操作。
                Text(text = "", style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/**
 * 预览浮层三态的转场键：只随「相变」变，不随数据刷新变——
 * Loaded 以 githubId 为身份：点搜索结果直出的 info 来自 Search API（无 etag），
 * 静默校准回流的 info 来自 repos API（必带 etag）→ 实例必不等；此前 target=preview
 * 实例时每次进预览都会重放一次转场 = 卡片闪一下。自动关联落地（linked 变化）
 * 同理不重放，原位更新。错误相携 error：换了个错误值得重放。
 * 内容侧用安全转换读实时 state：实际发生的相变组合（Idle→Loading→Loaded/Failed
 * →…→Idle）里退场帧读到的是自身或空，不存在「退场帧渲染新态内容」的串台。
 */
private sealed interface PreviewKey {
    data object Idle : PreviewKey
    data object Loading : PreviewKey
    data class Failed(val error: io.candy.tick.data.SourceError) : PreviewKey
    data class Loaded(val githubId: Long) : PreviewKey
}

private fun previewKeyOf(p: AddViewModel.PreviewState): PreviewKey = when (p) {
    AddViewModel.PreviewState.Idle -> PreviewKey.Idle
    AddViewModel.PreviewState.Loading -> PreviewKey.Loading
    is AddViewModel.PreviewState.Failed -> PreviewKey.Failed(p.error)
    is AddViewModel.PreviewState.Loaded -> PreviewKey.Loaded(p.info.githubId)
}

@Composable
private fun PreviewCard(
    // 当前 Loaded 态（AnimatedContent 相同相内原位更新；相变由 PreviewKey 控制）
    preview: AddViewModel.PreviewState.Loaded,
    state: AddViewModel.UiState,
    onLinkApp: () -> Unit,
    onConfirm: () -> Unit,
) {
    val info = preview.info
    val scheme = MiuixTheme.colorScheme
    val appIcon = io.candy.tick.ui.common.rememberAppIcon(preview.linkedPackage)
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
        // 与详情页头卡同一语言：身份区 → 信息组 → 分区线 → 关联/操作区；跨组 8（同档）
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 图标 56/14 与详情头卡同档（此前默认 48/12——自称「同一语言」却差一档）
                io.candy.tick.ui.common.LinkedRepoAvatar(info.owner, appIcon)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = info.repo,
                            style = MiuixTheme.textStyles.title4,
                            color = scheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        // star 挪名称旁（与详情头卡同构）；名称截断时 star 恒可见
                        io.candy.tick.ui.common.StarCount(count = CountFormat.format(info.stars))
                    }
                    io.candy.tick.ui.common.OwnerRepoLine(owner = info.owner, repo = info.repo)
                }
                // 维护状态徽章（与详情头卡同位：身份行最右）——订阅决策的核心信号；
                // statusAt 基于 RepoSnapshot，经 toSnapshot() 判定（仅用 archived+pushedAt，无副作用）
                io.candy.tick.ui.common.StatusBadge(status = info.toSnapshot().statusAt())
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
                    appIcon = appIcon,
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
                AppLinkRow(appIcon = null, onClick = onLinkApp) {
                    Text(
                        text = stringResource(R.string.detail_link_app),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onBackgroundVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            // 主按钮单开关（180 柔切换）：重复订阅 =「打开已订阅的仓库」——
            // 按钮文案自含状态语义（此前红条+按钮三层堆叠，与 CTA 胶囊挤在一起）；
            // SizeTransform 显式 180：默认 spring 是尺寸动画暗桩（与 fade 同拍收敛）
            androidx.compose.animation.AnimatedContent(
                targetState = state.duplicate,
                transitionSpec = {
                    ((androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.PHASE))) togetherWith
                        (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.PHASE))))
                        .using(
                            androidx.compose.animation.SizeTransform(clip = false) { _, _ ->
                                androidx.compose.animation.core.tween(Motion.PHASE)
                            },
                        )
                },
                label = "addPrimary",
            ) { dup ->
                if (dup) {
                    PrimaryButton(
                        text = stringResource(R.string.add_open_existing),
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.adding,
                    )
                } else {
                    PrimaryButton(
                        text = if (state.adding) stringResource(R.string.loading) else stringResource(R.string.add_confirm),
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.adding && !preview.matchingApp,
                    )
                }
            }
        }
    }
}
