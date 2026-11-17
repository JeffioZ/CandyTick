package io.candy.tick.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.RelTimeText
import io.candy.tick.ui.theme.MonoTokenStyle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 提交历史分页（到底自动加载；失败行内重试；尽头「已到底」） */
fun LazyListScope.commitList(
    state: DetailViewModel.UiState,
    onLoadMore: () -> Unit,
) {
    items(count = state.commits.size, key = { state.commits[it].sha + it }) { index ->
        val commit = state.commits[index]
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = commit.sha.take(7),
                        style = MonoTokenStyle,
                        color = MiuixTheme.colorScheme.primaryVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = commit.author ?: "",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    RelTimeText(
                        date = commit.date,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        style = MiuixTheme.textStyles.footnote2,
                    )
                }
                Text(
                    text = commit.message.lineSequence().firstOrNull().orEmpty(),
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onBackground,
                )
            }
        }
    }
    item(key = "commits-footer") {
        CommitFooter(state = state, onLoadMore = onLoadMore)
    }
}

@Composable
private fun CommitFooter(state: DetailViewModel.UiState, onLoadMore: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    when {
        state.commitsLoading -> androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            io.candy.tick.ui.common.LoadingDots(dotSize = 5.dp)
        }
        state.commitsFailed -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.load_failed),
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(text = stringResource(R.string.retry), onClick = onLoadMore)
        }
        !state.commitsEnd -> LaunchedEffect(state.commits.size) { onLoadMore() }
        state.commits.isEmpty() -> Text(
            text = stringResource(R.string.detail_no_commits),
            style = MiuixTheme.textStyles.footnote1,
            color = scheme.onBackgroundVariant,
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }
}
