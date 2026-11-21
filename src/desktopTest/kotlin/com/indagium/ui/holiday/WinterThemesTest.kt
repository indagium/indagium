// Dates, counts, colours and sizes under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.graphics.Color
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.ui.holiday.cozy.CozyTheme
import com.indagium.ui.holiday.snow.FirstSnowTheme
import com.indagium.ui.holiday.xmas.ClassicChristmasTheme
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The three winter packs: registry order and dates (the year crossing, the December 10 to 27 overlap), palettes, effects, dividers and tab pieces. */
class WinterThemesTest {
    private val classic get() = HOLIDAY_THEMES.getValue(HolidayPack.CLASSIC_CHRISTMAS)
    private val cozy get() = HOLIDAY_THEMES.getValue(HolidayPack.COZY_CHRISTMAS)
    private val snow get() = HOLIDAY_THEMES.getValue(HolidayPack.FIRST_SNOW)

    private fun auto(pack: HolidayPack) = HolidaySettings(mode = HolidayMode.AUTOMATIC, pack = pack)

    private fun day(month: Int, dayOfMonth: Int, year: Int = 2026) = LocalDate.of(year, month, dayOfMonth)

    @Test fun areRegisteredInTheOrderThatLetsClassicWinAndNewYearsEveBeatFirstSnow() {
        val order = HOLIDAY_THEMES.keys.toList()
        val winter = order.filter { it in setOf(HolidayPack.CLASSIC_CHRISTMAS, HolidayPack.COZY_CHRISTMAS, HolidayPack.NEW_YEARS_EVE, HolidayPack.FIRST_SNOW) }
        assertEquals(listOf(HolidayPack.CLASSIC_CHRISTMAS, HolidayPack.COZY_CHRISTMAS, HolidayPack.NEW_YEARS_EVE, HolidayPack.FIRST_SNOW), winter)
        assertTrue(order.indexOf(HolidayPack.THANKSGIVING) < order.indexOf(HolidayPack.CLASSIC_CHRISTMAS))
        assertEquals(
            listOf(HolidayPack.CLASSIC_CHRISTMAS, HolidayPack.COZY_CHRISTMAS, HolidayPack.FIRST_SNOW),
            HolidayPack.entries.filter { it in winter && it != HolidayPack.NEW_YEARS_EVE }.takeLast(3),
            "the persisted enum order keeps them appended in this order",
        )
        for ((theme, name) in listOf(classic to "Classic Christmas", cozy to "Cozy Christmas", snow to "First snow")) {
            assertEquals(name, theme.label)
            assertEquals(HolidayCategory.HOLIDAY, theme.category)
            assertTrue(theme.supportsAutomatic)
            assertTrue(theme.automaticLabel == null)
        }
        assertEquals("Christmas", classic.occasion)
        assertEquals("Christmas", cozy.occasion)
        assertEquals("Winter", snow.occasion)
        assertTrue("santa" in classic.keywords && "sleigh" in classic.keywords && "rudolph" in classic.keywords)
        assertTrue("gingerbread" in cozy.keywords && "lights" in cozy.keywords)
        assertTrue("snowman" in snow.keywords && "penguin" in snow.keywords)
    }

