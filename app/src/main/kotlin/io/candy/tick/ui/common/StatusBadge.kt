package io.candy.tick.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.SourceError
import io.candy.tick.data.model.RepoStatus
import io.candy.tick.ui.theme.LocalDarkTheme
import io.candy.tick.ui.theme.StatusAbandonedDark
import io.candy.tick.ui.theme.StatusAbandonedLight
import io.candy.tick.ui.theme.StatusActiveDark
import io.candy.tick.ui.theme.StatusActiveLight
import io.candy.tick.ui.theme.StatusArchivedDark
import io.candy.tick.ui.theme.StatusArchivedLight
import io.candy.tick.ui.theme.StatusColors
import io.candy.tick.ui.theme.StatusLowDark
import io.candy.tick.ui.theme.StatusLowLight
import io.candy.tick.ui.theme.StatusModerateDark
import io.candy.tick.ui.theme.StatusModerateLight
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun statusColorOf(status: RepoStatus): StatusColors? {
    val dark = LocalDarkTheme.current
    return when (status) {
        RepoStatus.ACTIVE -> if (dark) StatusActiveDark else StatusActiveLight
        RepoStatus.MODERATE -> if (dark) StatusModerateDark else StatusModerateLight
        RepoStatus.LOW -> if (dark) StatusLowDark else StatusLowLight
        RepoStatus.ABANDONED -> if (dark) StatusAbandonedDark else StatusAbandonedLight
        RepoStatus.ARCHIVED -> if (dark) StatusArchivedDark else StatusArchivedLight
        RepoStatus.UNKNOWN -> null
    }
}

@Composable
fun statusLabelOf(status: RepoStatus): String = stringResource(
    when (status) {
        RepoStatus.ACTIVE -> R.string.status_active
        RepoStatus.MODERATE -> R.string.status_moderate
        RepoStatus.LOW -> R.string.status_low
        RepoStatus.ABANDONED -> R.string.status_abandoned
        RepoStatus.ARCHIVED -> R.string.status_archived
        RepoStatus.UNKNOWN -> R.string.status_unknown
    },
)

/** 生命体征徽章：容器底 + 圆点 + 文字（深浅变体自动切换） */
@Composable
fun StatusBadge(status: RepoStatus, modifier: Modifier = Modifier) {
    val colors = statusColorOf(status) ?: return
    BadgeContainer(
        container = colors.badgeContainer,
        dot = colors.bar,
        text = statusLabelOf(status),
        textColor = colors.badgeText,
        modifier = modifier,
    )
}

/** 单仓库失效：黄色叹号角标（旧数据保留，详情页看具体错误） */
@Composable
fun InaccessibleBadge(modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val colors = if (dark) StatusLowDark else StatusLowLight
    BadgeContainer(
        container = colors.badgeContainer,
        dot = colors.bar,
        text = stringResource(R.string.repo_inaccessible),
        textColor = colors.badgeText,
        modifier = modifier,
    )
}

@Composable
private fun BadgeContainer(
    container: Color,
    dot: Color,
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            color = textColor,
            style = MiuixTheme.textStyles.footnote2,
        )
    }
}

/** 供横幅等场景显示四类失败文案 */
@Composable
fun sourceErrorText(error: SourceError): String = when (error) {
    is SourceError.NetworkUnreachable -> stringResource(R.string.err_network)
    is SourceError.Timeout -> stringResource(R.string.err_timeout)
    is SourceError.NotFound -> stringResource(R.string.err_not_found)
    is SourceError.RateLimited -> error.retryAt?.let {
        stringResource(R.string.err_rate_limited, relTimeUntilString(it))
    } ?: stringResource(R.string.err_rate_limited_plain)
    is SourceError.Http -> stringResource(R.string.err_http, error.code)
}
