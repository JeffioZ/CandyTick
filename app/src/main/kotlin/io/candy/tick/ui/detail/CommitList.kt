package io.candy.tick.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector

/** 提交历史分页（到底自动加载；失败行内重试；尽头「已到底」） */
fun LazyListScope.commitList(
    state: DetailViewModel.UiState,
    htmlBase: String,
    onLoadMore: () -> Unit,
) {
    items(count = state.commits.size, key = { state.commits[it].sha + it }) { index ->
        val commit = state.commits[index]
        val context = androidx.compose.ui.platform.LocalContext.current
        Card(
            modifier = Modifier.fillMaxWidth(),
            // 按压下沉：HyperOS 卡片惯例（Miuix Card 默认无反馈，需显式开启）
            pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
            // 点击跳 GitHub 查 diff/详情（GitHub app 同款延伸）
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("$htmlBase/commit/${commit.sha}"),
                        ),
                    )
                }
            },
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
            icon = ImageVector.vectorResource(R.drawable.ic_close),
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
            icon = ImageVector.vectorResource(R.drawable.ic_file),
            title = stringResource(R.string.detail_no_commits),
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
        )
        // 到底且有内容：终点提示（修复缺失——此前 when 链在此情形什么都不渲染）
        else -> io.candy.tick.ui.common.FooterText(text = stringResource(R.string.list_end))
    }
}
