package com.indagium

import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.toMermaid
import com.indagium.diagram3.toPlantUml
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.Seq3CopyTarget
import com.indagium.ui.Seq3RenderCache
import com.indagium.ui.Seq3ViewState
import com.indagium.ui.applySeq3Escape
import com.indagium.ui.mkTab
import com.indagium.ui.seq3AddNote
import com.indagium.ui.seq3AutoExpandOccurrences
import com.indagium.ui.seq3DisownAutoExpand
import com.indagium.ui.seq3ArtifactsSectionVisible
import com.indagium.ui.seq3ClearSelection
import com.indagium.ui.seq3CopyTargetLabel
import com.indagium.ui.seq3CopyTargetText
import com.indagium.ui.seq3DefaultNotePlacement
import com.indagium.ui.seq3PaneSegments
import com.indagium.ui.seq3PanelVisible
import com.indagium.ui.seq3PrefixToggleSegments
import com.indagium.ui.seq3ToggleFragmentSelection
import com.indagium.ui.seq3ToggleLifelineSelection
import com.indagium.ui.seq3ToggleNoteSelection
import com.indagium.ui.seq3TogglePaneSegment
import com.indagium.ui.seq3TogglePrefixSegment
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

    // ── seq3PaneSegments / seq3TogglePaneSegment: header 1a's Panes multi-select ─────────────

    @Test
    fun paneSegmentsReflectsAllThreeViewFlagsIndependently() {
        val view = Seq3ViewState()
        view.messagesSectionOpen = true
        view.lifelinesSectionOpen = false
        view.artifactsSectionOpen = true

        assertEquals(setOf(0, 2), seq3PaneSegments(view))
    }

    @Test
    fun paneSegmentsIsEmptyWhenAllThreePanesAreClosed() {
        val view = Seq3ViewState()
        view.messagesSectionOpen = false
        view.lifelinesSectionOpen = false
        view.artifactsSectionOpen = false

        assertEquals(emptySet(), seq3PaneSegments(view))
    }

    @Test
    fun paneSegmentsIsAllThreeWhenEveryPaneIsOpen() {
        val view = Seq3ViewState()
        view.messagesSectionOpen = true
        view.lifelinesSectionOpen = true
        view.artifactsSectionOpen = true

        assertEquals(setOf(0, 1, 2), seq3PaneSegments(view))
    }

    @Test
    fun togglePaneSegmentFlipsOnlyTheMatchingFlag() {
        val view = Seq3ViewState()
        view.messagesSectionOpen = true
        view.lifelinesSectionOpen = true
        view.artifactsSectionOpen = true

        seq3TogglePaneSegment(view, 1)

        assertTrue(view.messagesSectionOpen, "toggling index 1 must not touch messagesSectionOpen")
        assertFalse(view.lifelinesSectionOpen, "toggling index 1 must flip lifelinesSectionOpen")
        assertTrue(view.artifactsSectionOpen, "toggling index 1 must not touch artifactsSectionOpen")
    }

    @Test
    fun panelStaysVisibleWhenMessagesIsClosedButAnotherSectionIsStillOpen() {
        val view = Seq3ViewState()
        view.messagesSectionOpen = true
        view.lifelinesSectionOpen = true
        view.artifactsSectionOpen = false

        seq3TogglePaneSegment(view, 0)

        assertFalse(view.messagesSectionOpen, "index 0 closes only the Messages section")
        assertTrue(view.lifelinesSectionOpen, "closing Messages must not close Lifelines")
        assertTrue(seq3PanelVisible(view), "the panel stays up while any section is open")
    }

    @Test
    fun panelHidesOnlyOnceEverySectionIsClosed() {
        val view = Seq3ViewState()
        view.messagesSectionOpen = false
        view.lifelinesSectionOpen = false
        view.artifactsSectionOpen = true
        assertTrue(seq3PanelVisible(view))

        seq3TogglePaneSegment(view, 2)

        assertFalse(seq3PanelVisible(view), "the last section closing gives the canvas full width")
    }

    @Test
    fun panelComesBackWhenAnySectionIsReopened() {
        val view = Seq3ViewState()
        view.messagesSectionOpen = false
        view.lifelinesSectionOpen = false
        view.artifactsSectionOpen = false

        seq3TogglePaneSegment(view, 1)

        assertTrue(seq3PanelVisible(view))
        assertEquals(setOf(1), seq3PaneSegments(view))
    }

    @Test
    fun togglePaneSegmentIsItsOwnInverse() {
        val view = Seq3ViewState()
        val before = seq3PaneSegments(view)

        seq3TogglePaneSegment(view, 0)
        seq3TogglePaneSegment(view, 0)

        assertEquals(before, seq3PaneSegments(view))
    }

    // ── seq3PrefixToggleSegments / seq3TogglePrefixSegment: `#n` / `⏱ Time` multi-select ─────

    @Test
    fun prefixToggleSegmentsReflectsBothDocumentFieldsIndependently() {
        val document = Seq3Document(showSequenceNumbers = true, showTimestamps = false)
        assertEquals(setOf(0), seq3PrefixToggleSegments(document))

        val both = Seq3Document(showSequenceNumbers = true, showTimestamps = true)
        assertEquals(setOf(0, 1), seq3PrefixToggleSegments(both))

        val neither = Seq3Document(showSequenceNumbers = false, showTimestamps = false)
        assertEquals(emptySet(), seq3PrefixToggleSegments(neither))
    }

    @Test
    fun togglePrefixSegmentFlipsOnlyShowSequenceNumbersForIndexZero() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val session = state.seq3Sessions.sessions.single { it.id == id }
        val before = session.document

        seq3TogglePrefixSegment(state, session, 0)

        val after = state.seq3Sessions.sessions.single { it.id == id }.document
        assertEquals(!before.showSequenceNumbers, after.showSequenceNumbers)
        assertEquals(before.showTimestamps, after.showTimestamps, "index 0 must not touch showTimestamps")
    }

    @Test
    fun togglePrefixSegmentFlipsOnlyShowTimestampsForIndexOne() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val session = state.seq3Sessions.sessions.single { it.id == id }
        val before = session.document

        seq3TogglePrefixSegment(state, session, 1)

        val after = state.seq3Sessions.sessions.single { it.id == id }.document
        assertEquals(before.showSequenceNumbers, after.showSequenceNumbers, "index 1 must not touch showSequenceNumbers")
        assertEquals(!before.showTimestamps, after.showTimestamps)
    }

    // ── Seq3CopyTarget / seq3CopyTargetLabel / seq3CopyTargetText: header 1a's "Copy ▾" menu ──

    @Test
    fun copyTargetLabelsMatchTheMenuTextForEveryTarget() {
        assertEquals("PNG image", seq3CopyTargetLabel(Seq3CopyTarget.PNG_IMAGE))
        assertEquals("PlantUML source", seq3CopyTargetLabel(Seq3CopyTarget.PLANTUML_SOURCE))
        assertEquals("Mermaid source", seq3CopyTargetLabel(Seq3CopyTarget.MERMAID_SOURCE))
    }

    @Test
    fun copyTargetTextIsNullForPngSinceThatItemCopiesBytesNotText() {
        assertNull(seq3CopyTargetText(Seq3CopyTarget.PNG_IMAGE, Seq3Document()))
    }

    @Test
    fun copyTargetTextReturnsPlantUmlSourceForThePlantUmlTarget() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val document = state.seq3Sessions.sessions.single { it.id == id }.document

        val text = seq3CopyTargetText(Seq3CopyTarget.PLANTUML_SOURCE, document)

        assertNotNull(text)
        assertTrue(text.startsWith("@startuml"), "PlantUML source should start with @startuml, was: $text")
        assertEquals(document.toPlantUml(), text)
    }

    @Test
    fun copyTargetTextReturnsMermaidSourceForTheMermaidTarget() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val document = state.seq3Sessions.sessions.single { it.id == id }.document

        val text = seq3CopyTargetText(Seq3CopyTarget.MERMAID_SOURCE, document)

        assertNotNull(text)
        assertTrue(text.startsWith("sequenceDiagram"), "Mermaid source should start with sequenceDiagram, was: $text")
        assertEquals(document.toMermaid(), text)
    }

    // ── Canvas-driven queue expansion ─────────────────────────────────────────────────────────
    //
    // A canvas click opens the clicked occurrence's group in the queue so the submessage is visible
    // and actionable. That only ever ADDED, so clicking several repeated messages left every group
    // open, all of them still open after the selection was cleared. The expansion is now tracked as
    // automatic and released again — but only when it was this mechanism that opened it.

    @Test
    fun theCanvasCollapsesTheGroupItOpenedWhenItMovesToAnother() {
        val view = Seq3ViewState()

        seq3AutoExpandOccurrences(view, "m1")
        assertEquals(setOf("m1"), view.expandedOccurrenceMessageIds)

        seq3AutoExpandOccurrences(view, "m2")
        assertEquals(setOf("m2"), view.expandedOccurrenceMessageIds, "m1's automatic expansion should not linger")
    }

    @Test
    fun clearingTheSelectionCollapsesTheGroupTheCanvasOpened() {
        val view = Seq3ViewState()
        seq3AutoExpandOccurrences(view, "m1")

        seq3ClearSelection(view)

        assertEquals(emptySet(), view.expandedOccurrenceMessageIds)
        assertNull(view.autoExpandedOccurrenceMessageId)
    }

    @Test
    fun aGroupTheUserOpenedThemselvesIsNeverCollapsedByTheCanvas() {
        val view = Seq3ViewState()
        // The queue's own toggle, then a canvas click on that same group.
        view.expandedOccurrenceMessageIds = setOf("m1")
        seq3AutoExpandOccurrences(view, "m1")

        seq3AutoExpandOccurrences(view, "m2")
        seq3ClearSelection(view)

        assertEquals(setOf("m1"), view.expandedOccurrenceMessageIds, "the user's own expansion must survive")
    }

    @Test
    fun reachingForTheQueueToggleTakesOwnershipOfAnAutomaticExpansion() {
        val view = Seq3ViewState()
        seq3AutoExpandOccurrences(view, "m1")

        seq3DisownAutoExpand(view, "m1")
        seq3ClearSelection(view)

        assertEquals(setOf("m1"), view.expandedOccurrenceMessageIds)
    }

    @Test
    fun clickingTheSameGroupAgainKeepsItAutomatic() {
        val view = Seq3ViewState()

        seq3AutoExpandOccurrences(view, "m1")
        seq3AutoExpandOccurrences(view, "m1")
        seq3ClearSelection(view)

        assertEquals(emptySet(), view.expandedOccurrenceMessageIds, "re-clicking must not turn it into a sticky expansion")
    }

}
