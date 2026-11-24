package io.candy.tick.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val LinkActionSize = 48.dp

@Composable
internal fun AppLinkRow(
    appIcon: ImageBitmap?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    suggested: Boolean = false,
    iconPending: Boolean = false,
    onChangeApp: (() -> Unit)? = null,
    onUnlink: (() -> Unit)? = null,
    label: @Composable () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val surface by animateColorAsState(
        if (suggested) scheme.tertiaryContainer else scheme.surfaceContainer,
        tween(Motion.MICRO),
        label = "linkedAppSurface",
    )
    val (source, tint) = rememberRowPressTint(surface)
    val rowAction = if (onClick == null) Modifier else Modifier.clickable(
        interactionSource = source,
        indication = null,
        role = Role.Button,
        onClick = onClick,
    )
    Row(
        // 圆角同卡内其他色块（版本卡标题行、APK 行、首页展开行）：10dp
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tint)
            .then(rowAction).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(LinkActionSize), contentAlignment = Alignment.Center) {
            if (appIcon != null) {
                AppIconImage(appIcon, 22.dp, 6.dp)
            } else if (!iconPending) {
                LinkGlyph(R.drawable.ic_link)
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { label() }
        if (onChangeApp == null) {
            Box(Modifier.size(LinkActionSize), contentAlignment = Alignment.Center) {
                LinkGlyph(R.drawable.ic_chevron_right)
            }
        } else {
            PressIconButton(
                onClick = onChangeApp,
                contentDescription = stringResource(
                    if (suggested) R.string.detail_link_app_other else R.string.detail_link_change,
                ),
                tintInset = 6.dp,
                containerColor = surface,
            ) { LinkGlyph(if (suggested) R.drawable.ic_more else R.drawable.ic_link) }
        }
        if (onUnlink != null) {
            PressIconButton(
                onClick = onUnlink,
                contentDescription = stringResource(R.string.detail_unlink),
                tintInset = 6.dp,
                containerColor = surface,
            ) {
                LinkGlyph(R.drawable.ic_unlink)
            }
        }
    }
}

@Composable
private fun LinkGlyph(icon: Int) {
    Icon(
        imageVector = ImageVector.vectorResource(icon),
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier.size(14.dp),
    )
}
