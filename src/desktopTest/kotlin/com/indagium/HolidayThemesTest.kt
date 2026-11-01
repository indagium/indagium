package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayLightness
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.model.ThemePreset
import com.indagium.ui.holiday.HolidayDates
import com.indagium.ui.holiday.activeHolidayPack
import com.indagium.ui.holiday.effectiveThemeColors
import com.indagium.ui.holiday.pumpkin.PUMPKIN_DARK
import com.indagium.ui.holiday.pumpkin.PUMPKIN_LIGHT
import com.indagium.ui.holiday.pumpkin.PumpkinPatchTheme
import com.indagium.ui.holiday.resolveHolidayDark
import com.indagium.ui.holiday.resolveHolidayEffects
import com.indagium.ui.holiday.resolveHolidayLook
import com.indagium.ui.holiday.withHoliday
import com.indagium.ui.themeColors
import java.time.LocalDate
import java.time.MonthDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val PUMPKIN_EFFECT_IDS = listOf("pumpkin.embers", "pumpkin.bats", "pumpkin.lightning")

class HolidayThemesTest {
    private val auto = HolidaySettings(mode = HolidayMode.AUTOMATIC)
    private val always = HolidaySettings(mode = HolidayMode.ALWAYS)

    @Test
    fun offNeverActivatesAPack() {
        assertNull(activeHolidayPack(HolidaySettings(), LocalDate.of(2026, 10, 31)))
        assertNull(resolveHolidayLook(AppSettings(), LocalDate.of(2026, 10, 31)))
    }

    @Test
    fun alwaysActivatesThePackOnAnyDate() {
        assertEquals(HolidayPack.PUMPKIN_PATCH, activeHolidayPack(always, LocalDate.of(2026, 3, 3)))
    }

    @Test
    fun automaticCoversOctober24ThroughNovember1Inclusive() {
        assertNull(activeHolidayPack(auto, LocalDate.of(2026, 10, 23)))
        assertEquals(HolidayPack.PUMPKIN_PATCH, activeHolidayPack(auto, LocalDate.of(2026, 10, 24)))
        assertEquals(HolidayPack.PUMPKIN_PATCH, activeHolidayPack(auto, LocalDate.of(2026, 10, 31)))
        assertEquals(HolidayPack.PUMPKIN_PATCH, activeHolidayPack(auto, LocalDate.of(2026, 11, 1)))
        assertNull(activeHolidayPack(auto, LocalDate.of(2026, 11, 2)))
    }

    @Test
    fun leapDayIsSafeAndOutsideThePumpkinRange() {
        assertNull(activeHolidayPack(auto, LocalDate.of(2028, 2, 29)))
        assertFalse(PumpkinPatchTheme.dates.contains(LocalDate.of(2028, 2, 29)))
    }

    @Test
    fun aRangeThatWrapsPastNewYearContainsBothSides() {
        val wrap = HolidayDates(MonthDay.of(12, 20), MonthDay.of(1, 6))
        assertFalse(wrap.contains(LocalDate.of(2026, 12, 19)))
        assertTrue(wrap.contains(LocalDate.of(2026, 12, 20)))
        assertTrue(wrap.contains(LocalDate.of(2026, 12, 31)))
        assertTrue(wrap.contains(LocalDate.of(2027, 1, 1)))
        assertTrue(wrap.contains(LocalDate.of(2027, 1, 6)))
        assertFalse(wrap.contains(LocalDate.of(2027, 1, 7)))
        assertFalse(wrap.contains(LocalDate.of(2027, 6, 15)))
    }

    @Test
    fun followPresetPicksTheFamilyOfThePreset() {
        assertFalse(resolveHolidayDark(HolidayLightness.FOLLOW_PRESET, themeColors(ThemePreset.LIGHT)))
        assertTrue(resolveHolidayDark(HolidayLightness.FOLLOW_PRESET, themeColors(ThemePreset.DRACULA)))
    }

    @Test
    fun anExplicitLightnessIgnoresThePreset() {
        for (preset in listOf(ThemePreset.LIGHT, ThemePreset.DRACULA)) {
            assertTrue(resolveHolidayDark(HolidayLightness.DARK, themeColors(preset)), "$preset DARK")
            assertFalse(resolveHolidayDark(HolidayLightness.LIGHT, themeColors(preset)), "$preset LIGHT")
        }
    }

