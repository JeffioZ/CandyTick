package io.candy.tick.ui.common

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.SelfUpdater
import io.candy.tick.ui.theme.MonoTokenStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * 应用自身更新弹窗（全局宿主：挂 MainActivity 层，设置页未打开也能弹——
 * SyncWorker 后台自动检查发现新版同样触发）。
 *
 * 弹出策略：
 * - 自动：state 变为 NewVersion 且该 tag 未被用户关过（进程存活期防骚扰；
 *   冷启动 dismissedTag 随 remember 重置——有新版未处理时每次冷启动提醒一次，
 *   与系统更新提醒语义一致）
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

@Composable
fun UpdateSheet(
    selfUpdater: SelfUpdater,
    autoInstall: Boolean,
) {
    val state by selfUpdater.state.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }
    var dismissedTag by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(UpdateSheetController.manualRequest, state) {
        val s = state
        if (UpdateSheetController.manualRequest) {
            if (s is SelfUpdater.State.NewVersion || s is SelfUpdater.State.Downloading ||
                s is SelfUpdater.State.Downloaded || s is SelfUpdater.State.Failed
            ) {
                UpdateSheetController.manualRequest = false
                show = true
            } else if (s is SelfUpdater.State.UpToDate || s is SelfUpdater.State.Failed) {
                // 终态消费：检查完成无新版/失败即请求完结——残留会在后台下载态误弹窗
                UpdateSheetController.manualRequest = false
            }
        } else if (s is SelfUpdater.State.NewVersion && s.tag != dismissedTag && !s.incomparable) {
            // 自动检查发现新版：每版本自动弹一次；无法比较版本不弹（不宣称「有更新」，
            // design.md:183）——入口在设置页版本行，用户主动点开再看
            show = true
        }
    }

    // show 直通（勿用 if 包裹）：退场动画依赖组件持续组合（与全 App 弹层同一约定）
    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_update_found),
        onDismissRequest = {
            show = false
            (state as? SelfUpdater.State.NewVersion)?.let { dismissedTag = it.tag }
        },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 四相柔切换（与仓库下载四相同款 180 档）：新版 → 进度 → 安装 / 失败不硬跳。
            // Failed 此前映射 idle 只渲染空白——下载失败时弹窗只剩标题，看起来像界面坏了
            val phase = when (state) {
                is SelfUpdater.State.NewVersion -> "new"
                is SelfUpdater.State.Downloading -> "dl"
                is SelfUpdater.State.Downloaded -> "done"
                is SelfUpdater.State.Failed -> "failed"
                else -> "idle"
            }
            androidx.compose.animation.AnimatedContent(
                targetState = phase,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    ((androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                        androidx.compose.animation.slideInVertically(
                            animationSpec = androidx.compose.animation.core.tween(180),
                        ) { it / 6 }) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)))
                        // 尺寸动画显式 180 同拍（默认 SizeTransform 是 spring 暗桩）
                        .using(
                            androidx.compose.animation.SizeTransform(clip = false) { _, _ ->
                                androidx.compose.animation.core.tween(180)
                            },
                        )
                },
                label = "updatePhase",
            ) { ph ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (ph) {
                        "new" -> (state as? SelfUpdater.State.NewVersion)?.let { s ->
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
                            s.notes?.let { notes ->
                                // 更新说明：与版本历史展开区同款——链接可点（plain 后的裸 URL
                                // 由 annotated 再标注；remember 缓存防重组重算）
                                val scheme = MiuixTheme.colorScheme
                                val sheetCtx = androidx.compose.ui.platform.LocalContext.current
                                // 自身更新日志的 #123 指向 CandyTick 仓库 issue
                                val selfSlug = io.candy.tick.data.SelfUpdater.APP_OWNER + '/' + io.candy.tick.data.SelfUpdater.APP_REPO
                                val notesText = remember(notes, scheme.primaryVariant, selfSlug) {
                                    io.candy.tick.util.ReleaseNotes.annotated(
                                        md = notes,
                                        linkColor = scheme.primaryVariant,
                                        repoSlug = selfSlug,
                                    )
                                }
                                androidx.compose.foundation.text.ClickableText(
                                    text = notesText,
                                    style = MiuixTheme.textStyles.body2.copy(color = scheme.onBackgroundVariant),
                                    modifier = Modifier
                                        .heightIn(max = 280.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) { offset ->
                                    notesText.getStringAnnotations("URL", offset, offset)
                                        .firstOrNull()?.item?.let { url ->
                                            runCatching {
                                                sheetCtx.startActivity(
                                                    android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(url),
                                                    ),
                                                )
                                            }
                                        }
                                }
                            }
                            if (s.asset == null) {
                                // 无安装包资产（release 只有源码归档等）：此前按钮可点但静默无操作
                                Text(
                                    text = stringResource(R.string.self_update_no_apk),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                )
                                val releasesCtx = androidx.compose.ui.platform.LocalContext.current
                                io.candy.tick.ui.common.LinkText(
                                    text = stringResource(R.string.self_update_view_releases),
                                    onClick = {
                                        runCatching {
                                            releasesCtx.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(
                                                        "https://github.com/" + io.candy.tick.data.SelfUpdater.APP_OWNER +
                                                            '/' + io.candy.tick.data.SelfUpdater.APP_REPO + "/releases",
                                                    ),
                                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                    },
                                    showIcon = true,
                                )
                            } else {
                                PrimaryButton(
                                    text = stringResource(R.string.settings_download_update),
                                    onClick = {
                                        scope.launch {
                                            val file = selfUpdater.download(s.asset)
                                            // 与仓库 APK 同一行为：下载完成按开关自动进安装
                                            if (file != null && autoInstall) selfUpdater.install(file)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        "dl" -> (state as? SelfUpdater.State.Downloading)?.let { st ->
                            val p by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = st.progress,
                                animationSpec = androidx.compose.animation.core.tween(300),
                                label = "updateDl",
                            )
                            LinearProgressIndicator(
                                progress = p,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = stringResource(R.string.download_progress_percent, (p * 100).toInt()),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        }
                        "done" -> (state as? SelfUpdater.State.Downloaded)?.let { st ->
                            Text(
                                text = st.tag,
                                style = MonoTokenStyle.copy(fontWeight = FontWeight.Bold),
                                color = MiuixTheme.colorScheme.primaryVariant,
                            )
                            PrimaryButton(
                                text = stringResource(R.string.settings_install_update),
                                onClick = { selfUpdater.install(st.file) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        "failed" -> (state as? SelfUpdater.State.Failed)?.let { f ->
                            // 失败相（此前是空白 + 弹窗只剩标题）：错误条 + 对应出路
                            val scheme = MiuixTheme.colorScheme
                            Text(
                                text = when (f.reason) {
                                    SelfUpdater.Reason.INSTALL ->
                                        stringResource(R.string.self_update_install_failed_note)
                                    SelfUpdater.Reason.DOWNLOAD ->
                                        stringResource(R.string.settings_download_failed)
                                    SelfUpdater.Reason.NETWORK ->
                                        stringResource(R.string.settings_update_failed)
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = scheme.onErrorContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                    .background(scheme.errorContainer)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                            when (f.reason) {
                                // 下载失败：文件已清理，重试从零下
                                SelfUpdater.Reason.DOWNLOAD -> PrimaryButton(
                                    text = stringResource(R.string.retry),
                                    onClick = { scope.launch { selfUpdater.retryDownload() } },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                // 安装失败：安装包已保留，直接重装
                                SelfUpdater.Reason.INSTALL -> PrimaryButton(
                                    text = stringResource(R.string.settings_install_update),
                                    onClick = { f.file?.let(selfUpdater::install) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                else -> Unit
                            }
                        }
                        else -> {
                            // 关闭竞态兜底（state 已流转到弹窗外相）
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}
