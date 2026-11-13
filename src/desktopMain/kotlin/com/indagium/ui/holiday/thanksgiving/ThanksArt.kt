// Coordinates, colours and sizes are the approved design canvas' illustration values, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.thanksgiving

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.HOLIDAY_STILL_TIME
import com.indagium.ui.holiday.HolidayCompanionPainter
import com.indagium.ui.holiday.HolidayPicturePainter
import com.indagium.ui.holiday.alternateEase
import com.indagium.ui.holiday.drawSteamWisp
import com.indagium.ui.holiday.drawSvg
import com.indagium.ui.holiday.flameShape
import com.indagium.ui.holiday.glowFlicker
import com.indagium.ui.holiday.holidayBlinkClosed
import com.indagium.ui.holiday.parsePath

// The picture and the companion are layered. The static art is SVG, drawn by Skia's SVGDOM; Skia SVG does not run CSS animation and is
// unreliable with radial gradients, so everything that moves or glows (the sun, the geese, the leaves, the candles, the smoke, the fan, the head
// and the blink) is drawn natively between the layers, from the design's keyframes. A frame is a pure function of t; HOLIDAY_STILL_TIME is a
// calm pose (the sun at full, the geese mid-window, the flames and the fan at rest, eyes open).

private val PICTURE_BACK by lazy { thanksSvg("picture-back") }
private val PICTURE_WINDOW by lazy { thanksSvg("picture-window") }
private val PICTURE_ROOM by lazy { thanksSvg("picture-room") }
private val COMPANION_TAIL by lazy { thanksSvg("companion-tail") }
private val COMPANION_BODY by lazy { thanksSvg("companion-body") }
private val COMPANION_HEAD by lazy { thanksSvg("companion-head") }
private val COMPANION_LIDS by lazy { thanksSvg("companion-lids") }
private val CARD: Path by lazy { Path().apply { addRoundRect(RoundRect(0f, 0f, 300f, 420f, CornerRadius(14f, 14f))) } }
private val FLAME_OUTER_PATH: Path by lazy { parsePath(FLAME_OUTER) }
private val FLAME_INNER_PATH: Path by lazy { parsePath(FLAME_INNER) }
private val SMOKE_PATHS by lazy { PICTURE_SMOKE.map { parsePath(it.d) } }

private val SUN_CORE = Color(0xFFfff1b8)
private val SUN_RIM = Color(0xFFffd166)
private val FLAME_OUTER_COLOR = Color(0xFFff9a1f)
private val FLAME_INNER_COLOR = Color(0xFFfff0a8)
private val GLOW_COLOR = Color(0xFFffc24a)
private val FLAME_BASE = Offset(0f, 2f)

// The glass of the window: the sun, the geese and the leaves outside stay behind it.
private const val GLASS_LEFT = 46f
private const val GLASS_TOP = 30f
private const val GLASS_RIGHT = 254f
private const val GLASS_BOTTOM = 230f

// `s-pulse`: the sun's opacity .8 -> 1 and scale 1 -> 1.07 over 3.2 s (1.6 s each way), ease-in-out.
private const val PULSE_LEG = 1.6f

private fun DrawScope.drawSun(t: Float, still: Boolean) {
    val pulse = if (still) 1f else alternateEase(t, PULSE_LEG)
    val centre = Offset(PICTURE_SUN.x, PICTURE_SUN.y)
    val brush = Brush.radialGradient(
        colorStops = arrayOf(0f to SUN_CORE, .4f to SUN_RIM.copy(alpha = .9f), 1f to SUN_RIM.copy(alpha = 0f)),
        center = centre, radius = PICTURE_SUN.radius,
    )
    val scale = if (still) 1f else 1f + .07f * pulse
    withTransform({ scale(scale, scale, centre) }) { drawCircle(brush, PICTURE_SUN.radius, centre, alpha = if (still) 1f else .8f + .2f * pulse) }
}

private fun DrawScope.drawGeese(t: Float, still: Boolean) {
    val travel = if (still) 55f else geeseTravel(t)
    val flap = if (still) 1f else gooseFlap(t)
    withTransform({ translate(GEESE_ORIGIN.x + travel, GEESE_ORIGIN.y) }) {
        PICTURE_GEESE.forEach { g ->
            withTransform({
                translate(g.x, g.y)
                scale(g.scale, g.scale, Offset.Zero)
            }) { drawGoose(flap, PICTURE_GOOSE_COLOR) }
        }
    }
}

