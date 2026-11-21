@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.devday

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.indagium.model.HolidayPack
import com.indagium.ui.holiday.*
import com.indagium.ui.theme
import java.time.MonthDay

/**
 * Programmers' Day: a terminal with a robot and falling bits, on phosphor green on black (dark) or a cream manual (light). Square corners,
 * monospace labels with a `~/ ` before each tab's name and a blinking cursor on the active one, and a dashed accent rule under open tiles. An
 * Automatic holiday on September 12 to 14 (the 256th day of the year is the 12th or 13th), clear of every other window. The still art is SVG
 * (resources/holiday/devday); the binary rain and the scanlines are drawn natively. The light accent is the design's `#1f8a3c` a few steps darker so
 * white text on it reaches AA.
 */
internal object DevDayTheme : HolidayTheme {
    override val pack = HolidayPack.PROGRAMMERS_DAY
    override val label = "Programmers' Day"
    override val category = HolidayCategory.HOLIDAY
    override val occasion = "Programmers' Day"
    override val dates = HolidayDates(MonthDay.of(9, 12), MonthDay.of(9, 14))
    override val keywords = listOf(
        "programmer", "programmers", "developer", "code", "coding", "terminal", "matrix", "binary", "robot", "256", "hacker", "crt", "scanlines", "september",
    )
    override val corners = HolidayCorners(tabDp = 0f, toolbarDp = 0, tileDp = 0)
    override val labelFontFamily: FontFamily = FontFamily.Monospace
    override val tabLabelPrefix = "~/ "
    override val activeTabCursor = true
    override val tabTexture = HolidayTabTexture.PLAIN
    override val tintTabsByGroup = false

    // The design's `1px dashed var(--accent)`: short accent dashes with open gaps, 1 dp tall.
    override fun tileDividerDashes(dark: Boolean) = HolidayDividerDashes(palette(dark).ac, Color.Transparent, dashDp = 3f, gapDp = 3f, heightDp = 1f)

    override val icons = DevDayIcons
    override val tabScene: HolidayScenePainter = HolidayBandScene("devday", DEV_DAY_TITLE)
    override val picture: HolidayPicturePainter = HolidayLayerPicture("devday", DEV_DAY_PICTURE)
    override val companion: HolidayCompanionPainter = HolidayLayerCompanion("Robot in the Notes corner", "devday", DEV_DAY_COMPANION)
    override val effects: List<HolidayEffectSpec> = listOf(BinaryRain, CrtScanlines)

    override fun palette(dark: Boolean) = if (dark) theme(
        0xFF050a06, 0xFF0a120c, 0xFF0e1a11, 0xFF1d3b22, 0xFFb9f5c4,
        0xFF6fd389, 0xFF4a9a5e, 0xFF39ff6a, 0xFF39ff6a, 0xFFffbf00, onAc = 0xFF021006,
    ).copy(abg = Color(0xFF39ff6a).copy(alpha = .12f), sl = Color(0xFF39ff6a).copy(alpha = .16f),
        hv = Color.White.copy(alpha = .05f), searchMatchBg = Color(0xFFffbf00).copy(alpha = .32f),
        searchCurrentBg = Color(0xFF39ff6a).copy(alpha = .4f)) else theme(
        0xFFfbf8ef, 0xFFffffff, 0xFFf2efe3, 0xFFcfc9b2, 0xFF1d2a1f,
        0xFF3f6b4a, 0xFF7d8a7a, 0xFF1b823a, 0xFF1b823a, 0xFFcc8c00,
        hv = Color.Black.copy(alpha = .04f),
    ).copy(abg = Color(0xFF1b823a).copy(alpha = .1f), sl = Color(0xFF1b823a).copy(alpha = .13f),
        searchMatchBg = Color(0xFFcc8c00).copy(alpha = .25f), searchCurrentBg = Color(0xFF1b823a).copy(alpha = .28f))

    override fun chrome(dark: Boolean) = if (dark)
        HolidayChromeColors(Color(0xFF070e09), Color(0xFF0f1d12), Color.Transparent)
    else HolidayChromeColors(Color(0xFFebe7d8), Color(0xFFe2ddca), Color.Transparent)
}
