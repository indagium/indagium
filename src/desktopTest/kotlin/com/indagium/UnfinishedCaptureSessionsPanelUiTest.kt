package com.indagium

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.ui.AppState
import com.indagium.ui.UnfinishedCaptureSessionsPanel
import com.indagium.ui.UnfinishedSessionsSection
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UnfinishedCaptureSessionsPanelUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun retainedSessionUpdatesCountAndBodyTogetherAfterInitialEmptyState() {
        val session = CaptureSession(
            id = "restored-session",
            directory = File("/tmp/restored-session"),
            device = CaptureDevice(serial = "device-123", state = "device", model = "Phone"),
            settings = CaptureSettings(),
            startedEpochMs = 1L,
            status = CaptureStatus.INTERRUPTED,
        )
        val tempDir = Files.createTempDirectory("unfinished-sessions-ui-").toFile()
        val state = AppState(
            autosaveFile = File(tempDir, "state.cache"),
            restoreOnCreate = false,
            notesDir = File(tempDir, "notes"),
            archiveCacheDir = File(tempDir, "archive-cache"),
            customCommandsDir = File(tempDir, "commands"),
            filterBackupsDir = File(tempDir, "filter-backups"),
        )

        rule.setContent {
            var retained by remember { mutableStateOf(emptyList<CaptureSession>()) }
            LaunchedEffect(Unit) {
                delay(200)
                retained = listOf(session)
            }
            UnfinishedCaptureSessionsPanel(retained) { sessions ->
                UnfinishedSessionsSection(state, sessions)
            }
        }

        rule.onNodeWithText("Unfinished sessions · 0").assertExists()
        rule.onNodeWithText("No interrupted sessions").assertExists()
        rule.waitUntil(3_000) {
            rule.onAllNodesWithText("Unfinished sessions · 1").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText("Phone · INTERRUPTED").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("No interrupted sessions").assertDoesNotExist()
        rule.onNodeWithText("Unfinished sessions · 1").assertExists()
        rule.onNodeWithText("restored-session").assertExists()
        state.close()
        tempDir.deleteRecursively()
    }
}
