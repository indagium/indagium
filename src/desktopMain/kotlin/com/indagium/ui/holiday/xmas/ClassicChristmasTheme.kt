@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.xmas

import androidx.compose.ui.graphics.Color
import com.indagium.model.HolidayPack
import com.indagium.ui.holiday.*
import com.indagium.ui.holiday.snow.ChristmasBowAndStar
import com.indagium.ui.holiday.snow.FlakeInk
import com.indagium.ui.holiday.snow.LitTabScene
import com.indagium.ui.holiday.snow.SantasSleigh
import com.indagium.ui.holiday.snow.SnowSettling
import com.indagium.ui.holiday.snow.Snowfall
import com.indagium.ui.holiday.snow.StringOfLights
import com.indagium.ui.holiday.snow.WinterIcons
import com.indagium.ui.holiday.snow.span
import com.indagium.ui.theme
import java.time.MonthDay

/**
 * Classic Christmas: a tree, a night village and Rudolph, on pine green (dark) or snowy paper (light), with a ribbon rule under open tiles, pine
 * tabs that turn red when active, a string of lights over the tab bar and Santa's sleigh across the window. An Automatic holiday on December 10 to
 * 27, registered before Cozy Christmas and First snow so it wins those days. The still art is SVG (resources/holiday/xmas); the snow, the bulbs'
 * twinkle, the star and the sleigh are drawn natively.
 */
internal object ClassicChristmasTheme : HolidayTheme {
    override val pack = HolidayPack.CLASSIC_CHRISTMAS
    override val label = "Classic Christmas"
    override val category = HolidayCategory.HOLIDAY
    override val occasion = "Christmas"
    override val dates = HolidayDates(MonthDay.of(12, 10), MonthDay.of(12, 27))
    override val keywords = listOf(
        "christmas", "classic", "xmas", "santa", "sleigh", "rudolph", "reindeer", "tree", "star", "bow", "lights", "ribbon", "snow", "holiday", "december",
    )
    override val corners = HolidayCorners(tabDp = 8f, toolbarDp = 6, tileDp = 8)
    override val tabTexture = HolidayTabTexture.PLAIN
    override val tintTabsByGroup = false

    // The design's inset 3 px line on every tab: pine green, red on the active one.
    override fun tabEdgeLine(active: Boolean, dark: Boolean): Color = if (active) (if (dark) RED_DARK else RED_LIGHT) else if (dark) PINE_DARK else PINE_LIGHT

    // A bow on the active tab's top edge and a twinkling gold star on its corner.
    override val tabOrnament: HolidayTabOrnament = ChristmasBowAndStar

    // The uneven ribbon: red 7, green 6, gold 1, green 6, dark green 2, repeating, 4 dp tall.
    override fun tileDividerBlocks(dark: Boolean) = HolidayDividerBlocks(RIBBON, blockDp = 4f, heightDp = 4f, widthsDp = RIBBON_WIDTHS)

    override val icons = WinterIcons
    override val tabScene: HolidayScenePainter = LitTabScene(HolidayBandScene("xmas", XMAS_TITLE), XMAS_LIGHTS)
    override val picture: HolidayPicturePainter = HolidayLayerPicture("xmas", XMAS_PICTURE)
    override val companion: HolidayCompanionPainter = HolidayLayerCompanion("Rudolph in the Notes corner", "xmas", XMAS_COMPANION)
    override val effects: List<HolidayEffectSpec> = listOf(XMAS_SNOW, XMAS_SETTLING, SantasSleigh, XMAS_LIGHTS)

    override fun palette(dark: Boolean) = if (dark) theme(
        0xFF0c1d14, 0xFF11271b, 0xFF163122, 0xFF23452f, 0xFFf4efe3,
        0xFFc6cbb6, 0xFF8b9680, 0xFFdc323a, 0xFFdc323a, 0xFFe8b84a, onAc = 0xFFffffff,
    ).copy(abg = Color(0xFFdc323a).copy(alpha = .18f), sl = Color(0xFFdc323a).copy(alpha = .2f),
        hv = Color.White.copy(alpha = .05f), searchMatchBg = Color(0xFFe8b84a).copy(alpha = .36f),
        searchCurrentBg = Color(0xFFdc323a).copy(alpha = .55f)) else theme(
        0xFFf8f4ea, 0xFFfffdf7, 0xFFeef3ea, 0xFFcddac8, 0xFF182a1e,
        0xFF4b5e50, 0xFF83917d, 0xFFb8202a, 0xFFb8202a, 0xFFb88a1e,
        hv = Color.Black.copy(alpha = .04f),
    ).copy(abg = Color(0xFFb8202a).copy(alpha = .1f), sl = Color(0xFFb8202a).copy(alpha = .13f),
        searchMatchBg = Color(0xFFc9952a).copy(alpha = .28f), searchCurrentBg = Color(0xFFb8202a).copy(alpha = .28f))

    override fun chrome(dark: Boolean) = if (dark)
        HolidayChromeColors(Color(0xFF0e2418), Color(0xFF1b3a29), Color.Transparent)
    else HolidayChromeColors(Color(0xFFe3ecdd), Color(0xFFd4e2cd), Color.Transparent)
}

// The palette's accent (the design's `#e0343c`, a few steps darker so white text on it reaches AA), and the pine green
// of an idle tab's line (the design's `--green`).
private val RED_DARK = Color(0xFFdc323a)
private val RED_LIGHT = Color(0xFFb8202a)
private val PINE_DARK = Color(0xFF2e8b57)
private val PINE_LIGHT = Color(0xFF2e7d4f)

// The design's ribbon: `#c41e2a`, `#1f6b3f`, `#e8b84a`, `#1f6b3f`, `#0f3d22`.
internal val RIBBON: List<Color> = listOf(0xFFc41e2a, 0xFF1f6b3f, 0xFFe8b84a, 0xFF1f6b3f, 0xFF0f3d22).map(::Color)
internal val RIBBON_WIDTHS: List<Float> = listOf(7f, 6f, 1f, 6f, 2f)

private val XMAS_FLAKES = FlakeInk(Color(0xFFffffff), Color(0xFFb3c6b8))

// 36 flakes of 2 to 4.5 dp over 11 to 19 s, and 16 that settle (the design's Classic Christmas numbers).
internal val XMAS_SNOW = Snowfall("xmas.snow", 36, span(2f, 4.5f), span(.5f, .9f), span(11f, 19f), XMAS_FLAKES)
internal val XMAS_SETTLING = SnowSettling("xmas.landing", 16, span(2.5f, 4.2f), XMAS_FLAKES)

// The bulbs (`['#fff1b3', '#e0343c', '#ffd77a', '#e0343c']`) on the wire (`--wire`).
internal val XMAS_LIGHTS = StringOfLights(
    "xmas.lights", listOf(0xFFfff1b3, 0xFFe0343c, 0xFFffd77a, 0xFFe0343c).map(::Color), Color(0xFF3d7a50), Color(0xFF2e5a36),
)
