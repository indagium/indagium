@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.beer

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.*

// Beer time's scene band: the shelf with bottles and two mugs, the neon "IT'S BEER TIME" box and the clock, from the design's title bar
// (HolidayApp.dc.html, `is.beer`) at natural scale on its 1600 x 30 reference, right-aligned. Coordinates below are the design's.

private val SHELF = Rect(940f, 5f, 1500f, 30f)
private val SIGN = Rect(1160f, 5f, 1349f, 25f)
private val BAND_PARTS = listOf(HolidayBandPart("shelf", SHELF), HolidayBandPart("sign", SIGN))

private const val SHELF_X = 940f
private const val SHELF_WIDTH = 560f
private val BOTTLES = listOf(960f, 976f, 1424f, 1440f, 1456f)
private val MUGS = listOf(1010f, 1034f)
private val NEON_BOX = Rect(1160f, 6f, 1316f, 24f)
private const val NEON_TEXT_CENTER_X = 1238f
private const val NEON_BASELINE = 19f
private const val NEON_FONT_SIZE = 11f
private const val NEON_LETTER_SPACING = 1.5f
private const val CLOCK_X = 1340f
private const val CLOCK_Y = 15f
private const val CLOCK_R = 9f

private val bottleShape = parsePath("M0 25v-9q0-2.5 2.5-3.5v-3.5h3v3.5q2.5 1 2.5 3.5v9z")
private val mugFoamShape = parsePath("M0 0c0-2 2-2.6 3-1.6 1-1.6 3.6-1.6 4.4 0 1.2-1 3.4 0 3.4 1.6z")
private val mugHandleShape = parsePath("M0 0h2a2 2 0 0 1 2 2v1.6a2 2 0 0 1-2 2H0")
private val neonText by lazy { BeerTextPaths.outline("IT'S BEER TIME", BeerTypeface.ROUNDED, NEON_FONT_SIZE, NEON_LETTER_SPACING) }

private class BeerSceneColors(green: Boolean, dark: Boolean) {
    val wood = when {
        green -> if (dark) Color(0xFF24402c) else Color(0xFF8fae96)
        dark -> Color(0xFF4a2f16)
        else -> Color(0xFFa8743e)
    }
    val wood2 = when {
        green -> Color(0xFFc9cdd2)
        dark -> Color(0xFF6e4622)
        else -> Color(0xFFc79457)
    }
    val foam = if (!green && dark) Color(0xFFfff4dc) else Color.White
    val neon = beerNeon(green, dark)
}

internal class BeerScene(private val green: Boolean) : HolidayScenePainter {
    override val animated = true

    override fun layout(band: Rect): HolidaySceneLayout = HolidayBandLayout(HolidayBandSpace(band), BAND_PARTS)

    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) = drawFrame(scope, layout, dark, HOLIDAY_STILL_TIME)

    override fun drawFrame(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean, time: Float) {
        val l = layout as HolidayBandLayout
        val full = l.has("shelf")
        if (!full && !l.has("sign")) return
        val colors = BeerSceneColors(green, dark)
        l.space.drawElements(scope) {
            if (full) drawShelf(colors)
            drawNeonBox(colors, neonOpacity(time))
            drawClock(colors)
        }
    }
}

private fun DrawScope.drawShelf(c: BeerSceneColors) {
    drawRect(c.wood, Offset(SHELF_X, 25f), Size(SHELF_WIDTH, 5f))
    BOTTLES.forEachIndexed { index, x ->
        withTransform({ translate(x, 0f) }) {
            drawPath(bottleShape, if (index % 2 == 0) Color(0xFF2a8a38) else Color(0xFF1d6e2c))
            drawOval(Color(0xFFf4f6f3), Offset(1.2f, 17.4f), Size(5.6f, 4.4f))
            drawCircle(Color(0xFFe1252e), .8f, Offset(4f, 10.6f))
            drawRect(Color(0xFFc9cdd2), Offset(2.2f, 7.6f), Size(3.6f, 1.6f))
        }
    }
    MUGS.forEach { x ->
        drawRoundRect(Color(0xFFf2b33d), Offset(x, 15f), Size(11f, 10f), CornerRadius(1.2f))
        withTransform({ translate(x + 11f, 17.5f) }) { drawPath(mugHandleShape, c.foam, style = Stroke(1.2f)) }
        withTransform({ translate(x - .5f, 15.5f) }) { drawPath(mugFoamShape, c.foam) }
    }
}

private fun DrawScope.drawNeonBox(c: BeerSceneColors, flicker: Float) {
    val neon = c.neon.copy(alpha = flicker)
    drawRoundRect(neon, NEON_BOX.topLeft, NEON_BOX.size, CornerRadius(5f), style = Stroke(1.2f))
    withTransform({ translate(NEON_TEXT_CENTER_X - neonText.width / 2f, NEON_BASELINE) }) { drawPath(neonText.path, neon) }
}

private fun DrawScope.drawClock(c: BeerSceneColors) {
    val center = Offset(CLOCK_X, CLOCK_Y)
    drawCircle(c.foam, CLOCK_R, center)
    drawCircle(c.wood2, CLOCK_R, center, style = Stroke(1.4f))
    drawLine(c.wood, center, Offset(CLOCK_X, CLOCK_Y - 6.5f), 1.6f, StrokeCap.Round)
    drawLine(c.wood, center, Offset(CLOCK_X + 2.3f, CLOCK_Y + 3.9f), 1.6f, StrokeCap.Round)
}
