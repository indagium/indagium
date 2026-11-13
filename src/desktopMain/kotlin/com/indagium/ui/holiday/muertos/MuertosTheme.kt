@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.muertos

import androidx.compose.ui.graphics.Color
import com.indagium.model.HolidayPack
import com.indagium.ui.holiday.*
import com.indagium.ui.theme
import java.time.MonthDay

/**
 * Día de Muertos: an ofrenda with marigolds, a calavera and papel picado, on a plum night (dark) or warm marigold paper (light). An Automatic
 * holiday on October 31 to November 2, registered after the Halloween packs. The still art is SVG (resources/holiday/muertos); the candles,
 * smoke, petals, monarchs, flags and the blink are drawn natively from the design's keyframes.
 */
internal object MuertosTheme : HolidayTheme {
    override val pack = HolidayPack.DIA_DE_MUERTOS
    override val label = "Día de Muertos"
    override val occasion = "Día de Muertos"
    override val dates = HolidayDates(MonthDay.of(10, 31), MonthDay.of(11, 2))
    override val keywords = listOf(
        "muertos", "día de muertos", "dia de muertos", "day of the dead", "marigold", "cempasúchil", "cempasuchil",
        "calavera", "sugar skull", "ofrenda", "papel picado", "monarch", "mexico",
    )
    override val corners = HolidayCorners(tabDp = 8f, toolbarDp = 10, tileDp = 10)
    override val tabTexture = HolidayTabTexture.PLAIN
    override val tintTabsByGroup = false
    override val activeTabUnderlineDp = 3f

    // The papel picado stripe: five hard blocks, repeating.
    override fun tileDividerBlocks(dark: Boolean) = HolidayDividerBlocks(stripeColors, blockDp = 34f, heightDp = 3f)

    override val icons = MuertosIcons
    override val tabScene: HolidayScenePainter = MuertosScene
    override val picture: HolidayPicturePainter = MuertosPicture
    override val companion: HolidayCompanionPainter = MuertosCompanion
    override val effects: List<HolidayEffectSpec> = MUERTOS_EFFECTS

    override fun palette(dark: Boolean) = if (dark) theme(
        0xFF180c22, 0xFF21122e, 0xFF2b1a3a, 0xFF452c5c, 0xFFfbeedd,
        0xFFdcc6d6, 0xFFa892ad, 0xFFff5fae, 0xFFff5fae, 0xFFffb03a, onAc = 0xFF2e0318,
    ).copy(abg = Color(0xFFff5fae).copy(alpha = .16f), sl = Color(0xFFff5fae).copy(alpha = .2f),
        hv = Color.White.copy(alpha = .05f), searchMatchBg = Color(0xFFffb020).copy(alpha = .32f),
        searchCurrentBg = Color(0xFFff5fae).copy(alpha = .5f)) else theme(
        0xFFfff3e0, 0xFFfffbf3, 0xFFfde9cd, 0xFFecc48f, 0xFF2c1230,
        0xFF5a3358, 0xFF77506f, 0xFFc4006a, 0xFFc4006a, 0xFFb4530a,
        hv = Color.Black.copy(alpha = .04f),
    ).copy(abg = Color(0xFFc4006a).copy(alpha = .1f), sl = Color(0xFFc4006a).copy(alpha = .12f),
        searchMatchBg = Color(0xFFff9a1f).copy(alpha = .32f), searchCurrentBg = Color(0xFFc4006a).copy(alpha = .28f))

    override fun chrome(dark: Boolean) = if (dark)
        HolidayChromeColors(Color(0xFF140a1d), Color(0xFF2b1a3a), Color.Transparent)
    else HolidayChromeColors(Color(0xFFfbe0b8), Color(0xFFf7d3a3), Color.Transparent)
}

private val stripeColors = listOf(0xFFe6007e, 0xFFff9a1f, 0xFF12b5b0, 0xFF8a3fd1, 0xFFffc02e).map(::Color)
