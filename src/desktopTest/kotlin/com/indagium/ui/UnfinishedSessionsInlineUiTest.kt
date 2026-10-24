package com.indagium.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** The Capture card's inline unfinished-sessions list: every session with its full actions, and the "Open in window…" extra. */
class UnfinishedSessionsInlineUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val tempDir = Files.createTempDirectory("unfinished-inline-ui-").toFile()
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

    private fun session(id: String, model: String = "Phone") = CaptureSession(
        id = id,
        directory = File(tempDir, id),
        device = CaptureDevice(serial = "serial-$id", state = "device", model = model),
        settings = CaptureSettings(),
        startedEpochMs = 1L,
        status = CaptureStatus.INTERRUPTED,
    )

    @Test
    fun everySessionIsListedInlineWithItsActionsAndSelectAll() {
        val sessions = (1..5).map { session("s$it", model = "Phone $it") }
        rule.setContent { UnfinishedSessionsInline(state, sessions, onReclaimFocus = {}) }
        rule.onNodeWithText("Unfinished sessions · 5").assertExists()
        sessions.forEach { rule.onNodeWithText("${it.device.model} · INTERRUPTED").assertExists() }
        rule.onNodeWithText("Select all").assertExists()
        rule.onNodeWithText("Delete selected (0)").assertExists()
        assertEquals(5, rule.onAllNodesWithText("Open").fetchSemanticsNodes().size)
        assertEquals(5, rule.onAllNodesWithText("Save ZIP").fetchSemanticsNodes().size)
        assertEquals(5, rule.onAllNodesWithText("Save Compact").fetchSemanticsNodes().size)
        assertEquals(5, rule.onAllNodesWithText("Open folder").fetchSemanticsNodes().size)
        assertEquals(5, rule.onAllNodesWithText("Discard").fetchSemanticsNodes().size)
    }

    @Test
    fun discardAsksForConfirmationInlineAndCancelKeepsTheSession() {
        rule.setContent { UnfinishedSessionsInline(state, listOf(session("s1")), onReclaimFocus = {}) }
        rule.onNodeWithText("Discard").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Confirm discard").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Confirm discard").assertDoesNotExist()
        rule.onNodeWithText("Phone · INTERRUPTED").assertExists()
    }

    @Test
    fun openInWindowShowsTheSamePanelInADialogAndDoneClosesIt() {
        var reclaimed = 0
        val sessions = (1..3).map { session("s$it", model = "Phone $it") }
        rule.setContent { UnfinishedSessionsInline(state, sessions, onReclaimFocus = { reclaimed++ }) }
        assertEquals(1, rule.onAllNodesWithText("Select all").fetchSemanticsNodes().size)
        rule.onNodeWithTag("unfinished-open-window").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Done").assertExists()
        assertEquals(2, rule.onAllNodesWithText("Select all").fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithText("Phone 3 · INTERRUPTED").fetchSemanticsNodes().size)
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Done").assertDoesNotExist()
        assertEquals(1, rule.onAllNodesWithText("Select all").fetchSemanticsNodes().size)
        assertEquals(1, reclaimed)
    }

    @Test
    fun noSessionsShowsTheEmptyStateWithoutAWindowLink() {
        rule.setContent { UnfinishedSessionsInline(state, emptyList(), onReclaimFocus = {}) }
        rule.onNodeWithText("Unfinished sessions · 0").assertExists()
        rule.onNodeWithText("No interrupted sessions").assertExists()
        rule.onNodeWithTag("unfinished-open-window").assertDoesNotExist()
    }
}
