// Literal paths, colours and coordinates from the Haunted manor SVG in the holiday design.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.manor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.HolidayPicturePainter
import com.indagium.ui.holiday.drawUnitEllipse
import com.indagium.ui.holiday.hex
import com.indagium.ui.holiday.parsePath
import com.indagium.ui.holiday.unitRadial
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

private val ghostBody = parsePath("M56 250V140a54 54 0 0 1 108 0v110l-13.5-12-13.5 12-13.5-12-13.5 12-13.5-12-13.5 12-13.5-12z")
private val ghostArms = parsePath("M58 178c-16 4-26 14-24 26 11-2 18-8 24-13zM162 178c16 4 26 14 24 26-11-2-18-8-24-13z")
private val tree = parsePath("M270 396c-8-64 6-130-10-214")
private val branches = parsePath("M262 262c-22-14-32-32-36-54M260 226c14-14 22-30 22-50M264 300c14-10 28-12 36-10M228 210c-8-6-10-14-8-22M276 186c6-8 8-18 6-26")
private val lamp = parsePath("M224 236h16l-2 4v16l2 4h-16l2-4v-16z")
private val largeStone = parsePath("M28 390V320a42 42 0 0 1 84 0v70z")
private val stoneHighlight = parsePath("M34 388V322a36 36 0 0 1 22-32")
private val cross = parsePath("M70 320v36M57 333h26")
private val crack = parsePath("M98 300l-6 12 5 6-6 12")
private val grass1 = parsePath("M28 376c8-6 16-2 22 2s14 2 20-2 14-4 20 0 14 4 22-2v16H28z")
private val smallStone = parsePath("M132 392v-44q0-10 10-10h40q10 0 10 10v44z")
private val grass2 = parsePath("M132 384c10-5 18-1 26 2s16 1 34-4v10h-60z")
private val bat = parsePath("M20 5c1.6 0 2.6 1 2.6 1l1-2.4.7 3C26.6 5 30.4 3.6 36 6c-2.6 1.2-3.8 3.6-3.8 6.2-2.6-1.2-5.2-1.2-7.2 1.4-1.6-1.2-3.4-1.8-5-1.8s-3.4.6-5 1.8c-2-2.6-4.6-2.6-7.2-1.4C7.8 9.6 6.6 7.2 4 6c5.6-2.4 9.4-1 11.7.6l.7-3 1 2.4S18.4 5 20 5z")
private val ghostBrush = Brush.linearGradient(0f to hex(0xffffff), .6f to hex(0xf1f6f4), 1f to hex(0xcfdcd8), start = Offset(56f, 86f), end = Offset(164f, 250f))
private val stoneBrush = Brush.linearGradient(0f to hex(0xcfd4d8), .55f to hex(0x9aa1a8), 1f to hex(0x666d75), start = Offset(28f, 278f), end = Offset(112f, 390f))
private val smallStoneBrush = Brush.linearGradient(0f to hex(0xcfd4d8), .55f to hex(0x9aa1a8), 1f to hex(0x666d75), start = Offset(132f, 338f), end = Offset(192f, 392f))
private val lampGlow = unitRadial(arrayOf(0f to hex(0xfff3b0), .4f to hex(0xffd166, .8f), 1f to hex(0xffb000, 0f)))
private val mist = unitRadial(arrayOf(0f to hex(0xffffff, .18f), .6f to hex(0xffffff, .08f), 1f to hex(0xffffff, 0f)))

internal object ManorPicture : HolidayPicturePainter {
    override val designWidthDp = 300f
    override val designHeightDp = 420f
    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) { drawManorPicture(t) }
}

/** A brief blink in a 5-second loop, including a stable open frame when animation is off. */
internal fun manorBlinkScale(t: Float): Float {
    val phase = t - floor(t / 5f) * 5f
    return when { phase < 4.5f -> 1f; phase < 4.6f -> 1f - 9.5f * (phase - 4.5f); phase < 4.7f -> .05f + 9.5f * (phase - 4.6f); else -> 1f }
}

