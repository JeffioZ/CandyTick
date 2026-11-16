package io.candy.tick.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.candy.tick.R
import io.candy.tick.ui.common.CandyIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 设置页（施工第 8 步实现完整内容，当前占位） */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.settings_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = CandyIcons.Back,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.coming_next_step),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
    }
}
