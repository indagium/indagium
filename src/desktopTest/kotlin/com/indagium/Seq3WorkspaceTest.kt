package com.indagium

import com.indagium.diagram3.Seq3Document
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.Seq3RenderCache
import com.indagium.ui.Seq3ViewState
import com.indagium.ui.applySeq3Escape
import com.indagium.ui.mkTab
import com.indagium.ui.seq3AddNote
import com.indagium.ui.seq3ArtifactsSectionVisible
import com.indagium.ui.seq3ClearSelection
import com.indagium.ui.seq3DefaultNotePlacement
import com.indagium.ui.seq3ToggleFragmentSelection
import com.indagium.ui.seq3ToggleLifelineSelection
import com.indagium.ui.seq3ToggleNoteSelection
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WP-panel-toggle: covers the four `Seq3Workspace.kt`/`Seq3QueuePanel.kt` fixes —
 *  toggle-to-deselect for the panel's fragment/note/lifeline rows, Escape clearing them, the
 *  simplified `artifactsVisible` condition, and the header "+ note" button's free-floating
 *  default placement when nothing is selected. Follows `Seq3SessionTest.kt`'s own
 *  temp-dir-backed `AppState` + `mkTab` fixture shape for the cases that need a real session. */
class Seq3WorkspaceTest {
    private companion object {
        const val SHARED_PID = 7
        const val SHARED_TID = 11
    }

    private fun twoTagEntries(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", SHARED_PID, SHARED_TID),
        LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", SHARED_PID, SHARED_TID),
    )

    private fun stateFor(tab: LogTab): AppState {
        val root = createTempDirectory("indagium-seq3-workspace").toFile()
        return AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also { state ->
            state.tabs = listOf(tab)
            state.activateTab(tab.id)
        }
    }

    private fun state(): AppState = stateFor(mkTab("log", "sample.log", twoTagEntries()))

    private fun await(timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not become true within ${timeoutMs}ms")
    }

    private fun awaitGenerated(state: AppState, id: String) =
        await { state.seq3Sessions.sessions.firstOrNull { it.id == id }?.generating == false }

    // ── seq3Toggle*Selection: click-again-to-deselect ────────────────────────────────────────

    @Test
    fun toggleFragmentSelectionSelectsThenDeselectsOnASecondClick() {
        val view = Seq3ViewState()
        seq3ToggleFragmentSelection(view, "f1")
        assertEquals("f1", view.selectedFragmentId)
        seq3ToggleFragmentSelection(view, "f1")
        assertNull(view.selectedFragmentId)
    }

    @Test
    fun toggleFragmentSelectionSwitchesDirectlyToADifferentRow() {
        val view = Seq3ViewState()
        seq3ToggleFragmentSelection(view, "f1")
        seq3ToggleFragmentSelection(view, "f2")
        assertEquals("f2", view.selectedFragmentId, "clicking a different row should select it, not toggle it off")
    }

    @Test
    fun toggleNoteSelectionSelectsThenDeselectsOnASecondClick() {
        val view = Seq3ViewState()
        seq3ToggleNoteSelection(view, "n1")
        assertEquals("n1", view.selectedNoteId)
        seq3ToggleNoteSelection(view, "n1")
        assertNull(view.selectedNoteId)
    }

    @Test
    fun toggleLifelineSelectionSelectsThenDeselectsOnASecondClick() {
        val view = Seq3ViewState()
        seq3ToggleLifelineSelection(view, "l1")
        assertEquals("l1", view.selectedLifelineId)
        seq3ToggleLifelineSelection(view, "l1")
        assertNull(view.selectedLifelineId)
    }

    // ── applySeq3Escape: the new fragment/note/lifeline branch ──────────────────────────────

    @Test
    fun escapeClearsASelectedFragment() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        val session = state.seq3Sessions.sessions.single { it.id == id }
        val view = state.seq3Sessions.viewState(id)!!
        view.selectedFragmentId = "f1"

        assertTrue(applySeq3Escape(state, session, view))

