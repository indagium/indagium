// The panel's host size is literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.indagium.model.AppSettings
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayLightness
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.model.ThemePreset
import com.indagium.ui.holiday.*
import com.indagium.ui.holiday.pumpkin.PumpkinPatchTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings > Appearance > Special theme: each control writes its HolidaySettings field. */
@OptIn(ExperimentalTestApi::class)
class SeasonalThemesSettingsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun setup(configure: (AppState) -> Unit = {}) {
        directory = createTempDirectory("seasonal-settings-ui").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        configure(state)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) { Box(Modifier.size(700.dp, 1200.dp)) { ThemeSettingsBlock(state) } }
        }
        rule.waitForIdle()
    }

    private fun openSeasonalTab() {
        rule.onNode(hasText("Special themes") and hasAnyAncestor(hasTestTag(THEME_TABS_TAG))).performClick()
        rule.waitForIdle()
    }

    private fun choose(group: String, label: String) {
        rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(group))).performClick()
        rule.waitForIdle()
    }

    private fun present(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private val someDay = LocalDate.of(2026, 3, 3)

    @Test
    fun theBlockOpensOnTheThemesTabWhileNoSeasonalThemeIsOn() {
        setup()
        rule.onNodeWithText("Dracula").assertExists()
        assertTrue(!present(seasonalPackTag(HolidayPack.PUMPKIN_PATCH)), "the seasonal cards are on the other tab")
        // Switching tabs alone changes nothing.
        openSeasonalTab()
        assertEquals(HolidayMode.OFF, state.settings.holiday.mode)
        assertTrue(present(seasonalPackTag(HolidayPack.PUMPKIN_PATCH)))
    }

    @Test
    fun theBlockOpensOnTheSeasonalTabWhileASeasonalThemeIsOn() {
        setup { it.updateSettings { s -> s.withHoliday { h -> h.copy(mode = HolidayMode.AUTOMATIC) } } }
        assertTrue(present(seasonalPackTag(HolidayPack.PUMPKIN_PATCH)))
        rule.onNodeWithText("Dracula").assertDoesNotExist()
    }

    @Test
    fun aRegularThemeCardAppliesItAndTurnsTheSeasonalThemeOff() {
        setup { it.updateSettings { s -> s.withHoliday { h -> h.copy(mode = HolidayMode.ALWAYS) } } }
        rule.onNode(hasText("Themes") and hasAnyAncestor(hasTestTag(THEME_TABS_TAG))).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Dracula").performClick()
        rule.waitForIdle()
        assertEquals(ThemePreset.DRACULA, state.settings.theme)
        assertEquals(HolidayMode.OFF, state.settings.holiday.mode)
        assertNull(resolveHolidayLook(state.settings, someDay))
    }

    @Test
    fun aPackCardTurnsItOnAsAlwaysAndKeepsAnAutomaticChoice() {
        setup()
        openSeasonalTab()
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.PUMPKIN_PATCH)).performClick()
        rule.waitForIdle()
        assertEquals(HolidayMode.ALWAYS, state.settings.holiday.mode)
        assertEquals(HolidayPack.PUMPKIN_PATCH, state.settings.holiday.pack)
        assertNotNull(resolveHolidayLook(state.settings, someDay), "the look becomes active")
        choose(SEASONAL_MODE_TAG, "Only during its dates")
        assertEquals(HolidayMode.AUTOMATIC, state.settings.holiday.mode)
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.PUMPKIN_PATCH)).performClick()
        rule.waitForIdle()
        assertEquals(HolidayMode.AUTOMATIC, state.settings.holiday.mode, "an Automatic choice is kept")
    }

    @Test
    fun theWhenRowSwitchesBetweenAlwaysAndTheDatesAndTurnsTheThemeOnFromOff() {
        setup()
        openSeasonalTab()
        choose(SEASONAL_MODE_TAG, "Only during its dates")
        assertEquals(HolidayMode.AUTOMATIC, state.settings.holiday.mode, "from off, choosing a When turns the selected pack on")
        assertNull(resolveHolidayLook(state.settings, someDay))
        assertNotNull(resolveHolidayLook(state.settings, LocalDate.of(2026, 10, 31)))
        choose(SEASONAL_MODE_TAG, "Always")
        assertEquals(HolidayMode.ALWAYS, state.settings.holiday.mode)
        rule.onNodeWithText("Pumpkin patch: Oct 24 – Nov 1").assertExists()
    }

    @Test
    fun underAutomaticOutsideTheDatesAShortLineSaysWhenItTurnsOnAndWhatShowsUntilThen() {
        setup { it.updateSettings { s -> s.withHoliday { h -> h.copy(mode = HolidayMode.AUTOMATIC) }.copy(theme = ThemePreset.DRACULA) } }
        val today = LocalDate.now()
        if (PumpkinPatchTheme.dates.contains(today)) {
            assertTrue(!present(SEASONAL_STATUS_TAG), "inside the window nothing is waiting")
        } else {
            rule.onNodeWithTag(SEASONAL_STATUS_TAG).assertExists()
            rule.onNodeWithText("Pumpkin patch turns on Oct 24; until then your theme is Dracula").assertExists()
        }
    }

    @Test
    fun turnOffSeasonalThemeSwitchesItOffAndOnlyThenShows() {
        setup()
        openSeasonalTab()
        assertTrue(!present(SEASONAL_OFF_TAG), "nothing to turn off")
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.PUMPKIN_PATCH)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SEASONAL_OFF_TAG).performClick()
        rule.waitForIdle()
        assertEquals(HolidayMode.OFF, state.settings.holiday.mode)
        assertTrue(!present(SEASONAL_OFF_TAG))
    }

    @Test
    fun lightAndDarkAndEffectsWriteTheirFields() {
        setup()
        openSeasonalTab()
        choose(SEASONAL_LIGHTNESS_TAG, "Dark")
        assertEquals(HolidayLightness.DARK, state.settings.holiday.lightness)
        choose(SEASONAL_LIGHTNESS_TAG, "Light")
        assertEquals(HolidayLightness.LIGHT, state.settings.holiday.lightness)
        choose(SEASONAL_LIGHTNESS_TAG, "Follow preset")
        assertEquals(HolidayLightness.FOLLOW_PRESET, state.settings.holiday.lightness)
        choose(SEASONAL_INTENSITY_TAG, "Calm")
        assertEquals(HolidayIntensity.CALM, state.settings.holiday.intensity)
    }

    @Test
    fun theCheckboxesFlipTheirFieldsAndTheDimmedRowsStayEditable() {
        setup()
        openSeasonalTab()
        // No special theme is on, so the rows look dimmed, but they still take clicks.
        rule.onNodeWithTag(SEASONAL_FUN_ICONS_TAG).performClick()
        assertTrue(!state.settings.holiday.funIcons)
        rule.onNodeWithTag(SEASONAL_PAUSE_TAG).performClick()
        assertTrue(!state.settings.holiday.pauseWhileBusy)
    }

    @Test
    fun thePackCardShowsItsNameAndDatesAndIsSelectedOnlyWhileItIsOn() {
        setup()
        openSeasonalTab()
        rule.onNodeWithText("Pumpkin patch").assertExists()
        assertTrue(rule.onAllNodes(hasText("Halloween · Oct 24 – Nov 1")).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.PUMPKIN_PATCH)).assertIsNotSelected()
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.PUMPKIN_PATCH)).performClick()
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.PUMPKIN_PATCH)).assertIsSelected()
        assertEquals("Oct 24 – Nov 1", holidayDatesLabel(PumpkinPatchTheme))
    }

    @Test
    fun trickOrTreatCardSelectsTheNewTheme() {
        setup()
        openSeasonalTab()
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.TRICK_OR_TREAT)).assertIsNotSelected()
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.TRICK_OR_TREAT)).performClick()
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.TRICK_OR_TREAT)).assertIsSelected()
        assertEquals(HolidayPack.TRICK_OR_TREAT, state.settings.holiday.pack)
        assertEquals(HolidayMode.ALWAYS, state.settings.holiday.mode)
    }

    @Test
    fun beerCardsSelectTheirOwnTheme() {
        setup()
        openSeasonalTab()
        for (pack in listOf(HolidayPack.BEER_TIME_YELLOW, HolidayPack.BEER_TIME_GREEN)) {
            rule.onNodeWithTag(seasonalPackTag(pack)).performClick()
            rule.onNodeWithTag(seasonalPackTag(pack)).assertIsSelected()
            assertEquals(pack, state.settings.holiday.pack)
            assertEquals(HolidayMode.ALWAYS, state.settings.holiday.mode)
        }
    }

    @Test
    fun withRegularThemeAppliesThePresetAndTurnsSeasonalOffWithoutTouchingTheRest() {
        val before = AppSettings(theme = ThemePreset.LIGHT).withHoliday { it.copy(mode = HolidayMode.AUTOMATIC, intensity = HolidayIntensity.CALM) }
        val after = before.withRegularTheme(ThemePreset.GRUVBOX)
        assertEquals(ThemePreset.GRUVBOX, after.theme)
        assertEquals(HolidayMode.OFF, after.holiday.mode)
        assertEquals(HolidayIntensity.CALM, after.holiday.intensity)
        assertEquals(before.holiday.pack, after.holiday.pack)
        assertEquals(before.copy(theme = ThemePreset.GRUVBOX, holiday = before.holiday.copy(mode = HolidayMode.OFF)), after)
    }

    @Test
    fun withPackOnSelectsTheThemeAndTurnsItOnWithoutChangingAnAutomaticChoice() {
        assertEquals(HolidayMode.ALWAYS, HolidaySettings().withPackOn(HolidayPack.PUMPKIN_PATCH).mode)
        assertEquals(HolidayMode.AUTOMATIC, HolidaySettings(mode = HolidayMode.AUTOMATIC).withPackOn(HolidayPack.PUMPKIN_PATCH).mode)
        assertEquals(HolidayMode.ALWAYS, HolidaySettings(mode = HolidayMode.ALWAYS).withPackOn(HolidayPack.PUMPKIN_PATCH).mode)
    }
}
