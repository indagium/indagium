package com.indagium

import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Range
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.ui.ActiveSurface
import com.indagium.ui.AppState
import com.indagium.ui.Seq3Session
import com.indagium.ui.mkTab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers `ui/Seq3Session.kt`: session lifecycle, source-tab-close independence (SAAD §11.6),
 *  undo-stack routing over `applySeq3Command`, and the dirty-flag/draft-saved-timestamp contract.
 *  Follows `DiagramWorkspaceSessionTest.kt`'s own style (a poll-based `await`, a temp-dir-backed
 *  `AppState`, `mkTab` fixtures) since it exercises the v3 equivalent of the same coordinator role. */
class Seq3SessionTest {
    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val SHARED_PID = 7
        const val SHARED_TID = 11
    }

    private fun await(timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not become true within ${timeoutMs}ms")
    }

    // Same-thread (pid=7, tid=11), two-tag fixture: "Producer"/"Consumer" close enough in time for
    // Seq3Generator's thread-handoff inference to link them — mirrors Seq3GeneratorTest's own
    // fixture shape, chosen so begin() reliably yields >=2 lifelines to exercise lifeline commands
    // against, not an accident of two arbitrary unrelated tags.
    private fun twoTagEntries(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", SHARED_PID, SHARED_TID),
        LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", SHARED_PID, SHARED_TID),
    )

    private fun stateFor(tab: LogTab): AppState {
        val root = createTempDirectory("indagium-seq3-sessions").toFile()
        return AppState(File(root, "state.cache"), notesDir = File(root, "notes")).also { state ->
            state.tabs = listOf(tab)
            state.activateTab(tab.id)
        }
    }

    private fun state(): AppState = stateFor(mkTab("log", "sample.log", twoTagEntries()))

    private fun awaitGenerated(state: AppState, id: String) =
        await { state.seq3Sessions.sessions.firstOrNull { it.id == id }?.generating == false }

    // ── Lifecycle ────────────────────────────────────────────────────────────────────────────

    @Test
    fun beginOpensASessionAndSetsTheV3ActiveSurface() {
        val state = state()

        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!

        assertEquals(1, state.seq3Sessions.sessions.size)
        assertEquals(id, state.seq3Sessions.activeSessionId)
        assertEquals(ActiveSurface.Diagram3(id), state.activeSurface)
        assertEquals(1, state.tabs.size, "a v3 workspace must not be inserted into AppState.tabs")
    }

    @Test
    fun beginOnAMissingTabOpensNothing() {
        val state = state()

        val id = state.seq3Sessions.begin("does-not-exist")

        assertNull(id)
        assertTrue(state.seq3Sessions.sessions.isEmpty())
    }

    @Test
    fun beginGeneratesADocumentFromTheSelectedRange() {
        val state = state()

        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)

        val session = state.seq3Sessions.sessions.single()
        assertTrue(session.document.lifelines.size >= 2, "expected both tags to become lifelines")
        assertIs<Seq3Range.Ids>(session.range)
    }

    @Test
    fun activateSwitchesTheActiveSessionAndSurfaceWithoutClosingTheOther() {
        val state = state()
        val first = state.seq3Sessions.begin("log", setOf(1))!!
        val second = state.seq3Sessions.begin("log", setOf(2))!!
        assertEquals(second, state.seq3Sessions.activeSessionId)

        assertTrue(state.seq3Sessions.activate(first))

        assertEquals(first, state.seq3Sessions.activeSessionId)
        assertEquals(ActiveSurface.Diagram3(first), state.activeSurface)
        assertEquals(2, state.seq3Sessions.sessions.size)
    }

    @Test
    fun activateOnAnUnknownIdReturnsFalseAndChangesNothing() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1))!!

        assertFalse(state.seq3Sessions.activate("does-not-exist"))

        assertEquals(id, state.seq3Sessions.activeSessionId)
    }

    @Test
    fun closeRemovesTheSessionAndFallsBackToTheLogSurface() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1))!!

        state.seq3Sessions.close(id)

        assertTrue(state.seq3Sessions.sessions.isEmpty())
        assertNull(state.seq3Sessions.activeSessionId)
        assertEquals(ActiveSurface.Log("log"), state.activeSurface)
    }

    @Test
    fun closingTheActiveSessionActivatesAnotherRemainingOne() {
        val state = state()
        val first = state.seq3Sessions.begin("log", setOf(1))!!
        val second = state.seq3Sessions.begin("log", setOf(2))!!

        state.seq3Sessions.close(second)

        assertEquals(listOf(first), state.seq3Sessions.sessions.map { it.id })
        assertEquals(first, state.seq3Sessions.activeSessionId)
        assertEquals(ActiveSurface.Diagram3(first), state.activeSurface)
    }

    // ── Source-tab-close independence (SAAD §11.6 / SeqDiagramCoordinator.sourceTabClosed) ───────

    @Test
    fun closingTheSourceTabKeepsTheSessionAndItsDocumentButClearsSourceTabId() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val documentBeforeClose = state.seq3Sessions.sessions.single().document

        state.closeTab("log")

        val session = state.seq3Sessions.sessions.single()
        assertEquals(0, state.tabs.size)
        assertNull(session.sourceTabId)
        assertEquals(documentBeforeClose, session.document, "closing the log must not discard the cached diagram")
    }

    @Test
    fun requestGenerateAfterSourceTabCloseIsANoOpUntilRelinked() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val documentBeforeClose = state.seq3Sessions.sessions.single().document
        state.closeTab("log")

        state.seq3Sessions.requestGenerate(id)
        Thread.sleep(300)

        assertEquals(documentBeforeClose, state.seq3Sessions.sessions.single().document)
        assertFalse(state.seq3Sessions.sessions.single().generating)
    }

    @Test
    fun relinkRestoresSourceTabIdAndGenerationWorksAgain() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        state.closeTab("log")
        state.tabs = listOf(mkTab("log", "sample.log", twoTagEntries()))

        assertTrue(state.seq3Sessions.relink(id, "log"))
        assertEquals("log", state.seq3Sessions.sessions.single().sourceTabId)

        state.seq3Sessions.requestGenerate(id)
        awaitGenerated(state, id)

        assertTrue(state.seq3Sessions.sessions.single().document.lifelines.isNotEmpty())
    }

    @Test
    fun relinkToAMissingTabFails() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1))!!

        assertFalse(state.seq3Sessions.relink(id, "does-not-exist"))
    }

    // ── Undo stack routing over applySeq3Command/undoSeq3Command ────────────────────────────────

    @Test
    fun applyCommandPushesUndoAndUndoRestoresThePriorDocument() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val before = state.seq3Sessions.sessions.single().document
        val lifelineIds = before.lifelines.map { it.id }
        assertTrue(lifelineIds.size >= 2, "need at least two lifelines to exercise a reorder")

        val applied = state.seq3Sessions.applyCommand(id, Seq3Command.ReorderLifelines(lifelineIds.reversed()))

        assertTrue(applied)
        assertTrue(state.seq3Sessions.canUndo(id))
        assertEquals(
            lifelineIds.reversed(),
            state.seq3Sessions.sessions.single().document.lifelines.sortedBy { it.ordinal }.map { it.id },
        )

        assertTrue(state.seq3Sessions.undo(id))

        assertEquals(before, state.seq3Sessions.sessions.single().document)
        assertFalse(state.seq3Sessions.canUndo(id))
    }

    @Test
    fun anUnappliedCommandPushesNoUndoEntryAndLeavesTheDocumentUntouched() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val before = state.seq3Sessions.sessions.single().document

        val applied = state.seq3Sessions.applyCommand(id, Seq3Command.ReorderLifelines(listOf("not-a-real-lifeline-id")))

        assertFalse(applied)
        assertFalse(state.seq3Sessions.canUndo(id))
        assertEquals(before, state.seq3Sessions.sessions.single().document)
    }

    @Test
    fun undoOnAFreshSessionIsANoOp() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1))!!

        assertFalse(state.seq3Sessions.undo(id))
    }

    @Test
    fun undoActiveOperatesOnlyOnTheCurrentlyActiveSession() {
        val state = state()
        val first = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, first)
        val second = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, second)
        val lifelineIds = state.seq3Sessions.sessions.first { it.id == first }.document.lifelines.map { it.id }
        // Mutate the FIRST session directly while the SECOND stays active.
        state.seq3Sessions.applyCommand(first, Seq3Command.ReorderLifelines(lifelineIds.reversed()))
        assertTrue(state.seq3Sessions.canUndo(first))
        assertEquals(second, state.seq3Sessions.activeSessionId)

        assertFalse(state.seq3Sessions.undoActive(), "the active (second) session has nothing of its own to undo")
        assertTrue(state.seq3Sessions.canUndo(first), "undoActive must never reach into a session that isn't active")

        state.seq3Sessions.activate(first)

        assertTrue(state.seq3Sessions.undoActive())
        assertFalse(state.seq3Sessions.canUndo(first))
    }

    // ── Dirty flag / draft-saved timestamp ──────────────────────────────────────────────────────

    @Test
    fun applyCommandMarksDirtyImmediatelyThenSettlesToDraftSavedAfterTheDebounce() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        assertNull(state.seq3Sessions.sessions.single().draftSavedAtMillis)
        val lifelineIds = state.seq3Sessions.sessions.single().document.lifelines.map { it.id }

        state.seq3Sessions.applyCommand(id, Seq3Command.ReorderLifelines(lifelineIds.reversed()))

        assertTrue(state.seq3Sessions.sessions.single().dirty, "must be dirty the instant the command applies")

        await { !state.seq3Sessions.sessions.single().dirty }

        assertNotNull(state.seq3Sessions.sessions.single().draftSavedAtMillis)
    }

    @Test
    fun draftSavedTimestampComesFromTheInjectedClockNotWallClock() {
        val state = state()
        var now = 5_000_000L
        val session = Seq3Session(state, clock = { now })
        val id = session.begin("log", setOf(1, 2))!!
        await { session.sessions.single().generating == false }
        val lifelineIds = session.sessions.single().document.lifelines.map { it.id }

        now = 5_400_000L
        session.applyCommand(id, Seq3Command.ReorderLifelines(lifelineIds.reversed()))
        await { !session.sessions.single().dirty }

        assertEquals(5_400_000L, session.sessions.single().draftSavedAtMillis)
    }

    @Test
    fun updateTitleMarksDirtyButNeverAdvancesTheGenerateRunCount() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val lifelinesBefore = state.seq3Sessions.sessions.single().document.lifelines
        val runsBefore = state.seq3Sessions.generateRunCount.get()

        state.seq3Sessions.updateTitle(id, "My v3 diagram")

        assertTrue(state.seq3Sessions.sessions.single().dirty, "a title edit is still a dirtying edit")
        // Well past the 180ms generate debounce, so a wrongly-triggered rebuild would have run by now.
        Thread.sleep(300)

        val after = state.seq3Sessions.sessions.single()
        assertEquals("My v3 diagram", after.document.title)
        assertEquals(lifelinesBefore, after.document.lifelines, "title must not touch the generated model")
        assertFalse(after.generating)
        assertEquals(runsBefore, state.seq3Sessions.generateRunCount.get(), "a metadata-only edit must never call generateSeq3")
    }

    // ── Confirm: writes the document into a note ────────────────────────────────────────────────

    @Test
    fun confirmWritesANoteAndASecondConfirmUpdatesTheSameBlock() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)

        val blockId = state.seq3Sessions.confirm(id)

        assertNotNull(blockId)
        assertEquals(1, state.tab("log")!!.annotations.blocks.count { it.id == blockId })

        val secondBlockId = state.seq3Sessions.confirm(id)

        assertEquals(blockId, secondBlockId)
        assertEquals(1, state.tab("log")!!.annotations.blocks.size, "a second confirm must update, not append, the note")
    }

    @Test
    fun confirmReturnsNullBeforeAnyGenerationHasCompleted() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        // Deliberately not awaited — begin()'s initial document has no lifelines until the debounced
        // generate lands, and confirm() must refuse an empty document rather than write an empty note.
        assertNull(state.seq3Sessions.confirm(id))
    }

    @Test
    fun confirmReturnsNullOnceTheSourceTabIsClosed() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        state.closeTab("log")

        assertNull(state.seq3Sessions.confirm(id))
    }

    // ── Status-bar support ───────────────────────────────────────────────────────────────────────

    @Test
    fun scannedEntryCountCountsOnlyEntriesInsideAnIdsRange() {
        val entries = (1..10).map { LogEntry(it, "10:00:00.0$it", LogLevel.I, "T", "m$it") }
        val state = stateFor(mkTab("log", "sample.log", entries))
        val id = state.seq3Sessions.begin("log", setOf(3, 4, 5, 6))!!

        assertEquals(4, state.seq3Sessions.scannedEntryCount(id))
    }

    @Test
    fun scannedEntryCountForVisibleViewIsTheWholeTab() {
        val state = state()
        val id = state.seq3Sessions.begin("log")!!

        assertEquals(2, state.seq3Sessions.scannedEntryCount(id))
    }
}
