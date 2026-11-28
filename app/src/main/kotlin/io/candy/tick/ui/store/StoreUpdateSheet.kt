package io.candy.tick.ui.store

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.model.StoreSignatureRecord
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.ui.common.AppIconImage
import io.candy.tick.ui.common.CandySheet
import io.candy.tick.ui.common.LinkText
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.sheetCardColor
import io.candy.tick.ui.common.sheetDividerColor
import io.candy.tick.ui.theme.MonoTokenStyle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 固定尺寸应用图标槽：解码未完成前占稳空间显示柔和占位底，解码完成即展示真实图标，消除列表与浮层横向跳动。
 */
@Composable
internal fun StoreAppIconSlot(
    icon: ImageBitmap?,
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(scheme.surfaceVariant.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            AppIconImage(bitmap = icon, size = size, corner = corner)
        }
    }
}

/**
 * 本机应用浮层共享头部（可更新浮层 / 已安装浮层 100% 对齐）：
 * 左侧 44dp 圆角应用图标，右侧应用名称 + 包名。
 */
@Composable
internal fun StoreSheetAppHeader(
    icon: ImageBitmap?,
    displayName: String,
    packageName: String,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        StoreAppIconSlot(icon = icon, size = 44.dp, corner = 12.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MiuixTheme.textStyles.title4,
                color = scheme.onSurfaceContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = packageName,
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceContainerVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 本机应用浮层共享信息胶囊条（对齐全局 UpdateSheet 去线框柔和实色层）。
 */
@Composable
internal fun StoreSheetInfoBar(
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(sheetCardColor())
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content,
    )
}

/**
 * 浮层底部共享轻量化次级链接行（与空态「导入订阅」同款 LinkText，居中并排、淡竖线分隔）。
 */
@Composable
internal fun StoreSheetLinkActions(
    leftText: String,
    onLeftClick: () -> Unit,
    rightText: String,
    onRightClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LinkText(
            text = leftText,
            onClick = onLeftClick,
            alignment = Alignment.Center,
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .width(1.dp)
                .height(12.dp)
                .background(sheetDividerColor()),
        )
        LinkText(
            text = rightText,
            onClick = onRightClick,
            alignment = Alignment.Center,
        )
    }
}

/**
 * 本机应用更新确认弹层（对齐全局 UpdateSheet 审美与动效）：
 * - `show` 直通 [CandySheet]（常驻组合树），关闭时播放完整液态玻璃退场动画；
 * - 去线框柔和实色层（sheetCardColor）、单行版本胶囊条、统一勾选态，避免粗描边和底部拥挤。
 */
