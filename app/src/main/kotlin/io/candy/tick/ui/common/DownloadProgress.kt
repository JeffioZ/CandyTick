package io.candy.tick.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val bar by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = tween(Motion.PROGRESS, easing = LinearEasing),
                    label = "downloadProgress",
                )
                // 取消钮与进度条同线居中（不是与「条+标签」整块居中——那会让钮偏高于条）；
                // 条→钮 16dp = 钮→卡片内容缘 16dp（卡内衬），两侧等距（真机反馈）。
                // 代价：48dp 触摸区使本行高≈72（条+标签分组）——「大小未知→已知」的罕见
                // 瞬间块高 +24 一次；相位间切换走 AnimatedContent 高度动画不受影响
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LinearProgressIndicator(progress = bar, modifier = Modifier.weight(1f))
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
                Text(label, style = MiuixTheme.textStyles.footnote2, color = scheme.onBackgroundVariant)
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
