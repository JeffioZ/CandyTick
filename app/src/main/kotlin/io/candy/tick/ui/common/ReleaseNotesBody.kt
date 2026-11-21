package io.candy.tick.ui.common

import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import io.candy.tick.R
import io.candy.tick.util.ReleaseNotes
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 展开的发行记录与自身更新弹窗共用正文排版、链接语义及完整日志入口。 */
@Composable
internal fun ReleaseNotesBody(
    markdown: String,
    repoSlug: String?,
    repoUrl: String,
    tag: String,
    maxHeight: Dp,
    spaceAfter: Dp,
    showSource: Boolean = true,
    scrollState: ScrollState = rememberScrollState(),
) {
    val scheme = MiuixTheme.colorScheme
    val notes = remember(markdown, repoSlug, scheme.primaryVariant) {
        ReleaseNotes.annotated(markdown, scheme.primaryVariant, repoSlug)
    }
    val uriHandler = LocalUriHandler.current
    Column {
        BasicText(
            text = notes,
            style = MiuixTheme.textStyles.body2.copy(color = scheme.onBackground),
            modifier = Modifier.heightIn(max = maxHeight).verticalScroll(scrollState),
        )
        if (showSource || ReleaseNotes.isPreview(markdown)) {
            LinkText(
                text = stringResource(R.string.release_view_on_github),
                showIcon = true,
                spaceAfter = spaceAfter,
                onClick = { runCatching { uriHandler.openUri("$repoUrl/releases/tag/${Uri.encode(tag)}") } },
            )
        }
    }
}
