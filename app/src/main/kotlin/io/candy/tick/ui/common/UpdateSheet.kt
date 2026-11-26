package io.candy.tick.ui.common

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.SelfUpdater
import io.candy.tick.ui.theme.MonoTokenStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用自身更新弹窗（全局宿主：挂 MainActivity 层，设置页未打开也能弹——
 * SyncWorker 后台自动检查发现新版同样触发）。
 *
 * 弹出策略：
 * - 自动：state 变为 NewVersion 且该 tag 未被用户关过。dismissedTag 用 rememberSaveable 保存：
 *   旋转、进程被系统回收后恢复都不重弹已关掉的版本；从桌面冷启动才重置——
 *   有新版未处理时每次冷启动提醒一次，与系统更新提醒语义一致
 * - 手动：设置页点「立即检查」置 [UpdateSheetController.manualRequest]，
 *   NewVersion/Downloading/Downloaded 任一可用相强制弹（重开进度/安装入口）
 *
 * 三相内容：新版（tag + 说明 + 下载）→ 下载进度 → 安装。下载后台持续，
 * 关闭弹窗不中断；完成后由系统通知兜底召回。
 */
object UpdateSheetController {
    /** 手动请求弹出（设置页「立即检查」点击时置 true，弹窗消费后自动复位） */
    var manualRequest by androidx.compose.runtime.mutableStateOf(false)
}

/** 弹窗有内容可渲染的相位才带 tag：NewVersion/Downloading/Downloaded/Failed */
private fun contentTagOf(state: SelfUpdater.State): String? = when (state) {
    is SelfUpdater.State.NewVersion -> state.tag
    is SelfUpdater.State.Downloading -> state.tag
    is SelfUpdater.State.Downloaded -> state.tag
    is SelfUpdater.State.Failed -> state.tag
    else -> null
}

