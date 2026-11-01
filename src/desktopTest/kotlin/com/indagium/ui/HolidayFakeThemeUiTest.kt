// Host sizes of the panels under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.indagium.model.AppSettings
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.ui.holiday.FAKE_THEMES
import com.indagium.ui.holiday.HOLIDAY_THEMES
import com.indagium.ui.holiday.FakeSparkles
import com.indagium.ui.holiday.HOLIDAY_EFFECTS_OVERLAY_TAG
import com.indagium.ui.holiday.HolidayEffectsMenuSection
import com.indagium.ui.holiday.HolidayEffectsOverlay
import com.indagium.ui.holiday.LAYOUT_MENU_HOLIDAY_INTENSITY_TAG
import com.indagium.ui.holiday.LAYOUT_MENU_HOLIDAY_TAG
import com.indagium.ui.holiday.LocalHoliday
import com.indagium.ui.holiday.SEASONAL_FUN_ICONS_TAG
import com.indagium.ui.holiday.SEASONAL_PAUSE_TAG
import com.indagium.ui.holiday.ThemeSettingsBlock
import com.indagium.ui.holiday.layoutMenuHolidayToggleTag
import com.indagium.ui.holiday.resolveHolidayLook
import com.indagium.ui.holiday.seasonalPackTag
import com.indagium.ui.holiday.withHoliday
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Layout menu section, the Settings section and the overlay under a theme that provides almost nothing (FakeHolidayTheme). */
@OptIn(ExperimentalTestApi::class)
class HolidayFakeThemeUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun newState() {
        directory = createTempDirectory("holiday-fake-ui").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        state.updateSettings { it.withHoliday { h -> h.copy(mode = HolidayMode.ALWAYS) } }
    }

    private fun present(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theLayoutMenuSectionShowsOnlyTheFakeThemesRows() {
        newState()
        val look = resolveHolidayLook(state.settings, LocalDate.of(2026, 3, 3), FAKE_THEMES)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides (look?.colors ?: LIGHT_THEME), LocalHoliday provides look) {
                Box(Modifier.size(500.dp, 800.dp)) { androidx.compose.foundation.layout.Column { HolidayEffectsMenuSection(state) {} } }
            }
        }
        rule.waitForIdle()
        assertTrue(present(LAYOUT_MENU_HOLIDAY_TAG) && present(LAYOUT_MENU_HOLIDAY_INTENSITY_TAG))
        assertTrue(present(layoutMenuHolidayToggleTag(FakeSparkles.id)) && present(layoutMenuHolidayToggleTag("pause")))
        listOf("pumpkin.embers", "pumpkin.bats", "pumpkin.lightning", "scene", "picture", "animate", "icons", "companion").forEach {
            assertTrue(!present(layoutMenuHolidayToggleTag(it)), "no row $it without the capability")
        }
        rule.onNodeWithTag(layoutMenuHolidayToggleTag(FakeSparkles.id)).performClick()
        rule.waitForIdle()
        assertEquals(true, state.settings.holiday.effectToggles[FakeSparkles.id], "a default-off effect can be switched on")
    }

    @Test
    fun theSettingsSectionHasACardPerRegisteredThemeAndNoFunIconsRow() {
        newState()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) { Box(Modifier.size(700.dp, 900.dp)) { ThemeSettingsBlock(state, FAKE_THEMES) } }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Fake fest").assertExists()
        rule.onNodeWithText("Testing · Jun 1 – Jun 30").assertExists()
        assertTrue(present(seasonalPackTag(HolidayPack.PUMPKIN_PATCH)))
        assertTrue(!present(SEASONAL_FUN_ICONS_TAG), "no icons, no Fun icons row")
        assertTrue(present(SEASONAL_PAUSE_TAG))
    }

    @Test
    fun theRealSeasonalSettingsShowAndSelectTheHauntedManorCard() {
        newState()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(700.dp, 900.dp)) { ThemeSettingsBlock(state, HOLIDAY_THEMES) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Haunted manor").assertExists()
        assertTrue(present(seasonalPackTag(HolidayPack.HAUNTED_MANOR)))
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.HAUNTED_MANOR)).performClick()
        rule.waitForIdle()
        assertEquals(HolidayPack.HAUNTED_MANOR, state.settings.holiday.pack)
        assertEquals(HolidayMode.ALWAYS, state.settings.holiday.mode)
    }

    @Test
    fun theOverlayRunsForTheFakeThemesOneEffectWithoutCrashing() {
        newState()
        state.updateSettings { it.withHoliday { h -> h.copy(effectToggles = mapOf(FakeSparkles.id to true)) } }
        val look = resolveHolidayLook(state.settings, LocalDate.of(2026, 3, 3), FAKE_THEMES)
        rule.setContent {
            CompositionLocalProvider(LocalHoliday provides look) { Box(Modifier.size(400.dp, 300.dp)) { HolidayEffectsOverlay(Modifier.size(400.dp, 300.dp)) } }
        }
        rule.waitForIdle()
        assertTrue(present(HOLIDAY_EFFECTS_OVERLAY_TAG))
        assertEquals(AppSettings().holiday.effectToggles, emptyMap())
    }
}
