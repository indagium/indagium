// Paths, colors and positions are from the supplied Trick or treat SVG boards.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.candy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.*
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

private val iron = Brush.horizontalGradient(listOf(hex(0x5c636d), hex(0x2b3036), hex(0x15181c)), 48f, 252f)
private val rim = Brush.verticalGradient(listOf(hex(0x8b939d), hex(0x2b3036)), 281f, 319f)
private val hat = Brush.linearGradient(listOf(hex(0xa78bfa), hex(0x4c1d95)), Offset(18f, 180f), Offset(108f, 304f))
private val pail = Brush.verticalGradient(0f to hex(0xffb453), .55f to hex(0xf07a16), 1f to hex(0xa9440a), startY = 118f, endY = 226f)
private val potion = unitRadial(arrayOf(0f to hex(0xd4ff9e), .5f to hex(0x5fdc5a), 1f to hex(0x1f8f3a)))
private val bubbles = unitRadial(arrayOf(0f to hex(0xeaffd8), .5f to hex(0x8af07a), 1f to hex(0x2fb34a)))
private val shadow = unitRadial(arrayOf(0f to hex(0x000000, .35f), 1f to Color.Transparent))
private val aura = unitRadial(arrayOf(0f to hex(0x8af07a, .45f), 1f to Color.Transparent))
private val white = hex(0xfff6e8)
private val ink = hex(0x4a1e05)
private val orange = hex(0xff8a2a)
private val purple = hex(0x9b4dff)

// Paths are immutable and reused by the animated picture, companion and candy fields on every frame.
private val paths = java.util.concurrent.ConcurrentHashMap<String, Path>()

private fun path(data: String): Path = paths.getOrPut(data) { parsePath(data) }

private fun DrawScope.stroke(data: String, color: Color, width: Float) = drawPath(path(data), color, style = Stroke(width, cap = StrokeCap.Round))

private fun phase(t: Float, period: Float): Float = (t / period - floor(t / period)).coerceIn(0f, 1f)

private fun bob(t: Float, offset: Float = 0f): Float = -5f * (1f - kotlin.math.cos((t + offset) * 2f * PI.toFloat() / 4.2f))

/** Wrapped sweet from the picture and companion boards, in its original 72 x 30 coordinates. */
internal fun DrawScope.drawWrappedCandy() {
    drawPath(path("M-20 0l-16-12v24zM20 0l16-12v24z"), orange)
    drawPath(path("M-20 0l-16-12v6zM20 0l16-12v6z"), hex(0xffb066))
    drawOval(purple, Offset(-22f, -15f), Size(44f, 30f))
    stroke("M-12-12l8 24M0-15l8 28M12-11l-5 20", hex(0xd8b4fe, .75f), 4f)
    drawOval(hex(0xffffff, .55f), Offset(-16f, -10.5f), Size(18f, 7f))
}

internal fun DrawScope.drawCandyCorn() {
    drawPath(path("M-15-12Q0-19 15-12L10-3Q0-7-10-3z"), hex(0xffd23f))
    drawPath(path("M-10-3Q0-7 10-3L5 7Q0 5-5 7z"), orange)
    drawPath(path("M-5 7Q0 5 5 7L0 17z"), white)
    stroke("M-11-11q4-2 8-2", hex(0xffffff, .7f), 2f)
}

private fun DrawScope.candyAt(x: Float, y: Float, angle: Float, scale: Float = 1f, corn: Boolean = false) {
    withTransform({ translate(x, y); rotate(angle, Offset.Zero); scale(scale, scale, Offset.Zero) }) {
        if (corn) drawCandyCorn() else drawWrappedCandy()
    }
}

