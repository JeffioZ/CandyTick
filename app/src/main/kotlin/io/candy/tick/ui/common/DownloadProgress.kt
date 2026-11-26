package io.candy.tick.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.util.CountFormat
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 统一下载进度行（头卡更新 / 版本卡片 / App 自更新共用）：
 * - 恒定 48dp 最小高度，与「下载」主按钮等高，切换零高度跳动；
 * - 固定宽等宽数字(tnum)占位，进度条宽度从 0% 到 100% 绝对不动；
 * - 取消按钮外置单一实例且恒定右对齐，progress 从 null 切到数值时不横跳，
 *   并由 48dp 热区左侧自然承接与进度文案的视觉间距，避免左右空隙不对称。
 */
@Composable
internal fun DownloadProgress(
    progress: Float?,
    sizeBytes: Long = 0,
    onCancel: (() -> Unit)? = null,
    cancelEnabled: Boolean = true,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (progress == null) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoadingDots(contentDescription = null)
                Text(
                    text = stringResource(R.string.download_in_progress),
                    style = MiuixTheme.textStyles.footnote2,
                    color = scheme.onBackgroundVariant,
                )
            }
        } else {
            val percent = stringResource(R.string.download_progress_percent, (progress * 100).toInt())
            val maxPercent = stringResource(R.string.download_progress_percent, 100)
            val totalFormatted = if (sizeBytes > 0) CountFormat.formatBytes(sizeBytes) else null
            val label = if (totalFormatted != null) {
                val downloaded = (sizeBytes * progress.toDouble()).toLong()
                "$percent · ${CountFormat.formatBytes(downloaded)} / $totalFormatted"
            } else percent
            val placeholder = if (totalFormatted != null) {
                val widestDownloaded = if (totalFormatted.length >= 7) totalFormatted else "1023 KB"
                "$maxPercent · $widestDownloaded / $totalFormatted"
            } else maxPercent
            val labelStyle = MiuixTheme.textStyles.footnote2.copy(fontFeatureSettings = "tnum")
            val bar by animateFloatAsState(
                targetValue = progress,
                animationSpec = tween(Motion.PROGRESS, easing = LinearEasing),
                label = "downloadProgress",
            )
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinearProgressIndicator(
                    progress = bar,
                    modifier = Modifier.weight(1f).widthIn(min = 48.dp),
                )
                Spacer(Modifier.width(12.dp))
                // 有取消钮时左对齐紧贴进度条（12dp 恒距）；无取消钮（如自更新弹层）右对齐贴齐右缘
                Box(contentAlignment = if (onCancel != null) Alignment.CenterStart else Alignment.CenterEnd) {
                    Text(
                        text = placeholder,
                        style = labelStyle,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alpha(0f).clearAndSetSemantics { },
                    )
                    Text(
                        text = label,
                        style = labelStyle,
                        color = scheme.onBackgroundVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (onCancel != null) {
            Spacer(Modifier.width(4.dp))
            CompactTextAction(
                onClick = onCancel,
                enabled = cancelEnabled,
                alignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.primaryVariant,
                )
            }
        }
    }
}
