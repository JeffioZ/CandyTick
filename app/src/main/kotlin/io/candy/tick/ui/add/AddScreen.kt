package io.candy.tick.ui.add

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.candy.tick.data.model.statusAt
import io.candy.tick.util.CountFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.ui.common.AppPickerSheet
import io.candy.tick.ui.common.FooterText
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.sourceErrorText
import io.candy.tick.ui.common.toImageBitmap
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

@Composable
fun AddScreen(
    onClose: () -> Unit,
    onAdded: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as CandyTickApp
    val vm: AddViewModel = viewModel { AddViewModel(app.container.repoRepository, app) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showAppPicker by remember { mutableStateOf(false) }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // 进页即聚焦：本页唯一目的就是输入（等一帧布局稳定，防未挂载崩溃）
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        runCatching { inputFocus.requestFocus() }
    }

    // 每次进入添加页都从干净状态开始（VM 为 Activity 域单例）
    LaunchedEffect(Unit) { vm.reset() }

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
            SmallTopAppBar(
                color = androidx.compose.ui.graphics.Color.Transparent,
                title = stringResource(R.string.add_repo),
                navigationIcon = {
                    io.candy.tick.ui.common.PressIconButton(
                        onClick = onClose,
                        // 图标旁无文字的控件必须有可访问名称（icon-button a11y）
                        contentDescription = stringResource(R.string.back),
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_back),
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
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
                onValueChange = vm::onInput,
                label = stringResource(R.string.add_input_hint),
                // 单行：IME 确定显示「搜索」而非换行；长链接也不会被折行截断
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submit() }),
                // 非空清空钮：粘贴长链接后想换目标，逐字删除太苦
                trailingIcon = if (state.input.isNotEmpty()) {
                    {
                        io.candy.tick.ui.common.PressIconButton(
                            onClick = { vm.onInput("") },
                            contentDescription = stringResource(R.string.clear_input),
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
                        onAction = vm::submit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    state.results.isNotEmpty() -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // 底部 32：全 App 滚动终止留白统一档（此前无 bottom，footer 贴底）
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                    items(state.results, key = { it.githubId }) { info ->
                        // 与首页 RepoCard 同款语言：头像 + title4 名称 + body2 路径 + 统计
                        // 出现动画 fade+上滑 220（纯 fade 感知弱；新搜索全新 key 全卡组合整批播放）
                        val cardAppear = remember(info.githubId, resultsReveal) {
                            androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visibleState = cardAppear,
                            enter = androidx.compose.animation.fadeIn(
                                androidx.compose.animation.core.tween(220),
                            ) + androidx.compose.animation.slideInVertically(
                                animationSpec = androidx.compose.animation.core.tween(220),
                            ) { it / 10 },
                        ) {
                        io.candy.tick.ui.common.PressableCard(
                            onClick = {
                                // 进入预览=输入阶段结束：收键盘，整屏让位预览卡
                                // （skill: focus-not-obscured，预览按钮不被键盘遮挡）
                                keyboardController?.hide()
                                vm.pickResult(info)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(
                                    fadeInSpec = androidx.compose.animation.core.tween(150),
                                    fadeOutSpec = androidx.compose.animation.core.tween(150),
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
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        }
                    }
                        // 到底提示：搜索一次取前 20 条，明示列表不会再增长
                        item(key = "search-end") {
                            FooterText(text = stringResource(R.string.list_end), count = state.results.size)
                        }
                    }
                    state.searched -> io.candy.tick.ui.common.StatusPage(
                        // 无结果空态（搜索中/失败分支已在前排除）
                        icon = ImageVector.vectorResource(R.drawable.ic_search),
                        compact = true,
                        title = stringResource(R.string.add_search_empty),
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

                // 预览浮层：背景=页面底色完整遮挡底层；内容超高可滚（键盘弹出兜底）
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.preview !is AddViewModel.PreviewState.Idle,
                    enter = androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(150),
                    ) + androidx.compose.animation.slideInVertically(
                        animationSpec = androidx.compose.animation.core.tween(150),
                    ) { it / 16 },
                    // 退场=沿进场原路退回（下移+淡出）：返回动作有方向感；
                    // 列表静止显现（零重建保滚动位），揭走本身即返回动画
                    exit = androidx.compose.animation.fadeOut(
                        androidx.compose.animation.core.tween(150),
                    ) + androidx.compose.animation.slideOutVertically(
                        animationSpec = androidx.compose.animation.core.tween(150),
                    ) { it / 8 },
                    modifier = Modifier.matchParentSize(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MiuixTheme.colorScheme.background)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // 浮层内三态（骨架/卡/错误）柔切换；Loaded 不再叠出现动画
                        // （浮层自身 fade+slide 已承担进场，双层叠加反而迟滞）；
                        // SizeTransform 显式 180：三态高度不同，默认 spring 是尺寸暗桩
                        androidx.compose.animation.AnimatedContent(
                            // 目标态直用 PreviewState：内容切换读 target 而非实时 state——
                            // 退场帧定格自身快照，不再中途改读新态（此前 Loaded→Failed 过渡两帧同显失败态）
                            targetState = state.preview,
                            transitionSpec = {
                                ((androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180))) togetherWith
                                    (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))))
                                    .using(
                                        androidx.compose.animation.SizeTransform(clip = false) { _, _ ->
                                            androidx.compose.animation.core.tween(180)
                                        },
                                    )
                            },
                            label = "previewPhase",
                        ) { preview ->
                            when (preview) {
                                is AddViewModel.PreviewState.Loading -> PreviewSkeletonCard()
                                is AddViewModel.PreviewState.Failed -> Column {
                                    // 失败态同样给退路：此前只在 Loaded 相有「返回搜索结果」，
                                    // 失败浮层不透明盖住列表，用户只能退出整个添加页
                                    if (state.results.isNotEmpty()) {
                                        BackToResultsButton(onClick = {
                                            resultsReveal++
                                            vm.dismissPreview()
                                        })
                                        Spacer(Modifier.height(12.dp))
                                    }
                                    io.candy.tick.ui.common.StatusPage(
                                        // 与搜索失败/详情列表失败同款同构（「内容区加载失败」类别）；
                                        // 此前骨架卡变一行裸红字+文字钮，重量骤降悬空
                                        icon = ImageVector.vectorResource(R.drawable.ic_close),
                                        isError = true,
                                        title = sourceErrorText(preview.error),
                                        // 网络抖动类失败：重试不必改输入重来（skill error-recovery）
                                        actionLabel = stringResource(R.string.retry),
                                        onAction = vm::retryPreview,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                    )
                                }
                                is AddViewModel.PreviewState.Loaded -> androidx.compose.foundation.layout.Column {
                                    // 从搜索结果进入的预览：顶部留明确退路（skill: back-behavior）
                                    if (state.results.isNotEmpty()) {
                                        // 上=外层 spacedBy(12)+按钮 pad5，下=pad5+Spacer12：视觉 17/17 对称
                                        BackToResultsButton(onClick = {
                                            resultsReveal++
                                            vm.dismissPreview()
                                        })
                                        Spacer(Modifier.height(12.dp))
                                    }
                                    PreviewCard(
                                        preview = preview,
                                        state = state,
                                        onLinkApp = { showAppPicker = true },
                                        onConfirm = vm::confirmAdd,
                                        onOpenExisting = { onAdded(preview.info.githubId) },
                                    )
                                }
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
            vm.setLinkedApp(pkg, label)
            showAppPicker = false
        },
        onDismiss = { showAppPicker = false },
    )
}

@Composable
private fun BackToResultsButton(onClick: () -> Unit) {
    // 「← 返回搜索结果」：预览态的明确退路（skill: back-behavior）。
    // 左对齐导航动作；padding 计入命中区，触控带 ~40dp
    val (pressSrc, pressColor) = io.candy.tick.ui.common.rememberRowPressTint()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(pressColor, RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = pressSrc,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_back),
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primaryVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.add_back_to_results),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primaryVariant,
        )
    }
}

