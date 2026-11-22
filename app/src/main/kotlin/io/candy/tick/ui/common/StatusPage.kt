package io.candy.tick.ui.common

import androidx.annotation.PluralsRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.candy.tick.R
import io.candy.tick.data.SourceError
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Inline recovery for repository cards. Measure the retry action before the message wraps. */
@Composable
internal fun InlineErrorRow(
    message: String,
    onRetry: (() -> Unit)?,
    retryEnabled: Boolean = true,
    showRetryLabel: Boolean = false,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(scheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = if (onRetry == null) 12.dp else 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_info),
            contentDescription = null,
            tint = scheme.onErrorContainer,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = message,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MiuixTheme.textStyles.footnote1,
            color = scheme.onErrorContainer,
        )
        if (onRetry != null && showRetryLabel) {
            CompactTextAction(onClick = onRetry, enabled = retryEnabled, surface = scheme.errorContainer) {
                Text(
                    text = stringResource(R.string.retry),
                    style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onErrorContainer.copy(alpha = if (retryEnabled) 1f else 0.38f),
                )
            }
        } else if (onRetry != null) {
            ErrorBannerButton(
                R.drawable.ic_refresh, R.string.retry, lerp(scheme.errorContainer, scheme.onErrorContainer, 0.09f),
                onRetry, enabled = retryEnabled,
            )
        }
    }
}

/**
 * 统一的空态 / 错误态页面组件：圆底图标 + 主文案 + 可选副文案 + 可选操作。
 * 全应用的「没有内容」时刻共用同一结构——居中呈现，不再缩在角落。
 *
 * 布局由调用方给：LazyColumn 里用 fillParentMaxHeight(0.45f)，
 * 普通容器里用 weight(1f)，弹层等紧凑场景用 [compact] + 固定纵向留白。
 *
 * @param icon 空态按内容使用搜索、提交或版本图标；错误态默认使用信息图标。
 * @param compact 紧凑档用于弹层和局部状态。
 */
@Composable
fun StatusPage(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isError: Boolean = false,
    compact: Boolean = false,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
) {
    val scheme = MiuixTheme.colorScheme
    val statusIcon = icon ?: if (isError) ImageVector.vectorResource(R.drawable.ic_info) else null
    val circle = if (compact) 40.dp else 48.dp
    val iconSize = if (compact) 18.dp else 22.dp
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (statusIcon != null) {
            Box(
                modifier = Modifier
                    .size(circle)
                    .clip(CircleShape)
                    .background(if (isError) scheme.errorContainer else scheme.secondaryVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = if (isError) scheme.onErrorContainer else scheme.onBackgroundVariant,
                    modifier = Modifier.size(iconSize),
                )
            }
            Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
        }
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {
                if (isError) liveRegion = LiveRegionMode.Polite
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = (if (compact) MiuixTheme.textStyles.body1 else MiuixTheme.textStyles.title4)
                    .copy(fontWeight = FontWeight.Medium, lineBreak = LineBreak.Heading),
                color = scheme.onBackground,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = if (compact) MiuixTheme.textStyles.footnote1 else MiuixTheme.textStyles.body2,
                    color = scheme.onBackgroundVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            SecondaryButton(text = actionLabel, onClick = onAction, enabled = actionEnabled)
        }
    }
}

/** 在列表末尾显示当前列表的数量与单位。 */
@Composable
fun FooterText(@PluralsRes countLabel: Int, count: Int, modifier: Modifier = Modifier, topPadding: Dp = 24.dp) {
    // 列表终点标记：文字两侧短线（dividerLine 与卡内分隔线同源）——印刷式「线·文·线」
    // 终点语言；保持最弱层级（footnote2 + 分隔线色）不与内容争夺注意力
    val scheme = MiuixTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding),
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
            text = pluralStringResource(countLabel, count, count),
            modifier = Modifier.weight(1f, fill = false),
            style = MiuixTheme.textStyles.footnote2,
            color = scheme.onBackgroundVariant,
            textAlign = TextAlign.Center,
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

private val BannerButtonInset = 7.dp

/** 首页和详情共用的刷新提示。已知恢复时间优先于缓存说明。 */
@Composable
fun ErrorBanner(
    error: SourceError,
    hasSavedDetails: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val limited = error as? SourceError.RateLimited
    val title = if (limited != null) stringResource(R.string.banner_rate_limit_title) else sourceErrorText(error)
    val hint = when {
        limited?.retryAt != null -> stringResource(R.string.banner_retry_after, relTimeUntilString(limited.retryAt))
        limited != null -> stringResource(R.string.status_error_subtitle)
        hasSavedDetails -> stringResource(R.string.banner_old_data_kept)
        else -> null
    }
    val panel = lerp(scheme.surfaceContainer, scheme.errorContainer, 0.5f)
    val buttonColor = lerp(panel, scheme.onErrorContainer, 0.09f)
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(panel, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 16.dp - BannerButtonInset, top = 8.dp, bottom = 8.dp),
    ) {
        // 两枚按钮各保留 48dp，大字体或窄窗时让正文独占一行。
        val minTextWidth = 156.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (maxWidth - 104.dp < minTextWidth) {
            Column(Modifier.fillMaxWidth()) {
                ErrorBannerMessage(title, hint, Modifier.padding(end = BannerButtonInset))
                ErrorBannerActions(onRetry, onDismiss, buttonColor, Modifier.align(Alignment.End))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ErrorBannerMessage(title, hint, Modifier.weight(1f).padding(end = 8.dp))
                ErrorBannerActions(onRetry, onDismiss, buttonColor)
            }
        }
    }
}

@Composable
private fun ErrorBannerMessage(title: String, hint: String?, modifier: Modifier) {
    val scheme = MiuixTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium, lineBreak = LineBreak.Heading),
            color = scheme.onErrorContainer,
        )
        if (hint != null) {
            Text(text = hint, style = MiuixTheme.textStyles.footnote1, color = scheme.onBackgroundVariant)
        }
    }
}

@Composable
private fun ErrorBannerActions(onRetry: () -> Unit, onDismiss: () -> Unit, color: Color, modifier: Modifier = Modifier) {
    Row(modifier) {
        ErrorBannerButton(R.drawable.ic_refresh, R.string.retry, color, onRetry)
        ErrorBannerButton(R.drawable.ic_close, R.string.banner_dismiss, color, onDismiss)
    }
}

@Composable
private fun ErrorBannerButton(icon: Int, label: Int, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    PressIconButton(
        onClick = onClick,
        enabled = enabled,
        contentDescription = stringResource(label),
        containerColor = color,
        tintInset = BannerButtonInset,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(icon),
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onErrorContainer.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(20.dp),
        )
    }
}
