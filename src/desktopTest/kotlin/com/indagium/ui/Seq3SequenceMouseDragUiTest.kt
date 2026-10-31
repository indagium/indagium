package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3ArtifactStyle
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3RowRef
import com.indagium.diagram3.seq3DrawnRowRefs
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Sequence-mode drag driven with a real mouse (press, many moves, release) on a list whose
 *  rows differ in height because a fragment label sits above the first row of a run. */
class Seq3SequenceMouseDragUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var root: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun mouseDragDownFollowsThePointerAndCommitsOneMoveOnRelease() {
        val sessionId = startWorkspace("mouse-drag")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.messagesMode = Seq3MessagesMode.SEQUENCE
        showWorkspace()
        val before = seq3DrawnRowRefs(currentSession(sessionId).document)
        val undoBefore = currentSession(sessionId).undoStack.size
        val firstKey = key(1)
        val restingRows = (1..5).associateWith { bounds(row(it)) }

        val pointerCentre = dragFirstRowBetween(afterRow = 4, beforeRow = 5, release = false)

        assertEquals(firstKey, view.queueReorderDraggedId, "the press on the grip arms a drag")
        assertEquals(3, view.queueReorderVisualIds.indexOf(firstKey), "the slot opens after row 4: ${view.queueReorderVisualIds}")
        // Dragged like a Lifeline: no insertion bar, no floating copy, the row itself is the one node ...
        rule.onNodeWithTag("seq3-sequence-drop-bar", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("seq3-sequence-drag-ghost-$firstKey", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(1, rule.onAllNodesWithTag("seq3-sequence-row-$firstKey", useUnmergedTree = true).fetchSemanticsNodes().size)
        // ... and it follows the pointer in a slightly enlarged lift while the rows below slide up into its old place.
        val dragged = bounds(row(1))
        assertEquals(pointerCentre, dragged.center.y, 2.5f, "the dragged row is under the pointer")
        assertEquals(
            SEQ3_DRAG_LIFT_SCALE,
            dragged.height / restingRows.getValue(1).height,
            0.005f,
            "the dragged row is lifted by the scale a dragged Lifeline gets",
        )
        for (n in 2..4) {
            assertEquals(
                restingRows.getValue(n - 1).top,
                bounds(row(n)).top,
                2.5f,
                "row $n slid up by one row",
            )
        }
        assertEquals(restingRows.getValue(5).top, bounds(row(5)).top, 2.5f, "row 5 is below the new slot and stays")
        assertEquals(before, seq3DrawnRowRefs(currentSession(sessionId).document), "the preview does not touch the document")

        rule.onNodeWithTag("seq3-workspace-root").performMouseInput { release() }
        rule.waitForIdle()
        assertEquals(before.drop(1).take(3) + before.first() + before.drop(4), seq3DrawnRowRefs(currentSession(sessionId).document))
        assertEquals(undoBefore + 1, currentSession(sessionId).undoStack.size)
        assertNull(view.queueReorderDraggedId)
    }

    @Test
    fun escapeDuringAMouseDragCancelsWithoutAMove() {
        val sessionId = startWorkspace("mouse-drag-escape")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.messagesMode = Seq3MessagesMode.SEQUENCE
        showWorkspace()
        val before = currentSession(sessionId).document.rowOrder
        val grip = grip(1)
        grip.performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 60f))
            moveBy(Offset(0f, 60f))
        }
        rule.waitForIdle()
        assertEquals(key(1), view.queueReorderDraggedId)
        rule.onNodeWithTag("seq3-workspace-root").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertNull(view.queueReorderDraggedId)
        grip.performMouseInput { release() }
        rule.waitForIdle()
        assertEquals(before, currentSession(sessionId).document.rowOrder)
    }

    @Test
    fun leavingTheListWithTheButtonHeldKeepsTheDragAndReleaseCommits() {
        val sessionId = startWorkspace("mouse-drag-exit")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.messagesMode = Seq3MessagesMode.SEQUENCE
        showWorkspace()
        val before = seq3DrawnRowRefs(currentSession(sessionId).document)
        val undoBefore = currentSession(sessionId).undoStack.size
        val listBounds = bounds(rule.onNodeWithTag("seq3-sequence-list", useUnmergedTree = true))
        val grip = grip(1)
        val gripCentre = bounds(grip).center

        grip.performMouseInput {
            moveTo(center)
            press()
            for (step in 1..6) moveTo(Offset(center.x, center.y + 20f * step))
            // Onto the footer below the list, the button still held.
            moveTo(Offset(center.x, listBounds.bottom + 30f - gripCentre.y + center.y))
            moveTo(Offset(center.x, listBounds.bottom + 34f - gripCentre.y + center.y))
        }
        rule.waitForIdle()
        assertEquals(key(1), view.queueReorderDraggedId, "leaving the list does not cancel while the button is held")

        rule.onNodeWithTag("seq3-workspace-root").performMouseInput { release() }
        rule.waitForIdle()
        val after = seq3DrawnRowRefs(currentSession(sessionId).document)
        assertTrue(after.first() != before.first(), "the row left the top: $after")
        assertEquals(before.toSet(), after.toSet())
        assertEquals(undoBefore + 1, currentSession(sessionId).undoStack.size, "one move is committed on release")
        assertNull(view.queueReorderDraggedId)
    }

    @Test
    fun theDraggedRowCarriesNoFragmentLabelAndTheLabelStaysWithTheRun() {
        val sessionId = startWorkspace("mouse-drag-label")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.messagesMode = Seq3MessagesMode.SEQUENCE
        showWorkspace()
        val label = hasText("loop: retry") // the preview canvas draws the same text, so scope to the list below
        val inList = label and hasAnyAncestor(hasTestTag("seq3-sequence-list"))
        assertEquals(1, rule.onAllNodes(inList, useUnmergedTree = true).fetchSemanticsNodes().size, "the run's label sits above row 5")

        val grip = grip(5)
        grip.performMouseInput {
            moveTo(center)
            press()
            for (step in 1..4) moveTo(Offset(center.x, center.y - 12f * step))
        }
        rule.waitForIdle()
        assertEquals(key(5), view.queueReorderDraggedId)
        rule.onNodeWithTag("seq3-sequence-drag-ghost-${key(5)}", useUnmergedTree = true).assertDoesNotExist()
        row(5).assertIsDisplayed()
        // The run's first row moved out, so its label moves to the next row of the run (row 6) and stays in the list.
        val listLabels = rule.onAllNodes(inList, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, listLabels.size, "exactly one label, in the list")
        assertTrue(listLabels.single().boundsInRoot.top < bounds(row(6)).top, "above row 6")
        assertTrue(listLabels.single().boundsInRoot.bottom <= bounds(row(6)).top + 1f)

        rule.onNodeWithTag("seq3-workspace-root").performMouseInput { release() }
        rule.waitForIdle()
    }

    @Test
    fun aDraggedLifelineIsLiftedByTheSameScaleAsADraggedMessage() {
        startWorkspace("mouse-drag-lifeline-lift")
        showWorkspace()
        val resting = bounds(rule.onNodeWithTag("seq3-lifeline-row-a", useUnmergedTree = true))
        val handle = rule.onAllNodesWithText("⠿", useUnmergedTree = true)[0]

        handle.performMouseInput {
            moveTo(center)
            press()
            for (step in 1..4) moveTo(Offset(center.x, center.y + 6f * step))
        }
        rule.waitForIdle()

        val lifted = bounds(rule.onNodeWithTag("seq3-lifeline-row-a", useUnmergedTree = true))
        assertEquals(SEQ3_DRAG_LIFT_SCALE, lifted.height / resting.height, 0.005f, "the Lifelines list lifts by the shared scale")
        rule.onNodeWithTag("seq3-workspace-root").performMouseInput { release() }
        rule.waitForIdle()
    }

    /** Presses row 1's grip and walks the mouse in steps until the dragged row's centre sits between the
     *  centres of rows [afterRow] and [beforeRow], optionally releasing there. Returns that centre's root Y. */
    private fun dragFirstRowBetween(afterRow: Int, beforeRow: Int, release: Boolean): Float {
        val grip = grip(1)
        val gripBounds = bounds(grip)
        val row1 = bounds(row(1))
        val targetCentre = (bounds(row(afterRow)).center.y + bounds(row(beforeRow)).center.y) / 2f
        // The pointer sits on the grip, so it is (grip centre - row centre) off the row's centre.
        val pointerRootY = targetCentre + (gripBounds.center.y - row1.center.y)
        val deltaY = pointerRootY - gripBounds.center.y
        grip.performMouseInput {
            moveTo(center)
            press()
            val steps = 12
            for (step in 1..steps) moveTo(Offset(center.x, center.y + deltaY * step / steps))
            if (release) release()
        }
        rule.waitForIdle()
        return targetCentre
    }

    private fun key(n: Int): String = seq3SequenceRowKey(Seq3RowRef("m$n"))

    private fun grip(n: Int) = rule.onNodeWithTag("seq3-sequence-drag-${key(n)}", useUnmergedTree = true)

    private fun row(n: Int) = rule.onNodeWithTag("seq3-sequence-row-${key(n)}", useUnmergedTree = true)

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot

    private fun startWorkspace(tabId: String): String {
        root = createTempDirectory("$tabId-ui").toFile()
        val tab = mkTab(tabId, "$tabId.log", emptyList())
        state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also {
            it.tabs = listOf(tab)
            it.activateTab(tab.id)
        }
        val sessionId = requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
        assertTrue(state.seq3Sessions.applyCommand(sessionId, Seq3Command.ReplaceDocument(document())))
        return sessionId
    }

    private fun currentSession(sessionId: String) =
        requireNotNull(state.seq3Sessions.sessions.singleOrNull { it.id == sessionId })

    private fun showWorkspace() {
        val sessionId = state.seq3Sessions.sessions.single().id
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(1280.dp, 900.dp)) {
                    Seq3Workspace(state, sessionId)
                }
            }
        }
        rule.waitForIdle()
    }

    /** Twelve drawn rows; a red `loop` holds m5 and m6, so row 5 carries the fragment label line. */
    private fun document() = Seq3Document(
        lifelines = listOf(
            Seq3Lifeline("a", "Client", setOf("Client"), 0),
            Seq3Lifeline("b", "Server", setOf("Server"), 1),
        ),
        messages = (1..12).map { index ->
            Seq3Message(
                id = "m$index",
                match = Seq3Match("Client", "call$index"),
                fromLifelineId = "a",
                toLifelineId = "b",
                labelTemplate = "call$index",
            )
        },
        fragments = listOf(
            Seq3Fragment(
                id = "f1",
                kind = Seq3FragmentKind.LOOP,
                label = "retry",
                messageIds = listOf("m5", "m6"),
                style = Seq3ArtifactStyle(borderArgb = 0xFFCC2222.toInt()),
            ),
        ),
    )
}
