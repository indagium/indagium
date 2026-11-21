// Dates, counts, colours and sizes under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** New Year's Eve, Programmers' Day and Retro: registry, dates (the new-year crossing and overlap, the hand-picked Retro), palettes, effects, rules, tabs. */
class PartyThemesTest {
    private val newYear get() = HOLIDAY_THEMES.getValue(HolidayPack.NEW_YEARS_EVE)
    private val devDay get() = HOLIDAY_THEMES.getValue(HolidayPack.PROGRAMMERS_DAY)
    private val retro get() = HOLIDAY_THEMES.getValue(HolidayPack.RETRO)

    private fun auto(pack: HolidayPack) = HolidaySettings(mode = HolidayMode.AUTOMATIC, pack = pack)

    private fun day(month: Int, dayOfMonth: Int, year: Int = 2026) = LocalDate.of(year, month, dayOfMonth)

    @Test fun areAppendedLastToTheEnumAndRegisteredWhereTheirDatesNeedThem() {
        assertEquals(
            listOf(HolidayPack.NEW_YEARS_EVE, HolidayPack.PROGRAMMERS_DAY, HolidayPack.RETRO),
            HolidayPack.entries.takeLast(3),
        )
        val order = HOLIDAY_THEMES.keys.toList()
        assertTrue(order.indexOf(HolidayPack.NEW_YEARS_EVE) < order.indexOf(HolidayPack.FIRST_SNOW), "New Year's Eve wins the days it shares with First snow")
        assertTrue(order.indexOf(HolidayPack.NEW_YEARS_EVE) > order.indexOf(HolidayPack.COZY_CHRISTMAS))
        assertEquals(HolidayPack.RETRO, order.last(), "Retro is registered last, in the Playful group after Ready prompt")
        assertEquals("New Year's Eve", newYear.label)
        assertEquals("Programmers' Day", devDay.label)
        assertEquals("Retro", retro.label)
        assertEquals(HolidayCategory.HOLIDAY, newYear.category)
        assertEquals(HolidayCategory.HOLIDAY, devDay.category)
        assertEquals(HolidayCategory.FUN, retro.category)
        assertTrue(newYear.supportsAutomatic && devDay.supportsAutomatic)
        assertFalse(retro.supportsAutomatic)
        assertTrue("fireworks" in newYear.keywords && "confetti" in newYear.keywords)
        assertTrue("terminal" in devDay.keywords && "binary" in devDay.keywords)
        assertTrue("neon" in retro.keywords && "vhs" in retro.keywords)
        assertTrue(holidayThemesIn(HOLIDAY_THEMES, HolidayCategory.FUN).any { it.pack == HolidayPack.RETRO })
    }

    @Test fun newYearsEveRunsDecember28ToJanuary2AcrossTheNewYear() {
        val dates = newYear.dates
        assertFalse(dates.contains(day(12, 27)))
        assertTrue(dates.contains(day(12, 28)))
        assertTrue(dates.contains(day(12, 31)))
        assertTrue(dates.contains(day(1, 1, 2027)))
        assertTrue(dates.contains(day(1, 2, 2027)))
        assertFalse(dates.contains(day(1, 3, 2027)))
        assertFalse(dates.contains(day(7, 4)))
    }

