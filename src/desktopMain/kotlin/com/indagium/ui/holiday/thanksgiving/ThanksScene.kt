// Bounds and sway timing are the approved design canvas' values, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.thanksgiving

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.HOLIDAY_STILL_TIME
import com.indagium.ui.holiday.HolidayBandLayout
import com.indagium.ui.holiday.HolidayBandPart
import com.indagium.ui.holiday.HolidayBandSpace
import com.indagium.ui.holiday.HolidaySceneLayout
import com.indagium.ui.holiday.HolidayScenePainter
import com.indagium.ui.holiday.drawSvg
import com.indagium.ui.holiday.swayAngle

// The harvest field in the title bar: a far and a near field stretched over the bar, the low sun between them (the near field hides its lower
// half), and the bales, shocks, barn and wheat of the design standing on the near field. The sun is right-aligned with the barn like the other
// elements, so it stays round and next to the barn in any bar width instead of stretching with the fields. The elements are band parts, so a narrow
// bar drops the leftmost whole instead of cutting one. All art is SVG except the sun (a radial glow) and the swaying wheat.

private val FAR_DARK by lazy { thanksSvg("scene-far-dark") }
private val FAR_LIGHT by lazy { thanksSvg("scene-far-light") }
private val NEAR_DARK by lazy { thanksSvg("scene-near-dark") }
private val NEAR_LIGHT by lazy { thanksSvg("scene-near-light") }
private val BALES by lazy { thanksSvg("scene-bales") }
private val SHOCKS by lazy { thanksSvg("scene-shocks") }
private val BARN by lazy { thanksSvg("scene-barn") }
private val WHEAT by lazy { List(SCENE_WHEAT.size) { thanksSvg("scene-wheat-$it") } }

private val SUN_COLOR = Color(0xFFf5b041)

// `tsway`: 3.2 s; `tsway2` (the slower stalks): 3.9 s, started 1.4 s ago.
private const val SWAY_PERIOD = 3.2f
private const val SWAY_SLOW_PERIOD = 3.9f
private const val SWAY_SLOW_DELAY = 1.4f

private const val SUN_KEY = "sun"
private const val BALES_KEY = "bales"
private const val SHOCKS_KEY = "shocks"
private const val BARN_KEY = "barn"
private const val WHEAT_KEY = "wheat"

// Each part's rectangle on the 1600 x 30 title bar, from its SVG (strokes and the wheat's sway included).
private val PARTS = listOf(
    HolidayBandPart(SUN_KEY, Rect(1156f, 2f, 1204f, 30f)),
    HolidayBandPart(BALES_KEY, Rect(988f, 15f, 1034f, 30f)),
    HolidayBandPart(SHOCKS_KEY, Rect(1070f, 11f, 1110f, 30f)),
    HolidayBandPart(BARN_KEY, Rect(1225f, 7f, 1265f, 30f)),
    HolidayBandPart(WHEAT_KEY, Rect(1325f, 7f, 1394f, 30f)),
)

private fun DrawScope.drawSun() {
    val centre = Offset(SCENE_SUN.x, SCENE_SUN.y)
    val glow = Brush.radialGradient(
        colorStops = arrayOf(0f to SUN_COLOR.copy(alpha = .35f), .45f to SUN_COLOR.copy(alpha = .35f), 1f to SUN_COLOR.copy(alpha = 0f)),
        center = centre, radius = SCENE_SUN_GLOW_RADIUS,
    )
    drawCircle(glow, SCENE_SUN_GLOW_RADIUS, centre)
    drawCircle(SUN_COLOR, SCENE_SUN.radius, centre)
}

private fun DrawScope.drawWheat(time: Float, still: Boolean) {
    SCENE_WHEAT.forEach { w ->
        val angle = when {
            still -> 0f
            w.slow -> swayAngle(time, SWAY_SLOW_PERIOD, SWAY_SLOW_DELAY)
            else -> swayAngle(time, SWAY_PERIOD)
        }
        withTransform({ rotate(angle, Offset(w.x, w.y)) }) { drawSvg(WHEAT[w.index]) }
    }
}

internal object ThanksScene : HolidayScenePainter {
    override val animated = true

    override fun layout(band: Rect): HolidaySceneLayout = HolidayBandLayout(HolidayBandSpace(band), PARTS)

    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) = drawFrame(scope, layout, dark, HOLIDAY_STILL_TIME)

    override fun drawFrame(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean, time: Float) {
        val l = layout as HolidayBandLayout
        val still = time == HOLIDAY_STILL_TIME
        l.space.drawBackdrop(scope) { drawSvg(if (dark) FAR_DARK else FAR_LIGHT) }
        if (l.has(SUN_KEY)) l.space.drawElements(scope) { drawSun() }
        l.space.drawBackdrop(scope) { drawSvg(if (dark) NEAR_DARK else NEAR_LIGHT) }
        l.space.drawElements(scope) {
            if (l.has(BALES_KEY)) drawSvg(BALES)
            if (l.has(SHOCKS_KEY)) drawSvg(SHOCKS)
            if (l.has(BARN_KEY)) drawSvg(BARN)
            if (l.has(WHEAT_KEY)) drawWheat(time, still)
        }
    }
}
