// Coordinates, colours and sizes are the approved design canvas' illustration values, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.muertos

import androidx.compose.ui.geometry.Offset
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
import com.indagium.ui.holiday.drawSteamWisp
import com.indagium.ui.holiday.drawSvg
import com.indagium.ui.holiday.flameShape
import com.indagium.ui.holiday.glowFlicker
import com.indagium.ui.holiday.holidayBlinkClosed
import com.indagium.ui.holiday.holidaySvg
import com.indagium.ui.holiday.parsePath
import com.indagium.ui.holiday.swayAngle

// The picture and the companion are two layers each. The static art (sky, altar, portrait, skull) is SVG, drawn by Skia's SVGDOM; Skia SVG
// does not run CSS animation and is unreliable with radial gradients, so everything that moves or glows is drawn natively on top, from the
// design's keyframes (MuertosMotion.kt). A frame is a pure function of t; HOLIDAY_STILL_TIME is a calm pose (no blink, flames at rest).

private val ARCH: Path by lazy { parsePath("M6 420V150A144 144 0 0 1 294 150V420Z") }
private val PICTURE_BACK by lazy { holidaySvg("muertos", "picture-back") }
private val PICTURE_FRONT by lazy { holidaySvg("muertos", "picture-front") }
private val COMPANION_ART by lazy { holidaySvg("muertos", "companion") }
private val COMPANION_LIDS by lazy { holidaySvg("muertos", "companion-lids") }
private val FLAME_OUTER_PATH: Path by lazy { parsePath(FLAME_OUTER) }
private val FLAME_INNER_PATH: Path by lazy { parsePath(FLAME_INNER) }
private val SMOKE_PATHS by lazy { PICTURE_SMOKE.map { parsePath(it.d) } }

private val FLAME_OUTER_COLOR = Color(0xFFff9a1f)
private val FLAME_INNER_COLOR = Color(0xFFfff0a8)
private val GLOW_COLOR = Color(0xFFffc24a)
private val WARM_COLOR = Color(0xFFffb347)
private val FLAME_BASE = Offset(0f, 2f)

private fun DrawScope.drawGlows(t: Float, still: Boolean) {
    val flicker = if (still) 1f else glowFlicker(t)
    PICTURE_GLOWS.forEach { g ->
        val brush = Brush.radialGradient(listOf(GLOW_COLOR.copy(alpha = .6f), GLOW_COLOR.copy(alpha = 0f)), Offset(g.x, g.y), g.radius)
        drawCircle(brush, g.radius, Offset(g.x, g.y), alpha = flicker)
    }
}

private fun DrawScope.drawFlames(t: Float, still: Boolean) {
    PICTURE_FLAMES.forEach { f ->
        val (scaleY, turn) = if (still) 1f to 0f else flameShape(t, f.variant)
        // The flame grows and leans from its base, the bottom-centre of its own box (the outer tongue ends at y = 2).
        withTransform({
            translate(f.x, f.y)
            scale(f.scale, f.scale, Offset.Zero)
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

internal object MuertosPicture : HolidayPicturePainter {
    override val designWidthDp = 300f
    override val designHeightDp = 420f

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        val still = t == HOLIDAY_STILL_TIME
        drawSvg(PICTURE_BACK)
        // The altar's warm light, drawn here because Skia SVG is unreliable with radial gradients.
        clipPath(ARCH) {
            val warm = Brush.radialGradient(listOf(WARM_COLOR.copy(alpha = .38f), WARM_COLOR.copy(alpha = 0f)), Offset(150f, 240f), 180f)
            drawCircle(warm, 150f, Offset(150f, 210f))
        }
        drawSvg(PICTURE_FRONT)
        clipPath(ARCH) {
            drawGlows(t, still)
            drawFlames(t, still)
            drawSmoke(t, still)
            PICTURE_PETALS.forEach { drawArtPetal(it, t, still) }
            PICTURE_MONARCHS.forEach { drawArtMonarch(it, t, still) }
        }
    }
}

/** Whether the calavera's eyes are closed at [t]: the last 4 % of every 5.2 s blink, `cf-lid`'s 94 % to 98 %. */
internal fun muertosLidsClosed(t: Float): Boolean = holidayBlinkClosed(t)

internal object MuertosCompanion : HolidayCompanionPainter {
    override val label = "Calavera in the Notes corner"
    override val designWidthDp = 240f
    override val designHeightDp = 240f

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        val still = t == HOLIDAY_STILL_TIME
        val sway = if (still) 0f else swayAngle(t, 3.8f)
        clipRect(0f, 0f, 240f, 240f) {
            withTransform({ rotate(sway, Offset(120f, 218f)) }) {
                drawSvg(COMPANION_ART)
                if (muertosLidsClosed(t)) drawSvg(COMPANION_LIDS)
            }
            COMPANION_MONARCHS.forEach { drawArtMonarch(it, t, still) }
            COMPANION_PETALS.forEach { drawArtPetal(it, t, still) }
        }
    }
}
