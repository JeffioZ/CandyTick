package io.candy.tick.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.model.SyncFrequency
import io.candy.tick.ui.theme.MonoTokenStyle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页分组列表体系：基于 Miuix BasicComponent 构建（不自绘行布局），
 * 只补两层薄封装：图标芯片 + 等宽 value。
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

/** 图标芯片（LibChecker 风格：实色圆角方块 + 白 glyph） */
@Composable
private fun IconChip(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 设置行：BasicComponent + 图标芯片 + 等宽 value 支持 */
@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    valueMonospace: Boolean = false,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    BasicComponent(
        modifier = modifier.fillMaxWidth(),
        title = title,
        summary = summary,
        startAction = icon?.let {
            { IconChip(icon = it, tint = iconTint) }
        },
        endActions = {
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
        },
        onClick = onClick,
        insideMargin = PaddingValues(
            start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp,
        ),
    )
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
) {
    SettingRow(
        title = title,
        summary = summary,
        icon = icon,
        iconTint = iconTint,
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
internal fun frequencyLabel(frequency: SyncFrequency): String = stringResource(
    when (frequency) {
        SyncFrequency.ON_OPEN -> R.string.frequency_on_open
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
    OptionRow(frequencyLabel(option), option == current) {
        onSelect(option); onDone()
    }
}

/** 选择行（底部弹层用）：BasicComponent + Check 图标 */
@Composable
internal fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    BasicComponent(
        modifier = Modifier.fillMaxWidth(),
        title = label,
        endActions = {
            if (selected) {
                Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primaryVariant,
                )
            }
        },
        onClick = onClick,
        insideMargin = PaddingValues(
            start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp,
        ),
    )
}