internal fun DrawScope.drawManorPicture(t: Float) {
    // Lightning is owned by the framework flash effect so all flashes share one safety schedule.
    drawPath(tree, hex(0x2c2f2b), style = Stroke(9f, cap = StrokeCap.Round))
    drawPath(branches, hex(0x2c2f2b), style = Stroke(4f, cap = StrokeCap.Round))
    drawLine(hex(0x2c2f2b), Offset(232f, 216f), Offset(232f, 234f), 1.4f)
    drawUnitEllipse(lampGlow, 232f, 248f, 26f, 26f, .9f + .1f * sin(t * 2f))
    drawPath(lamp, hex(0x3b3f3a))
    drawRoundRect(hex(0xffd166), Offset(227f, 241f), Size(10f, 14f), androidx.compose.ui.geometry.CornerRadius(2f))
    val bob1 = -5f * (1f - kotlin.math.cos(t * 2f * PI.toFloat() / 4.2f))
    withTransform({ translate(0f, bob1) }) { drawGhost(t, 1f) }
    val bob2 = -5f * (1f - kotlin.math.cos((t + 1.8f) * 2f * PI.toFloat() / 5.4f))
    withTransform({ translate(170f, 64f + bob2); scale(.42f, .42f, Offset.Zero) }) { drawGhost(t + 1.8f, .72f) }
    withTransform({ translate(250f, 300f + 3f * sin(t)); scale(.9f, .9f, Offset.Zero) }) { drawPath(bat, hex(0x26292e)) }
    drawPath(largeStone, stoneBrush)
    drawPath(stoneHighlight, hex(0xeef1f3, .55f), style = Stroke(3f, cap = StrokeCap.Round))
    drawPath(cross, hex(0x5d646b), style = Stroke(5f, cap = StrokeCap.Round))
    drawPath(crack, hex(0x5d646b), style = Stroke(1.5f))
    drawPath(grass1, hex(0x5f8f4a))
    drawPath(smallStone, smallStoneBrush)
    drawRip()
    drawPath(grass2, hex(0x6fa055))
    drawUnitEllipse(mist, 150f + 8f * sin(t * .25f), 398f, 150f, 24f)
}

private fun DrawScope.drawGhost(t: Float, alpha: Float) {
    drawPath(ghostBody, ghostBrush, alpha = alpha)
    drawPath(ghostArms, ghostBrush, alpha = alpha)
    val eyeHeight = 12f * manorBlinkScale(t)
    for (x in listOf(92f, 128f)) drawOval(hex(0x1d2620, alpha), Offset(x - 9f, 150f - eyeHeight), Size(18f, 2f * eyeHeight))
    for (x in listOf(95f, 131f)) drawCircle(hex(0xffffff, alpha), 3f * manorBlinkScale(t), Offset(x, 145f))
    drawOval(hex(0x1d2620, alpha), Offset(102f, 170f), Size(16f, 20f))
    drawOval(hex(0xe0607a, alpha), Offset(105.5f, 180f), Size(9f, 8f))
    for (x in listOf(76f, 144f)) drawOval(hex(0xff9fb2, .6f * alpha), Offset(x - 9f, 165.5f), Size(18f, 9f))
}

private fun DrawScope.drawRip() {
    // Small serif inscription on the second tombstone.
    val ink = hex(0x5d646b, .78f)
    val letters = parsePath("M143 368v-12h5q4 0 4 3t-4 3h-5m5 0 5 6M156 356h6m-3 0v12m-3 0h6M166 368v-12h5q4 0 4 3t-4 3h-5")
    drawPath(letters, ink, style = Stroke(1.5f))
    for (x in listOf(154f, 164f, 178f)) drawCircle(ink, .8f, Offset(x, 368f))
}
