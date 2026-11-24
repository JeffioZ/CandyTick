package io.candy.tick.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.candy.tick.R
import io.candy.tick.data.AppStateStore
import io.candy.tick.data.AppStateStore.SaveStatus
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Keep unreadable data protected at startup and make failed writes recoverable from any screen. */
@Composable
internal fun StorageStatusHost(store: AppStateStore, content: @Composable () -> Unit) {
    val load by store.loadStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (load != AppStateStore.LoadStatus.READY) {
        val retrying = load == AppStateStore.LoadStatus.RETRYING
        StatusPage(
            title = stringResource(R.string.state_load_failed),
            subtitle = stringResource(R.string.state_load_hint),
            actionLabel = stringResource(if (retrying) R.string.loading else R.string.retry),
            onAction = { scope.launch { store.ensureLoaded() } },
            actionEnabled = !retrying,
            isError = true,
            modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).wrapContentHeight()
                .padding(vertical = 16.dp),
        )
        return
    }
    val status by store.saveStatus.collectAsStateWithLifecycle()
    val pending = status != SaveStatus.SAVED
    var minimized by rememberSaveable(pending) { mutableStateOf(false) }
    val showBanner = pending && minimized
    // Keep the exiting error surface stable until its animation finishes.
    var lastIssue by remember { mutableStateOf(SaveStatus.FAILED) }
    if (pending) SideEffect { lastIssue = status }
    val presented = if (pending) status else lastIssue
    val retrying = presented == SaveStatus.RETRYING
    val title = stringResource(if (presented == SaveStatus.RETRY_FAILED) R.string.save_retry_failed else R.string.save_failed)
    val message = if (retrying) stringResource(R.string.save_retrying) else title
    val confirmation = stringResource(R.string.changes_saved)
    val retry: () -> Unit = {
        scope.launch {
            if (store.retrySave()) Feedback.show(confirmation)
        }
    }

    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        AnimatedVisibility(
            visible = showBanner,
            enter = expandVertically(tween(Motion.PANEL), expandFrom = Alignment.Top) + fadeIn(tween(Motion.MICRO)),
            exit = shrinkVertically(tween(Motion.PANEL), shrinkTowards = Alignment.Top) + fadeOut(tween(Motion.MICRO)),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                InlineErrorRow(message, onRetry = retry, retryEnabled = showBanner && !retrying, showRetryLabel = true)
            }
        }
        Box(Modifier.fillMaxSize().recalculateWindowInsets()) { content() }
    }
    CandySheet(
        show = pending && !minimized,
        title = title,
        onDismissRequest = { minimized = true },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.save_failed_hint),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            PrimaryButton(
                text = stringResource(R.string.retry),
                onClick = retry,
                modifier = Modifier.fillMaxWidth(),
                enabled = pending && !minimized,
                busyText = if (retrying) stringResource(R.string.save_retrying) else null,
            )
            SecondaryButton(
                text = stringResource(R.string.save_retry_later),
                onClick = { minimized = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
