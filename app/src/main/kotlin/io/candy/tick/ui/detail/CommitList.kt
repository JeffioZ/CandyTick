package io.candy.tick.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.File
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
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 提交历史分页（到底自动加载；失败行内重试；尽头「已到底」） */
fun LazyListScope.commitList(
    state: DetailViewModel.UiState,
    onLoadMore: () -> Unit,
) {
    items(count = state.commits.size, key = { state.commits[it].sha + it }) { index ->
        val commit = state.commits[index]
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
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
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            io.candy.tick.ui.common.LoadingDots(dotSize = 5.dp)
        }
        state.commitsFailed -> io.candy.tick.ui.common.StatusPage(
            icon = top.yukonga.miuix.kmp.icon.MiuixIcons.Close,
            isError = true,
            title = stringResource(R.string.load_failed),
            subtitle = stringResource(R.string.status_error_subtitle),
            actionLabel = stringResource(R.string.retry),
            onAction = onLoadMore,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
        )
        !state.commitsEnd -> LaunchedEffect(state.commits.size) { onLoadMore() }
        state.commits.isEmpty() -> io.candy.tick.ui.common.StatusPage(
            icon = top.yukonga.miuix.kmp.icon.MiuixIcons.File,
            title = stringResource(R.string.detail_no_commits),
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
        )
        // 到底且有内容：终点提示（修复缺失——此前 when 链在此情形什么都不渲染）
        else -> io.candy.tick.ui.common.FooterText(text = stringResource(R.string.list_end))
    }
}