        assertNull(view.selectedFragmentId)
    }

    @Test
    fun escapeClearsASelectedNote() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        val session = state.seq3Sessions.sessions.single { it.id == id }
        val view = state.seq3Sessions.viewState(id)!!
        view.selectedNoteId = "n1"

        assertTrue(applySeq3Escape(state, session, view))

        assertNull(view.selectedNoteId)
    }

    @Test
    fun escapeClearsASelectedLifeline() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        val session = state.seq3Sessions.sessions.single { it.id == id }
        val view = state.seq3Sessions.viewState(id)!!
        view.selectedLifelineId = "l1"

        assertTrue(applySeq3Escape(state, session, view))

        assertNull(view.selectedLifelineId)
    }

    @Test
    fun escapeClearsAllThreePanelSelectionsTogetherWhenMoreThanOneIsSet() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        val session = state.seq3Sessions.sessions.single { it.id == id }
        val view = state.seq3Sessions.viewState(id)!!
        view.selectedFragmentId = "f1"
        view.selectedNoteId = "n1"
        view.selectedLifelineId = "l1"

        assertTrue(applySeq3Escape(state, session, view))

        assertNull(view.selectedFragmentId)
        assertNull(view.selectedNoteId)
        assertNull(view.selectedLifelineId)
    }

    @Test
    fun escapeWithNoPanelSelectionAndNoMessageSelectionFallsThroughToFalse() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        val session = state.seq3Sessions.sessions.single { it.id == id }
        val view = state.seq3Sessions.viewState(id)!!

        assertFalse(applySeq3Escape(state, session, view))
    }

    // ── seq3ClearSelection: clicking empty canvas background also clears a panel selection ───

    @Test
    fun clearSelectionWithClearFocusAlsoClearsThePanelSelections() {
        val view = Seq3ViewState()
        view.selectedFragmentId = "f1"
        view.selectedNoteId = "n1"
        view.selectedLifelineId = "l1"
        view.focusedMessageId = "m1"

        seq3ClearSelection(view, clearFocus = true)

        assertNull(view.selectedFragmentId)
        assertNull(view.selectedNoteId)
        assertNull(view.selectedLifelineId)
        assertNull(view.focusedMessageId)
    }

    @Test
    fun clearSelectionWithoutClearFocusLeavesThePanelSelectionsAlone() {
        val view = Seq3ViewState()
        view.selectedFragmentId = "f1"
        view.selectedNoteId = "n1"
        view.selectedLifelineId = "l1"

        seq3ClearSelection(view, clearFocus = false)

        assertEquals("f1", view.selectedFragmentId)
        assertEquals("n1", view.selectedNoteId)
        assertEquals("l1", view.selectedLifelineId)
    }

    // ── seq3ArtifactsSectionVisible: purely the toggle, never document content ──────────────

    @Test
    fun artifactsSectionVisibleIgnoresWhetherTheDocumentHasAnyFragmentsOrNotes() {
        val view = Seq3ViewState()
        view.artifactsSectionOpen = true
        assertTrue(seq3ArtifactsSectionVisible(view), "an empty document must not hide the section when the toggle is on")

        view.artifactsSectionOpen = false
        assertFalse(seq3ArtifactsSectionVisible(view))
    }

    // ── seq3DefaultNotePlacement: the header "+ note" button's fallback with nothing selected ─

    @Test
    fun defaultNotePlacementIsNonNegativeForAnEmptyDocument() {
        val placement = seq3DefaultNotePlacement(Seq3Document())
        assertTrue(placement.x >= 0.0)
        assertTrue(placement.y >= 0.0)
    }

    @Test
    fun defaultNotePlacementLandsWithinTheGeneratedDiagramsOwnWidth() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val document = state.seq3Sessions.sessions.single { it.id == id }.document

        val placement = seq3DefaultNotePlacement(document)
        val layoutWidth = Seq3RenderCache.layout(document).width

        assertTrue(placement.x >= 0.0)
        assertTrue(placement.x <= layoutWidth, "the note should land within the diagram's own width")
    }

    // ── seq3AddNote from the header button with nothing selected ─────────────────────────────

    @Test
    fun addNoteWithNoSelectionUsesTheDefaultPlacementAndSucceeds() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val session = state.seq3Sessions.sessions.single { it.id == id }
        val view = state.seq3Sessions.viewState(id)!!
        val document = session.document
        val placement = seq3DefaultNotePlacement(document)

        val applied = seq3AddNote(state, session, view, document, emptySet(), placement = placement)

        assertTrue(applied, "seq3AddNote must succeed for a free-floating note even with nothing selected")
        val updated = state.seq3Sessions.sessions.single { it.id == id }.document
        val note = updated.notes.singleOrNull()
        assertNotNull(note, "a free-floating note should have been added")
        assertTrue(note.messageIds.isEmpty(), "a note added with nothing selected must not be anchored to any message")
        assertEquals(placement.x, note.x)
        assertEquals(placement.y, note.y)
    }

    @Test
    fun addNoteWithNoSelectionAndNoPlacementStillReportsTheOldFailure() {
        // The header button itself now always supplies a placement when nothing is selected (see
        // `Seq3FragmentsAndNotesSection` in Seq3QueuePanel.kt) — this only confirms the one
        // remaining genuine failure mode `seq3AddNote` itself still guards against: no selection
        // AND no placement, which the button's hint text still exists to cover.
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        val session = state.seq3Sessions.sessions.single { it.id == id }
        val view = state.seq3Sessions.viewState(id)!!
        val document = session.document

        val applied = seq3AddNote(state, session, view, document, emptySet(), placement = null)

        assertFalse(applied)
    }
}
