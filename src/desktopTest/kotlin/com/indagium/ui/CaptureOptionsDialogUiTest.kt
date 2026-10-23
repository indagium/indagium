package com.indagium.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// pollDevices = false everywhere: no adb runs.
class CaptureOptionsDialogUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val tempDir = Files.createTempDirectory("capture-options-ui-").toFile()
    private val state = AppState(
        autosaveFile = File(tempDir, "state.cache"),
        restoreOnCreate = false,
        notesDir = File(tempDir, "notes"),
        archiveCacheDir = File(tempDir, "archive-cache"),
        customCommandsDir = File(tempDir, "commands"),
        filterBackupsDir = File(tempDir, "filter-backups"),
    )

    @After
    fun tearDown() {
        state.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun togglingAnOptionChangesTheSavedCaptureSettings() {
        assertTrue(state.settings.captureSettings.recordVideo)
        rule.setContent { CaptureOptionsDialogContent(state, "home", deviceSerial = null, onDismiss = {}) }
        rule.onNodeWithText("Recording options").assertExists()
        rule.onNodeWithText("Record video to file").performClick()
        rule.waitForIdle()
        assertFalse(state.settings.captureSettings.recordVideo)
    }

    @Test
    fun allCaptureSettingsLinkOpensSettingsOnTheCaptureSectionAndClosesTheDialog() {
        var dismissed = false
        rule.setContent { CaptureOptionsDialogContent(state, "home", deviceSerial = null, onDismiss = { dismissed = true }) }
        rule.onNodeWithTag("capture-all-settings").performClick()
        rule.waitForIdle()
        assertEquals(SettingsSection.Capture, state.requestedSettingsSection)
        assertTrue(state.settingsOpen)
        assertTrue(dismissed)
    }

    @Test
    fun changeLinkOnTheCaptureSectionOpensTheOptionsDialog() {
        rule.setContent { HomeCaptureSection(state, "home", onReclaimFocus = {}, pollDevices = false) }
        rule.onNodeWithTag("capture-summary").assertExists()
        rule.onNodeWithText("Connect a device to start").assertExists()
        rule.onNodeWithTag("capture-start").assertIsNotEnabled()
        rule.onNodeWithText("Recording options").assertDoesNotExist()
        rule.onNodeWithTag("capture-change-options").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Recording options").assertExists()
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Recording options").assertDoesNotExist()
    }
}
