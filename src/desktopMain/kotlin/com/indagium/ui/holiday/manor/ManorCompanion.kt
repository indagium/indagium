// Literal paths, colours and coordinates from the Haunted manor Notes companion SVG.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.manor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.HolidayCompanionPainter
import com.indagium.ui.holiday.drawUnitEllipse
import com.indagium.ui.holiday.hex
import com.indagium.ui.holiday.parsePath
import com.indagium.ui.holiday.unitRadial
import kotlin.math.sin

private val glow = unitRadial(arrayOf(0f to hex(0xffd166, .55f), 1f to Color.Transparent))
private val brass = Brush.verticalGradient(0f to hex(0xe9c46a), 1f to hex(0x7a5a14), startY = 201f, endY = 227f)
private val flamePath = parsePath("M0-16c-6 9-9 15-9 20a9 9 0 0 0 18 0c0-5-3-11-9-20z")
private val innerFlame = parsePath("M0-4c-2 3-3 6-3 8a3 3 0 0 0 6 0c0-2-1-5-3-8z")
private val drips = parsePath(
    "M70 124q5 12 0 26 7-5 6-24zM96 123q-4 16 0 34-7-7-6-30zM106 94q5 18 0 36 8-8 7-34z" +
        "M136 94q-4 12 0 24-6-5-6-22zM146 150q4 10 0 20 6-4 6-18zM170 150q-3 14 0 26-6-6-6-24z",
)
private val spiderLegs = parsePath("M-6-2l-10-8-4 6M-6 2l-12 2-2 8M-5 5l-9 8-1 7M6-2l10-8 4 6M6 2l12 2 2 8M5 5l9 8 1 7")
private val ink = hex(0x1d1d1d)

internal object ManorCompanion : HolidayCompanionPainter {
    override val label = "Candles and a spider in the Notes corner"
    override val designWidthDp = 240f
    override val designHeightDp = 240f

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) { drawManorCompanion(t) }
}

internal fun DrawScope.drawManorCompanion(t: Float) {
    drawUnitEllipse(glow, 118f, 110f, 100f, 100f, .93f + .07f * sin(t * 2f))
    val bob = 3f * sin(t * .9f)
    drawLine(hex(0xc9d0c9), Offset(206f, -60f), Offset(206f, 58f + bob), 1f)
    withTransform({ translate(206f, 66f + bob) }) {
        drawPath(spiderLegs, ink, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawOval(ink, Offset(-8f, -7f), Size(16f, 18f))
        drawOval(hex(0x5c6660), Offset(-8f, -7f), Size(16f, 18f), style = Stroke(1f))
        drawCircle(ink, 5f, Offset(0f, -8f)); drawCircle(hex(0x5c6660), 5f, Offset(0f, -8f), style = Stroke(1f))
        drawCircle(hex(0xe5484d), 1.1f, Offset(-2f, -9f)); drawCircle(hex(0xe5484d), 1.1f, Offset(2f, -9f))
    }
    drawOval(brass, Offset(40f, 201f), Size(160f, 26f))
    drawOval(hex(0xf1d78a, .55f), Offset(50f, 203f), Size(140f, 16f))
    candle(70f, 120f, 26f, 92f); candle(106f, 90f, 30f, 122f); candle(146f, 146f, 24f, 66f)
    drawPath(drips, hex(0xfffaf0))
    drawOval(hex(0xfffaf0), Offset(70f, 116.4f), Size(26f, 7.2f))
    drawOval(hex(0xfffaf0), Offset(106f, 86f), Size(30f, 8f))
    drawOval(hex(0xfffaf0), Offset(146f, 142.6f), Size(24f, 6.8f))
    drawOval(hex(0xfff4dc), Offset(76f, 206f), Size(28f, 8f)); drawOval(hex(0xfff4dc), Offset(150f, 207f), Size(20f, 6f))
    for ((x, y) in listOf(83f to 120f, 121f to 90f, 158f to 146f)) drawLine(hex(0x2b2b2b), Offset(x, y), Offset(x, y - 7f), 1.6f, cap = StrokeCap.Round)
    drawFlame(83f, 100f, t); drawFlame(121f, 70f, t + .7f); drawFlame(158f, 126f, t + 1.4f)
}

private fun DrawScope.candle(x: Float, y: Float, width: Float, height: Float) {
    val wax = Brush.horizontalGradient(0f to hex(0xe6d8b8), .35f to hex(0xfff8e6), 1f to hex(0xcdbd98), startX = x, endX = x + width)
    drawRoundRect(wax, Offset(x, y), Size(width, height), CornerRadius(3f))
}

private fun DrawScope.drawFlame(x: Float, y: Float, t: Float) {
    val sy = .93f + .07f * sin(t * 2.3f)
    withTransform({ translate(x, y); scale(1f, sy, Offset.Zero) }) {
        // Gradient coordinates follow the flame shape, with a bright inner tongue.
        val brush = Brush.verticalGradient(0f to hex(0xff6a00), .45f to hex(0xffd23a), 1f to hex(0xfffbe0), startY = -16f, endY = 13f)
        drawPath(flamePath, brush)
        drawPath(innerFlame, hex(0xfffbe0))
    }
}
