@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.candy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.HolidayBandSpace
import com.indagium.ui.holiday.HolidaySceneLayout
import com.indagium.ui.holiday.HolidayScenePainter
import kotlin.math.PI
import kotlin.math.atan

// Trick or treat's bunting in the scene band, as in the design's title bar (`is.candy`): a wire sagging between hooks every 160 design units
// along the whole band with a pennant at four spots of each sag, the middle of the band left clear. One design unit is one dp.

private const val SEGMENT = 160f
private const val SAG = 10f
private const val HOOK_Y = 1f
private val FLAG_SPOTS = listOf(.15f, .38f, .62f, .85f)
private const val FLAG_WIDTH = 13f
private const val FLAG_HEIGHT = 15f
private const val CLEAR_HALF_WIDTH = 100f
private const val PADDING = 3f

private class Flag(val x: Float, val y: Float, val degrees: Float, val colorIndex: Int)

private class Bunting(override val band: Rect, val widthUnits: Float, val flags: List<Flag>) : HolidaySceneLayout {
    override val elementBounds: List<Rect> = flags.map { flag ->
        val unit = band.height / 30f
        Rect(
            band.left + (flag.x - PADDING) * unit, band.top + (flag.y - PADDING) * unit,
            band.left + (flag.x + FLAG_WIDTH + PADDING) * unit, band.top + (flag.y + FLAG_HEIGHT + PADDING) * unit,
        )
    }
}

private fun sagY(t: Float) = HOOK_Y + 4 * SAG * t * (1 - t)

private fun flagsFor(widthUnits: Float): List<Flag> {
    val flags = mutableListOf<Flag>()
    var index = 0
    var x = 0f
    while (x < widthUnits) {
        FLAG_SPOTS.forEach { t ->
            val spot = x + SEGMENT * t
            val fits = spot - FLAG_WIDTH / 2 - PADDING >= 0f && spot + FLAG_WIDTH / 2 + PADDING <= widthUnits
            val clear = kotlin.math.abs(spot - widthUnits / 2f) < CLEAR_HALF_WIDTH
            if (fits && !clear) {
                val angle = atan(4 * SAG * (1 - 2 * t) / SEGMENT) * 180f / PI.toFloat()
                flags += Flag(spot - FLAG_WIDTH / 2, sagY(t) - 1f, angle, index)
            }
            index++
        }
        x += SEGMENT
    }
    return flags
}

/** The reference's multicolored bunting along the scene band. */
internal object CandyTabScene : HolidayScenePainter {
    override fun layout(band: Rect): HolidaySceneLayout {
        val unit = (band.height / 30f).coerceAtLeast(0f)
        return if (unit <= 0f) Bunting(band, 0f, emptyList()) else Bunting(band, band.width / unit, flagsFor(band.width / unit))
    }

    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) = with(scope) {
        val bunting = layout as Bunting
        val space = HolidayBandSpace(bunting.band)
        if (space.unit <= 0f) return@with
        val colors = if (dark) listOf(Color(0xFFFFD23F), Color(0xFFFF8A2A), Color(0xFFA855F7), Color(0xFFFFF2DF))
        else listOf(Color(0xFFF5B800), Color(0xFFFF8A2A), Color(0xFF7C3AED), Color(0xFF3FB950))
        val wireColor = (if (dark) Color(0xFFC8B8E5) else Color(0xFF67567C)).copy(alpha = .5f)
        space.drawFromStart(this) {
            val wire = Path().apply {
                moveTo(0f, HOOK_Y)
                var x = 0f
                while (x < bunting.widthUnits) {
                    quadraticTo(x + SEGMENT / 2, HOOK_Y + 2 * SAG, x + SEGMENT, HOOK_Y)
                    x += SEGMENT
                }
            }
            drawPath(wire, wireColor, style = Stroke(1.2f))
            bunting.flags.forEach { flag ->
                val pennant = Path().apply { moveTo(0f, 0f); lineTo(FLAG_WIDTH, 0f); lineTo(FLAG_WIDTH / 2, FLAG_HEIGHT); close() }
                withTransform({
                    translate(flag.x, flag.y)
                    rotate(flag.degrees, Offset(FLAG_WIDTH / 2, 0f))
                }) { drawPath(pennant, colors[flag.colorIndex % colors.size]) }
            }
        }
    }
}
