@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.indagium.ui.holiday.cozy.COZY_COMPANION
import com.indagium.ui.holiday.cozy.COZY_PICTURE
import com.indagium.ui.holiday.cozy.COZY_TITLE
import com.indagium.ui.holiday.devday.DEV_DAY_COMPANION
import com.indagium.ui.holiday.devday.DEV_DAY_PICTURE
import com.indagium.ui.holiday.devday.DEV_DAY_TITLE
import com.indagium.ui.holiday.newyear.NEW_YEAR_COMPANION
import com.indagium.ui.holiday.newyear.NEW_YEAR_PICTURE
import com.indagium.ui.holiday.newyear.NEW_YEAR_TITLE
import com.indagium.ui.holiday.retro.RETRO_COMPANION
import com.indagium.ui.holiday.retro.RETRO_PICTURE
import com.indagium.ui.holiday.retro.RETRO_TITLE
import com.indagium.ui.holiday.snow.FIRST_SNOW_COMPANION
import com.indagium.ui.holiday.snow.FIRST_SNOW_PICTURE
import com.indagium.ui.holiday.snow.FIRST_SNOW_TITLE
import com.indagium.ui.holiday.xmas.XMAS_COMPANION
import com.indagium.ui.holiday.xmas.XMAS_PICTURE
import com.indagium.ui.holiday.xmas.XMAS_TITLE

/** Draws [draw] into a fresh transparent [w] x [h] bitmap at [density] (1 design unit = [density] pixels is the caller's business). */
internal fun renderBitmap(w: Int, h: Int, density: Float = 1f, draw: DrawScope.() -> Unit): ImageBitmap = ImageBitmap(w, h).also {
    CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(it), Size(w.toFloat(), h.toFloat()), draw)
}

/** The bounding box of the pixels that differ between [a] and [b] (same size), or null when they are identical. */
internal fun differingBounds(a: ImageBitmap, b: ImageBitmap): Rect? {
    val pa = a.toPixelMap()
    val pb = b.toPixelMap()
    var left = a.width
    var top = a.height
    var right = -1
    var bottom = -1
    for (y in 0 until a.height) {
        for (x in 0 until a.width) {
            if (pa[x, y].toArgb() != pb[x, y].toArgb()) {
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
    }
    return if (right < 0) null else Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
}

/** How many pixels of [image] are not fully transparent. */
internal fun paintedPixels(image: ImageBitmap): Int {
    val pixels = image.toPixelMap()
    var n = 0
    for (y in 0 until image.height) for (x in 0 until image.width) if (pixels[x, y].alpha > 0f) n++
    return n
}

/** The generated art of one of the six packs the layered painter serves. */
internal class GeneratedArt(
    val folder: String,
    val picture: List<HolidayArtLayer>,
    val companion: List<HolidayArtLayer>,
    val title: List<HolidayBandItem>,
)

internal val GENERATED_ARTS: List<GeneratedArt> = listOf(
    GeneratedArt("snow", FIRST_SNOW_PICTURE, FIRST_SNOW_COMPANION, FIRST_SNOW_TITLE),
    GeneratedArt("cozy", COZY_PICTURE, COZY_COMPANION, COZY_TITLE),
    GeneratedArt("xmas", XMAS_PICTURE, XMAS_COMPANION, XMAS_TITLE),
    GeneratedArt("newyear", NEW_YEAR_PICTURE, NEW_YEAR_COMPANION, NEW_YEAR_TITLE),
    GeneratedArt("retro", RETRO_PICTURE, RETRO_COMPANION, RETRO_TITLE),
    GeneratedArt("devday", DEV_DAY_PICTURE, DEV_DAY_COMPANION, DEV_DAY_TITLE),
)

/** Every layer of [this] art: picture, companion and both palettes of every title item. */
internal fun GeneratedArt.allLayers(): List<HolidayArtLayer> = picture + companion + title.flatMap { listOf(it.dark, it.light) }