@Composable
fun UpdateSheet(selfUpdater: SelfUpdater) {
    val state by selfUpdater.state.collectAsStateWithLifecycle()
    var show by rememberSaveable { mutableStateOf(false) }
    var dismissedTag by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var lastCheck by remember(show) { mutableStateOf<Pair<SelfUpdater.State.Downloaded, SelfUpdater.State>?>(null) }

    LaunchedEffect(state is SelfUpdater.State.Idle) {
        if (state is SelfUpdater.State.Idle) show = false
    }
    val release = state as? SelfUpdater.State.NewVersion
    val contentTag = contentTagOf(state)
    // state::class 入键：Checking 直转 UpToDate 时 contentTag 仍为 null，不加会漏掉 manualRequest 的消费
    LaunchedEffect(UpdateSheetController.manualRequest, contentTag, state::class) {
        if (UpdateSheetController.manualRequest) {
            when (contentTag) {
                null -> if (state !is SelfUpdater.State.Checking) {
                    // Idle/UpToDate 无内容可弹：静默消费（设置页检查结果已走全局胶囊）；
                    // Checking 等出结果再决定，不提前消费
                    UpdateSheetController.manualRequest = false
                }
                else -> {
                    UpdateSheetController.manualRequest = false
                    show = true
                }
            }
        } else if (release != null && release.tag != dismissedTag && !release.incomparable) {
            // 自动检查发现新版：每版本自动弹一次；无法比较版本不弹（不宣称「有更新」，
            // design.md:183）——入口在设置页版本行，用户主动点开再看
            show = true
        }
    }

    // show 直通（勿用 if 包裹）：退场动画依赖组件持续组合（与全 App 弹层同一约定）
    val scroll = key(release?.tag) { rememberScrollState() }
    CandySheet(
        show = show,
        onDismissRequest = {
            show = false
            // 任意内容相位关闭都记 tag：Downloading/Failed 期间关掉后，
            // 同 tag 回到 NewVersion 不得再次自动弹出
            dismissedTag = contentTag
        },
        onDismissFinished = { scope.launch { scroll.scrollTo(0) } },
        startAction = {
            SheetScrollToTopTitle(
                text = stringResource(R.string.notif_update_ready_title),
                canScroll = scroll.canScrollBackward,
                scrollToTop = scroll::scrollToTop,
            )
        },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp),
        ) {
            // Keep the action ahead of the notes when a full notes pane would crowd it out.
            val notesHeight = 280.dp
            val shortBody = maxHeight < notesHeight + 160.dp * LocalDensity.current.fontScale
            // 四相柔切换（与仓库下载四相同款 180 档）：新版 → 进度 → 安装 / 失败不硬跳。
            // Failed 此前映射 idle 只渲染空白——下载失败时弹窗只剩标题，看起来像界面坏了
            androidx.compose.animation.AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                modifier = Modifier.fillMaxWidth().then(if (shortBody) Modifier.verticalScroll(scroll) else Modifier),
                transitionSpec = { downloadActionTransition() },
                label = "updatePhase",
            ) { phase ->
                // An outgoing phase stays composed during the fade and may still receive a tap.
                fun runAction(action: suspend () -> Unit) {
                    scope.launch { if (show && selfUpdater.state.value == phase) action() }
                }
                val sectionGap = 10.dp
                Column(
                    modifier = Modifier.padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(sectionGap),
                ) {
                    when (phase) {
                        is SelfUpdater.State.NewVersion -> {
                            val s = phase
                            // 醒目版本 tag：一眼看到升到什么
                            Text(
                                text = s.tag,
                                style = MonoTokenStyle.copy(fontWeight = FontWeight.Bold),
                                color = MiuixTheme.colorScheme.primaryVariant,
                            )
                            // 无法比较版本：如实说明，不宣称「有更新」（design.md:183/294）
                            if (s.incomparable) {
                                Text(
                                    text = stringResource(R.string.self_update_incomparable_note),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                )
                            }
                            val releaseNotes: @Composable () -> Unit = {
                                s.notes?.let { notes ->
                                    val slug = "${SelfUpdater.APP_OWNER}/${SelfUpdater.APP_REPO}"
                                    ReleaseNotesBody(
                                        markdown = notes,
                                        repoSlug = slug,
                                        repoUrl = "https://github.com/$slug",
                                        tag = s.tag,
                                        maxHeight = if (shortBody) null else notesHeight,
                                        spaceAfter = sectionGap,
                                        showSource = false,
                                        scrollState = scroll,
                                    )
                                }
                            }
                            if (!shortBody) releaseNotes()
                            if (s.asset == null) {
                                Column {
                                    Text(
                                        text = stringResource(R.string.self_update_no_apk),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                                    )
                                    val uriHandler = rememberExternalUriHandler()
                                    LinkText(
                                        text = stringResource(R.string.self_update_view_releases),
                                        onClick = {
                                            val slug = "${SelfUpdater.APP_OWNER}/${SelfUpdater.APP_REPO}"
                                            uriHandler.openUri("https://github.com/$slug/releases")
                                        },
                                        showIcon = true,
                                        // 图标与上方提示文字左缘对齐
                                        alignInk = true,
                                    )
                                }
                            } else {
                                PrimaryButton(
                                    text = stringResource(R.string.download_update_action),
                                    onClick = { runAction { selfUpdater.requestManualDownload(s) } },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (shortBody) releaseNotes()
                        }
                        is SelfUpdater.State.Downloading -> DownloadProgress(phase.progress)
                        is SelfUpdater.State.Downloaded -> {
                            val checked = lastCheck?.takeIf { it.first == phase }?.second
                            Text(
                                text = if (checked == phase) stringResource(R.string.version_latest_only, phase.tag) else phase.tag,
                                style = MonoTokenStyle.copy(fontWeight = FontWeight.Bold),
                                color = MiuixTheme.colorScheme.primaryVariant,
                            )
                            if (checked is SelfUpdater.State.Failed) {
                                InlineErrorRow(
                                    message = stringResource(
                                        if (checked.reason == SelfUpdater.Reason.RATE_LIMITED) R.string.err_rate_limited_plain
                                        else R.string.settings_update_failed,
                                    ),
                                    onRetry = null,
                                )
                            }
                            PrimaryButton(
                                text = stringResource(R.string.settings_install_update),
                                onClick = { runAction { selfUpdater.installDownloaded(phase) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SecondaryButton(
                                text = stringResource(R.string.settings_current_version_title),
                                onClick = { runAction {
                                    lastCheck = null
                                    val result = selfUpdater.recheckDownloaded(phase)
                                    if (result != null && selfUpdater.state.value == phase) lastCheck = phase to result
                                } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        is SelfUpdater.State.Failed -> {
                            val f = phase
                            // 失败相（此前是空白 + 弹窗只剩标题）：错误条 + 对应出路
                            InlineErrorRow(
                                message = when (f.reason) {
                                    SelfUpdater.Reason.INSTALL ->
                                        Feedback.withErrorCode(stringResource(R.string.self_update_install_failed_note), "E-APK")
                                    SelfUpdater.Reason.DOWNLOAD ->
                                        Feedback.withErrorCode(stringResource(R.string.settings_download_failed), "E-DL")
                                    SelfUpdater.Reason.NETWORK ->
                                        Feedback.withErrorCode(stringResource(R.string.settings_update_failed), "E-NET")
                                    SelfUpdater.Reason.RATE_LIMITED ->
                                        Feedback.withErrorCode(stringResource(R.string.err_rate_limited_plain), "HTTP 403")
                                },
                                onRetry = null,
                            )
                            when (f.reason) {
                                SelfUpdater.Reason.DOWNLOAD -> {
                                    PrimaryButton(
                                        text = stringResource(R.string.retry),
                                        onClick = { runAction { selfUpdater.requestManualDownload(f) } },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    f.downloaded?.let { saved ->
                                        SecondaryButton(
                                            text = stringResource(R.string.self_update_install_saved_version, saved.tag),
                                            onClick = { runAction { selfUpdater.installDownloaded(f) } },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                // 安装失败：安装包已保留，直接重装
                                SelfUpdater.Reason.INSTALL -> PrimaryButton(
                                    text = stringResource(R.string.settings_install_update),
                                    onClick = { runAction { selfUpdater.installDownloaded(f) } },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SelfUpdater.Reason.NETWORK -> PrimaryButton(
                                    text = stringResource(R.string.retry),
                                    onClick = { runAction { selfUpdater.check() } },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                // 限流：立即重试只会再吃 403，文案已说明稍后再试
                                SelfUpdater.Reason.RATE_LIMITED -> Unit
                            }
                        }
                        SelfUpdater.State.Checking -> Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LoadingDots(contentDescription = null)
                            Text(stringResource(R.string.settings_checking), style = MiuixTheme.textStyles.footnote1)
                        }
                        SelfUpdater.State.UpToDate -> Text(
                            stringResource(R.string.version_local_current),
                            style = MiuixTheme.textStyles.footnote1,
                        )
                        SelfUpdater.State.Idle -> {
                            // 关闭竞态兜底（state 已流转到弹窗外相）
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}