private fun DrawScope.lollipop(x: Float, y: Float, radius: Float, t: Float) {
    withTransform({ translate(x, y); rotate(t * 30f, Offset.Zero) }) {
        drawCircle(hex(0xec4899), radius, Offset.Zero)
        val spiral = Path().apply {
            val points = 140
            for (i in 0..points) {
                val a = i * 2f * PI.toFloat() * 4f / points
                val r = radius * .78f * i / points
                val px = kotlin.math.cos(a) * r; val py = sin(a) * r
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        drawPath(spiral, hex(0xffe4f1), style = Stroke(radius * .16f, cap = StrokeCap.Round))
    }
}

internal object CandyPicture : HolidayPicturePainter {
    override val designWidthDp = 300f
    override val designHeightDp = 420f

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        drawUnitEllipse(shadow, 150f, 396f, 130f, 14f)
        drawUnitEllipse(aura, 150f, 292f, 120f, 60f, .9f + .1f * sin(t))
        translate(3f * sin(t * .8f), 0f) { stroke("M120 270c-10-14 10-22 0-38s8-24 0-40", hex(0xe8ffe0, .5f), 5f) }
        translate(-3f * sin(t * .7f), 0f) { stroke("M178 268c10-14-10-22 0-38s-8-24 0-40", hex(0xe8ffe0, .45f), 4f) }
        lollipop(222f, 178f, 28f, t)
        drawOval(hex(0xffffff, .45f), Offset(205f, 163f), Size(18f, 10f))
        stroke("M216 206l-22 86", hex(0xf3eadc), 6f)
        drawPath(path("M48 300c0 54 36 84 102 84s102-30 102-84z"), iron)
        stroke("M68 318c4 28 22 48 48 56", hex(0x9aa3ad, .45f), 5f)
        drawPath(path("M80 372l-12 20h20zM220 372l12 20h-20z"), hex(0x1c1f24))
        drawOval(rim, Offset(42f, 281f), Size(216f, 38f))
        drawUnitEllipse(potion, 150f, 300f, 96f, 13f)
        listOf(Triple(112f, 297f, 9f), Triple(132f, 301f, 6f), Triple(186f, 298f, 7f)).forEach { (x, y, r) -> drawUnitEllipse(bubbles, x, y, r, r) }
        listOf(Triple(122f, 292f, 5f), Triple(160f, 294f, 4f), Triple(196f, 292f, 3.5f)).forEachIndexed { i, (x, y, r) ->
            val u = phase(t + i * 1.1f, 3.3f); drawUnitEllipse(bubbles, x, y - u * 36f, r * (1f - u * .35f), r * (1f - u * .35f), 1f - u)
        }
        candyAt(146f, 286f, -24f); candyAt(176f, 282f, 18f, corn = true)
        withTransform({ translate(30f, 0f); rotate(-14f, Offset(62f, 304f)) }) {
            drawPath(path("M18 304Q40 236 74 180Q84 232 108 304z"), hat)
            stroke("M74 180q14 4 22 18", hex(0x4c1d95), 5f)
            drawOval(hex(0x5b21b6), Offset(4f, 295f), Size(120f, 22f))
            stroke("M26 292h76", orange, 8f)
            drawRect(hex(0xffd23f), Offset(56f, 285f), Size(14f, 14f), style = Stroke(2.6f))
            stroke("M40 254q10-30 26-54", hex(0xc4b5fd, .6f), 3f)
        }
        candyAt(36f, 386f, 12f, corn = true); candyAt(258f, 388f, -8f, .85f); candyAt(278f, 368f, 30f, .8f, true)
        candyAt(82f, 118f + bob(t), -20f); candyAt(160f, 96f + bob(t, 1.8f), 14f, corn = true)
        candyAt(40f, 190f + bob(t, .9f), 30f, .7f, true)
    }
}

internal object CandyPail : HolidayCompanionPainter {
    override val label = "Treat pail in the Notes corner"
    override val designWidthDp = 240f
    override val designHeightDp = 240f

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        drawUnitEllipse(shadow, 120f, 230f, 80f, 8f)
        stroke("M56 120c0-80 128-80 128 0", hex(0x2b2b2b), 5f)
        drawOval(ink, Offset(58f, 110f), Size(124f, 16f))
        stroke("M146 118l14-56", hex(0xf3eadc), 5f)
        lollipop(162f, 54f, 18f, t)
        candyAt(94f, 112f, -18f, .8f); candyAt(126f, 106f, 14f, corn = true); candyAt(78f, 114f, 30f, .8f, true)
        drawPath(path("M56 118h128l-12 98a12 12 0 0 1-12 10H80a12 12 0 0 1-12-10z"), pail)
        stroke("M54 118a66 10 0 0 0 132 0", hex(0xffc46b), 5f)
        stroke("M70 132c-2 30 2 56 8 80", hex(0xffd08a, .5f), 4f)
        drawPath(path("M86 152l12-17 12 17zM130 152l12-17 12 17z"), ink)
        drawPath(path("M84 170q36 24 72 0q-8 24-36 26-28-2-36-26z"), ink)
        drawPath(path("M103 176h9v8h-9zM128 176h9v8h-9z"), hex(0xf07a16))
        candyAt(40f, 60f + bob(t), -20f, corn = true)
        candyAt(206f, 128f + bob(t, 1.8f), 16f, .75f)
    }
}
