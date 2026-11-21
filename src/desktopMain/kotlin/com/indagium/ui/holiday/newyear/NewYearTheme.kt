@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.newyear

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.indagium.model.HolidayPack
import com.indagium.model.TabStripPosition
import com.indagium.ui.holiday.*
import com.indagium.ui.holiday.snow.drawTabStar
import com.indagium.ui.holiday.snow.withTabFrame
import com.indagium.ui.theme
import java.time.MonthDay

private object PillTabShape : HolidayTabShape {
    override fun shape(position: TabStripPosition): Shape = RoundedCornerShape(50)
}

private val STAR_DARK = Color(0xFFf0c75e)
private val STAR_LIGHT = Color(0xFF8a6a10)

/** New Year's Eve's ornament: a twinkling star in the accent colour on the active pill's corner (the design's `.tab.on .spark`). */
internal object NewYearStar : HolidayTabOrnament {
    override val animated = true

    override fun draw(scope: DrawScope, position: TabStripPosition, outerEdgePx: Float, dark: Boolean) =
        drawFrame(scope, position, outerEdgePx, dark, HOLIDAY_STILL_TIME)

    override fun drawFrame(scope: DrawScope, position: TabStripPosition, outerEdgePx: Float, dark: Boolean, time: Float) = with(scope) {
        withTabFrame(position, outerEdgePx) { length -> drawTabStar(length, if (dark) STAR_DARK else STAR_LIGHT, time) }
    }
}

/**
 * New Year's Eve: a city skyline under fireworks, a clock and balloons, on a midnight navy (dark) or warm paper (light), with gold accents and pill tabs
 * topped by a twinkling star. An Automatic holiday on December 28 to January 2, which crosses the new year, registered before First snow so it wins
 * those days. The still art is SVG (resources/holiday/newyear); the confetti, its settling on the tabs and tiles, and the fireworks are drawn natively.
 */
internal object NewYearTheme : HolidayTheme {
    override val pack = HolidayPack.NEW_YEARS_EVE
    override val label = "New Year's Eve"
    override val category = HolidayCategory.HOLIDAY
    override val occasion = "New Year"
    override val dates = HolidayDates(MonthDay.of(12, 28), MonthDay.of(1, 2))
    override val keywords = listOf(
        "new year", "new year's eve", "nye", "fireworks", "confetti", "champagne", "midnight", "countdown", "party", "balloons", "clock", "december", "january",
    )
    override val corners = HolidayCorners(tabDp = 16f, toolbarDp = 16, tileDp = 10)
    override val tabShape: HolidayTabShape = PillTabShape
    override val tabCrossAxisInsetDp = 5f
    override val tabTexture = HolidayTabTexture.PLAIN
    override val tintTabsByGroup = false
    override val tabOrnament: HolidayTabOrnament = NewYearStar

    override val icons = NewYearIcons
    override val tabScene: HolidayScenePainter = HolidayBandScene("newyear", NEW_YEAR_TITLE)
    override val picture: HolidayPicturePainter = HolidayLayerPicture("newyear", NEW_YEAR_PICTURE)
    override val companion: HolidayCompanionPainter = HolidayLayerCompanion("Balloons in the Notes corner", "newyear", NEW_YEAR_COMPANION)
    override val effects: List<HolidayEffectSpec> = listOf(Confetti, ConfettiSettling, Fireworks)

    override fun palette(dark: Boolean) = if (dark) theme(
        0xFF09091a, 0xFF11112a, 0xFF171734, 0xFF2a2a4f, 0xFFeeedf8,
        0xFFb2b1ce, 0xFF7c7ba0, 0xFFf0c75e, 0xFFf0c75e, 0xFFff6fae, onAc = 0xFF1c1403,
    ).copy(abg = Color(0xFFf0c75e).copy(alpha = .14f), sl = Color(0xFFf0c75e).copy(alpha = .17f),
        hv = Color.White.copy(alpha = .05f), searchMatchBg = Color(0xFFff6fae).copy(alpha = .32f),
        searchCurrentBg = Color(0xFFf0c75e).copy(alpha = .5f)) else theme(
        0xFFf7f5ef, 0xFFffffff, 0xFFefebe0, 0xFFddd4bf, 0xFF1c1a2e,
        0xFF5a5773, 0xFF8e8ba3, 0xFF8a6a10, 0xFF8a6a10, 0xFFdb2777,
        hv = Color.Black.copy(alpha = .04f),
    ).copy(abg = Color(0xFF8a6a10).copy(alpha = .11f), sl = Color(0xFF8a6a10).copy(alpha = .14f),
        searchMatchBg = Color(0xFFdb2777).copy(alpha = .2f), searchCurrentBg = Color(0xFF8a6a10).copy(alpha = .3f))

    override fun chrome(dark: Boolean) = if (dark)
        HolidayChromeColors(Color(0xFF0c0c22), Color(0xFF1c1c40), Color.Transparent)
    else HolidayChromeColors(Color(0xFFe8e1d0), Color(0xFFe0d7c3), Color.Transparent)
}
