// The panel's host size is literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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

    // Through the semantics, not the mouse: the cards fill several rows, so a row can sit below the test window's bottom edge.
    private fun choose(group: String, label: String) {
        rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(group))).performSemanticsAction(SemanticsActions.OnClick)
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
        rule.onNodeWithText("Pumpkin patch: Oct 24 – Nov 1.").assertExists()
    }

    @Test
    fun underAutomaticOutsideTheDatesAShortLineSaysWhenItTurnsOnAndWhatShowsUntilThen() {
        setup { it.updateSettings { s -> s.withHoliday { h -> h.copy(mode = HolidayMode.AUTOMATIC) }.copy(theme = ThemePreset.DRACULA) } }
        val today = LocalDate.now()
        if (PumpkinPatchTheme.dates.contains(today)) {
            assertTrue(!present(SEASONAL_STATUS_TAG), "inside the window nothing is waiting")
        } else {
            rule.onNodeWithTag(SEASONAL_STATUS_TAG).assertExists()
            rule.onNodeWithText("Pumpkin patch turns on Oct 24; until then your theme is Dracula.").assertExists()
        }
    }

    @Test
    fun offIsTheFirstWhenSegmentAndSwitchesTheSeasonalThemeOffKeepingThePack() {
        setup()
        openSeasonalTab()
        // With nothing on, Off is the chosen segment (selected, not merely dimmed).
        rule.onNode(hasText("Off") and hasAnyAncestor(hasTestTag(SEASONAL_MODE_TAG))).assertIsSelected()
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.PUMPKIN_PATCH)).performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Off") and hasAnyAncestor(hasTestTag(SEASONAL_MODE_TAG))).assertIsNotSelected()
        rule.onNode(hasText("Always") and hasAnyAncestor(hasTestTag(SEASONAL_MODE_TAG))).assertIsSelected()
        choose(SEASONAL_MODE_TAG, "Off")
        assertEquals(HolidayMode.OFF, state.settings.holiday.mode)
        assertEquals(HolidayPack.PUMPKIN_PATCH, state.settings.holiday.pack, "Off keeps the pack for next time")
        rule.onNode(hasText("Off") and hasAnyAncestor(hasTestTag(SEASONAL_MODE_TAG))).assertIsSelected()
    }

    @Test
    fun theWhenSegmentsAreOffAlwaysAndTheDatesAndAnAnyTimePackHasNoDatesOption() {
        setup()
        openSeasonalTab()

        fun inMode(label: String) = rule.onAllNodes(hasText(label) and hasAnyAncestor(hasTestTag(SEASONAL_MODE_TAG))).fetchSemanticsNodes().size
        assertEquals(1, inMode("Off"))
        assertEquals(1, inMode("Always"))
        assertEquals(1, inMode("Only during its dates"))
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.COFFEE_BEAR)).performClick()
        rule.waitForIdle()
        assertEquals(1, inMode("Off"))
        assertEquals(1, inMode("Always"))
        assertEquals(0, inMode("Only during its dates"), "an any-time pack has no dates to follow")
        rule.onNodeWithText("Coffee bear is on all year while chosen.").assertExists()
        assertTrue(rule.onAllNodes(hasText("Jan 1 – Dec 31", substring = true)).fetchSemanticsNodes().isEmpty(), "no dates for an any-time pack")
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
    fun theTabsChoiceAddsAndRemovesTheSelectedPackFromMyTabStylePacks() {
        setup()
        openSeasonalTab()
        assertTrue(state.settings.holiday.ownTabStylePacks.isEmpty())
        rule.onNodeWithText("My tab style keeps your Tab style (Settings › Tabs & toolbar) in the pack's colours.").assertExists()
        choose(SEASONAL_TABS_TAG, "My tab style")
        assertEquals(setOf(HolidayPack.PUMPKIN_PATCH), state.settings.holiday.ownTabStylePacks)
        choose(SEASONAL_TABS_TAG, "Theme's tabs")
        assertTrue(state.settings.holiday.ownTabStylePacks.isEmpty())
        // The choice is only the selected pack's: another pack in the set stays.
        state.updateSettings { s -> s.withHoliday { it.copy(ownTabStylePacks = setOf(HolidayPack.HAUNTED_MANOR)) } }
        rule.waitForIdle()
        choose(SEASONAL_TABS_TAG, "My tab style")
        assertEquals(setOf(HolidayPack.HAUNTED_MANOR, HolidayPack.PUMPKIN_PATCH), state.settings.holiday.ownTabStylePacks)
        choose(SEASONAL_TABS_TAG, "Theme's tabs")
        assertEquals(setOf(HolidayPack.HAUNTED_MANOR), state.settings.holiday.ownTabStylePacks)
    }

    @Test
    fun theTabsRowIsASeasonalAnchorSoASearchOpensTheSpecialThemesTab() {
        assertTrue(SEASONAL_ANCHOR_TABS in SEASONAL_ANCHORS)
    }

    @Test
    fun theTwoSwitchesFlipTheirFieldsAndTheRowsStayEditableWithNoSpecialThemeOn() {
        setup()
        openSeasonalTab()
        // No special theme is on and nothing is dimmed: the rows take clicks, so a choice can be made before switching it on. The cards fill several
        // rows now, so these rows can sit below the test window's bottom edge: they are clicked through their semantics.
        rule.onNodeWithTag(SEASONAL_FUN_ICONS_TAG).assertIsOn().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(!state.settings.holiday.funIcons)
        rule.onNodeWithTag(SEASONAL_FUN_ICONS_TAG).assertIsOff()
        rule.onNodeWithTag(SEASONAL_PAUSE_TAG).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(!state.settings.holiday.pauseWhileBusy)
        assertEquals(HolidayMode.OFF, state.settings.holiday.mode)
    }

    @Test
    fun theRowsAreSettingsRowsWithTheirHintsAsWrappingDescriptions() {
        setup()
        openSeasonalTab()
        listOf(
            SEASONAL_ANCHOR_PACK, SEASONAL_ANCHOR_WHEN, SEASONAL_ANCHOR_LIGHTNESS, SEASONAL_ANCHOR_EFFECTS, SEASONAL_ANCHOR_TABS,
            SEASONAL_ANCHOR_FUN_ICONS, SEASONAL_ANCHOR_PAUSE,
        ).forEach { rule.onNodeWithText(it).assertExists() }
        // The Effects hint is a whole sentence, not clipped mid-word.
        rule.onNodeWithText(holidayEffectsHint(PumpkinPatchTheme)).assertExists()
        rule.onNodeWithText("Log levels, sequences and highlighters keep their colours in every pack.").assertExists()
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
    fun tulipsCardTurnsOnAnAnytimeThemeFromAutomatic() {
        setup { it.updateSettings { s -> s.withHoliday { h -> h.copy(mode = HolidayMode.AUTOMATIC) } } }
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.TULIPS)).performClick()
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.TULIPS)).assertIsSelected()
        assertEquals(HolidayPack.TULIPS, state.settings.holiday.pack)
        assertEquals(HolidayMode.ALWAYS, state.settings.holiday.mode)
    }

    @Test
    fun coffeeBearCardTurnsOnAnAnytimeThemeFromAutomatic() {
        setup { it.updateSettings { s -> s.withHoliday { h -> h.copy(mode = HolidayMode.AUTOMATIC) } } }
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.COFFEE_BEAR)).performClick()
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.COFFEE_BEAR)).assertIsSelected()
        assertEquals(HolidayPack.COFFEE_BEAR, state.settings.holiday.pack)
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

    @Test
    fun categoryFilterShowsOnlyThatGroupsCards() {
        setup()
        openSeasonalTab()
        val filter = { label: String -> rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(SEASONAL_CATEGORY_TAG))) }
        val shown = { pack: HolidayPack -> rule.onAllNodesWithTag(seasonalPackTag(pack)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(HOLIDAY_THEMES.keys.all(shown), "All shows every card")

        filter(HolidayCategory.PAINTING.label).performClick()
        rule.waitForIdle()
        assertEquals(setOf(HolidayPack.TULIPS, HolidayPack.COFFEE_BEAR), HOLIDAY_THEMES.keys.filter(shown).toSet())

        filter(HolidayCategory.FUN.label).performClick()
        rule.waitForIdle()
        assertEquals(
            setOf(HolidayPack.BEER_TIME_YELLOW, HolidayPack.BEER_TIME_GREEN, HolidayPack.READY_PROMPT, HolidayPack.RETRO),
            HOLIDAY_THEMES.keys.filter(shown).toSet(),
        )

        filter(HolidayCategory.HOLIDAY.label).performClick()
        rule.waitForIdle()
        assertTrue(shown(HolidayPack.PUMPKIN_PATCH) && !shown(HolidayPack.TULIPS))

        filter("All").performClick()
        rule.waitForIdle()
        assertTrue(HOLIDAY_THEMES.keys.all(shown))
        assertEquals(AppSettings().holiday, state.settings.holiday, "filtering changes no setting")
    }

    @Test
    fun categoryFilterListsOnlyGroupsThatHaveThemes() {
        assertEquals(listOf(null, HolidayCategory.HOLIDAY, HolidayCategory.FUN, HolidayCategory.PAINTING), holidayCategoryFilters(HOLIDAY_THEMES))
        assertEquals(listOf(null, HolidayCategory.HOLIDAY), holidayCategoryFilters(FAKE_THEMES))
    }
}
