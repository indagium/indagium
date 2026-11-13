// Flag positions and sway timing are the approved design canvas' values, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.muertos

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.HOLIDAY_SCENE_REFERENCE_HEIGHT_DP
import com.indagium.ui.holiday.HOLIDAY_STILL_TIME
import com.indagium.ui.holiday.HolidayBandLayout
import com.indagium.ui.holiday.HolidayBandPart
import com.indagium.ui.holiday.HolidayBandSpace
import com.indagium.ui.holiday.HolidaySceneLayout
import com.indagium.ui.holiday.HolidayScenePainter
import com.indagium.ui.holiday.parsePath
import com.indagium.ui.holiday.swayAngle

// The papel picado string in the title bar: flags hang from a wire, a gap is left over the app name. Flags are grouped about four to a part so
// a narrow bar drops whole groups (the leftmost first, the composition is right-aligned) instead of cutting a flag.

private const val FLAG_WIDTH = 30f
private const val FLAG_SWAY_TOP = 2f
private const val WIRE_MARGIN = 12f
private const val WIRE_END = 1700f
private const val FLAG_SWAY_PERIOD = 2.6f
private const val FLAG_SWAY_SLOW_PERIOD = 3.1f
private const val FLAG_SWAY_SLOW_DELAY = 1.2f

/** The flag counts of the groups on each side of the gap, leftmost group first. */
private val GROUP_SIZES = listOf(5, 4, 4, 4)

private class FlagGroup(val key: String, val flags: List<MuertosFlag>) {
    val part = HolidayBandPart(
        key,
        Rect(flags.first().x - 2f, FLAG_SWAY_TOP, flags.last().x + FLAG_WIDTH + 2f, HOLIDAY_SCENE_REFERENCE_HEIGHT_DP),
    )
}

private val FLAG_GROUPS: List<FlagGroup> by lazy {
    val (left, right) = TITLE_FLAGS.partition { it.x < GAP_MIDDLE }
    listOf(left, right).flatMapIndexed { side, flags ->
        var from = 0
        GROUP_SIZES.mapIndexed { i, size ->
            FlagGroup("flags-$side-$i", flags.subList(from, minOf(from + size, flags.size))).also { from += size }
        }
    }
}

// The flags skip x = 690..910, the app name.
private const val GAP_MIDDLE = 800f

private val WIRE_PATH: Path by lazy { parsePath(TITLE_WIRE) }

// The wire is the palette's secondary text colour at .55, worked out once rather than per frame.
private val WIRE_DARK by lazy { MuertosTheme.palette(true).ts.copy(alpha = .55f) }
private val WIRE_LIGHT by lazy { MuertosTheme.palette(false).ts.copy(alpha = .55f) }

internal object MuertosScene : HolidayScenePainter {
    override val animated = true

    private val parts by lazy { FLAG_GROUPS.map { it.part } }

    override fun layout(band: Rect): HolidaySceneLayout = HolidayBandLayout(HolidayBandSpace(band), parts)

    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) = drawFrame(scope, layout, dark, HOLIDAY_STILL_TIME)

    override fun drawFrame(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean, time: Float) {
        val l = layout as HolidayBandLayout
        val shown = FLAG_GROUPS.filter { l.has(it.key) }
        if (shown.isEmpty()) return
        val still = time == HOLIDAY_STILL_TIME
        l.space.drawElements(scope) {
            // The wire runs from just before the first group that fits to the right end, across the gap over the app name.
            clipRect(shown.minOf { it.part.ref.left } - WIRE_MARGIN, 0f, WIRE_END, HOLIDAY_SCENE_REFERENCE_HEIGHT_DP) {
                drawPath(WIRE_PATH, if (dark) WIRE_DARK else WIRE_LIGHT, style = Stroke(1f))
            }
            shown.forEach { group -> group.flags.forEach { drawFlag(it, time, still) } }
        }
    }

    private fun DrawScope.drawFlag(flag: MuertosFlag, time: Float, still: Boolean) {
        val angle = when {
            still -> 0f
            flag.slowSway -> swayAngle(time, FLAG_SWAY_SLOW_PERIOD, FLAG_SWAY_SLOW_DELAY)
            else -> swayAngle(time, FLAG_SWAY_PERIOD)
        }
        withTransform({
            rotate(angle, Offset(flag.x + FLAG_WIDTH / 2f, flag.y))
            translate(flag.x, flag.y)
        }) { drawPath(flagPath(flag.kind), flag.color) }
    }
}
