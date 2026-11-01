package com.indagium.ui.holiday

import com.indagium.model.AppSettings
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayMode
import com.indagium.model.HolidaySettings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The framework driven by a theme that provides almost nothing: no pumpkin assumption may survive, rows hide, nothing crashes. */
class HolidayFakeThemeTest {
    private val always = AppSettings().withHoliday { it.copy(mode = HolidayMode.ALWAYS) }

    @Test
    fun aRegistryOfOneFakeThemeResolvesItsOwnLookAndNotPumpkins() {
        val look = assertNotNull(resolveHolidayLook(always, LocalDate.of(2026, 3, 3), FAKE_THEMES))
        assertEquals(FakeHolidayTheme, look.theme)
        assertEquals(FakeHolidayTheme.palette(look.isDark), look.colors)
        assertNull(resolveHolidayLook(AppSettings(), LocalDate.of(2026, 3, 3), FAKE_THEMES), "Off stays off")
    }

    @Test
    fun automaticFollowsTheFakeThemesOwnDates() {
        val auto = HolidaySettings(mode = HolidayMode.AUTOMATIC)
        assertNull(activeHolidayTheme(auto, LocalDate.of(2026, 10, 31), FAKE_THEMES), "pumpkin dates mean nothing to it")
        assertEquals(FakeHolidayTheme, activeHolidayTheme(auto, LocalDate.of(2026, 6, 15), FAKE_THEMES))
        assertNull(activeHolidayTheme(auto, LocalDate.of(2026, 7, 1), FAKE_THEMES))
    }

    @Test
    fun automaticCanFollowAWeeklyTimeSchedule() {
        val weekly = object : HolidayTheme by FakeHolidayTheme {
            override fun automaticAt(now: LocalDateTime): Boolean = now.dayOfWeek == DayOfWeek.FRIDAY && now.hour >= 17
            override val automaticLabel = "Fridays from 17:00"
        }
        val themes = holidayThemeRegistry(weekly)
        val auto = HolidaySettings(mode = HolidayMode.AUTOMATIC)
        assertNull(activeHolidayTheme(auto, LocalDateTime.of(2026, 10, 30, 16, 59), themes))
        assertEquals(weekly, activeHolidayTheme(auto, LocalDateTime.of(2026, 10, 30, 17, 0), themes))
        assertNull(activeHolidayTheme(auto, LocalDateTime.of(2026, 10, 31, 0, 0), themes))
        assertEquals("Fridays from 17:00", holidayDatesLabel(weekly))
    }

    @Test
    fun capabilitiesTheThemeLacksAreOffWhateverTheSettingsSay() {
        val fx = resolveHolidayEffects(HolidaySettings(), FakeHolidayTheme)
        assertFalse(fx.tabScene || fx.filterPicture || fx.notesCompanion || fx.funIcons)
        assertTrue(fx.activeEffects.isEmpty(), "the only effect is off by default")
    }

    @Test
    fun anEffectTakesItsSpecDefaultUntilTheUserChoosesAndThenTheirChoice() {
        assertTrue(resolveHolidayEffects(HolidaySettings(), FakeHolidayTheme).activeEffects.isEmpty())
        val on = HolidaySettings(effectToggles = mapOf(FakeSparkles.id to true))
        assertEquals(listOf<HolidayEffectSpec>(FakeSparkles), resolveHolidayEffects(on, FakeHolidayTheme).activeEffects)
        assertTrue(resolveHolidayEffects(on.copy(intensity = HolidayIntensity.OFF), FakeHolidayTheme).activeEffects.isEmpty())
        // Another theme's ids in the settings are ignored.
        assertTrue(resolveHolidayEffects(HolidaySettings(effectToggles = mapOf("pumpkin.bats" to true)), FakeHolidayTheme).activeEffects.isEmpty())
    }

    @Test
    fun theLayoutMenuRowsAreTheEffectsPlusOnlyTheGenericRowsTheThemeCanHonour() {
        assertEquals(listOf(FakeSparkles.id, "pause"), holidayToggles(FakeHolidayTheme).map { it.key })
        assertEquals(listOf("Sparkles", "Pause while capturing or playing video"), holidayToggles(FakeHolidayTheme).map { it.label })
    }

    @Test
    fun theEffectsHintNamesTheThemesOwnEffects() {
        assertTrue(holidayEffectsHint(FakeHolidayTheme).startsWith("Full: sparkles."))
        assertTrue(holidayEffectsHint(com.indagium.ui.holiday.pumpkin.PumpkinPatchTheme).startsWith("Full: embers, bats and lightning."))
    }

    @Test
    fun flashesNeverExceedTheLimitsHoweverManyEffectsShareTheSchedule() {
        for (t in 0..40_000) {
            val frame = holidayFlashFrame(31, t * 0.01f, HOLIDAY_FLASH_GAP_FULL_SEC)
            assertTrue(frame.alpha in 0f..HOLIDAY_FLASH_MAX_ALPHA)
        }
        assertEquals(20f, HOLIDAY_FLASH_GAP_FULL_SEC)
        assertEquals(45f, HOLIDAY_FLASH_GAP_CALM_SEC)
        assertEquals(0.12f, HOLIDAY_FLASH_MAX_ALPHA)
        assertEquals(0.15f, HOLIDAY_FLASH_SEC)
    }

    @Test
    fun backdropArtsFollowWhatTheThemeProvidesAndTheUserHasOn() {
        val settings = AppSettings().withHoliday { it.copy(mode = HolidayMode.ALWAYS) }
        val day = LocalDate.of(2026, 3, 3)

        fun arts(theme: HolidayTheme) =
            holidayBackdropArts(assertNotNull(resolveHolidayLook(settings, day, holidayThemeRegistry(theme)))).map { it.anchor }
        assertTrue(arts(FakeHolidayTheme).isEmpty(), "no picture and no companion: no art")
        assertEquals(listOf(HolidayArtAnchor.BOTTOM_END), arts(FakeCompanionTheme), "a companion and no picture")
        assertEquals(
            listOf(HolidayArtAnchor.BOTTOM_START, HolidayArtAnchor.BOTTOM_END),
            arts(com.indagium.ui.holiday.pumpkin.PumpkinPatchTheme),
        )
        val off = settings.withHoliday { it.copy(notesCompanion = false) }
        val look = assertNotNull(resolveHolidayLook(off, day, holidayThemeRegistry(FakeCompanionTheme)))
        assertTrue(holidayBackdropArts(look).isEmpty(), "the user's switch removes it")
        assertEquals(listOf(FakeSparkles.id, "animate", "companion", "pause"), holidayToggles(FakeCompanionTheme).map { it.key })
        assertEquals("Little ghost in the Notes corner", holidayToggles(FakeCompanionTheme).first { it.key == "companion" }.label)
    }
}
