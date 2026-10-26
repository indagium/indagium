package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
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

    private fun retainedSessions(count: Int): List<CaptureSession> = (1..count).map { index ->
        CaptureSession(
            id = "retained-$index",
            directory = File(tempDir, "retained-$index"),
            device = CaptureDevice(serial = "serial-$index", state = "device", model = "Phone $index"),
            settings = CaptureSettings(),
            startedEpochMs = index.toLong(),
            status = CaptureStatus.INTERRUPTED,
        )
    }

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

    @Test
    fun unfinishedListUsesTheRemainingCaptureHeightAndScrollsInsideAtBothSizes() {
        val retained = retainedSessions(24)
        val listHeights = mutableListOf<Float>()
        for (heightDp in listOf(450, 900)) {
            rule.setContent {
                Box(Modifier.requiredSize(900.dp, heightDp.dp).testTag("capture-test-host")) {
                    HomeCaptureSection(
                        state = state,
                        launcherTabId = "home",
                        onReclaimFocus = {},
                        modifier = Modifier.fillMaxSize(),
                        pollDevices = false,
                        retainedSessions = retained,
                    )
                }
            }
            rule.waitForIdle()
            val panel = rule.onNodeWithTag("unfinished-inline").getBoundsInRoot()
            val host = rule.onNodeWithTag("capture-test-host").getBoundsInRoot()
            val list = rule.onNodeWithTag("unfinished-session-list").getBoundsInRoot()
            assertTrue(
                kotlin.math.abs(panel.bottom.value - (host.bottom.value - 12f)) <= 2f,
                "panel bottom ${panel.bottom} should track card bottom ${host.bottom}",
            )
            assertTrue(list.height.value > 0f)
            listHeights += list.height.value
        }
        assertTrue(listHeights[1] > listHeights[0], "the taller card gives the unfinished list more room: $listHeights")
    }
}
