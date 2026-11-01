// The popup's host size is literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.indagium.model.AppSettings
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.ui.holiday.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Layout menu's "Holiday effects" section: only under an active pack, and every row writes its setting. */
@OptIn(ExperimentalTestApi::class)
class LayoutMenuHolidayUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState
    private var reclaims = 0

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun setup(mode: HolidayMode, pack: HolidayPack = HolidayPack.PUMPKIN_PATCH) {
        directory = createTempDirectory("layout-menu-holiday-ui").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        val tab = mkTab("holiday-menu", "menu.log", emptyList())
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.updateSettings { it.withHoliday { h -> h.copy(mode = mode, pack = pack) } }
        // The pack is active exactly when the setting says so, like App.kt decides it.
        val look = resolveHolidayLook(state.settings, LocalDate.of(2026, 10, 31))
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides (look?.colors ?: LIGHT_THEME), LocalHoliday provides look) {
                Box(Modifier.size(500.dp, 800.dp)) { LayoutMenuButton(state, onReclaimFocus = { reclaims++ }) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.waitForIdle()
    }

    private fun present(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theSectionIsAbsentWithoutAPack() {
        setup(HolidayMode.OFF)
        assertTrue(present("layout-menu-lock"), "the menu itself is open")
        assertFalse(present(LAYOUT_MENU_HOLIDAY_TAG), "no pack, no Holiday effects")
    }

    @Test
    fun theSectionListsEveryRowUnderAPack() {
        setup(HolidayMode.ALWAYS)
        assertTrue(present(LAYOUT_MENU_HOLIDAY_TAG))
        assertTrue(present(LAYOUT_MENU_HOLIDAY_INTENSITY_TAG))
        listOf("pumpkin.embers", "pumpkin.bats", "pumpkin.lightning", "scene", "picture", "animate", "icons", "companion", "pause").forEach {
            assertTrue(present(layoutMenuHolidayToggleTag(it)), "row $it")
        }
    }

    @Test
    fun theManorLayoutMenuOffersItsOwnEffectsAndCompanionSwitch() {
        setup(HolidayMode.ALWAYS, HolidayPack.HAUNTED_MANOR)
        listOf("manor.ghosts", "manor.fog", "manor.lightning", "scene", "picture", "animate", "icons", "companion", "pause").forEach {
            assertTrue(present(layoutMenuHolidayToggleTag(it)), "row $it")
        }
        rule.onNodeWithTag(layoutMenuHolidayToggleTag("manor.fog")).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(false, state.settings.holiday.effectToggles["manor.fog"])
    }

    @Test
    fun trickOrTreatExposesAndPersistsItsOwnEffectControls() {
        setup(HolidayMode.ALWAYS, HolidayPack.TRICK_OR_TREAT)
        listOf("candy.rain", "candy.landing", "candy.lightning", "scene", "picture", "animate", "icons", "companion").forEach {
            assertTrue(present(layoutMenuHolidayToggleTag(it)), "row $it")
        }
        rule.onNodeWithTag(layoutMenuHolidayToggleTag("candy.landing")).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(false, state.settings.holiday.effectToggles["candy.landing"])
    }

    @Test
    fun clickingARowFlipsItsSettingAndHandsFocusBack() {
        setup(HolidayMode.ALWAYS)
        val before = reclaims
        assertTrue(state.settings.holiday.effectToggles["pumpkin.bats"] != false)
        rule.onNodeWithTag(layoutMenuHolidayToggleTag("pumpkin.bats")).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(false, state.settings.holiday.effectToggles["pumpkin.bats"])
        assertTrue(reclaims > before, "focus is reclaimed after the click")
        rule.onNodeWithTag(layoutMenuHolidayToggleTag("companion")).performScrollTo().performClick()
        rule.waitForIdle()
        assertFalse(state.settings.holiday.notesCompanion)
        rule.onNodeWithTag(layoutMenuHolidayToggleTag("pause")).performScrollTo().performClick()
        rule.waitForIdle()
        assertFalse(state.settings.holiday.pauseWhileBusy)
    }

    @Test
    fun theIntensityControlSetsCalmAndOff() {
        setup(HolidayMode.ALWAYS)
        rule.onNode(hasText("Calm") and hasAnyAncestor(hasTestTag(LAYOUT_MENU_HOLIDAY_INTENSITY_TAG))).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(HolidayIntensity.CALM, state.settings.holiday.intensity)
        rule.onNode(hasText("Off") and hasAnyAncestor(hasTestTag(LAYOUT_MENU_HOLIDAY_INTENSITY_TAG))).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(HolidayIntensity.OFF, state.settings.holiday.intensity)
        assertEquals(HolidayMode.ALWAYS, state.settings.holiday.mode)
        assertEquals(AppSettings().tabStripStyle, state.settings.tabStripStyle)
    }
}
