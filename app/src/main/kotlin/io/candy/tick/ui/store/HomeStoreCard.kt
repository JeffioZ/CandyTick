package io.candy.tick.ui.store

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.download.SignatureVerdict
import io.candy.tick.data.model.StoreAppUpdate
import io.candy.tick.data.model.StorePreferences
import io.candy.tick.data.model.StoreState
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreSystemAppPolicy
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.common.PressableCard
import io.candy.tick.ui.common.relTimeString
import io.candy.tick.ui.common.rememberMotionEnabled
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页本机应用更新入口卡：
 * 1. 未开启任何渠道，或「仅发现新版时显示」已开启且暂无更新 → 自然折叠隐藏（0dp 零占位）；
 * 2. 有可更新应用 → 薄荷绿浅底汇总卡（复用 StoreIconCache，后退零重解码）；
 * 3. 关闭「仅发现新版时显示」且暂无更新 → 单行轻卡片。
 */
@Composable
fun HomeStoreCard(
    prefs: StorePreferences,
    storeState: StoreState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionable = remember(
        prefs.enabledStores,
        prefs.excludedPackages,
        prefs.skippedVersions,
        prefs.pinnedSources,
        storeState.updates,
        storeState.signatures,
    ) {
        val enabledSet = prefs.enabledStores.toSet()
        val deviceOemStore = StoreSystemAppPolicy.deviceOemStoreId()
        storeState.updates.values.mapNotNull { entry ->
            if (entry.packageName in prefs.excludedPackages) return@mapNotNull null
            val isAospPkg = StoreSystemAppPolicy.isAospSystemPackage(entry.packageName)
            val enabledCandidates = entry.candidates.filter { cand ->
                cand.storeId in enabledSet && (!isAospPkg || (deviceOemStore != null && cand.storeId == deviceOemStore))
            }
            if (enabledCandidates.isEmpty()) return@mapNotNull null
            val best = StoreCatalog.selectBestCandidate(
                candidates = enabledCandidates,
                enabledOrder = prefs.enabledStores,
                pinnedStoreId = prefs.pinnedSources[entry.packageName],
                verdictOf = { sid ->
                    when (storeState.signatures[storeState.signatureKey(entry.packageName, sid)]?.verdict) {
                        "MATCH" -> SignatureVerdict.MATCH
                        "DIFFERENT" -> SignatureVerdict.DIFFERENT
                        else -> SignatureVerdict.UNKNOWN
                    }
                },
            ) ?: return@mapNotNull null
            if (best.versionCode <= (prefs.skippedVersions[entry.packageName] ?: 0L)) return@mapNotNull null
            entry.copy(candidates = enabledCandidates)
        }
    }
    val visible = prefs.enabledStores.isNotEmpty() &&
        (actionable.isNotEmpty() || storeState.lastCheckedAt == null || !prefs.hideHomeCardWhenUpToDate)
    val motionEnabled = rememberMotionEnabled()

    // 退场折叠期间保留最后一次可见内容，避免收缩瞬间闪切成「均为最新」
    var lastActionable by remember { mutableStateOf(actionable) }
    if (visible) lastActionable = actionable
    val displayActionable = if (visible) actionable else lastActionable

    AnimatedVisibility(
        visible = visible,
        enter = if (motionEnabled) {
            expandVertically(
                animationSpec = tween(Motion.PANEL, easing = Motion.Decelerate),
                expandFrom = Alignment.Top,
            ) + fadeIn(tween(Motion.PHASE))
        } else EnterTransition.None,
        exit = if (motionEnabled) {
            shrinkVertically(
                animationSpec = tween(Motion.PANEL, easing = Motion.Decelerate),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(tween(Motion.MICRO))
        } else ExitTransition.None,
        label = "homeStoreCard",
    ) {
        AnimatedContent(
            targetState = displayActionable.isNotEmpty(),
            transitionSpec = {
                val spec = if (motionEnabled) tween<Float>(Motion.MICRO) else snap()
                fadeIn(spec) togetherWith fadeOut(spec)
            },
            label = "homeStoreCardMode",
            modifier = modifier,
        ) { hasUpdates ->
            if (hasUpdates) {
                UpdatableStoreCard(actionable = displayActionable, onClick = onClick)
            } else {
                UpToDateStoreCard(storeState = storeState, onClick = onClick)
            }
        }
    }
}

@Composable
private fun UpdatableStoreCard(
    actionable: List<StoreAppUpdate>,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scheme = MiuixTheme.colorScheme
    val previewPkgs = remember(actionable) { actionable.take(3).map { it.packageName } }
    val cachedIcons = remember(previewPkgs) { previewPkgs.map { StoreIconCache.peek(it) } }
    val icons by produceState<List<ImageBitmap?>>(cachedIcons, previewPkgs) {
        if (cachedIcons.all { it != null }) {
            value = cachedIcons
        } else {
            value = previewPkgs.map { pkg -> StoreIconCache.load(context, pkg) }
        }
    }
    val sourceNames = remember(actionable, resources) {
        actionable
            .flatMap { it.candidates }
            .map { it.storeId }
            .distinct()
            .take(3)
            .mapNotNull { id -> StoreCatalog.descriptorOf(id)?.let { resources.getString(it.nameRes) } }
            .joinToString(resources.getString(R.string.list_separator))
    }

    PressableCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            scheme.primary.copy(alpha = 0.14f),
                            scheme.surfaceContainer,
                        ),
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (previewPkgs.isNotEmpty()) {
                    Box(modifier = Modifier.width((36 + (previewPkgs.size - 1) * 20).dp)) {
                        previewPkgs.forEachIndexed { idx, _ ->
                            StoreAppIconSlot(
                                icon = icons.getOrNull(idx),
                                size = 36.dp,
                                corner = 10.dp,
                                modifier = Modifier.offset(x = (idx * 20).dp),
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pluralStringResource(R.plurals.store_home_updatable, actionable.size, actionable.size),
                        style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurfaceContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (sourceNames.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.store_home_sources_prefix, sourceNames),
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceContainerVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = scheme.onSurfaceContainerVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun UpToDateStoreCard(
    storeState: StoreState,
    onClick: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val lastChecked = storeState.lastCheckedAt
    val title = if (lastChecked != null) {
        stringResource(R.string.store_home_all_current)
    } else {
        stringResource(R.string.store_updates_title)
    }
    val sub = if (lastChecked != null) {
        stringResource(R.string.store_home_checked_rel, relTimeString(lastChecked))
    } else {
        stringResource(R.string.store_home_tap_to_check)
    }
    PressableCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium),
                    color = scheme.onSurfaceContainer,
                )
                Text(
                    text = sub,
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onSurfaceContainerVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.store_home_view_all),
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.onSurfaceContainerVariant,
                )
            }
        }
    }
}