    @Test fun firstSnowRunsFromDecember1ToJanuary7AcrossTheNewYear() {
        val dates = FirstSnowTheme.dates
        assertFalse(dates.contains(day(11, 30)))
        assertTrue(dates.contains(day(12, 1)))
        assertTrue(dates.contains(day(12, 31)))
        assertTrue(dates.contains(day(1, 1, 2027)))
        assertTrue(dates.contains(day(1, 7, 2027)))
        assertFalse(dates.contains(day(1, 8, 2027)))
        assertFalse(dates.contains(day(6, 15)))
        val settings = auto(HolidayPack.FIRST_SNOW)
        // November 30 still belongs to Thanksgiving, whose window closes that day; First snow opens the next one.
        assertEquals(HolidayPack.THANKSGIVING, activeHolidayPack(settings, day(11, 30)))
        assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(settings, day(12, 1)))
        assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(settings, day(12, 9)))
        assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(settings, day(12, 28)))
        assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(settings, day(12, 31)))
        assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(settings, day(1, 1, 2027)))
        assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(settings, day(1, 7, 2027)))
        assertNull(activeHolidayPack(settings, day(1, 8, 2027)))
        // It is the only window open on December 1 to 9 and January 3 to 7, so another selected pack does not hide it.
        assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(auto(HolidayPack.TULIPS), day(12, 5)))
        assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(auto(HolidayPack.COZY_CHRISTMAS), day(1, 3, 2027)))
    }

    @Test fun bothChristmasPacksRunDecember10To27() {
        for (theme in listOf(classic, cozy)) {
            assertFalse(theme.dates.contains(day(12, 9)), "${theme.pack} opens on the 10th")
            assertTrue(theme.dates.contains(day(12, 10)))
            assertTrue(theme.dates.contains(day(12, 27)))
            assertFalse(theme.dates.contains(day(12, 28)), "${theme.pack} closes after the 27th")
            assertFalse(theme.dates.contains(day(1, 2, 2027)))
        }
    }

    @Test fun onTheOverlapClassicWinsByDefaultAndTheSelectedPackWinsWhenItIsOpen() {
        // The default selection is the pumpkin, which is closed in December: the first registered open window is Classic Christmas.
        for (d in listOf(day(12, 10), day(12, 18), day(12, 24), day(12, 27))) {
            assertEquals(HolidayPack.CLASSIC_CHRISTMAS, activeHolidayPack(auto(HolidayPack.PUMPKIN_PATCH), d), "$d")
            assertEquals(HolidayPack.CLASSIC_CHRISTMAS, activeHolidayPack(HolidaySettings(mode = HolidayMode.AUTOMATIC), d), "$d default")
            assertEquals(HolidayPack.COZY_CHRISTMAS, activeHolidayPack(auto(HolidayPack.COZY_CHRISTMAS), d), "$d cozy selected")
            assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(auto(HolidayPack.FIRST_SNOW), d), "$d first snow selected")
            assertEquals(HolidayPack.CLASSIC_CHRISTMAS, activeHolidayPack(auto(HolidayPack.CLASSIC_CHRISTMAS), d), "$d classic selected")
        }
        // After the 27th New Year's Eve opens (it is registered before First snow); a selected First snow keeps its own days.
        assertEquals(HolidayPack.NEW_YEARS_EVE, activeHolidayPack(auto(HolidayPack.COZY_CHRISTMAS), day(12, 28)))
        assertEquals(HolidayPack.NEW_YEARS_EVE, activeHolidayPack(auto(HolidayPack.CLASSIC_CHRISTMAS), day(12, 30)))
        assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(auto(HolidayPack.FIRST_SNOW), day(12, 30)))
    }

    @Test fun usesTheDesignsPalettesAndChrome() {
        assertEquals(Color(0xFF0a1520), snow.palette(true).bg)
        assertEquals(Color(0xFF7cc4ff), snow.palette(true).ac)
        assertEquals(Color(0xFF03111d), snow.palette(true).onAc)
        assertEquals(Color(0xFFedf3f8), snow.palette(false).bg)
        assertEquals(Color(0xFF1d6fb8), snow.palette(false).ac)
        assertEquals(Color(0xFF0c1a28), snow.chrome(true).tabBar)
        assertEquals(Color(0xFFcadbea), snow.chrome(false).tabBg)

        assertEquals(Color(0xFF170f0d), cozy.palette(true).bg)
        assertEquals(Color(0xFFec5a50), cozy.palette(true).ac)
        assertEquals(Color(0xFF3fa46a), cozy.palette(true).seq2)
        assertEquals(Color(0xFFfbf6ee), cozy.palette(false).bg)
        assertEquals(Color(0xFFb3261e), cozy.palette(false).ac)
        assertEquals(Color(0xFF1b1210), cozy.chrome(true).tabBar)
        assertEquals(Color(0xFFe8d6bb), cozy.chrome(false).tabBg)

        assertEquals(Color(0xFF0c1d14), classic.palette(true).bg)
        assertEquals(Color(0xFFdc323a), classic.palette(true).ac, "the design's red, a few steps darker so white text on it is AA")
        assertEquals(Color(0xFFe8b84a), classic.palette(true).seq2)
        assertEquals(Color(0xFFf8f4ea), classic.palette(false).bg)
        assertEquals(Color(0xFFb8202a), classic.palette(false).ac)
        assertEquals(Color(0xFF0e2418), classic.chrome(true).tabBar)
        assertEquals(Color(0xFFd4e2cd), classic.chrome(false).tabBg)

        for (theme in listOf(classic, cozy, snow)) {
            assertEquals(HolidayTabTexture.PLAIN, theme.tabTexture)
            assertFalse(theme.tintTabsByGroup)
            assertEquals("Find", assertNotNull(theme.icons?.findIcon(Color.Black)).name)
            assertNotNull(theme.picture)
            assertNotNull(theme.companion)
            assertNotNull(theme.tabScene)
        }
        assertEquals(HolidayCorners(tabDp = 8f, toolbarDp = 9, tileDp = 10), snow.corners)
        assertEquals(HolidayCorners(tabDp = 3f, toolbarDp = 4, tileDp = 6), cozy.corners)
        assertEquals(HolidayCorners(tabDp = 8f, toolbarDp = 6, tileDp = 8), classic.corners)
        assertEquals("Penguin in the Notes corner", snow.companion?.label)
        assertEquals("Gingerbread man in the Notes corner", cozy.companion?.label)
        assertEquals("Rudolph in the Notes corner", classic.companion?.label)
    }

    @Test fun effectsAreLabelledAndMatchTheDesignsList() {
        assertEquals(listOf("snow.fall", "snow.landing"), snow.effects.map { it.id })
        assertEquals(listOf("Snowfall", "Snow settles on tabs and tiles"), snow.effects.map { it.label })
        assertEquals(listOf("cozy.snow", "cozy.lights"), cozy.effects.map { it.id })
        assertEquals(listOf("Snowfall", "String of lights"), cozy.effects.map { it.label })
        assertEquals(listOf("xmas.snow", "xmas.landing", "xmas.sleigh", "xmas.lights"), classic.effects.map { it.id })
        assertEquals(listOf("Snowfall", "Snow settles on tabs and tiles", "Santa's sleigh", "String of lights"), classic.effects.map { it.label })
        assertTrue((snow.effects + cozy.effects + classic.effects).all { it.defaultOn })
        assertTrue(snow.effects[1] is HolidayLandingEffect && classic.effects[1] is HolidayLandingEffect)
        assertTrue(cozy.effects[1] is HolidaySceneEffect && classic.effects[3] is HolidaySceneEffect)
        assertTrue(cozy.effects.none { it is HolidayLandingEffect }, "Cozy Christmas has no settling snow in the design")
        assertTrue(snow.effects.none { it is HolidaySceneEffect }, "First snow has no string of lights")
    }

    @Test fun particleCountsAreTheDesignsAndScaleWithTheIntensity() {
        fun count(theme: HolidayTheme, index: Int, countScale: Float = 1f, speed: Float = 1f) =
            (theme.effects[index] as HolidayParticleEffect).spawn(7, countScale, speed).count
        assertEquals(56, count(snow, 0))
        assertEquals(22, count(snow, 1))
        assertEquals(28, count(cozy, 0))
        assertEquals(36, count(classic, 0))
        assertEquals(16, count(classic, 1))
        assertEquals(1, count(classic, 2))
        assertEquals(22, count(snow, 0, countScale = .4f), "Calm thins the snow")
        for (theme in listOf(snow, classic)) {
            for (i in theme.effects.indices) {
                val effect = theme.effects[i] as? HolidayParticleEffect ?: continue
                assertEquals(0, effect.spawn(7, 1f, 0f).count, "${effect.id} stands still at speed 0")
            }
        }
        assertEquals(0, count(cozy, 0, speed = 0f))
    }

    @Test fun offIntensityKeepsOnlyTheStringsOfLightsAndSwitchesWork() {
        for (theme in listOf(snow, cozy, classic)) {
            val settings = HolidaySettings(mode = HolidayMode.ALWAYS, pack = theme.pack)
            val full = resolveHolidayEffects(settings, theme)
            assertEquals(theme.effects.size, full.activeEffects.size)
            for (effect in full.activeEffects) {
                val toggled = resolveHolidayEffects(settings.copy(effectToggles = mapOf(effect.id to false)), theme)
                assertFalse(toggled.activeEffects.any { it.id == effect.id })
            }
            val off = resolveHolidayEffects(settings.copy(intensity = HolidayIntensity.OFF), theme)
            assertTrue(off.activeEffects.all { it is HolidaySceneEffect }, "${theme.pack}: only static decorations survive Off")
            assertEquals(theme.effects.filterIsInstance<HolidaySceneEffect>().map { it.id }, off.activeEffects.map { it.id })
        }
    }

    @Test fun classicHasTheUnevenRibbonAndCozyTheCandyCaneBlocks() {
        for (dark in listOf(true, false)) {
            val ribbon = assertNotNull(classic.tileDividerBlocks(dark))
            assertEquals(listOf(0xFFc41e2a, 0xFF1f6b3f, 0xFFe8b84a, 0xFF1f6b3f, 0xFF0f3d22).map { Color(it) }, ribbon.colors)
            assertEquals(listOf(7f, 6f, 1f, 6f, 2f), ribbon.widthsDp, "red 7, green 6, gold 1, green 6, dark green 2")
            assertEquals(4f, ribbon.heightDp)
            val segments = holidayDividerSegments(ribbon, 44f)
            assertEquals(listOf(0f, 7f, 13f, 14f, 20f, 22f, 29f, 35f, 36f, 42f), segments.map { it.startDp })

            val cane = assertNotNull(cozy.tileDividerBlocks(dark))
            assertEquals(listOf(cozy.palette(dark).ac, cozy.palette(dark).p), cane.colors, "accent and panel alternate")
            assertEquals(7f, cane.blockDp)
            assertEquals(3f, cane.heightDp)
            assertNull(cane.widthsDp)

            assertNull(snow.tileDividerBlocks(dark), "First snow keeps the plain divider, as the design does")
            for (theme in listOf(classic, cozy, snow)) {
                assertNull(theme.tileDividerColors(dark))
                assertNull(theme.tileDividerGradient(dark))
                assertNull(theme.tileDividerDashes(dark))
            }
        }
        val withBlocks = setOf(HolidayPack.DIA_DE_MUERTOS, HolidayPack.CLASSIC_CHRISTMAS, HolidayPack.COZY_CHRISTMAS)
        assertTrue(HOLIDAY_THEMES.values.filter { it.pack !in withBlocks }.all { it.tileDividerBlocks(true) == null })
    }

    @Test fun tabPiecesFollowTheDesign() {
        for (theme in listOf(classic, cozy, snow)) assertNotNull(theme.tabOrnament, "${theme.pack} dresses its active tab")
        assertTrue(snow.tabOrnament?.animated == false, "the snow cap does not move")
        assertTrue(cozy.tabOrnament?.animated == false, "the bow does not move")
        assertTrue(classic.tabOrnament?.animated == true, "the gold star twinkles")
        assertNotNull(cozy.tabShape, "the gift tag")
        assertNull(classic.tabShape)
        assertNull(snow.tabShape)
        assertEquals(14f, cozy.tabLabelInsetDp, "the label starts after the tag's point and hole")
        assertNull(cozy.tabLabelPrefix)
        assertEquals(0f, snow.tabLabelInsetDp)
        assertEquals(0f, classic.tabLabelInsetDp)
        for (dark in listOf(true, false)) {
            assertEquals(classic.palette(dark).ac, classic.tabEdgeLine(true, dark), "red on the active tab")
            val green = assertNotNull(classic.tabEdgeLine(false, dark))
            assertTrue(green.green > green.red && green.green > green.blue, "pine green on an idle tab")
            assertNull(cozy.tabEdgeLine(true, dark))
            assertNull(snow.tabEdgeLine(false, dark))
            assertTrue(cozy.activeTabWashAlpha(dark)!! > 0f)
            assertNull(snow.activeTabWashAlpha(dark))
        }
        assertEquals(3f, classic.tabEdgeLineDp)
        assertTrue(HOLIDAY_THEMES.values.filter { it !== classic }.all { it.tabEdgeLine(false, true) == null && it.tabEdgeLine(true, false) == null })
        assertEquals(CozyTheme.pack, cozy.pack)
        assertEquals(ClassicChristmasTheme.pack, classic.pack)
    }
}
