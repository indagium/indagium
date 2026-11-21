@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.graphics.Color
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThanksThemeTest {
    private val theme get() = HOLIDAY_THEMES.getValue(HolidayPack.THANKSGIVING)

    @Test fun isRegisteredBeforeTheWinterPacksAndIsAnAutomaticHoliday() {
        assertTrue(HOLIDAY_THEMES.keys.toList().let { it.indexOf(HolidayPack.THANKSGIVING) < it.indexOf(HolidayPack.CLASSIC_CHRISTMAS) })
        assertEquals("Thanksgiving", theme.label)
        assertEquals("Thanksgiving", theme.occasion)
        assertTrue(theme.supportsAutomatic)
        assertTrue("turkey" in theme.keywords && "geese" in theme.keywords)
    }

    @Test fun isAutomaticFromNovember21ToNovember30WhenSelected() {
        val settings = HolidaySettings(mode = HolidayMode.AUTOMATIC, pack = HolidayPack.THANKSGIVING)
        assertNull(activeHolidayPack(settings, LocalDate.of(2026, 11, 20)))
        assertEquals(HolidayPack.THANKSGIVING, activeHolidayPack(settings, LocalDate.of(2026, 11, 21)))
        assertEquals(HolidayPack.THANKSGIVING, activeHolidayPack(settings, LocalDate.of(2026, 11, 26)))
        assertEquals(HolidayPack.THANKSGIVING, activeHolidayPack(settings, LocalDate.of(2026, 11, 30)))
        // First snow's window opens on December 1, the day after this one closes: it takes over (a selected Thanksgiving is closed by then).
        assertEquals(HolidayPack.FIRST_SNOW, activeHolidayPack(settings, LocalDate.of(2026, 12, 1)))
        // It is the only pack open in its window, so another selected pack does not hide it.
        val other = settings.copy(pack = HolidayPack.TULIPS)
        assertEquals(HolidayPack.THANKSGIVING, activeHolidayPack(other, LocalDate.of(2026, 11, 26)))
    }

    @Test fun usesItsOwnPalettesAndChrome() {
        assertEquals(Color(0xFF1b130e), theme.palette(true).bg)
        assertEquals(Color(0xFFf0b13c), theme.palette(true).ac)
        assertEquals(Color(0xFFe0703a), theme.palette(true).seq2)
        assertEquals(Color(0xFF2a1a05), theme.palette(true).onAc)
        assertEquals(Color(0xFFfaf3e6), theme.palette(false).bg)
        assertEquals(Color(0xFFa8431b), theme.palette(false).ac)
        assertEquals(Color(0xFF5f6e22), theme.palette(false).seq2)
        assertEquals(Color(0xFF160f0b), theme.chrome(true).tabBar)
        assertEquals(Color(0xFFf0dfc0), theme.chrome(false).tabBar)
        assertEquals(Color(0xFFe8d2ac), theme.chrome(false).tabBg)
        assertEquals("Find", assertNotNull(theme.icons?.findIcon(Color.Black)).name)
        assertEquals(HolidayTabTexture.PLAIN, theme.tabTexture)
        assertFalse(theme.tintTabsByGroup)
        assertEquals(10f, theme.corners.tabDp)
        assertEquals(12, theme.corners.tileDp)
        assertEquals(3f, theme.activeTabUnderlineDp)
    }

    @Test fun effectsAreLabelledRespectSwitchesAndStopMovingAtOff() {
        assertEquals(listOf("thanksgiving.leaves", "thanksgiving.geese"), theme.effects.map { it.id })
        assertEquals(listOf("Falling leaves", "Geese flying south"), theme.effects.map { it.label })
        assertEquals(listOf("leaves", "geese"), theme.effects.map { it.noun })
        assertTrue(theme.effects.all { it.defaultOn })
        assertTrue(theme.effects.none { it is HolidayLandingEffect })
        val settings = HolidaySettings(mode = HolidayMode.ALWAYS, pack = HolidayPack.THANKSGIVING)
        val full = resolveHolidayEffects(settings, theme)
        assertEquals(2, full.activeEffects.size)
        for (effect in full.activeEffects) {
            val toggled = resolveHolidayEffects(settings.copy(effectToggles = mapOf(effect.id to false)), theme)
            assertFalse(toggled.activeEffects.any { it.id == effect.id })
        }
        val off = resolveHolidayEffects(settings.copy(intensity = HolidayIntensity.OFF), theme)
        assertTrue(off.activeEffects.none { it is HolidayParticleEffect || it.moves }, "Off has no moving effects")
    }

    @Test fun particleFieldsHaveTheDesignCounts() {
        val leaves = theme.effects[0] as HolidayParticleEffect
        val geese = theme.effects[1] as HolidayParticleEffect
        assertEquals(26, leaves.spawn(7, 1f, 1f).count)
        assertEquals(2, geese.spawn(7, 1f, 1f).count)
        assertEquals(0, leaves.spawn(7, 1f, 0f).count)
        assertEquals(0, geese.spawn(7, 1f, 0f).count)
    }

    @Test fun threeSegmentDividerIsReturnedForBothPalettes() {
        val expected = listOf(0xFFb5451b, 0xFFe8a33a, 0xFF7a8a3a).map { Color(it) }
        for (dark in listOf(true, false)) {
            assertEquals(expected, theme.tileDividerColors(dark))
            assertNull(theme.tileDividerBlocks(dark))
            assertNull(theme.tileDividerGradient(dark))
            assertNull(theme.tileDividerDashes(dark))
        }
    }
}
