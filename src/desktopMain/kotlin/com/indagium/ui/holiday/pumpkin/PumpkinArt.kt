// SVG path data, gradient stops and coordinates are the approved design canvas' illustration values, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.pumpkin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.ArtPath
import com.indagium.ui.holiday.Stops
import com.indagium.ui.holiday.fillElliptical
import com.indagium.ui.holiday.hex
import com.indagium.ui.holiday.horizontalOval
import com.indagium.ui.holiday.shadowStops
import com.indagium.ui.holiday.unitRadial
import com.indagium.ui.holiday.verticalOval
import com.indagium.ui.holiday.washStops

// The pumpkin patch's own gradients and pumpkin-shaped helpers, on top of the framework's art kit (HolidayArtKit.kt).

internal val PUMPKIN_BODY_STOPS: Stops = arrayOf(0f to hex(0xffb453), 0.55f to hex(0xf07a16), 1f to hex(0xa9440a))
internal val PUMPKIN_MID_STOPS: Stops = arrayOf(0f to hex(0xe8700f), 0.5f to hex(0xffa13d), 1f to hex(0xe8700f))
internal val PUMPKIN_GLOW_STOPS: Stops = arrayOf(0f to hex(0xfffbd0), 0.45f to hex(0xffd23a), 1f to hex(0xff7a00))
internal val PUMPKIN_STEM_STOPS: Stops = arrayOf(0f to hex(0x9ccb57), 1f to hex(0x3b6420))
internal val FIREFLY_STOPS: Stops = arrayOf(0f to hex(0xfffbd0), 0.35f to hex(0xffd23a, 0.9f), 1f to hex(0xffb000, 0f))

internal val UNIT_GLOW: Brush = unitRadial(PUMPKIN_GLOW_STOPS)
internal val UNIT_FIREFLY: Brush = unitRadial(FIREFLY_STOPS)
internal val UNIT_MOON: Brush = unitRadial(arrayOf(0f to hex(0xfffbe8), 0.7f to hex(0xffe7a6), 1f to hex(0xefc061)))
internal val UNIT_HALO: Brush = unitRadial(washStops(0xffe7a6, 0.6f))
internal val UNIT_AURA: Brush = unitRadial(washStops(0xffcf4a, 0.5f))
internal val UNIT_SHADOW_PICTURE: Brush = unitRadial(shadowStops(0.38f))
internal val UNIT_SHADOW_CAT: Brush = unitRadial(shadowStops(0.35f))
internal val UNIT_CAT_GLOW: Brush = unitRadial(washStops(0xffb453, 0.45f))

internal fun bodyOval(cx: Float, cy: Float, rx: Float, ry: Float, stops: Stops = PUMPKIN_BODY_STOPS) = verticalOval(cx, cy, rx, ry, stops)

internal fun midOval(cx: Float, cy: Float, rx: Float, ry: Float, stops: Stops = PUMPKIN_MID_STOPS) = horizontalOval(cx, cy, rx, ry, stops)

/** The pumpkin glow (SVG pk-glow: centre 50% / 60%, radius 65% of the box) over [shape], at [alpha]. */
internal fun DrawScope.fillGlow(shape: ArtPath, alpha: Float = 1f) {
    val b = shape.bounds
    fillElliptical(shape.path, b.left + 0.5f * b.width, b.top + 0.6f * b.height, 0.65f * b.width, 0.65f * b.height, UNIT_GLOW, alpha)
}

/** Applies a [Bob] (a vertical lift and a tilt about [pivot]) to [block]. */
internal fun DrawScope.bobbed(bob: Bob, pivot: Offset, block: DrawScope.() -> Unit) {
    withTransform({
        translate(0f, bob.dy)
        rotate(bob.deg, pivot)
    }, block)
}

internal fun DrawTransform.placed(x: Float, y: Float, scale: Float) {
    translate(x, y)
    scale(scale, scale, Offset.Zero)
}
