package io.candy.tick.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.util.CountFormat
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A transfer can start before either GitHub or the HTTP response supplies its size. */
@Composable
internal fun DownloadProgress(progress: Float?, sizeBytes: Long = 0, onCancel: (() -> Unit)? = null, cancelEnabled: Boolean = true) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            val label = if (sizeBytes > 0) {
                val downloaded = (sizeBytes * progress.toDouble()).toLong()
                "$percent · ${CountFormat.formatBytes(downloaded)} / ${CountFormat.formatBytes(sizeBytes)}"
            } else percent
            val bar by animateFloatAsState(
                targetValue = progress,
                animationSpec = tween(Motion.PROGRESS, easing = LinearEasing),
                label = "downloadProgress",
            )
            // 单行布局：条(weight) + 标签 + 取消钮同线居中——块高恒 48dp（取消钮触摸区），
            // 与「下载」按钮相位等高，点击下载前后卡片高度不变（真机反馈高度跳变）。
            // 标签随条同行（skill: 紧凑标签保持单行），窄屏时条自动收窄
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LinearProgressIndicator(
                    progress = bar,
                    // 条保底 48dp：Row 先量非 weight 子项（标签+取消钮），无保底时
                    // 大字号/长标签会把条压到 0dp（DSH R12）
                    modifier = Modifier.weight(1f).widthIn(min = 48.dp),
                )
                Text(
                    label,
                    style = MiuixTheme.textStyles.footnote2,
                    color = scheme.onBackgroundVariant,
                    // 单行 + 省略号：长标签不折行（破坏 48dp 恒高），超出部分截断
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (onCancel != null) {
                    CompactTextAction(onClick = onCancel, enabled = cancelEnabled, alignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.cancel),
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.primaryVariant,
                        )
                    }
                }
            }
        }
        if (onCancel != null && progress == null) {
            CompactTextAction(onClick = onCancel, enabled = cancelEnabled, alignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.cancel),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.primaryVariant,
                )
            }
        }
    }
}
