package io.candy.tick.ui.store

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.candy.tick.ui.common.collectPressTint
import io.candy.tick.ui.common.rememberMotionEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val TabInset = 3.dp
private val TabTrackShape = RoundedCornerShape(16.dp)
private val TabThumbRadius = 16.dp - TabInset

/**
 * 与详情页 [io.candy.tick.ui.detail.DetailTabs] 统一风格的 3 槽滑动页签：
 * 接收外部共享时钟 [position]，让滑块与下方列表行同拍横向落位。
 */
@Composable
internal fun StoreTabs(
    selected: StoreFilterTab,
    position: State<Float>,
    labels: List<String>,
    onSelect: (StoreFilterTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val motion = rememberMotionEnabled()
    val tabs = StoreFilterTab.entries

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.secondaryVariant, TabTrackShape)
            .padding(horizontal = TabInset),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val count = tabs.size
                    if (count <= 0) return@drawBehind
                    val tabWidth = size.width / count
                    val start = tabWidth * position.value
                    val left = if (layoutDirection == LayoutDirection.Ltr) start else size.width - start - tabWidth
                    val inset = TabInset.toPx()
                    drawRoundRect(
                        color = scheme.surface,
                        topLeft = Offset(left, inset),
                        size = Size(tabWidth, size.height - 2 * inset),
                        cornerRadius = CornerRadius(TabThumbRadius.toPx()),
                    )
                }
                .selectableGroup(),
        ) {
            tabs.forEachIndexed { idx, tab ->
                val isSelected = tab == selected
                val source = remember { MutableInteractionSource() }
                val press = remember { Animatable(0f) }
                LaunchedEffect(source) { collectPressTint(source, press) {} }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = isSelected,
                            interactionSource = source,
                            indication = null,
                            role = Role.Tab,
                        ) {
                            if (!isSelected) onSelect(tab)
                        }
                        .padding(vertical = TabInset, horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = labels.getOrElse(idx) { "" },
                        color = if (isSelected) scheme.onSurface else scheme.onBackgroundVariant,
                        style = MiuixTheme.textStyles.body2.copy(
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer {
                            val amount = press.value
                            scaleX = if (motion) 1f - 0.035f * amount else 1f
                            scaleY = scaleX
                        },
                    )
                }
            }
        }
    }
}

/**
 * 内存级本机应用图标缓存：避免滚动、切页签或弹出浮层时反复通过 PackageManager 解码 Bitmap 导致掉帧。
 */
internal object StoreIconCache {
    private val cache = LruCache<String, ImageBitmap>(120)

    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(context: Context, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()?.also { bmp -> cache.put(packageName, bmp) }
        }
    }
}
