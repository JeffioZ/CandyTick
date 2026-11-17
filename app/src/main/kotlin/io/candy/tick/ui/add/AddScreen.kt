package io.candy.tick.ui.add

import io.candy.tick.util.CountFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.candy.tick.CandyTickApp
import io.candy.tick.R
import io.candy.tick.ui.common.AppPickerSheet
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.sourceErrorText
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

@Composable
fun AddScreen(
    onClose: () -> Unit,
    onAdded: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as CandyTickApp
    val vm: AddViewModel = viewModel { AddViewModel(app.container.repoRepository, app) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showAppPicker by remember { mutableStateOf(false) }

    // 每次进入添加页都从干净状态开始（VM 为 Activity 域单例）
    LaunchedEffect(Unit) { vm.reset() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is AddViewModel.Event.Added -> onAdded(event.repoId)
            }
        }
    }

    Scaffold(
             containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            SmallTopAppBar(
                color = androidx.compose.ui.graphics.Color.Transparent,
                title = stringResource(R.string.add_repo),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = top.yukonga.miuix.kmp.icon.MiuixIcons.Back,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            TextField(
                value = state.input,
                onValueChange = vm::onInput,
                label = stringResource(R.string.add_input_hint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.parseError) {
                Text(
                    text = stringResource(R.string.add_parse_error),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                )
            }

            when (val preview = state.preview) {
                is AddViewModel.PreviewState.Loading -> Text(
                    text = stringResource(R.string.loading),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                is AddViewModel.PreviewState.Failed -> Text(
                    text = sourceErrorText(preview.error),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                )
                is AddViewModel.PreviewState.Loaded -> PreviewCard(
                    state = state,
                    onLinkApp = { showAppPicker = true },
                    onConfirm = vm::confirmAdd,
                )
                AddViewModel.PreviewState.Idle -> Unit
            }

            if (state.searching) {
                Text(
                    text = stringResource(R.string.loading),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
            if (state.searchFailed) {
                Text(
                    text = stringResource(R.string.add_search_failed),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                )
            } else if (state.searched && !state.searching && state.results.isEmpty() &&
                state.preview is AddViewModel.PreviewState.Idle
            ) {
                Text(
                    text = stringResource(R.string.add_search_empty),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }

            if (state.results.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.results, key = { it.githubId }) { info ->
                        Card(modifier = Modifier.fillMaxWidth(), onClick = { vm.pickResult(info) }) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    text = "${info.owner}/${info.repo}",
                                    style = MiuixTheme.textStyles.subtitle,
                                    color = MiuixTheme.colorScheme.onBackground,
                                )
                                if (!info.description.isNullOrBlank()) {
                                    Text(
                                        text = info.description,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                                        maxLines = 2,
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.add_stars, CountFormat.format(info.stars)),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                )
                            }
                        }
                    }
                }
            } else if (state.preview is AddViewModel.PreviewState.Idle && !state.searching) {
                Spacer(Modifier.weight(1f))
            }
        }
    }

    AppPickerSheet(
        show = showAppPicker,
        onPick = { pkg, label ->
            vm.setLinkedApp(pkg, label)
            showAppPicker = false
        },
        onDismiss = { showAppPicker = false },
    )
}

@Composable
private fun PreviewCard(
    state: AddViewModel.UiState,
    onLinkApp: () -> Unit,
    onConfirm: () -> Unit,
) {
    val preview = state.preview as AddViewModel.PreviewState.Loaded
    val info = preview.info
    val scheme = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "${info.owner}/${info.repo}",
                style = MiuixTheme.textStyles.subtitle,
                color = scheme.onBackground,
            )
            if (!info.description.isNullOrBlank()) {
                Text(
                    text = info.description,
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onBackgroundVariant,
                )
            }
            Text(
                text = stringResource(R.string.add_stars, CountFormat.format(info.stars)),
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onBackgroundVariant,
            )
            Text(
                text = stringResource(R.string.add_license, info.license ?: "—"),
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onBackgroundVariant,
            )

            Spacer(Modifier.height(4.dp))
            TextButtonLike(
                text = state.linkedLabel?.let {
                    stringResource(R.string.add_linked_app_named, it)
                } ?: stringResource(R.string.add_link_app_optional),
                onClick = onLinkApp,
            )

            if (state.duplicate) {
                Text(
                    text = stringResource(R.string.add_duplicate),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onErrorContainer,
                )
            } else {
                PrimaryButton(
                    text = if (state.adding) stringResource(R.string.loading) else stringResource(R.string.add_confirm),
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.adding,
                )
            }
        }
    }
}

@Composable
private fun TextButtonLike(text: String, onClick: () -> Unit) {
    top.yukonga.miuix.kmp.basic.TextButton(
        text = text,
        onClick = onClick,
    )
}
