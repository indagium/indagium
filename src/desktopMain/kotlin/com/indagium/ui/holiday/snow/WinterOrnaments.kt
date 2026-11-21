// Offsets, sizes and paths are the design canvas' (a 16 x 9 bow, a 13 x 13 star, a snow cap of 100 x 10 units), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.snow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.indagium.model.TabStripPosition
import com.indagium.ui.holiday.HOLIDAY_STILL_TIME
import com.indagium.ui.holiday.HolidayTabOrnament
import com.indagium.ui.holiday.cycle
import com.indagium.ui.holiday.keyframes
import com.indagium.ui.holiday.parsePath

// What the three winter packs put on the active tab, in the design's own shapes: First snow's snow cap, the green bow of both Christmas packs
// and Classic Christmas' twinkling gold star. They are drawn over the tab, outside its clip, in a frame where x runs along the tab's outer edge
// and y into the tab (see [withTabFrame]), so one drawing serves a strip on any edge.

private val CAP = parsePath(
    "M0 7C3 2 8 1 14 3S24 1 32 2 44 4 52 2 64 1 72 3 84 1 92 2 98 3 100 6V8C96 10 90 8 84 9S72 8 64 9 52 10 44 8 30 9 22 8 8 10 0 9Z",
)
private val BOW_LOOPS = parsePath("M8 4.5C5 0 0 0 1.5 3.5 2.5 6 6 5 8 4.5zm0 0c3-4.5 8-4.5 6.5-1C13.5 6 10 5 8 4.5z")
private val STAR = parsePath("M6 0l1.4 4.6L12 6 7.4 7.4 6 12 4.6 7.4 0 6l4.6-1.4z")

private val SNOW_DARK = Color(0xFFeef5fc)
private val SNOW_LIGHT = Color(0xFFffffff)
private val CAP_SHADOW = Color(0xFF466e96).copy(alpha = .28f)
private val BOW_GREEN = Color(0xFF3fa46a)
private val BOW_GOLD = Color(0xFFf2c14e)
private val STAR_GOLD_DARK = Color(0xFFe8b84a)
private val STAR_GOLD_LIGHT = Color(0xFFb88a1e)

/**
 * Runs [block] in the tab's frame: x along the outer edge, y inwards from it, in pixels, for a strip at [position]. A side strip's frame is a
 * turned (and flipped) copy of the top one, which is harmless for these symmetric ornaments. [length] is the size of the tab cell along the strip.
 */
internal fun DrawScope.withTabFrame(position: TabStripPosition, outerEdgePx: Float, block: DrawScope.(length: Float) -> Unit) {
    val horizontal = position == TabStripPosition.TOP || position == TabStripPosition.BOTTOM
    val length = if (horizontal) size.width else size.height
    withTransform({
        when (position) {
            TabStripPosition.TOP -> translate(0f, outerEdgePx)
            TabStripPosition.BOTTOM -> {
                translate(0f, size.height - outerEdgePx)
                scale(1f, -1f, Offset.Zero)
            }
            // A quarter turn maps x along the edge to y and y into the tab to -x; the left strip flips it so y points into the tab.
            TabStripPosition.LEFT -> {
                translate(outerEdgePx, 0f)
                rotate(90f, Offset.Zero)
                scale(1f, -1f, Offset.Zero)
            }
            TabStripPosition.RIGHT -> {
                translate(size.width - outerEdgePx, 0f)
                rotate(90f, Offset.Zero)
            }
        }
    }) { block(length) }
}

/** First snow's ornament: a snow cap over the active tab, a little wider than the tab and 6 dp above its top edge (the design's `.cap`). */
internal object SnowCap : HolidayTabOrnament {
    override fun draw(scope: DrawScope, position: TabStripPosition, outerEdgePx: Float, dark: Boolean) = with(scope) {
        val dp = 1.dp.toPx()
        withTabFrame(position, outerEdgePx) { length ->
            // The design's cap spans the tab less 3 dp each side; the tab sits 1 dp in from its cell's ends.
            val width = length + 4f * dp
            withTransform({
                translate(-2f * dp, -6f * dp)
                scale(width / 100f, 11f * dp / 10f, Offset.Zero)
            }) {
                if (!dark) withTransform({ translate(0f, 1f / 1.1f) }) { drawPath(CAP, CAP_SHADOW) }
                drawPath(CAP, if (dark) SNOW_DARK else SNOW_LIGHT)
            }
        }
    }
}

