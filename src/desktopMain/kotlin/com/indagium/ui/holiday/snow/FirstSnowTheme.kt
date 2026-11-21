@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.snow

import androidx.compose.ui.graphics.Color
import com.indagium.model.HolidayPack
import com.indagium.ui.holiday.*
import com.indagium.ui.theme
import java.time.MonthDay

/**
 * First snow: a snowy hillside with a snowman and a penguin, on a frosty blue night (dark) or pale morning (light). An Automatic holiday on
 * December 1 to January 7, which crosses the new year, registered last of the winter packs so Classic and Cozy Christmas win December 10 to 27.
 * The still art is SVG (resources/holiday/snow); the snowfall and the snow settling on the tabs and tiles are drawn natively.
 */
internal object FirstSnowTheme : HolidayTheme {
    override val pack = HolidayPack.FIRST_SNOW
    override val label = "First snow"
    override val category = HolidayCategory.HOLIDAY
    override val occasion = "Winter"
    override val dates = HolidayDates(MonthDay.of(12, 1), MonthDay.of(1, 7))
    override val keywords = listOf("snow", "winter", "frost", "snowman", "penguin", "snowflake", "ice", "cold", "snowfall", "december", "january")
    override val corners = HolidayCorners(tabDp = 8f, toolbarDp = 9, tileDp = 10)
    override val tabTexture = HolidayTabTexture.PLAIN
    override val tintTabsByGroup = false

    // A snow cap over the active tab (the design caps every tab and toolbar button; a theme's ornament belongs to the active tab).
    override val tabOrnament: HolidayTabOrnament = SnowCap

    override val icons = WinterIcons
    override val tabScene: HolidayScenePainter = HolidayBandScene("snow", FIRST_SNOW_TITLE)
    override val picture: HolidayPicturePainter = HolidayLayerPicture("snow", FIRST_SNOW_PICTURE)
    override val companion: HolidayCompanionPainter = HolidayLayerCompanion("Penguin in the Notes corner", "snow", FIRST_SNOW_COMPANION)
    override val effects: List<HolidayEffectSpec> = listOf(FIRST_SNOW_FALL, FIRST_SNOW_SETTLING)

    override fun palette(dark: Boolean) = if (dark) theme(
        0xFF0a1520, 0xFF0f1f2e, 0xFF132637, 0xFF1f3549, 0xFFdfeaf4,
        0xFF9db3c7, 0xFF6e859a, 0xFF7cc4ff, 0xFF7cc4ff, 0xFFf0883e, onAc = 0xFF03111d,
    ).copy(abg = Color(0xFF7cc4ff).copy(alpha = .14f), sl = Color(0xFF7cc4ff).copy(alpha = .18f),
        hv = Color.White.copy(alpha = .05f), searchMatchBg = Color(0xFFf0883e).copy(alpha = .34f),
        searchCurrentBg = Color(0xFF7cc4ff).copy(alpha = .45f)) else theme(
        0xFFedf3f8, 0xFFfbfdff, 0xFFe2ecf5, 0xFFc4d4e3, 0xFF12233a,
        0xFF4b6280, 0xFF8195aa, 0xFF1d6fb8, 0xFF1d6fb8, 0xFFf0883e,
        hv = Color.Black.copy(alpha = .04f),
    ).copy(abg = Color(0xFF1d6fb8).copy(alpha = .11f), sl = Color(0xFF1d6fb8).copy(alpha = .14f),
        searchMatchBg = Color(0xFFf0883e).copy(alpha = .26f), searchCurrentBg = Color(0xFF1d6fb8).copy(alpha = .3f))

    override fun chrome(dark: Boolean) = if (dark)
        HolidayChromeColors(Color(0xFF0c1a28), Color(0xFF173045), Color.Transparent)
    else HolidayChromeColors(Color(0xFFd7e5f1), Color(0xFFcadbea), Color.Transparent)
}

private val FLAKES = FlakeInk(Color(0xFFffffff), Color(0xFFa3bed8))

// 56 flakes of 2 to 5 dp over 8 to 15 s, and 22 that settle (the design's First snow numbers).
internal val FIRST_SNOW_FALL = Snowfall("snow.fall", 56, span(2f, 5f), span(.55f, .95f), span(8f, 15f), FLAKES)
internal val FIRST_SNOW_SETTLING = SnowSettling("snow.landing", 22, span(2.5f, 4.5f), FLAKES)
