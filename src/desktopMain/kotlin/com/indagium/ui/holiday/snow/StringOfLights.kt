// Wire geometry, bulb size and blink timing are the design canvas' values, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.snow

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.HOLIDAY_STILL_TIME
import com.indagium.ui.holiday.HolidayBandLayout
import com.indagium.ui.holiday.HolidayBandSpace
import com.indagium.ui.holiday.HolidaySceneEffect
import com.indagium.ui.holiday.HolidaySceneLayout
import com.indagium.ui.holiday.HolidayScenePainter
import com.indagium.ui.holiday.cycle
import com.indagium.ui.holiday.keyframes

// The "String of lights": a wire hung in swags along the top of the tab bar with a twinkling bulb on each, drawn BEHIND the tabs as part of the
// tab bar's scene (the tabs cover it where they are, so the bulbs show above and between them). It is a scene effect: its switch reaches the
// painter, and under Off intensity it stays up at its still frame while the twinkle stops.

/** The switch of a pack's string of lights (`cozy.lights`, `xmas.lights`). */
internal class StringOfLights(
    override val id: String,
    private val bulbs: List<Color>,
    private val wireDark: Color,
    private val wireLight: Color,
) : HolidaySceneEffect {
    override val label = "String of lights"
    override val noun = "lights"
    override val defaultOn = true

    fun wire(dark: Boolean) = if (dark) wireDark else wireLight

    fun bulb(index: Int): Color = bulbs[index % bulbs.size]

    /** One bulb of the string: its [x] and [y] (design units of the 1600 x 30 title bar), its colour index and the blink's delay in seconds. */
    class Bulb(val x: Float, val y: Float, val colorIndex: Int, val delaySec: Float)

    companion object {
        const val SEGMENT = 160f
        const val HOOK_Y = 1f
        const val SAG = 5f
        const val BULB_WIDTH = 7f
        const val BULB_HEIGHT = 10f
        private const val BLINK_SEC = 1.8f
        private const val GLOW_RADIUS = 8f
        private const val GLOW_ALPHA = .55f
        private val POSITIONS = floatArrayOf(.2f, .5f, .8f)
        private val BLINK_STOPS = floatArrayOf(0f, .5f, 1f)
        private val BLINK_VALUES = floatArrayOf(1f, .35f, 1f)

        /** The height of the wire at [t] (0..1) along a segment: the design's quadratic swag, hanging [SAG] below its hooks. */
        fun swag(t: Float): Float = HOOK_Y + 4f * SAG * t * (1f - t)

        /** The bulbs along a bar of [lengthUnits] design units: three per swag of [SEGMENT] units, the colour and delay stepping through four. */
        fun bulbsAlong(lengthUnits: Float): List<Bulb> {
            val out = ArrayList<Bulb>()
            var x = 0f
            var i = 0
            while (x < lengthUnits) {
                POSITIONS.forEach { t ->
                    out.add(Bulb(x + SEGMENT * t - BULB_WIDTH / 2f, swag(t) - 1f, i % 4, (i % 4) * .45f))
                    i++
                }
                x += SEGMENT
            }
            return out
        }

        /** The twinkle (`hol-blink`): a bulb's opacity at [time], 1 down to .35 and back every 1.8 s once its delay has passed. */
        fun blink(time: Float, delaySec: Float): Float =
            if (time == HOLIDAY_STILL_TIME || time < delaySec) 1f else keyframes(cycle(time - delaySec, BLINK_SEC), BLINK_STOPS, BLINK_VALUES, eased = true)

        private fun wirePath(lengthUnits: Float): Path = Path().apply {
            moveTo(0f, HOOK_Y)
            var x = 0f
            while (x < lengthUnits) {
                quadraticTo(x + SEGMENT / 2f, HOOK_Y + 2f * SAG, x + SEGMENT, HOOK_Y)
                x += SEGMENT
            }
        }

        private val BULB_SHAPE = Path().apply {
            addRoundRect(
                RoundRect(
                    Rect(0f, 0f, BULB_WIDTH, BULB_HEIGHT),
                    CornerRadius(BULB_WIDTH / 2f, BULB_HEIGHT * .6f), CornerRadius(BULB_WIDTH / 2f, BULB_HEIGHT * .6f),
                    CornerRadius(BULB_WIDTH / 2f, BULB_HEIGHT * .4f), CornerRadius(BULB_WIDTH / 2f, BULB_HEIGHT * .4f),
                ),
            )
        }

        /** Draws the string over [lengthUnits] design units in the current (design-unit) transform: the wire, then each bulb with its glow. */
        fun draw(scope: DrawScope, effect: StringOfLights, dark: Boolean, time: Float, lengthUnits: Float) = with(scope) {
            drawPath(wirePath(lengthUnits), effect.wire(dark), style = Stroke(1.8f))
            bulbsAlong(lengthUnits).forEach { bulb ->
                val color = effect.bulb(bulb.colorIndex)
                val alpha = blink(time, bulb.delaySec)
                val centre = Offset(bulb.x + BULB_WIDTH / 2f, bulb.y + BULB_HEIGHT / 2f)
                val glow = Brush.radialGradient(listOf(color.copy(alpha = GLOW_ALPHA * alpha), color.copy(alpha = 0f)), centre, GLOW_RADIUS)
                drawCircle(glow, GLOW_RADIUS, centre)
                withTransform({ translate(bulb.x, bulb.y) }) { drawPath(BULB_SHAPE, color.copy(alpha = alpha)) }
            }
        }
    }
}

/**
 * A tab-bar scene made of an optional [base] (the pack's title-bar art) and a [lights] string over it. The string spans the whole band from its
 * left edge in design units (it is a wire, not a right-aligned element) and is drawn only while its switch is in the enabled set.
 */
internal class LitTabScene(private val base: HolidayScenePainter?, private val lights: StringOfLights) : HolidayScenePainter {
    override val animated = true

    override fun layout(band: Rect): HolidaySceneLayout = base?.layout(band) ?: HolidayBandLayout(HolidayBandSpace(band), emptyList())

    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) =
        drawFrame(scope, layout, dark, HOLIDAY_STILL_TIME, setOf(lights.id))

    override fun drawFrame(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean, time: Float) =
        drawFrame(scope, layout, dark, time, setOf(lights.id))

    override fun drawFrame(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean, time: Float, enabledEffects: Set<String>) {
        base?.drawFrame(scope, layout, dark, time, enabledEffects)
        if (lights.id in enabledEffects) {
            val space = HolidayBandSpace(layout.band)
            val lengthUnits = if (space.unit > 0f) layout.band.width / space.unit else 0f
            space.drawFromStart(scope) { StringOfLights.draw(this, lights, dark, time, lengthUnits) }
        }
    }
}
