@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.cozy

import androidx.compose.ui.graphics.Color
import com.indagium.model.HolidayPack
import com.indagium.ui.holiday.*
import com.indagium.ui.holiday.snow.ChristmasBow
import com.indagium.ui.holiday.snow.FlakeInk
import com.indagium.ui.holiday.snow.LitTabScene
import com.indagium.ui.holiday.snow.Snowfall
import com.indagium.ui.holiday.snow.StringOfLights
import com.indagium.ui.holiday.snow.WinterIcons
import com.indagium.ui.holiday.snow.span
import com.indagium.ui.theme
import java.time.MonthDay

/**
 * Cozy Christmas: gift-tag tabs with a punched hole and a bow on the active one, a candy-cane tile rule, a string of lights over the tab bar,
 * a gingerbread man and a tree, on a warm brown night (dark) or cream paper (light). An Automatic holiday on December 10 to 27, registered after
 * Classic Christmas, which wins the same days when selected or first. The still art is SVG (resources/holiday/cozy); the bulbs' twinkle and the
 * snow are drawn natively.
 */
internal object CozyTheme : HolidayTheme {
    override val pack = HolidayPack.COZY_CHRISTMAS
    override val label = "Cozy Christmas"
    override val category = HolidayCategory.HOLIDAY
    override val occasion = "Christmas"
    override val dates = HolidayDates(MonthDay.of(12, 10), MonthDay.of(12, 27))
    override val keywords = listOf(
        "christmas", "cozy", "xmas", "gift", "tag", "bow", "lights", "gingerbread", "tree", "snow", "winter", "candy cane", "holiday", "december",
    )
    override val corners = HolidayCorners(tabDp = 3f, toolbarDp = 4, tileDp = 6)
    override val tabTexture = HolidayTabTexture.PLAIN
    override val tintTabsByGroup = false
    override val tabShape: HolidayTabShape = GiftTagShape

    // The gift tag's point and hole take the first 26 dp of a tab (the design's label starts there): 14 dp past the tab's own 12 dp start padding.
    override val tabLabelInsetDp = 14f
    override val tabOrnament: HolidayTabOrnament = ChristmasBow

    // The active tab is washed with the accent (the design's `--accent-bg`) rather than left on the panel colour.
    override fun activeTabWashAlpha(dark: Boolean): Float = if (dark) .22f else .14f

    // The candy-cane rule: accent and panel blocks alternating (the design draws them as diagonal stripes of 5 px).
    override fun tileDividerBlocks(dark: Boolean) = palette(dark).let { HolidayDividerBlocks(listOf(it.ac, it.p), blockDp = 7f, heightDp = 3f) }

    override val icons = WinterIcons
    override val tabScene: HolidayScenePainter = LitTabScene(null, COZY_LIGHTS)
    override val picture: HolidayPicturePainter = HolidayLayerPicture("cozy", COZY_PICTURE)
    override val companion: HolidayCompanionPainter = HolidayLayerCompanion("Gingerbread man in the Notes corner", "cozy", COZY_COMPANION)
    override val effects: List<HolidayEffectSpec> = listOf(COZY_SNOW, COZY_LIGHTS)

    override fun palette(dark: Boolean) = if (dark) theme(
        0xFF170f0d, 0xFF201613, 0xFF281c18, 0xFF3b2a23, 0xFFf5eadc,
        0xFFc8b19c, 0xFF937c6a, 0xFFec5a50, 0xFFec5a50, 0xFF3fa46a, onAc = 0xFF1d0605,
    ).copy(abg = Color(0xFFec5a50).copy(alpha = .15f), sl = Color(0xFFec5a50).copy(alpha = .18f),
        hv = Color.White.copy(alpha = .05f), searchMatchBg = Color(0xFF3fa46a).copy(alpha = .38f),
        searchCurrentBg = Color(0xFFec5a50).copy(alpha = .5f)) else theme(
        0xFFfbf6ee, 0xFFfffdf9, 0xFFf4ead9, 0xFFe2d1b9, 0xFF2a1a12,
        0xFF6e5646, 0xFF9e8774, 0xFFb3261e, 0xFFb3261e, 0xFF2e7d4f,
        hv = Color.Black.copy(alpha = .04f),
    ).copy(abg = Color(0xFFb3261e).copy(alpha = .1f), sl = Color(0xFFb3261e).copy(alpha = .13f),
        searchMatchBg = Color(0xFF2e7d4f).copy(alpha = .25f), searchCurrentBg = Color(0xFFb3261e).copy(alpha = .28f))

    override fun chrome(dark: Boolean) = if (dark)
        HolidayChromeColors(Color(0xFF1b1210), Color(0xFF33231e), Color.Transparent)
    else HolidayChromeColors(Color(0xFFefe2cd), Color(0xFFe8d6bb), Color.Transparent)
}

// The design's bulbs (`['#e5484d', '#f2c14e', '#3fa46a', '#5aa9e6']`) on its wire (`--wire`).
internal val COZY_LIGHTS = StringOfLights(
    "cozy.lights", listOf(0xFFe5484d, 0xFFf2c14e, 0xFF3fa46a, 0xFF5aa9e6).map(::Color), Color(0xFF4b7a52), Color(0xFF2e5a36),
)

// 28 small flakes, slow (the design's Cozy snowfall).
internal val COZY_SNOW = Snowfall(
    "cozy.snow", 28, span(2f, 4f), span(.45f, .8f), span(13f, 21f), FlakeInk(Color(0xFFfff6ea), Color(0xFFc9b7a1)),
)
