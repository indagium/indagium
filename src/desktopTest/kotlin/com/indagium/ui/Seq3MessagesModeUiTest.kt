package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3InsertionPosition
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3RowRef
import com.indagium.diagram3.Seq3Selection
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.seq3DrawnRowRefs
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Messages tile's Templates | Sequence switch, driven through the real workspace. */
class Seq3MessagesModeUiTest {
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
    fun templatesModeHasNoDragHandleAndTheSortReadsDiagramOrder() {
        val sessionId = startWorkspace("mode-templates", repeatedDocument())
        showWorkspace(sessionId)

        assertEquals(0, tagsWithPrefix("seq3-queue-drag-").size, "the template list has no grip")
        assertEquals(0, tagsWithPrefix("seq3-sequence-drag-").size, "and the sequence list is not shown")
        rule.onNodeWithText("Diagram order", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Log order", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun switchingModeKeepsTheSelectionAndTheSequenceListMatchesTheDrawnRows() {
        val sessionId = startWorkspace("mode-switch", repeatedDocument())
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.selection = Seq3Selection(setOf("rep"), "rep")
        view.selectedOccurrenceMessageId = "rep"
        view.selectedOccurrenceEntryId = 2
        showWorkspace(sessionId)

        switchTo("Sequence")
        val drawn = seq3DrawnRowRefs(currentSession(sessionId).document)
        assertEquals(4, drawn.size)
        assertEquals(drawn.map { "seq3-sequence-row-${seq3SequenceRowKey(it)}" }, tagsWithPrefix("seq3-sequence-row-"))
        assertEquals(0, tagsWithPrefix("seq3-queue-row-").size, "the template cards are replaced, not stacked")
        assertEquals(Seq3Selection(setOf("rep"), "rep"), view.selection)
        assertEquals("rep", view.selectedOccurrenceMessageId)
        assertEquals(2, view.selectedOccurrenceEntryId)

        switchTo("Templates")
        assertEquals(Seq3Selection(setOf("rep"), "rep"), view.selection)
        assertEquals(2, view.selectedOccurrenceEntryId)
        assertEquals(2, tagsWithPrefix("seq3-queue-row-").size)
        rule.onNodeWithText("2 templates · 4 messages · 0 need a target", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun clickingASequenceRowSelectsItsArrowTheWayACanvasClickDoes() {
        val sessionId = startWorkspace("mode-select", repeatedDocument())
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.messagesMode = Seq3MessagesMode.SEQUENCE
        showWorkspace(sessionId)

        rule.onNodeWithTag("seq3-sequence-row-rep::3", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        assertEquals(setOf(Seq3CanvasRowRef("rep", 3)), view.selectedCanvasRows)
        assertEquals("rep", view.selectedOccurrenceMessageId)
        assertEquals(3, view.selectedOccurrenceEntryId)
        assertEquals("rep", view.focusedMessageId)
        assertEquals(setOf("rep"), view.selection.selectedIds)
    }

    @Test
    fun hidingASequenceRowHidesOnlyThatOccurrence() {
        val sessionId = startWorkspace("mode-hide", repeatedDocument())
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.messagesMode = Seq3MessagesMode.SEQUENCE
        showWorkspace(sessionId)

        rule.onNode(
            hasContentDescription("Hide only this occurrence") and hasAnyAncestor(hasTestTag("seq3-sequence-row-rep::2")),
            useUnmergedTree = true,
        ).performClick()
        rule.waitForIdle()

        val rep = currentSession(sessionId).document.messages.first { it.id == "rep" }
        assertEquals(Seq3Visibility.HIDDEN, rep.occurrences.first { it.entryId == 2 }.visibility)
        assertEquals(Seq3Visibility.VISIBLE, rep.occurrences.first { it.entryId == 1 }.visibility)
        assertEquals(3, seq3DrawnRowRefs(currentSession(sessionId).document).size)
        rule.onNodeWithTag("seq3-sequence-row-rep::2", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun customOrderStripAppearsAfterAMoveAndResetClearsIt() {
        val sessionId = startWorkspace("mode-reset", repeatedDocument())
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.messagesMode = Seq3MessagesMode.SEQUENCE
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-custom-order", useUnmergedTree = true).assertDoesNotExist()

        assertTrue(
            state.seq3Sessions.applyCommand(
                sessionId,
                Seq3Command.MoveRow(Seq3RowRef("rep", 3), Seq3InsertionPosition.BeforeRow(Seq3RowRef("rep", 1))),
            ),
        )
        rule.waitForIdle()
        assertTrue(currentSession(sessionId).document.rowOrder.isNotEmpty())
        rule.onNodeWithTag("seq3-custom-order", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Custom order", useUnmergedTree = true).assertIsDisplayed()

        rule.onNodeWithTag("seq3-custom-order-reset", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(currentSession(sessionId).document.rowOrder.isEmpty())
        rule.onNodeWithTag("seq3-custom-order", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun anActiveFilterDisablesTheSequenceDrag() {
        val sessionId = startWorkspace("mode-filtered-drag", repeatedDocument())
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.messagesMode = Seq3MessagesMode.SEQUENCE
        // "e" matches every template's label, so all rows stay and only the filter itself is active.
        view.textFilter = "e"
        showWorkspace(sessionId)
        val before = currentSession(sessionId).document.rowOrder

        rule.onNodeWithTag("seq3-sequence-drag-rep::1", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, 120f * rule.density.density))
        }
        rule.waitForIdle()
        assertNull(view.queueReorderDraggedId, "a filtered list never starts a reorder")
        rule.onNodeWithTag("seq3-workspace-root").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(before, currentSession(sessionId).document.rowOrder)
    }

    @Test
    fun anArrowClickRequestScrollsTheSequenceListToItsRow() {
        val sessionId = startWorkspace("mode-scroll", manyDocument(120))
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.messagesMode = Seq3MessagesMode.SEQUENCE
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-sequence-row-m100::*", useUnmergedTree = true).assertDoesNotExist()

        view.scrollRequestId = "m100"
        rule.waitUntil(3_000) { view.scrollRequestId == null }
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-sequence-row-m100::*", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun switchTo(labelPrefix: String) {
        rule.onNode(
            hasText(labelPrefix, substring = true) and hasAnyAncestor(hasTestTag("seq3-messages-mode")),
            useUnmergedTree = true,
        ).performClick()
        rule.waitForIdle()
    }

    private fun tagsWithPrefix(prefix: String): List<String> =
        rule.onAllNodes(
            SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    private fun startWorkspace(tabId: String, document: Seq3Document): String {
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
        assertTrue(state.seq3Sessions.applyCommand(sessionId, Seq3Command.ReplaceDocument(document)))
        return sessionId
    }

    private fun currentSession(sessionId: String) =
        requireNotNull(state.seq3Sessions.sessions.singleOrNull { it.id == sessionId })

    private fun showWorkspace(sessionId: String) {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(1280.dp, 900.dp)) {
                    Seq3Workspace(state, sessionId)
                }
            }
        }
        rule.waitForIdle()
    }

    private val lifelines = listOf(
        Seq3Lifeline("a", "Client", setOf("Client"), 0),
        Seq3Lifeline("b", "Server", setOf("Server"), 1),
    )

    /** `rep` is one template with three drawn occurrences, `solo` a custom message: 2 templates, 4 rows. */
    private fun repeatedDocument() = Seq3Document(
        lifelines = lifelines,
        messages = listOf(
            Seq3Message(
                id = "rep",
                match = Seq3Match("Client", "send {n}"),
                fromLifelineId = "a",
                toLifelineId = "b",
                labelTemplate = "send {n}",
                occurrences = (1..3).map { Seq3Occurrence(it, it * 100L, "10:00:00.$it", 1, 1, 'I', "send $it") },
            ),
            Seq3Message(
                id = "solo",
                match = Seq3Match("Server", "reply"),
                fromLifelineId = "b",
                toLifelineId = "a",
                labelTemplate = "reply",
                kind = Seq3Kind.RETURN,
            ),
        ),
    )

    private fun manyDocument(count: Int) = Seq3Document(
        lifelines = lifelines,
        messages = (1..count).map { index ->
            Seq3Message(
                id = "m$index",
                match = Seq3Match("Client", "call$index"),
                fromLifelineId = "a",
                toLifelineId = "b",
                labelTemplate = "call$index",
            )
        },
    )
}