    @Test fun onTheDaysItSharesWithFirstSnowNewYearsEveWinsUnlessFirstSnowIsSelected() {
        for (d in listOf(day(12, 28), day(12, 31), day(1, 1, 2027), day(1, 2, 2027))) {
            assertEquals(HolidayPack.NEW_YEARS_EVE, activeHolidayPack(auto(HolidayPack.PUMPKIN_PATCH), d), "$d default")
            assertEquals(HolidayPack.NEW_YEARS_EVE, activeHolidayPack(auto(HolidayPack.NEW_YEARS_EVE), d), "$d own")
            assertEquals(HolidayPack.NEW_YEARS_EVE, activeHolidayPack(auto(HolidayPack.COZY_CHRISTMAS), d), "$d cozy is closed")
            assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(auto(HolidayPack.FIRST_SNOW), d), "$d first snow selected")
        }
        // Around the window: Classic Christmas ends on the 27th, First snow carries on after the 2nd.
        assertEquals(HolidayPack.CLASSIC_CHRISTMAS, activeHolidayPack(auto(HolidayPack.PUMPKIN_PATCH), day(12, 27)))
        assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(auto(HolidayPack.NEW_YEARS_EVE), day(1, 3, 2027)))
        assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(auto(HolidayPack.NEW_YEARS_EVE), day(1, 7, 2027)))
        assertNull(activeHolidayPack(auto(HolidayPack.NEW_YEARS_EVE), day(1, 8, 2027)))
    }

    @Test fun programmersDayRunsSeptember12To14AndNothingElseIsOpen() {
        assertFalse(devDay.dates.contains(day(9, 11)))
        for (d in 12..14) assertTrue(devDay.dates.contains(day(9, d)))
        assertFalse(devDay.dates.contains(day(9, 15)))
        for (selected in listOf(HolidayPack.PUMPKIN_PATCH, HolidayPack.TULIPS, HolidayPack.PROGRAMMERS_DAY, HolidayPack.FIRST_SNOW)) {
            assertNull(activeHolidayPack(auto(selected), day(9, 11)), "$selected, the 11th")
            for (d in 12..14) assertEquals(HolidayPack.PROGRAMMERS_DAY, activeHolidayPack(auto(selected), day(9, d)), "$selected, the $d")
            assertNull(activeHolidayPack(auto(selected), day(9, 15)), "$selected, the 15th")
        }
        // 2028 is a leap year: the 256th day is the 12th, still inside the window.
        assertTrue(devDay.dates.contains(day(9, 12, 2028)))
    }

    @Test fun retroIsNeverPickedByAutomaticAndNeedsAnExplicitChoice() {
        var d = day(1, 1)
        while (d.year == 2026) {
            assertNotEquals(HolidayPack.RETRO, activeHolidayPack(auto(HolidayPack.RETRO), d), "$d")
            d = d.plusDays(1)
        }
        assertFalse(retro.automaticAt(day(6, 1).atStartOfDay()))
        assertEquals(HolidayPack.RETRO, activeHolidayPack(HolidaySettings(mode = HolidayMode.ALWAYS, pack = HolidayPack.RETRO), day(6, 1)))
        // Turning it on from Automatic (or Off) switches to Always, since Automatic would never show it.
        assertEquals(HolidayMode.ALWAYS, auto(HolidayPack.PUMPKIN_PATCH).withPackOn(HolidayPack.RETRO).mode)
        assertEquals(HolidayMode.ALWAYS, HolidaySettings().withPackOn(HolidayPack.RETRO).mode)
    }

    @Test fun usesTheDesignsPalettesAndChrome() {
        assertEquals(Color(0xFF09091a), newYear.palette(true).bg)
        assertEquals(Color(0xFFf0c75e), newYear.palette(true).ac)
        assertEquals(Color(0xFFff6fae), newYear.palette(true).seq2)
        assertEquals(Color(0xFFf7f5ef), newYear.palette(false).bg)
        assertEquals(Color(0xFF8a6a10), newYear.palette(false).ac)
        assertEquals(Color(0xFF0c0c22), newYear.chrome(true).tabBar)
        assertEquals(Color(0xFFe0d7c3), newYear.chrome(false).tabBg)

        assertEquals(Color(0xFF050a06), devDay.palette(true).bg)
        assertEquals(Color(0xFF39ff6a), devDay.palette(true).ac)
        assertEquals(Color(0xFFffbf00), devDay.palette(true).seq2)
        assertEquals(Color(0xFFfbf8ef), devDay.palette(false).bg)
        assertEquals(Color(0xFF1b823a), devDay.palette(false).ac, "the design's green, a few steps darker so white text on it is AA")
        assertEquals(Color(0xFF070e09), devDay.chrome(true).tabBar)
        assertEquals(Color(0xFFe2ddca), devDay.chrome(false).tabBg)

        assertEquals(Color(0xFF120a24), retro.palette(true).bg)
        assertEquals(Color(0xFFff4fd8), retro.palette(true).ac)
        assertEquals(Color(0xFF00f0ff), retro.palette(true).seq2)
        assertEquals(Color(0xFFfdf4ff), retro.palette(false).bg)
        assertEquals(Color(0xFFb8148e), retro.palette(false).ac)
        assertEquals(Color(0xFF150c2c), retro.chrome(true).tabBar)
        assertEquals(Color(0xFFe8ccfa), retro.chrome(false).tabBg)

        for (theme in listOf(newYear, devDay, retro)) {
            assertEquals(HolidayTabTexture.PLAIN, theme.tabTexture)
            assertFalse(theme.tintTabsByGroup)
            assertEquals("Find", assertNotNull(theme.icons?.findIcon(Color.Black)).name)
            assertNotNull(theme.picture)
            assertNotNull(theme.companion)
            assertNotNull(theme.tabScene)
        }
        assertEquals(HolidayCorners(tabDp = 16f, toolbarDp = 16, tileDp = 10), newYear.corners)
        assertEquals(HolidayCorners(tabDp = 0f, toolbarDp = 0, tileDp = 0), devDay.corners)
        assertEquals(HolidayCorners(tabDp = 2f, toolbarDp = 2, tileDp = 4), retro.corners)
        assertEquals("Balloons in the Notes corner", newYear.companion?.label)
        assertEquals("Robot in the Notes corner", devDay.companion?.label)
        assertEquals("Mixtape in the Notes corner", retro.companion?.label)
    }

    @Test fun effectsAreLabelledAndMatchTheDesignsList() {
        assertEquals(listOf("newyear.confetti", "newyear.landing", "newyear.fireworks"), newYear.effects.map { it.id })
        assertEquals(listOf("Confetti", "Confetti lands on tabs and tiles", "Fireworks"), newYear.effects.map { it.label })
        assertEquals(listOf("devday.rain", "devday.scanlines"), devDay.effects.map { it.id })
        assertEquals(listOf("Binary rain", "CRT scanlines"), devDay.effects.map { it.label })
        assertEquals(listOf("retro.stars", "retro.car", "retro.scanlines"), retro.effects.map { it.id })
        assertEquals(listOf("Shooting stars", "Neon car", "VHS scanlines"), retro.effects.map { it.label })
        assertTrue((newYear.effects + devDay.effects + retro.effects).all { it.defaultOn })
        assertTrue(newYear.effects[1] is HolidayLandingEffect && newYear.effects.none { it is HolidayFlashEffect }, "fireworks are sprites, not window flashes")
        assertFalse(devDay.effects[1].moves)
        assertFalse(retro.effects[2].moves)
        assertTrue(devDay.effects[0].moves && retro.effects[0].moves && retro.effects[1].moves)
    }

    @Test fun particleCountsAreTheDesignsAndStopAtSpeedZero() {
        fun count(theme: HolidayTheme, index: Int, countScale: Float = 1f, speed: Float = 1f) =
            (theme.effects[index] as HolidayParticleEffect).spawn(7, countScale, speed).count
        assertEquals(34, count(newYear, 0))
        assertEquals(18, count(newYear, 1))
        assertEquals(6, count(newYear, 2))
        assertEquals(60, count(devDay, 0))
        assertEquals(1, count(devDay, 1))
        assertEquals(9, count(retro, 0))
        assertEquals(1, count(retro, 1))
        assertEquals(1, count(retro, 2))
        assertEquals(14, count(newYear, 0, countScale = .4f), "Calm thins the confetti")
        for (theme in listOf(newYear, devDay, retro)) {
            for (i in theme.effects.indices) {
                val effect = theme.effects[i]
                if (effect.moves || effect is HolidayLandingEffect) assertEquals(0, count(theme, i, speed = 0f), "${effect.id} at speed 0")
            }
        }
    }

    @Test fun switchesWorkAndOffIntensityKeepsNoMovingEffect() {
        for (theme in listOf(newYear, devDay, retro)) {
            val settings = HolidaySettings(mode = HolidayMode.ALWAYS, pack = theme.pack)
            val full = resolveHolidayEffects(settings, theme)
            assertEquals(theme.effects.size, full.activeEffects.size)
            for (effect in full.activeEffects) {
                assertFalse(resolveHolidayEffects(settings.copy(effectToggles = mapOf(effect.id to false)), theme).activeEffects.any { it.id == effect.id })
            }
            val off = resolveHolidayEffects(settings.copy(intensity = HolidayIntensity.OFF), theme)
            assertTrue(off.activeEffects.none { it.moves || it is HolidayParticleEffect })
        }
    }

    @Test fun eachPackHasItsOwnTileRule() {
        for (dark in listOf(true, false)) {
            assertNull(newYear.tileDividerColors(dark))
            assertNull(newYear.tileDividerGradient(dark))
            assertNull(newYear.tileDividerDashes(dark))
            assertNull(newYear.tileDividerBlocks(dark), "New Year's Eve keeps the plain divider, as the design does")

            val dashes = assertNotNull(devDay.tileDividerDashes(dark))
            assertEquals(devDay.palette(dark).ac, dashes.dash, "accent dashes")
            assertEquals(Color.Transparent, dashes.gap, "with open gaps")
            assertEquals(1f, dashes.heightDp)
            assertEquals(3f, dashes.dashDp)
            assertEquals(3f, dashes.gapDp)
            assertNull(devDay.tileDividerGradient(dark))
            assertNull(devDay.tileDividerBlocks(dark))

            val gradient = assertNotNull(retro.tileDividerGradient(dark))
            assertEquals(2, gradient.size)
            assertEquals(if (dark) Color(0xFFff4fd8) else Color(0xFFc42aa8), gradient.first(), "neon first")
            assertEquals(if (dark) Color(0xFF00f0ff) else Color(0xFF0097b2), gradient.last(), "to cyan")
            assertNull(retro.tileDividerColors(dark))
            assertNull(retro.tileDividerDashes(dark))
            assertNull(retro.tileDividerBlocks(dark))
        }
    }

    @Test fun tabPiecesFollowTheDesign() {
        assertNotNull(newYear.tabShape, "pill tabs")
        assertEquals(5f, newYear.tabCrossAxisInsetDp)
        assertTrue(newYear.tabOrnament?.animated == true, "a twinkling star")
        assertNull(newYear.tabEdgeLine(true, true))

        assertEquals("~/ ", devDay.tabLabelPrefix)
        assertTrue(devDay.activeTabCursor)
        assertEquals(FontFamily.Monospace, devDay.labelFontFamily)
        assertNull(devDay.tabShape)
        assertNull(devDay.tabOrnament)
        assertFalse(newYear.activeTabCursor || retro.activeTabCursor)
        assertNull(newYear.tabLabelPrefix)
        assertNull(retro.tabLabelPrefix)

        assertNotNull(retro.tabOrnament, "the neon glow")
        assertTrue(retro.tabOrnament?.animated == false)
        assertNull(retro.tabShape)
        assertNull(retro.labelFontFamily)
    }
}