@Composable
internal fun StoreUpdateSheet(
    show: Boolean,
    item: StoreUpdateRowItem?,
    icon: ImageBitmap?,
    signatures: Map<String, StoreSignatureRecord>,
    shouldPromptMetered: (Long) -> Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit = {},
    onDownload: (StoreReleaseInfo) -> Unit,
    onPinSource: (String) -> Unit,
    onSkipVersion: (Long) -> Unit,
    onUnskipVersion: () -> Unit,
    onExcludeApp: () -> Unit,
) {
    // 退场动画期间保留最后一次展示的应用与图标，防止下滑瞬间内容变空
    var cachedItem by remember { mutableStateOf(item) }
    var cachedIcon by remember { mutableStateOf(icon) }
    if (show && item != null) {
        cachedItem = item
        if (icon != null || cachedItem?.local?.packageName != item.local.packageName) {
            cachedIcon = icon
        }
    }
    val current = if (show && item != null) item else cachedItem
    val displayIcon = if (show && icon != null) icon else cachedIcon

    CandySheet(
        show = show && current != null,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
    ) {
        if (current == null) return@CandySheet
        val candidates = remember(current.local.packageName, current.update?.candidates) {
            current.update?.candidates.orEmpty().sortedByDescending { it.versionCode }
        }
        var selectedStoreId by remember(current.local.packageName, current.best?.storeId) {
            mutableStateOf(current.best?.storeId ?: candidates.firstOrNull()?.storeId.orEmpty())
        }
        var askMeteredConfirm by remember(current.local.packageName, selectedStoreId, show) { mutableStateOf(false) }

        val selected = candidates.firstOrNull { it.storeId == selectedStoreId } ?: current.best ?: return@CandySheet
        val context = LocalContext.current
        val scheme = MiuixTheme.colorScheme
        val sizeText = if (selected.sizeBytes > 0L) Formatter.formatShortFileSize(context, selected.sizeBytes) else ""
        val selectedSig = signatures["${current.local.packageName}|${selected.storeId}"]?.verdict

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. 头部：应用图标 + 名称 + 包名（与已安装浮层共用组件）
            StoreSheetAppHeader(
                icon = displayIcon,
                displayName = current.local.displayName,
                packageName = current.local.packageName,
            )

            // 2. 统一信息卡：新版本号优先展示（不被长旧版本号截断）+ 当前版本/单源信息 + 可滚更新日志
            val singleStoreName = if (candidates.size == 1) {
                StoreCatalog.descriptorOf(selected.storeId)?.let { stringResource(it.nameRes) } ?: selected.storeId
            } else ""
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(sheetCardColor()),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = selected.versionName,
                            style = MonoTokenStyle.copy(fontWeight = FontWeight.Bold),
                            color = scheme.primaryVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (sizeText.isNotEmpty()) {
                            Text(
                                text = sizeText,
                                style = MiuixTheme.textStyles.footnote2.copy(fontFeatureSettings = "tnum"),
                                color = scheme.onBackgroundVariant,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    val currentLine = stringResource(R.string.store_sheet_current_ver, current.local.versionName) +
                        if (singleStoreName.isNotBlank()) " · $singleStoreName" else ""
                    Text(
                        text = currentLine,
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceContainerVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected.changelog.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .height(1.dp)
                            .background(sheetDividerColor()),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    ) {
                        Text(
                            text = selected.changelog.trim(),
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceContainerVariant,
                        )
                    }
                }
            }

            // 3. 多渠道切换卡片（仅 >= 2 个来源时展示，单源不额外多画框）
            if (candidates.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.store_sheet_choose_source),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceContainerVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(sheetCardColor()),
                    ) {
                        candidates.forEachIndexed { idx, cand ->
                            if (idx > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp)
                                        .height(1.dp)
                                        .background(sheetDividerColor()),
                                )
                            }
                            val isSelected = cand.storeId == selected.storeId
                            val desc = StoreCatalog.descriptorOf(cand.storeId)
                            val storeName = desc?.let { stringResource(it.nameRes) } ?: cand.storeId
                            val sigRecord = signatures["${current.local.packageName}|${cand.storeId}"]?.verdict
                            val compatText = when (sigRecord) {
                                "MATCH" -> stringResource(R.string.store_sig_compatible)
                                "DIFFERENT" -> stringResource(R.string.store_sig_different_short)
                                else -> stringResource(R.string.store_sig_verify_after_download)
                            }
                            val candSize = if (cand.sizeBytes > 0L) Formatter.formatShortFileSize(context, cand.sizeBytes) + " · " else ""
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.RadioButton) {
                                        selectedStoreId = cand.storeId
                                        onPinSource(cand.storeId)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "$storeName · ${cand.versionName}",
                                        style = MiuixTheme.textStyles.body2.copy(
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        color = if (isSelected) scheme.primaryVariant else scheme.onSurfaceContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = candSize + compatText,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = if (sigRecord == "DIFFERENT") scheme.error else scheme.onSurfaceContainerVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_check_circle),
                                        contentDescription = null,
                                        tint = scheme.primaryVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (askMeteredConfirm) {
                val confirmText = if (sizeText.isNotBlank()) {
                    stringResource(R.string.store_metered_confirm, sizeText)
                } else {
                    stringResource(R.string.store_metered_confirm_unknown)
                }
                Text(
                    text = confirmText,
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // 4. 主按钮 + 轻量化次级链接（与空态「导入订阅」同款 LinkText）
            val isIncompatible = selectedSig == "DIFFERENT"
            val btnLabel = when {
                isIncompatible -> stringResource(R.string.store_sig_different_short)
                askMeteredConfirm -> stringResource(R.string.store_metered_continue)
                sizeText.isNotBlank() -> stringResource(R.string.store_download_and_install_size, sizeText)
                else -> stringResource(R.string.store_download_and_install)
            }
            PrimaryButton(
                text = btnLabel,
                enabled = !isIncompatible,
                onClick = {
                    if (!askMeteredConfirm && shouldPromptMetered(selected.sizeBytes)) {
                        askMeteredConfirm = true
                    } else {
                        onDownload(selected)
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            StoreSheetLinkActions(
                leftText = if (current.isSkipped) {
                    stringResource(R.string.store_unskip_version)
                } else {
                    stringResource(R.string.store_skip_version)
                },
                onLeftClick = {
                    if (current.isSkipped) {
                        onUnskipVersion()
                    } else {
                        onSkipVersion(selected.versionCode)
                    }
                    onDismiss()
                },
                rightText = stringResource(R.string.store_exclude_app),
                onRightClick = {
                    onExcludeApp()
                    onDismiss()
                },
            )
        }
    }
}
