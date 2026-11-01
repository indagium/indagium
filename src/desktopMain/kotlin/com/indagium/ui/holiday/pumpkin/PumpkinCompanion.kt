// SVG path data and coordinates are the approved design canvas' illustration values (a 240 x 240 picture), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.pumpkin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.*
import com.indagium.ui.holiday.*

// The black cat on a pumpkin that sits in the bottom-right corner of the Notes column, as a backdrop art behind the tiles: its tail swishes and it blinks.

private val INK = hex(0x1a1322)
private val RIBS = hex(0xa8430a, 0.5f)
private val SHINE = hex(0xffd08a, 0.55f)
private val WHISKER = hex(0x9a8aa8)
private val PINK = hex(0xe889a8)
private val CAT_STOPS: Stops = arrayOf(0f to hex(0x3a2f4a), 1f to hex(0x110c18))

private object CatArt {
    val bodies = listOf(bodyOval(78f, 194f, 42f, 34f), bodyOval(162f, 194f, 42f, 34f), bodyOval(100f, 192f, 36f, 38f), bodyOval(140f, 192f, 36f, 38f))
    val mid = midOval(120f, 191f, 30f, 40f)
    val ribs = parsePath("M108 154c-6 18-6 58 0 74M132 154c6 18 6 58 0 74M84 164c-10 14-10 48 0 60M156 164c10 14 10 48 0 60")
    val shine = parsePath("M74 172c-6 12-6 30 0 42")
    val tail = parsePath("M144 152c26 6 38-14 30-32-5-10-15-10-15-2")
    val torso = ArtPath("M94 160c-8-34 4-66 26-66s34 32 26 66z")
    val torsoBrush = diagonalBrush(torso.bounds, CAT_STOPS)
    val torsoEdge = parsePath("M140 98c8 14 10 36 6 58")
    val ears = ArtPath("M99 68l-3-27 21 15zM141 68l3-27-21 15z")
    val earsBrush = diagonalBrush(ears.bounds, CAT_STOPS)
    val innerEars = parsePath("M102 61l-1-13 9 7zM138 61l1-13-9 7z")
    val headBrush = diagonalBrush(Rect(95f, 55f, 145f, 105f), CAT_STOPS)
    val collar = parsePath("M101 98q19 9 38 0")
    val nose = parsePath("M117 88h6l-3 3.5z")
    val mouth = parsePath("M120 91.5q-3 3-6 1M120 91.5q3 3 6 1")
    val whiskers = parsePath("M104 89l-16-2M104 92l-15 3M136 89l16-2M136 92l15 3")
}

/** Draws the cat on its pumpkin at time [t] seconds in its own 240 x 240 coordinates. */
internal fun DrawScope.drawCatCompanion(t: Float) {
    drawUnitEllipse(UNIT_CAT_GLOW, 120f, 150f, 96f, 96f, pumpkinFlicker(t))
    drawUnitEllipse(UNIT_SHADOW_CAT, 120f, 228f, 96f, 10f)
    drawCatPumpkin()
    drawCatTail(t)
    drawCatBody()
    drawCatHead()
    drawCatEyes(t)
    drawCatFace()
    drawCatFireflies(t)
}

private fun DrawScope.drawCatPumpkin() {
    CatArt.bodies.forEach { drawOvalFill(it) }
    drawOvalFill(CatArt.mid)
    drawPath(CatArt.ribs, RIBS, style = Stroke(2f))
    drawPath(CatArt.shine, SHINE, style = Stroke(3f, cap = StrokeCap.Round))
}

// The tail swishes about its base, where it leaves the body.
private fun DrawScope.drawCatTail(t: Float) {
    withTransform({ rotate(catTailAngle(t), Offset(144f, 152f)) }) { drawPath(CatArt.tail, INK, style = Stroke(9f, cap = StrokeCap.Round)) }
}

private fun DrawScope.drawCatBody() {
    drawPath(CatArt.torso.path, CatArt.torsoBrush)
    drawPath(CatArt.torsoEdge, hex(0x6b5a85, 0.6f), style = Stroke(2.5f, cap = StrokeCap.Round))
    drawOval(INK, Offset(97f, 153f), Size(18f, 10f))
    drawOval(INK, Offset(125f, 153f), Size(18f, 10f))
}

private fun DrawScope.drawCatHead() {
    drawPath(CatArt.ears.path, CatArt.earsBrush)
    drawPath(CatArt.innerEars, PINK.copy(alpha = 0.8f))
    drawCircle(CatArt.headBrush, 25f, Offset(120f, 80f))
    drawPath(CatArt.collar, hex(0xff8a2a), style = Stroke(4f, cap = StrokeCap.Round))
    drawCircle(hex(0xffd23a), 3.6f, Offset(120f, 104f))
}

// The eyes squash shut about their own middle.
private fun DrawScope.drawCatEyes(t: Float) {
    withTransform({ scale(1f, catBlink(t), Offset(120f, 78f)) }) {
        for (x in listOf(110f, 130f)) {
            drawOval(hex(0xffd23a), Offset(x - 5.5f, 71.5f), Size(11f, 13f))
            drawOval(hex(0x1a1a1a), Offset(x - 1.6f, 72.6f), Size(3.2f, 10.8f))
        }
    }
    drawCircle(Color.White, 1.2f, Offset(111.6f, 75.5f))
    drawCircle(Color.White, 1.2f, Offset(131.6f, 75.5f))
}

private fun DrawScope.drawCatFace() {
    drawPath(CatArt.nose, PINK)
    drawPath(CatArt.mouth, WHISKER, style = Stroke(1f))
    drawPath(CatArt.whiskers, WHISKER, style = Stroke(1f, cap = StrokeCap.Round))
}

private fun DrawScope.drawCatFireflies(t: Float) {
    bobbed(fireflyAt(0, t), Offset(30f, 60f)) { drawUnitEllipse(UNIT_FIREFLY, 30f, 60f, 6f, 6f) }
    bobbed(fireflyAt(1, t), Offset(212f, 96f)) { drawUnitEllipse(UNIT_FIREFLY, 212f, 96f, 5f, 5f) }
}

/** The pumpkin patch's [HolidayCompanionPainter]: the black cat on a pumpkin. */
internal object PumpkinCat : HolidayCompanionPainter {
    override val label = "Black cat in the Notes corner"
    override val designSizeDp = 240f

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) {
        with(scope) { drawCatCompanion(t) }
    }
}