private fun DrawScope.drawGlows(t: Float, still: Boolean) {
    val flicker = if (still) 1f else glowFlicker(t)
    PICTURE_GLOWS.forEach { g ->
        val brush = Brush.radialGradient(listOf(GLOW_COLOR.copy(alpha = .55f), GLOW_COLOR.copy(alpha = 0f)), Offset(g.x, g.y), g.radius)
        drawCircle(brush, g.radius, Offset(g.x, g.y), alpha = flicker)
    }
}

private fun DrawScope.drawFlames(t: Float, still: Boolean) {
    PICTURE_FLAMES.forEach { f ->
        val (scaleY, turn) = if (still) 1f to 0f else flameShape(t, f.variant)
        // The flame grows and leans from its base, the bottom-centre of its own box (the outer tongue ends at y = 2).
        withTransform({
            translate(f.x, f.y)
            scale(1f, scaleY, FLAME_BASE)
            rotate(turn, FLAME_BASE)
        }) {
            drawPath(FLAME_OUTER_PATH, FLAME_OUTER_COLOR)
            drawPath(FLAME_INNER_PATH, FLAME_INNER_COLOR)
        }
    }
}

private fun DrawScope.drawSmoke(t: Float, still: Boolean) {
    PICTURE_SMOKE.forEachIndexed { i, smoke ->
        val path = SMOKE_PATHS[i]
        drawSteamWisp(path, path.getBounds(), smoke.width, smoke.alpha, smoke.late, t, still)
    }
}

internal object ThanksPicture : HolidayPicturePainter {
    override val designWidthDp = 300f
    override val designHeightDp = 420f

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        val still = t == HOLIDAY_STILL_TIME
        drawSvg(PICTURE_BACK)
        clipRect(GLASS_LEFT, GLASS_TOP, GLASS_RIGHT, GLASS_BOTTOM) { drawSun(t, still) }
        drawSvg(PICTURE_WINDOW)
        clipRect(GLASS_LEFT, GLASS_TOP, GLASS_RIGHT, GLASS_BOTTOM) {
            drawGeese(t, still)
            WINDOW_LEAVES.forEach { drawArtLeaf(it, t, still) }
        }
        drawSvg(PICTURE_ROOM)
        clipPath(CARD) {
            drawGlows(t, still)
            drawFlames(t, still)
            drawSmoke(t, still)
        }
    }
}

/** Whether the turkey's eyes are closed at [t]: the last 4 % of every 5.2 s blink, `cf-lid`'s 94 % to 98 %. */
internal fun thanksLidsClosed(t: Float): Boolean = holidayBlinkClosed(t)

// `tg-fan`: the tail turns -2.5 to 2.5 degrees and grows .98 to 1.02 over 2.8 s each way. `tg-bob`: the head dips 2 and leans -3 degrees and
// back over 2.6 s (1.3 s each way).
private const val FAN_LEG = 2.8f
private const val BOB_LEG = 1.3f

internal object ThanksCompanion : HolidayCompanionPainter {
    override val label = "Turkey in the Notes corner"
    override val designWidthDp = 240f
    override val designHeightDp = 240f

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        val still = t == HOLIDAY_STILL_TIME
        val fan = if (still) .5f else alternateEase(t, FAN_LEG)
        val bob = if (still) 0f else alternateEase(t, BOB_LEG)
        clipRect(0f, 0f, 240f, 240f) {
            withTransform({
                rotate(-2.5f + 5f * fan, TAIL_ORIGIN)
                scale(.98f + .04f * fan, .98f + .04f * fan, TAIL_ORIGIN)
            }) { drawSvg(COMPANION_TAIL) }
            drawSvg(COMPANION_BODY)
            withTransform({
                translate(0f, 2f * bob)
                rotate(-3f * bob, HEAD_ORIGIN)
            }) {
                drawSvg(COMPANION_HEAD)
                if (thanksLidsClosed(t)) drawSvg(COMPANION_LIDS)
            }
            COMPANION_LEAVES.forEach { drawArtLeaf(it, t, still) }
        }
    }
}