    @Test
    fun fullIntensityKeepsEveryToggleAndMovesOverTheLog() {
        val fx = resolveHolidayEffects(HolidaySettings(intensity = HolidayIntensity.FULL), PumpkinPatchTheme)
        assertEquals(PUMPKIN_EFFECT_IDS, fx.activeEffects.map { it.id })
        assertTrue(fx.animatePicture && fx.moving)
        assertTrue(fx.tabScene && fx.filterPicture && fx.notesCompanion && fx.funIcons)
        assertTrue(fx.overLog)
        assertEquals(1f, fx.countScale)
        assertEquals(1f, fx.speedScale)
        assertEquals(20f, fx.flashMinGapSec)
    }

    @Test
    fun calmScalesCountsAndSpeedKeepsLightningRarerAndStaysOffTheLog() {
        val fx = resolveHolidayEffects(HolidaySettings(intensity = HolidayIntensity.CALM), PumpkinPatchTheme)
        assertEquals(PUMPKIN_EFFECT_IDS, fx.activeEffects.map { it.id })
        assertTrue(fx.moving)
        assertFalse(fx.overLog)
        assertEquals(0.4f, fx.countScale)
        assertEquals(0.6f, fx.speedScale)
        assertEquals(45f, fx.flashMinGapSec)
    }

    @Test
    fun offIntensityStopsMotionButKeepsStaticDecorations() {
        val fx = resolveHolidayEffects(HolidaySettings(intensity = HolidayIntensity.OFF), PumpkinPatchTheme)
        assertTrue(fx.activeEffects.isEmpty())
        assertFalse(fx.animatePicture || fx.moving)
        assertTrue(fx.tabScene && fx.filterPicture && fx.notesCompanion && fx.funIcons)
        val off = resolveHolidayEffects(HolidaySettings(intensity = HolidayIntensity.OFF, tabScene = false, funIcons = false), PumpkinPatchTheme)
        assertFalse(off.tabScene || off.funIcons)
    }

    @Test
    fun togglesTurnIndividualEffectsOffAtFullIntensity() {
        val off = mapOf("pumpkin.bats" to false, "pumpkin.lightning" to false)
        val fx = resolveHolidayEffects(HolidaySettings(effectToggles = off, animatePicture = false), PumpkinPatchTheme)
        assertEquals(listOf("pumpkin.embers"), fx.activeEffects.map { it.id })
        assertFalse(fx.animatePicture)
    }

    @Test
    fun lookCarriesThePaletteForTheResolvedFamily() {
        val darkLook = resolveHolidayLook(AppSettings(theme = ThemePreset.DRACULA).copy(holiday = always), LocalDate.of(2026, 5, 1))
        assertNotNull(darkLook)
        assertTrue(darkLook.isDark)
        assertSame(PUMPKIN_DARK, darkLook.colors)
        val lightSettings = AppSettings(theme = ThemePreset.DRACULA).copy(holiday = always.copy(lightness = HolidayLightness.LIGHT))
        val lightLook = resolveHolidayLook(lightSettings, LocalDate.of(2026, 5, 1))
        assertNotNull(lightLook)
        assertFalse(lightLook.isDark)
        assertSame(PUMPKIN_LIGHT, lightLook.colors)
    }

    @Test
    fun effectiveThemeColorsFallBackToThePresetWhenNoPackApplies() {
        val settings = AppSettings(theme = ThemePreset.GRUVBOX)
        assertEquals(themeColors(ThemePreset.GRUVBOX), effectiveThemeColors(settings, LocalDate.of(2026, 10, 31)))
        val seasonal = settings.withHoliday { it.copy(mode = HolidayMode.AUTOMATIC) }
        assertSame(PUMPKIN_DARK, effectiveThemeColors(seasonal, LocalDate.of(2026, 10, 31)))
        assertEquals(themeColors(ThemePreset.GRUVBOX), effectiveThemeColors(seasonal, LocalDate.of(2026, 11, 2)))
    }

    @Test
    fun withHolidayOnlyChangesTheHolidaySettings() {
        val base = AppSettings(fontSize = 15)
        val next = base.withHoliday { it.copy(funIcons = false) }
        assertEquals(base.copy(holiday = base.holiday.copy(funIcons = false)), next)
    }
}
