@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.beer

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.indagium.model.HolidayPack
import com.indagium.model.TabStripPosition
import com.indagium.ui.ThemeColors
import com.indagium.ui.holiday.*
import com.indagium.ui.theme
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.MonthDay

private val yellowDark = theme(
    0xFF160f09, 0xFF1f160d, 0xFF281c11, 0xFF45321c, 0xFFf7ead2,
    0xFFd4b98e, 0xFF9a8162, 0xFFf2a93b, 0xFF8cbf3f, 0xFFf2a93b,
    onAc = 0xFF241300,
)
private val yellowLight = theme(
    0xFFfbf6ea, 0xFFfffdf6, 0xFFf6ecd6, 0xFFe0caa0, 0xFF2a1a0a,
    0xFF6b5232, 0xFF8a7152, 0xFFa8560a, 0xFF6ea028, 0xFFa8560a,
    hv = Color.Black.copy(alpha = .04f),
)
private val greenDark = theme(
    0xFF0b1a10, 0xFF102417, 0xFF153020, 0xFF24472f, 0xFFeef6ee,
    0xFFb9d3bd, 0xFF7fa086, 0xFF4cc16a, 0xFFe1252e, 0xFF4cc16a,
    onAc = 0xFF04170a,
)
private val greenLight = theme(
    0xFFf4f8f3, 0xFFffffff, 0xFFe8f1e7, 0xFFc4dcc2, 0xFF10261a,
    0xFF3f5e48, 0xFF647f6a, 0xFF13792f, 0xFFe1252e, 0xFF13792f,
    hv = Color.Black.copy(alpha = .04f),
)

private object BeerTabShape : HolidayTabShape {
    override fun shape(position: TabStripPosition): Shape = when (position) {
        TabStripPosition.TOP -> RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
        TabStripPosition.BOTTOM -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 10.dp, bottomEnd = 10.dp)
        TabStripPosition.LEFT -> RoundedCornerShape(topStart = 10.dp, topEnd = 4.dp, bottomStart = 10.dp, bottomEnd = 4.dp)
        TabStripPosition.RIGHT -> RoundedCornerShape(topStart = 4.dp, topEnd = 10.dp, bottomStart = 4.dp, bottomEnd = 10.dp)
    }
}

// The foam cap on the active tab and over each toolbar group: 11 dp tall, its top 6 dp outside the part's outer edge (the parts reserve that room).
// On a light bar it carries the design's 1 px accent shadow so the white head reads against the cream.
private class BeerFoam(private val green: Boolean) : HolidayTabOrnament {
    private val foam = parsePath(
        "M0 7C3 2 8 1 14 3S24 1 32 2 " +
            "44 4 52 2 64 1 72 3 84 1 92 2 98 3 100 6V8C96 10 90 8 84 9S72 8 64 9 52 10 44 8 30 9 22 8 8 10 0 9Z",
    )

    override fun draw(scope: DrawScope, position: TabStripPosition, outerEdgePx: Float, dark: Boolean) = with(scope) {
        val color = if (dark) Color(0xFFfff4dc) else Color.White
        val horizontal = position == TabStripPosition.TOP || position == TabStripPosition.BOTTOM
        val length = if (horizontal) size.width else size.height
        val height = 11.dp.toPx()
        val outset = 6.dp.toPx()
        val edge = when (position) {
            TabStripPosition.TOP, TabStripPosition.LEFT -> outerEdgePx
            else -> (if (horizontal) size.height else size.width) - outerEdgePx
        }
        withTransform({
            when (position) {
                TabStripPosition.TOP -> translate(0f, edge - outset)
                TabStripPosition.BOTTOM -> { translate(length, edge + outset); rotate(180f, Offset.Zero) }
                TabStripPosition.LEFT -> { translate(edge - outset, length); rotate(-90f, Offset.Zero) }
                TabStripPosition.RIGHT -> { translate(edge + outset, 0f); rotate(90f, Offset.Zero) }
            }
            scale(length / 100f, height / 10f, Offset.Zero)
        }) {
            if (!dark) {
                val shadow = (if (green) Color(0xFF13792f) else Color(0xFFa8560a)).copy(alpha = .3f)
                val lift = 1.dp.toPx() * 10f / height
                withTransform({ translate(0f, lift) }) { drawPath(foam, shadow) }
            }
            drawPath(foam, color)
        }
    }
}

internal class BeerTheme(override val pack: HolidayPack, private val green: Boolean) : HolidayTheme {
    override val label = if (green) "Beer time · Green" else "Beer time · Yellow"
    override val occasion = "Beer time"
    override val dates = HolidayDates(MonthDay.of(1, 1), MonthDay.of(12, 31))
    override val automaticLabel = "Fridays from 17:00"

    override fun automaticAt(now: LocalDateTime) = now.dayOfWeek == DayOfWeek.FRIDAY && now.hour >= 17

    override val keywords = listOf("beer", "mug", "foam", "bubbles", "bottle", "friday")
    override val corners = HolidayCorners(tabDp = 10f, toolbarDp = 10, tileDp = 10)
    override val tabShape: HolidayTabShape = BeerTabShape
    override val tabTexture = HolidayTabTexture.PLAIN
    private val foam = BeerFoam(green)
    override val tabOrnament: HolidayTabOrnament = foam
    override val toolbarOrnament: HolidayTabOrnament = foam
    override val icons = BeerIcons
    override val picture = BeerPicture
    override val companion = BeerCompanion(green)
    override val tabScene = BeerScene(green)
    override val effects = listOf(BeerBubbles(if (green) "beer.green.bubbles" else "beer.yellow.bubbles", green))

    override fun palette(dark: Boolean): ThemeColors = if (green) { if (dark) greenDark else greenLight } else { if (dark) yellowDark else yellowLight }

    override fun chrome(dark: Boolean): HolidayChromeColors = if (green) {
        if (dark) HolidayChromeColors(Color(0xFF0d1e13), Color(0xFF173322), Color.Black.copy(alpha = .22f))
        else HolidayChromeColors(Color(0xFFe2ede0), Color(0xFFd3e6d1), Color(0x1A13792f))
    } else {
        if (dark) HolidayChromeColors(Color(0xFF1a120a), Color(0xFF2e2113), Color.Black.copy(alpha = .22f))
        else HolidayChromeColors(Color(0xFFf1e3c4), Color(0xFFead7ae), Color(0x21784614))
    }

    // The tile header's underline: accent dashes with a wood (yellow) or silver (green) gap, 2 dp tall, as in the design.
    override fun tileDividerDashes(dark: Boolean) = HolidayDividerDashes(
        dash = palette(dark).ac,
        gap = if (green) Color(0xFFc9cdd2) else if (dark) Color(0xFF6e4622) else Color(0xFFc79457),
    )

    // The active tab's wash: the accent at 16% (dark) / 11% (light; green light 10%) fading out over the tab's depth.
    override fun activeTabWashAlpha(dark: Boolean): Float = if (dark) .16f else if (green) .10f else .11f
}

internal val BeerYellowTheme = BeerTheme(HolidayPack.BEER_TIME_YELLOW, false)
internal val BeerGreenTheme = BeerTheme(HolidayPack.BEER_TIME_GREEN, true)
