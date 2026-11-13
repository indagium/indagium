@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.thanksgiving

import androidx.compose.ui.graphics.Color
import com.indagium.model.HolidayPack
import com.indagium.ui.holiday.*
import com.indagium.ui.theme
import java.time.MonthDay

/**
 * Thanksgiving: a harvest table in a window with a turkey, on warm brown (dark) or cream paper (light). An Automatic holiday on November 21 to 30,
 * wide enough for the fourth Thursday and the weekend after it, registered last. The still art is SVG (resources/holiday/thanksgiving); the sun,
 * geese, leaves, candles, smoke, wheat, fan, bob and blink are drawn natively from the design's keyframes (ui/holiday/HolidayKeyframes.kt).
 */
internal object ThanksTheme : HolidayTheme {
    override val pack = HolidayPack.THANKSGIVING
    override val label = "Thanksgiving"
    override val occasion = "Thanksgiving"
    override val dates = HolidayDates(MonthDay.of(11, 21), MonthDay.of(11, 30))
    override val keywords = listOf(
        "thanksgiving", "harvest", "autumn", "fall", "turkey", "pie", "cornucopia", "leaves", "maple", "acorn", "wishbone", "geese",
    )
    override val corners = HolidayCorners(tabDp = 10f, toolbarDp = 10, tileDp = 12)
    override val tabTexture = HolidayTabTexture.PLAIN
    override val tintTabsByGroup = false
    override val activeTabUnderlineDp = 3f

    // The three-segment divider: rust, gold and olive, the same in both palettes.
    override fun tileDividerColors(dark: Boolean) = dividerColors

    override val icons = ThanksIcons
    override val tabScene: HolidayScenePainter = ThanksScene
    override val picture: HolidayPicturePainter = ThanksPicture
    override val companion: HolidayCompanionPainter = ThanksCompanion
    override val effects: List<HolidayEffectSpec> = THANKS_EFFECTS

    override fun palette(dark: Boolean) = if (dark) theme(
        0xFF1b130e, 0xFF241a13, 0xFF2f2219, 0xFF4b3727, 0xFFf7ead6,
        0xFFdcc4a4, 0xFFa88f74, 0xFFf0b13c, 0xFFf0b13c, 0xFFe0703a, onAc = 0xFF2a1a05,
    ).copy(abg = Color(0xFFf0b13c).copy(alpha = .16f), sl = Color(0xFFf0b13c).copy(alpha = .2f),
        hv = Color.White.copy(alpha = .05f), searchMatchBg = Color(0xFFd64c36).copy(alpha = .38f),
        searchCurrentBg = Color(0xFFf0b13c).copy(alpha = .5f)) else theme(
        0xFFfaf3e6, 0xFFfffdf8, 0xFFf3e6cf, 0xFFdfc69d, 0xFF2d1c0f,
        0xFF674b33, 0xFF7a5f47, 0xFFa8431b, 0xFFa8431b, 0xFF5f6e22,
        hv = Color.Black.copy(alpha = .04f),
    ).copy(abg = Color(0xFFa8431b).copy(alpha = .1f), sl = Color(0xFFa8431b).copy(alpha = .13f),
        searchMatchBg = Color(0xFFf0b13c).copy(alpha = .38f), searchCurrentBg = Color(0xFFa8431b).copy(alpha = .28f))

    override fun chrome(dark: Boolean) = if (dark)
        HolidayChromeColors(Color(0xFF160f0b), Color(0xFF2f2219), Color.Transparent)
    else HolidayChromeColors(Color(0xFFf0dfc0), Color(0xFFe8d2ac), Color.Transparent)
}

private val dividerColors = listOf(0xFFb5451b, 0xFFe8a33a, 0xFF7a8a3a).map(::Color)
