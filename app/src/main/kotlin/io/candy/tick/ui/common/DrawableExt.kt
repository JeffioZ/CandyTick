package io.candy.tick.ui.common

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt

/** Use the largest display slot as the allocation ceiling, including drawables without an intrinsic size. */
internal fun Drawable.toImageBitmap(maxSizePx: Int): ImageBitmap {
    val sourceWidth = intrinsicWidth.takeIf { it > 0 } ?: maxSizePx
    val sourceHeight = intrinsicHeight.takeIf { it > 0 } ?: maxSizePx
    val scale = minOf(1f, maxSizePx.toFloat() / maxOf(sourceWidth, sourceHeight))
    val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    val bitmap = createBitmap(width, height)
    val previousBounds = copyBounds()
    try {
        setBounds(0, 0, width, height)
        draw(Canvas(bitmap))
    } finally {
        bounds = previousBounds
    }
    return bitmap.asImageBitmap()
}