private fun DrawScope.drawBow(length: Float) {
    val dp = 1.dp.toPx()
    withTransform({ translate(length / 2f - 8f * dp, -7f * dp); scale(dp, dp, Offset.Zero) }) {
        drawPath(BOW_LOOPS, BOW_GREEN)
        drawCircle(BOW_GOLD, 1.6f, Offset(8f, 4.5f))
    }
}

/** Cozy Christmas' ornament: the green bow with a gold knot on the top edge of the active tab (the design's `.bow`). */
internal object ChristmasBow : HolidayTabOrnament {
    override fun draw(scope: DrawScope, position: TabStripPosition, outerEdgePx: Float, dark: Boolean) = with(scope) {
        withTabFrame(position, outerEdgePx) { length -> drawBow(length) }
    }
}

/** The star's twinkle (`hol-twinkle`): opacity .35 to 1 and scale .7 to 1 and back every 1.6 s, eased; calm (full size, full) when [time] is the still time. */
internal fun starTwinkle(time: Float): Pair<Float, Float> {
    if (time == HOLIDAY_STILL_TIME) return 1f to 1f
    val u = cycle(time, 1.6f)
    return keyframes(u, TWINKLE_STOPS, TWINKLE_OPACITY, eased = true) to keyframes(u, TWINKLE_STOPS, TWINKLE_SCALE, eased = true)
}

private val TWINKLE_STOPS = floatArrayOf(0f, .5f, 1f)
private val TWINKLE_OPACITY = floatArrayOf(.35f, 1f, .35f)
private val TWINKLE_SCALE = floatArrayOf(.7f, 1f, .7f)

/**
 * The design's `.tab.on .spark`: a twinkling 13 dp star in [color] on the active tab's corner, 7 dp above its top edge and ending where the tab ends
 * (1 dp inside its cell; the design lets it hang 4 dp past, but the next tab, drawn later, would cover it). Drawn in the tab frame
 * ([withTabFrame]) of a tab [length] px long, at [time] (calm at the still time).
 */
internal fun DrawScope.drawTabStar(length: Float, color: Color, time: Float) {
    val dp = 1.dp.toPx()
    val (alpha, scale) = starTwinkle(time)
    val left = length - 1f * dp - 13f * dp
    val centre = Offset(left + 6.5f * dp, -7f * dp + 6.5f * dp)
    withTransform({
        translate(centre.x, centre.y)
        scale(scale, scale, Offset.Zero)
        translate(-6.5f * dp, -6.5f * dp)
        scale(13f * dp / 12f, 13f * dp / 12f, Offset.Zero)
    }) { drawPath(STAR, color.copy(alpha = alpha)) }
}

/**
 * Classic Christmas' ornament: the bow on the top edge of the active tab and a twinkling gold star on its corner, 7 dp above its top edge (the
 * design's `.bow` and `.tab.on .spark`).
 */
internal object ChristmasBowAndStar : HolidayTabOrnament {
    override val animated = true

    override fun draw(scope: DrawScope, position: TabStripPosition, outerEdgePx: Float, dark: Boolean) =
        drawFrame(scope, position, outerEdgePx, dark, HOLIDAY_STILL_TIME)

    override fun drawFrame(scope: DrawScope, position: TabStripPosition, outerEdgePx: Float, dark: Boolean, time: Float) = with(scope) {
        withTabFrame(position, outerEdgePx) { length ->
            drawBow(length)
            drawTabStar(length, if (dark) STAR_GOLD_DARK else STAR_GOLD_LIGHT, time)
        }
    }
}