@Composable
private fun PreviewSkeletonCard() {
    // 预览骨架卡：与 PreviewCard 同构占位（头像/两行文字/描述/统计/按钮）——
    // 仅 URL 粘贴路径走到（点搜索结果已乐观直出）；数据到达原位替换，无布局跳动
    // （skill: content-jumping / CLS）。静态色块：几百 ms 的等待不值得再叠呼吸动画
    // （skill: excessive-motion / loading-states）
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
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.Box(
                Modifier
                    .width(96.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(block),
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(block),
            )
        }
    }
}

@Composable
private fun PreviewCard(
    // 由 AnimatedContent 的 target 快照传入：过渡期退场帧渲染自身数据，
    // 不回读实时 state（实时值已变会 ClassCastException / 内容跳变）
    preview: AddViewModel.PreviewState.Loaded,
    state: AddViewModel.UiState,
    onLinkApp: () -> Unit,
    onConfirm: () -> Unit,
    onOpenExisting: () -> Unit,
) {
    val info = preview.info
    val scheme = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
        // 与详情页头卡同一语言：身份区 → 信息组 → 分区线 → 关联/操作区；跨组 8（同档）
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 图标 56/14 与详情头卡同档（此前默认 48/12——自称「同一语言」却差一档）
                io.candy.tick.ui.common.RepoIconBlock(
                    owner = info.owner,
                    linkedPackage = preview.linkedPackage,
                    size = 56.dp,
                    corner = 14.dp,
                )
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

            Spacer(Modifier.height(8.dp))
            // 描述与详情头卡同款：body2 灰字 + 3 行截断（长描述不撑爆卡）
            if (!info.description.isNullOrBlank()) {
                Text(
                    text = info.description,
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onBackgroundVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            // 分区线（与详情头卡同款节奏：前 8 / 线 / 后 6）：档案信息 → 关联/订阅操作区
            io.candy.tick.ui.common.SectionDivider()
            Spacer(Modifier.height(6.dp))
            // 关联区跨页统一语义：薄荷=行动（未关联的引导入口），已关联=状态信息行
            // （与详情页 linked 态同构：应用图标 + 灰字 + 右侧动作；窄卡用文字动作代替图标钮）
            val linkedLabel = preview.linkedLabel
            if (linkedLabel != null) {
                val appBitmap = io.candy.tick.ui.common.rememberAppIcon(preview.linkedPackage)
                // 与详情页 linked 态逐参数同构：应用图标 + 灰字 + ic_link 钮(44dp/14dp 触控下限)——
                // 行为一致（动作在钮上，行本身不可点），跨页零学习成本
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    io.candy.tick.ui.common.PressIconButton(
                        onClick = onLinkApp,
                        contentDescription = stringResource(R.string.detail_link_change),
                        size = 44.dp, // 行内紧凑档：与详情页 linked 态动作钮同规格
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_link),
                            contentDescription = null,
                            tint = scheme.onBackgroundVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            } else {
                // 未关联：与详情页 none 态逐参数同构的选项行（link 图标 + chevron + 按压染色）——
                // 薄荷语义收敛为「高置信建议」专用（详情 suggest 态），通用入口一律低调；
                // 单卡聚焦场景里行式入口可发现性充分，跨页同构优先
                val (nonePressSrc, nonePressColor) = io.candy.tick.ui.common.rememberRowPressTint()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(nonePressColor, RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = nonePressSrc,
                            indication = null,
                            onClick = onLinkApp,
                        )
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

            Spacer(Modifier.height(8.dp))
            // 主按钮单开关（180 柔切换）：重复订阅 =「打开已订阅的仓库」——
            // 按钮文案自含状态语义（此前红条+按钮三层堆叠，与 CTA 胶囊挤在一起）；
            // SizeTransform 显式 180：默认 spring 是尺寸动画暗桩（与 fade 同拍收敛）
            androidx.compose.animation.AnimatedContent(
                targetState = state.duplicate,
                transitionSpec = {
                    ((androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180))) togetherWith
                        (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))))
                        .using(
                            androidx.compose.animation.SizeTransform(clip = false) { _, _ ->
                                androidx.compose.animation.core.tween(180)
                            },
                        )
                },
                label = "addPrimary",
            ) { dup ->
                if (dup) {
                    PrimaryButton(
                        text = stringResource(R.string.add_open_existing),
                        onClick = onOpenExisting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    PrimaryButton(
                        text = if (state.adding) stringResource(R.string.loading) else stringResource(R.string.add_confirm),
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.adding,
                    )
                }
            }
        }
    }
}
