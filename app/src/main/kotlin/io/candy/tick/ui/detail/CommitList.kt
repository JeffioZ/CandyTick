package io.candy.tick.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.ui.common.RelTimeText
import io.candy.tick.ui.theme.MonoTokenStyle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector

/** 提交历史分页（到底自动加载；失败行内重试；尽头「已到底」） */
fun LazyListScope.commitList(
    state: DetailViewModel.UiState,
    contentModifier: Modifier,
    htmlBase: String,
    showPagingIndicator: Boolean,
    onLoadMore: () -> Unit,
) {
    items(
        count = state.commits.size,
        key = { "commits:${state.commits[it].sha}" },
        contentType = { "commit" },
    ) { index ->
        val commit = state.commits[index]
        val title = remember(commit.message) { commitTitlePreview(commit.message) }
        val context = androidx.compose.ui.platform.LocalContext.current
        // 组合期取串（lint LocalContextGetResourceValueCall）：回调里查资源不随配置刷新
        val openFailed = stringResource(io.candy.tick.R.string.open_failed)
        io.candy.tick.ui.common.PressableCard(
            // 点击跳 GitHub 查 diff/详情（GitHub app 同款延伸）；无浏览器时给出反馈而非静默
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("$htmlBase/commit/${commit.sha}"),
                        ),
                    )
                }.onFailure { io.candy.tick.ui.common.Feedback.show(text = openFailed, isError = true) }
            },
            modifier = contentModifier.fillMaxWidth(),
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
        CommitFooter(state, onLoadMore, showPagingIndicator, contentModifier)
    }
}

@Composable
private fun LazyItemScope.CommitFooter(
    state: DetailViewModel.UiState,
    onLoadMore: () -> Unit,
    showPagingIndicator: Boolean,
    contentModifier: Modifier,
) {
    val pageModifier = contentModifier.fillParentMaxHeight(0.45f)
    when {
        state.commitsReloading -> Unit // 首卡上方波点或下拉刷新头负责提示
        state.commitsLoading -> if (showPagingIndicator) DetailPagingIndicator()
        state.commitsFailed -> io.candy.tick.ui.common.StatusPage(
            icon = ImageVector.vectorResource(R.drawable.ic_close),
            isError = true,
            title = stringResource(R.string.load_failed),
            subtitle = stringResource(R.string.status_error_subtitle),
            actionLabel = stringResource(R.string.retry),
            onAction = onLoadMore,
            modifier = pageModifier.fillMaxWidth(),
        )
        !state.commitsEnd && state.commits.isNotEmpty() -> LaunchedEffect(state.commits.size) { onLoadMore() }
        state.commits.isEmpty() && state.commitsEnd -> io.candy.tick.ui.common.StatusPage(
            icon = ImageVector.vectorResource(R.drawable.ic_file),
            title = stringResource(R.string.detail_no_commits),
            modifier = pageModifier.fillMaxWidth(),
        )
        // 到底且有内容：终点提示（修复缺失——此前 when 链在此情形什么都不渲染）
        state.commits.isNotEmpty() -> io.candy.tick.ui.common.FooterText(
            R.plurals.footer_commits, state.commits.size, contentModifier,
        )
    }
}
