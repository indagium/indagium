// The panel's host size is literal on purpose.
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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayLightness
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings > Appearance > Seasonal theme: each control writes its HolidaySettings field. */
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

    private fun setup() {
        directory = createTempDirectory("seasonal-settings-ui").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) { Box(Modifier.size(700.dp, 900.dp)) { SeasonalThemesSettings(state) } }
        }
        rule.waitForIdle()
    }

    private fun choose(group: String, label: String) {
        rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(group))).performClick()
        rule.waitForIdle()
    }

    @Test
    fun alwaysTurnsThePackOnAndOffTurnsItBack() {
        setup()
        assertNull(resolveHolidayLook(state.settings, LocalDate.of(2026, 3, 3)))
        choose(SEASONAL_MODE_TAG, "Always")
        assertEquals(HolidayMode.ALWAYS, state.settings.holiday.mode)
        assertNotNull(resolveHolidayLook(state.settings, LocalDate.of(2026, 3, 3)), "the look becomes active")
        choose(SEASONAL_MODE_TAG, "Automatic")
        assertEquals(HolidayMode.AUTOMATIC, state.settings.holiday.mode)
        assertNull(resolveHolidayLook(state.settings, LocalDate.of(2026, 3, 3)))
        assertNotNull(resolveHolidayLook(state.settings, LocalDate.of(2026, 10, 31)))
        choose(SEASONAL_MODE_TAG, "Off")
        assertEquals(HolidayMode.OFF, state.settings.holiday.mode)
    }

    @Test
    fun lightAndDarkAndEffectsWriteTheirFields() {
        setup()
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
        // The mode is Off, so the rows look dimmed, but they still take clicks.
        rule.onNodeWithTag(SEASONAL_FUN_ICONS_TAG).performClick()
        assertTrue(!state.settings.holiday.funIcons)
        rule.onNodeWithTag(SEASONAL_PAUSE_TAG).performClick()
        assertTrue(!state.settings.holiday.pauseWhileBusy)
    }

    @Test
    fun thePackCardIsASelectedButtonWithItsNameAndDates() {
        setup()
        rule.onNodeWithTag(seasonalPackTag(HolidayPack.PUMPKIN_PATCH)).performClick()
        assertEquals(HolidayPack.PUMPKIN_PATCH, state.settings.holiday.pack)
        rule.onNodeWithText("Pumpkin patch").assertExists()
        rule.onNodeWithText("Halloween · Oct 24 – Nov 1").assertExists()
        assertEquals("Oct 24 – Nov 1", holidayDatesLabel(com.indagium.ui.holiday.pumpkin.PumpkinPatchTheme))
    }
}
