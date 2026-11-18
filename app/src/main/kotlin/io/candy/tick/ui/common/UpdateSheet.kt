package io.candy.tick.ui.common

import androidx.compose.animation.togetherWith
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
                s is SelfUpdater.State.Downloaded
            ) {
                UpdateSheetController.manualRequest = false
                show = true
            }
        } else if (s is SelfUpdater.State.NewVersion && s.tag != dismissedTag) {
            show = true // 自动检查发现新版：每版本自动弹一次
        }
    }

    if (!show) return

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
            // 三相柔切换（与仓库下载四相同款 180 档）：新版 → 进度 → 安装不硬跳
            val phase = when (state) {
                is SelfUpdater.State.NewVersion -> "new"
                is SelfUpdater.State.Downloading -> "dl"
                is SelfUpdater.State.Downloaded -> "done"
                else -> "idle"
            }
            androidx.compose.animation.AnimatedContent(
                targetState = phase,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                        androidx.compose.animation.slideInVertically(
                            animationSpec = androidx.compose.animation.core.tween(180),
                        ) { it / 6 }) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))
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
                            s.notes?.let { notes ->
                                // 更新说明取自 release body（已剥 markdown）；长文限高可滚
                                Text(
                                    text = notes,
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                    modifier = Modifier
                                        .heightIn(max = 280.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                            PrimaryButton(
                                text = stringResource(R.string.settings_download_update),
                                onClick = {
                                    scope.launch {
                                        val file = s.asset?.let { selfUpdater.download(it) }
                                        // 与仓库 APK 同一行为：下载完成按开关自动进安装
                                        if (file != null && autoInstall) selfUpdater.install(file)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
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
