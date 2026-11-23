package io.candy.tick.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.rememberExternalUriHandler
import io.candy.tick.ui.common.PressableCard
import io.candy.tick.ui.common.RelTimeText
import io.candy.tick.ui.theme.MonoTokenStyle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 提交历史分页（到底自动加载；失败行内重试；尽头「已到底」） */
fun LazyListScope.commitList(
    state: DetailViewModel.UiState,
    contentModifier: Modifier,
    htmlBase: String,
    showPagingIndicator: Boolean,
    animateRefreshChanges: Boolean,
    onLoadMore: () -> Unit,
) {
    items(
        count = state.commits.size,
        key = { "commits:${state.commits[it].sha}" },
        contentType = { "commit" },
    ) { index ->
        val commit = state.commits[index]
        val title = remember(commit.message) { commitTitlePreview(commit.message) }
        val links = rememberExternalUriHandler()
        PressableCard(
            modifier = refreshItemMotion(animateRefreshChanges).then(contentModifier).fillMaxWidth(),
            // 点击跳 GitHub 查 diff/详情（GitHub app 同款延伸）；无浏览器时给出反馈而非静默
            onClick = { links.openUri("$htmlBase/commit/${commit.sha}") },
            onClickLabel = stringResource(R.string.open_in_browser),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shaColor = MiuixTheme.colorScheme.primaryVariant
                    val byline = remember(commit.sha, commit.author, shaColor) {
                        buildAnnotatedString {
                            withStyle(MonoTokenStyle.toSpanStyle().copy(color = shaColor)) {
                                append(commit.sha.take(7))
                            }
                            commit.author?.takeIf { it.isNotBlank() }?.let {
                                append("\u2002")
                                append(it)
                            }
                        }
                    }
                    Text(
                        text = byline,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    RelTimeText(
                        date = commit.date,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        style = MiuixTheme.textStyles.footnote2,
                    )
                }
                Text(
                    text = title,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onBackground,
                )
            }
        }
    }
    item(key = "commits-footer") {
        HistoryFooter(
            state = state,
            tab = DetailViewModel.DetailTab.COMMITS,
            displayedCount = state.commits.size,
            showPagingIndicator = showPagingIndicator,
            modifier = contentModifier,
            onLoadMore = onLoadMore,
        )
    }
}
