package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3CustomMessageSpec
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Add message after this" in the canvas arrow menu and the Messages list menu. */
class Seq3InsertMenuUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var root: File
    private lateinit var state: AppState
    private lateinit var sessionId: String

    @After
    fun tearDown() {
        if (::state.isInitialized) state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun labels() = currentSession().document.messages.map { it.labelTemplate }

    private fun firstMessageId() = currentSession().document.messages.first().id

    private fun rightClickQueueRow() {
        rule.onNodeWithTag("seq3-queue-row-${firstMessageId()}", useUnmergedTree = true).performMouseInput { rightClick() }
        rule.waitForIdle()
    }

    private fun closeMenu() {
        rule.onNodeWithTag("seq3-queue-context-add-after", useUnmergedTree = true).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
    }

    @Test
    fun queueMenuOpensTheCustomDialogDirectlyAndCancelClosesWithoutAChooser() {
        start()
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        rightClickQueueRow()
        rule.onNodeWithText("Add custom message after this…", useUnmergedTree = true).assertIsDisplayed().performClick()
        rule.waitForIdle()

        rule.onAllNodesWithText("Add message after this row", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithText("After clicked row", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(Seq3InsertStart.CUSTOM, view.pendingInsertRequest?.start)

        rule.onNode(hasText("Cancel") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertNull(view.pendingInsertRequest, "Cancel closes the request")
        rule.onAllNodesWithText("Add message after this row", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText("After clicked row", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun theSelectedRowsEntryExistsOnlyWithASelectionAndInsertsAfterTheRowInOneClick() {
        start()
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        rightClickQueueRow()
        rule.onAllNodesWithText("selected log row", substring = true, useUnmergedTree = true).assertCountEquals(0)
        closeMenu()

        state.tabs = state.tabs.map { it.copy(selected = setOf(SELECTED_ENTRY)) }
        rule.waitForIdle()
        val undoBefore = currentSession().undoStack.size
        rightClickQueueRow()
        rule.onNodeWithText("Add 1 selected log row after this", useUnmergedTree = true).assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals(3, labels().size, "the selected row became a message")
        assertEquals("Second", labels().last(), "and it sits right after the clicked row, before the next one")
        assertTrue(labels()[1] != "Second" && labels()[1] != "First")
        assertEquals(undoBefore + 1, currentSession().undoStack.size)
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        assertNull(view.pendingInsertRequest, "no dialog and no pending request for the one-click path")
    }

    @Test
    fun theSelectedRowsLabelCountsTheSelection() {
        assertEquals("Add 1 selected log row after this", seq3AddSelectedRowsLabel(1))
        assertEquals("Add 4 selected log rows after this", seq3AddSelectedRowsLabel(4))
    }

    @Test
    fun fromLogSubmenuEntersRowNumbersAndOpensTheLogView() {
        start()
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        rightClickQueueRow()
        rule.onNodeWithTag("seq3-add-after-from-log", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Enter row numbers…", useUnmergedTree = true).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Add message from log rows", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(Seq3InsertStart.ROWS, view.pendingInsertRequest?.start)
        rule.onNode(hasText("Cancel") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertNull(view.pendingInsertRequest, "cancelling the row-number dialog closes the request")

        state.activeSurface = ActiveSurface.Diagram3(sessionId)
        rightClickQueueRow()
        rule.onNodeWithTag("seq3-add-after-from-log", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Open log view to select rows", useUnmergedTree = true).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(ActiveSurface.Log(SOURCE_TAB), state.activeSurface, "the source log tab is brought forward")
        assertNull(view.pendingInsertRequest)
    }

    @Test
    fun canvasMenuOffersTheSameEntriesAndTheCustomDialogOpensAfterTheArrow() {
        start()
        state.tabs = state.tabs.map { it.copy(selected = setOf(SELECTED_ENTRY)) }
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        rule.waitForIdle()
        val first = Seq3RenderCache.layout(currentSession().document).rows.filterIsInstance<Seq3ArrowRow>().first()
        val body = rule.onNodeWithTag("seq3-canvas-body", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = rule.onNodeWithTag("seq3-workspace-root", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val at = Offset(
            body.left - root.left + ((first.fromX + first.toX) / 2).toFloat() * rule.density.density,
            body.top - root.top + first.y.toFloat() * rule.density.density,
        )
        rule.onNodeWithTag("seq3-workspace-root").performMouseInput { rightClick(at) }
        rule.waitForIdle()

        rule.onNodeWithText("Add custom message after this…", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Add 1 selected log row after this", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("From log", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithText("Add message after this…", useUnmergedTree = true).assertCountEquals(0)

        rule.onNodeWithText("Add custom message after this…", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("After clicked row", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(first.messageId, view.pendingInsertRequest?.after?.messageId)
        assertEquals(Seq3InsertStart.CUSTOM, view.pendingInsertRequest?.start)
    }

    private fun start() {
        root = createTempDirectory("insert-menu-ui").toFile()
        val log = listOf(
            LogEntry(1, "10:00:00.100", LogLevel.I, "Client", "call one", pid = 1, tid = 1),
            LogEntry(SELECTED_ENTRY, "10:00:00.200", LogLevel.I, "Client", "picked row", pid = 1, tid = 1),
        )
        val tab = mkTab(SOURCE_TAB, "$SOURCE_TAB.log", log)
        state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also {
            it.tabs = listOf(tab)
            it.activateTab(tab.id)
        }
        sessionId = requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddLifeline(Seq3Lifeline("a", "A", setOf("A"), 0)))
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddLifeline(Seq3Lifeline("b", "B", setOf("B"), 1)))
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", "First")))
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", "Second")))
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(1280.dp, 900.dp)) { Seq3Workspace(state, sessionId) }
            }
        }
        rule.waitForIdle()
    }

    private fun currentSession() = requireNotNull(state.seq3Sessions.sessions.singleOrNull { it.id == sessionId })

    private companion object {
        const val SOURCE_TAB = "insert-menu"
        const val SELECTED_ENTRY = 7
    }
}
