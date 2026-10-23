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

class UnfinishedSessionsCompactUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val tempDir = Files.createTempDirectory("unfinished-compact-ui-").toFile()
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
    fun moreMenuListsTheFourActions() {
        rule.setContent { UnfinishedSessionsCompact(state, listOf(session("s1")), onReclaimFocus = {}) }
        rule.onNodeWithText("Unfinished sessions · 1").assertExists()
        rule.onNodeWithText("Save ZIP").assertDoesNotExist()
        rule.onNodeWithTag("unfinished-more-s1").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Save ZIP").assertExists()
        rule.onNodeWithText("Save Compact").assertExists()
        rule.onNodeWithText("Open folder").assertExists()
        rule.onNodeWithText("Discard…").assertExists()
    }

    @Test
    fun discardAsksForConfirmationAndCancelKeepsTheSession() {
        rule.setContent { UnfinishedSessionsCompact(state, listOf(session("s1")), onReclaimFocus = {}) }
        rule.onNodeWithTag("unfinished-more-s1").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Discard…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete this session?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete this session?").assertDoesNotExist()
        rule.onNodeWithText("Phone · INTERRUPTED").assertExists()
    }

    @Test
    fun onlyTheFirstThreeSessionsAreListedAndManageAllShowsEverythingWithSelectAll() {
        val sessions = (1..5).map { session("s$it", model = "Phone $it") }
        rule.setContent { UnfinishedSessionsCompact(state, sessions, onReclaimFocus = {}) }
        rule.onNodeWithText("Unfinished sessions · 5").assertExists()
        assertEquals(HOME_UNFINISHED_ROWS, sessions.count { rule.onAllNodesWithText("${it.device.model} · INTERRUPTED").fetchSemanticsNodes().isNotEmpty() })
        rule.onNodeWithText("Select all").assertDoesNotExist()
        rule.onNodeWithTag("unfinished-manage-all").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Select all").assertExists()
        rule.onNodeWithText("Phone 5 · INTERRUPTED").assertExists()
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Select all").assertDoesNotExist()
    }

    @Test
    fun noSessionsShowsTheEmptyStateWithoutAManageLink() {
        rule.setContent { UnfinishedSessionsCompact(state, emptyList(), onReclaimFocus = {}) }
        rule.onNodeWithText("Unfinished sessions · 0").assertExists()
        rule.onNodeWithText("No interrupted sessions").assertExists()
        rule.onNodeWithTag("unfinished-manage-all").assertDoesNotExist()
    }
}
