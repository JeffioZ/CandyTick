package io.candy.tick.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 统一的空态 / 错误态页面组件：圆底图标 + 主文案 + 可选副文案 + 可选操作。
 * 全应用的「没有内容」时刻共用同一结构——居中呈现，不再缩在角落。
 *
 * 布局由调用方给：LazyColumn 里用 fillParentMaxHeight(0.45f)，
 * 普通容器里用 weight(1f)，弹层等紧凑场景用 [compact] + 固定纵向留白。
 *
 * @param icon 状态图标（空态传 File / 搜索传 Search 等；错误态通常传 Close）
 * @param isError 错误态着色（errorContainer 圆底 + 红 ✓）；否则中性圆底
 * @param compact 紧凑档：弹层 / 半屏场景用（40dp 圆、小间距）
 */
@Composable
fun StatusPage(
    icon: ImageVector?,
    title: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    compact: Boolean = false,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val scheme = MiuixTheme.colorScheme
    val circle = if (compact) 40.dp else 48.dp
    val iconSize = if (compact) 18.dp else 22.dp
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(circle)
                    .clip(CircleShape)
                    .background(if (isError) scheme.errorContainer else scheme.secondaryVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isError) scheme.onErrorContainer else scheme.onBackgroundVariant,
                    modifier = Modifier.size(iconSize),
                )
            }
            Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
        }
        Text(
            text = title,
            style = MiuixTheme.textStyles.title4,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MiuixTheme.textStyles.body2,
                color = scheme.onBackgroundVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            TextButton(text = actionLabel, onClick = onAction)
        }
    }
}

/** 列表终点提示（「已到底」）：全应用列表共用，footnote2 弱化 + 居中 */
@Composable
fun FooterText(text: String, modifier: Modifier = Modifier, count: Int? = null) {
    // 列表终点标记：文字两侧短线（dividerLine 与卡内分隔线同源）——印刷式「线·文·线」
    // 终点语言；保持最弱层级（footnote2 + 分隔线色）不与内容争夺注意力
    val scheme = MiuixTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            // 上 24 / 下 0：上方 = spacedBy(8~10)+24 ≈ 32~34，下方 = 容器终止 32——上下对称
            // （此前 v8 上下各半：上 16 / 下 40，footer 悬得偏上、底下空一截）
            .padding(top = 24.dp),
    ) {
        Box(
            Modifier
                .width(24.dp)
                .height(1.5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(scheme.dividerLine),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            // count 非空时「已到底 · 共 N 项」：滚到底的清点时刻给总数（书末页码心理）
            text = if (count != null) {
                stringResource(io.candy.tick.R.string.list_end_count, text, count)
            } else {
                text
            },
            style = MiuixTheme.textStyles.footnote2,
            color = scheme.onBackgroundVariant,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .width(24.dp)
                .height(1.5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(scheme.dividerLine),
        )
    }
}
