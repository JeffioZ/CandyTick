package io.candy.tick.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.model.SyncFrequency
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.theme.MonoTokenStyle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val RowSideInset = 16.dp
private val IconChipSize = 30.dp

@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.padding(start = RowSideInset, top = 20.dp, bottom = 8.dp),
        )
        val motionEnabled = io.candy.tick.ui.common.rememberMotionEnabled()
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = if (motionEnabled) {
                    Modifier.animateContentSize(tween(Motion.PHASE, easing = Motion.Decelerate))
                } else Modifier,
                content = content,
            )
        }
    }
}

/** 分隔线从行标题起笔，末端与右侧值或开关对齐。 */
@Composable
internal fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = RowSideInset + IconChipSize + 12.dp, end = RowSideInset)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

/** 图标芯片（LibChecker 风格：实色圆角方块 + 白 glyph；end=4dp 配合 BasicComponent 默认 8dp 凑齐 12dp 图文间距） */
@Composable
private fun IconChip(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .size(IconChipSize)
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
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    valueMonospace: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    BasicComponent(
        modifier = modifier.fillMaxWidth(),
        title = title,
        summary = summary,
        startAction = { IconChip(icon = icon, tint = iconTint) },
        endActions = {
            if (trailing != null || value != null) Spacer(Modifier.width(12.dp))
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
            start = RowSideInset, end = RowSideInset, top = 12.dp, bottom = 12.dp,
        ),
    )
}

@Composable
internal fun SwitchRow(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    SettingRow(
        modifier = Modifier.switchSemantics(checked, enabled).alpha(if (enabled) 1f else 0.6f),
        title = title,
        summary = summary,
        icon = icon,
        iconTint = iconTint,
        onClick = if (enabled) { { onChange(!checked) } } else null,
        trailing = {
            Switch(
                checked = checked, onCheckedChange = onChange,
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics { },
            )
        },
    )
}

/** 读屏聚焦整行，同时保留开关本身的拖动手势。 */
internal fun Modifier.switchSemantics(checked: Boolean, enabled: Boolean = true): Modifier =
    semantics(mergeDescendants = true) {
        role = Role.Switch
        toggleableState = ToggleableState(checked)
        if (!enabled) disabled()
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
