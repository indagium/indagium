// SVG path data and coordinates are the approved design canvas' illustration values (a 300 x 420 picture), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.pumpkin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.*
import com.indagium.ui.holiday.ArtPath
import com.indagium.ui.holiday.HolidayPicturePainter
import com.indagium.ui.holiday.boxRadialBrush
import com.indagium.ui.holiday.diagonalBrush
import com.indagium.ui.holiday.drawOvalFill
import com.indagium.ui.holiday.drawUnitEllipse
import com.indagium.ui.holiday.fillElliptical
import com.indagium.ui.holiday.hex
import com.indagium.ui.holiday.ovalPath
import com.indagium.ui.holiday.parsePath

// The pumpkin patch's picture under the filter sections: a moon, two bats, a vine, a big jack-o'-lantern with a flickering glow, a small pumpkin, a tiny
// carved one and four fireflies, drawn on a Canvas from the canvas' SVG. Everything is drawn from [PictureArt] (parsed once) and the
// pure functions of HolidayAnimation.kt; the clock is read only while drawing, so a frame re-draws without recomposing anything.

private val INK = hex(0x3b6420)
private val CRATER = hex(0xefc982, 0.55f)
private val RIB = hex(0xa8430a, 0.5f)
private val SHINE = hex(0xffd08a, 0.55f)
private val LEAF = hex(0x5fa043)
private val VINE = hex(0x4f8f3a)

private const val FLICKER2_PERIOD = 2.1f
private const val FLICKER2_DELAY = -0.7f

private object PictureArt {
    const val BAT_D = "M20 5c1.6 0 2.6 1 2.6 1l1-2.4.7 3C26.6 5 30.4 3.6 36 6c-2.6 1.2-3.8 3.6-3.8 6.2-2.6-1.2-5.2-1.2-7.2 1.4-1.6-1.2-3.4-1.8-5-1.8" +
        "s-3.4.6-5 1.8c-2-2.6-4.6-2.6-7.2-1.4C7.8 9.6 6.6 7.2 4 6c5.6-2.4 9.4-1 11.7.6l.7-3 1 2.4S18.4 5 20 5z"
    val bat = parsePath(BAT_D)
    val moonClip = ovalPath(214f, 80f, 38f, 38f)
    val vine = parsePath("M6 352c18-24 34-6 44-30s28-26 40-14")
    val leaf = parsePath("M40 316c-16-8-26 4-20 16 12 2 20-4 20-16z")
    val leafVein = parsePath("M22 330c5-4 11-8 18-14")

    val bigBody = listOf(bodyOval(66f, 320f, 44f, 60f), bodyOval(174f, 320f, 44f, 60f), bodyOval(94f, 318f, 40f, 64f), bodyOval(146f, 318f, 40f, 64f))
    val bigMid = midOval(120f, 317f, 36f, 66f)
    val bigRibs = parsePath("M106 257c-8 22-8 98 0 122M134 257c8 22 8 98 0 122M80 268c-14 20-14 82 0 104M160 268c14 20 14 82 0 104")
    val bigShine = parsePath("M92 262c-10 20-12 60-6 92M124 256c-3 20-3 50-1 70")
    val bigStem = ArtPath("M113 258c-2-14 2-27 12-33l8 4c-8 6-10 17-8 29z")
    val bigStemBrush = diagonalBrush(bigStem.bounds, PUMPKIN_STEM_STOPS)
    val bigCurl = parsePath("M131 230c10-8 22-2 18 8-3 6-10 4-8-1")
    val bigGlow = listOf(
        ArtPath("M78 302l18-28 16 28z"),
        ArtPath("M128 302l16-28 18 28z"),
        ArtPath("M114 318l6-11 6 11z"),
        ArtPath("M72 326Q120 340 168 326Q160 360 120 364Q80 360 72 326Z"),
    )
    val bigTeeth = parsePath("M97 331h10l-1 9h-8zM133 331h10l-1 9h-8zM114 352h12l-1 12h-10z")

    val smallBody = listOf(bodyOval(214f, 364f, 22f, 24f), bodyOval(248f, 364f, 22f, 24f))
    val smallMid = midOval(231f, 363f, 20f, 26f)
    val smallShine = parsePath("M220 348c-5 6-5 20 0 28")
    val smallStem = ArtPath("M231 338c0-8 3-12 8-14")
    val smallStemBrush = diagonalBrush(smallStem.bounds, PUMPKIN_STEM_STOPS)
    val smallLeaf = parsePath("M239 331c11-7 20 0 15 7-7 2-13 0-15-7z")

    val tinyBody = bodyOval(34f, 378f, 22f, 17f)
    val tinyStem = parsePath("M34 362c0-5 2-8 5-9")
    val tinyEyes = ArtPath("M24 375l5-6 5 6zM36 375l5-6 5 6z")
    val tinySmile = ArtPath("M26 382q8 6 16 0")
    val tinySmileBrush = boxRadialBrush(tinySmile.bounds, PUMPKIN_GLOW_STOPS, 0.5f, 0.6f, 0.65f)
}

