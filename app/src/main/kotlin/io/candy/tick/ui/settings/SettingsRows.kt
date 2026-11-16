package io.candy.tick.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.model.SyncFrequency
import io.candy.tick.ui.theme.MonoTokenStyle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页分组列表体系（HyperOS 设置观感）：
 * 组标题 13sp 次要色 → 16dp 圆角卡片 → 行内边距 16dp、单行高 ≈52dp → 行间 1dp 分隔线（与文字起点对齐）
 */

@Composable
internal fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
            )
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

/** 行间分隔线：起点与文字对齐（缩进 16dp），1dp */
@Composable
internal fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    valueMonospace: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onBackground,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            trailing != null -> trailing()
            value != null -> Text(
                text = value,
                style = if (valueMonospace) MonoTokenStyle else MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    summary: String? = null,
) {
    SettingRow(
        title = title,
        summary = summary,
        onClick = { onChange(!checked) }, // 整行可点，Switch 自身消费自己的点击
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
internal fun frequencyLabel(frequency: SyncFrequency): String = stringResource(
    when (frequency) {
        SyncFrequency.EVERY_6_HOURS -> R.string.frequency_6h
        SyncFrequency.EVERY_12_HOURS -> R.string.frequency_12h
        SyncFrequency.DAILY -> R.string.frequency_daily
    },
)

@Composable
internal fun FrequencyRow(
    option: SyncFrequency,
    current: SyncFrequency,
    onSelect: (SyncFrequency) -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                onSelect(option)
                onDone()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (option == current) {
                Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primaryVariant,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = frequencyLabel(option),
            style = MiuixTheme.textStyles.body1,
            color = if (option == current) MiuixTheme.colorScheme.primaryVariant else MiuixTheme.colorScheme.onBackground,
        )
    }
}
