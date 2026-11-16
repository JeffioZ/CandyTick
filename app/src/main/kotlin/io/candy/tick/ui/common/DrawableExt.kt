package io.candy.tick.ui.common

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap

/** Drawable → ImageBitmap（避开 core-ktx 版本差异，自绘兜底） */
fun Drawable.toImageBitmap(): ImageBitmap {
    val width = intrinsicWidth.coerceAtLeast(1)
    val height = intrinsicHeight.coerceAtLeast(1)
    val bitmap = createBitmap(width, height)
    setBounds(0, 0, width, height)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