/** Draws the whole picture at time [t] seconds in its own 300 x 420 coordinates. */
internal fun DrawScope.drawPumpkinPicture(t: Float) {
    drawMoon(t)
    drawBat(0, t, Offset(176f, 62f), 1.5f, hex(0x2a1b3d))
    drawBat(1, t, Offset(60f, 132f), 0.9f, hex(0x3a2a52))
    drawVine()
    drawGroundLight(t)
    drawBigPumpkin(t)
    drawSmallPumpkin()
    drawTinyPumpkin(t)
    drawFireflies(t)
}

private fun DrawScope.drawMoon(t: Float) {
    val pulse = moonPulse(t)
    withTransform({ scale(pulse.scale, pulse.scale, Offset(214f, 80f)) }) { drawUnitEllipse(UNIT_HALO, 214f, 80f, 74f, 74f, pulse.alpha) }
    // The moon's gradient is centred at 42% / 38% of its 76 x 76 box with a radius of 62% of it.
    fillElliptical(PictureArt.moonClip, 207.92f, 70.88f, 47.12f, 47.12f, UNIT_MOON)
    drawCircle(CRATER, 6f, Offset(202f, 70f))
    drawCircle(CRATER, 4f, Offset(227f, 92f))
    drawCircle(CRATER, 2.5f, Offset(222f, 66f))
}

// A bat at [origin] scaled by [size]: bobbing, with its wings flapping (a vertical squash about the body's middle).
private fun DrawScope.drawBat(i: Int, t: Float, origin: Offset, size: Float, color: Color) {
    val body = Offset(origin.x + 20f * size, origin.y + 9f * size)
    bobbed(batBob(i, t), body) {
        withTransform({
            placed(origin.x, origin.y, size)
            scale(1f, batFlap(t), Offset(20f, 9f))
        }) { drawPath(PictureArt.bat, color) }
    }
}

private fun DrawScope.drawVine() {
    drawPath(PictureArt.vine, VINE, style = Stroke(3f, cap = StrokeCap.Round))
    drawPath(PictureArt.leaf, LEAF)
    drawPath(PictureArt.leafVein, INK, style = Stroke(1.2f))
}

// The ground shadows and the soft aura that flickers around the big pumpkin.
private fun DrawScope.drawGroundLight(t: Float) {
    drawUnitEllipse(UNIT_SHADOW_PICTURE, 124f, 390f, 118f, 16f)
    drawUnitEllipse(UNIT_SHADOW_PICTURE, 232f, 390f, 54f, 9f)
    drawUnitEllipse(UNIT_AURA, 120f, 318f, 110f, 80f, pumpkinFlicker(t))
}

private fun DrawScope.drawBigPumpkin(t: Float) {
    PictureArt.bigBody.forEach { drawOvalFill(it) }
    drawOvalFill(PictureArt.bigMid)
    drawPath(PictureArt.bigRibs, RIB, style = Stroke(2f))
    drawPath(PictureArt.bigShine, SHINE, style = Stroke(3.5f, cap = StrokeCap.Round))
    drawPath(PictureArt.bigStem.path, PictureArt.bigStemBrush)
    drawPath(PictureArt.bigCurl, hex(0x5f9a35), style = Stroke(2.4f, cap = StrokeCap.Round))
    val flicker = pumpkinFlicker(t)
    PictureArt.bigGlow.forEach { fillGlow(it, flicker) }
    drawPath(PictureArt.bigTeeth, hex(0xe8700f))
}

private fun DrawScope.drawSmallPumpkin() {
    PictureArt.smallBody.forEach { drawOvalFill(it) }
    drawOvalFill(PictureArt.smallMid)
    drawPath(PictureArt.smallShine, SHINE, style = Stroke(2.5f, cap = StrokeCap.Round))
    drawPath(PictureArt.smallStem.path, PictureArt.smallStemBrush, style = Stroke(5f, cap = StrokeCap.Round))
    drawPath(PictureArt.smallLeaf, LEAF)
}

private fun DrawScope.drawTinyPumpkin(t: Float) {
    drawOvalFill(PictureArt.tinyBody)
    drawPath(PictureArt.tinyStem, VINE, style = Stroke(3f, cap = StrokeCap.Round))
    val flicker = pumpkinFlicker(t, FLICKER2_PERIOD, FLICKER2_DELAY)
    fillGlow(PictureArt.tinyEyes, flicker)
    drawPath(PictureArt.tinySmile.path, PictureArt.tinySmileBrush, flicker, style = Stroke(3f, cap = StrokeCap.Round))
}

private val FIREFLIES = listOf(Triple(34f, 226f, 7f), Triple(262f, 248f, 6f), Triple(190f, 196f, 5f), Triple(276f, 318f, 5f))

private fun DrawScope.drawFireflies(t: Float) {
    FIREFLIES.forEachIndexed { i, (x, y, r) ->
        bobbed(fireflyAt(i, t), Offset(x, y)) { drawUnitEllipse(UNIT_FIREFLY, x, y, r, r) }
    }
}

/** The pumpkin patch's [HolidayPicturePainter]: a 300 x 420 picture. */
internal object PumpkinPicture : HolidayPicturePainter {
    override val designWidthDp = 300f
    override val designHeightDp = 420f

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) {
        with(scope) { drawPumpkinPicture(t) }
    }
}
