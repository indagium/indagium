package com.indagium

import com.indagium.diagram3.Seq3AttachmentMode
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Range
import com.indagium.diagram3.parseSeq3Note
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.ui.ActiveSurface
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibrarySnapshot
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.DiagramSourceIdentity
import com.indagium.ui.Seq3Session
import com.indagium.ui.defaultSeq3Title
import com.indagium.ui.mkTab
import com.indagium.utils.computeLogFingerprint
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

    // ── Dirty flag / explicit note save (item 6a) ──────────────────────────────────────────────

    @Test
    fun applyCommandMarksDirtyImmediatelyAndOnlyExplicitNoteActionWrites() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        assertNull(state.seq3Sessions.sessions.single().draftSavedAtMillis)
        assertNull(state.seq3Sessions.sessions.single().confirmedBlockId, "nothing written yet — no edit has happened")
        val lifelineIds = state.seq3Sessions.sessions.single().document.lifelines.map { it.id }

        state.seq3Sessions.applyCommand(id, Seq3Command.ReorderLifelines(lifelineIds.reversed()))

        assertTrue(state.seq3Sessions.sessions.single().dirty, "must be dirty the instant the command applies")

        Thread.sleep(600)
        assertTrue(state.seq3Sessions.sessions.single().dirty, "editing must not silently save a note")
        assertNull(state.seq3Sessions.sessions.single().confirmedBlockId)
        assertTrue(state.tab("log")!!.annotations.blocks.isEmpty())

        val blockId = state.seq3Sessions.confirm(id)
        val settled = state.seq3Sessions.sessions.single()
        assertNotNull(settled.draftSavedAtMillis)
        assertEquals(blockId, settled.confirmedBlockId)
        assertEquals(1, state.tab("log")!!.annotations.blocks.count { it.id == blockId })
    }

    @Test
    fun generatingADiagramWithNoEditNeverAutoSaves() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)

        // Well past the 400ms auto-save debounce — nobody has touched the document, so nothing
        // must have been written. markDirty is called only from applyCommand/undo/updateTitle,
        // never from generation completing on its own.
        Thread.sleep(600)

        val session = state.seq3Sessions.sessions.single()
        assertFalse(session.dirty)
        assertNull(session.draftSavedAtMillis)
        assertNull(session.confirmedBlockId)
        assertTrue(state.tab("log")!!.annotations.blocks.isEmpty(), "opening/generating a diagram must never write a note by itself")
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
        assertTrue(session.sessions.single().dirty)
        assertNull(session.sessions.single().draftSavedAtMillis)
        session.confirm(id)
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

    @Test
    fun updateTitleRejectsABlankStringAndKeepsThePreviousTitle() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        state.seq3Sessions.updateTitle(id, "Kept title")
        state.seq3Sessions.updateTitle(id, "   ")

        assertEquals("Kept title", state.seq3Sessions.sessions.single().document.title)
        assertTrue(state.seq3Sessions.sessions.single().dirty, "a rejected title update must not clear an earlier unsaved edit")
    }

    @Test
    fun updateTitleTrimsSurroundingWhitespace() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)

        state.seq3Sessions.updateTitle(id, "  Padded title  ")

        assertEquals("Padded title", state.seq3Sessions.sessions.single().document.title)
    }

    // ── Unique default title (item 6b) ──────────────────────────────────────────────────────────

    @Test
    fun defaultSeq3TitleIsUntitledDiagramWhenNothingCollides() {
        assertEquals("Untitled diagram", defaultSeq3Title(emptySet()))
    }

    @Test
    fun defaultSeq3TitlePicksTheLowestFreeNumberWhenTheBaseIsTaken() {
        assertEquals("Untitled diagram 2", defaultSeq3Title(setOf("Untitled diagram")))
        assertEquals("Untitled diagram 3", defaultSeq3Title(setOf("Untitled diagram", "Untitled diagram 2")))
        // "3" is free even though "4" is taken — the lowest free N, not "one past the highest used".
        assertEquals("Untitled diagram 3", defaultSeq3Title(setOf("Untitled diagram", "Untitled diagram 2", "Untitled diagram 4")))
    }

    @Test
    fun defaultSeq3TitleIgnoresUnrelatedTitles() {
        assertEquals("Untitled diagram", defaultSeq3Title(setOf("Login flow", "Untitled diagram 2")))
    }

    @Test
    fun beginSeedsAUniqueDefaultTitleAgainstOnlyThisLogsLibraryEntries() {
        val root = createTempDirectory("indagium-seq3-default-title").toFile()
        val tab = mkTab("log", "sample.log", twoTagEntries())
        val libraryStore = DiagramLibraryStore(File(root, "library.cache"))
        val state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = libraryStore,
        ).also {
            it.tabs = listOf(tab)
            it.activateTab(tab.id)
        }
        val sameLogIdentity = DiagramSourceIdentity(tab.sourcePath ?: tab.filename, computeLogFingerprint(tab.logData))
        val otherLogIdentity = DiagramSourceIdentity("other.log", "different-fingerprint")
        val snapshot = DiagramLibrarySnapshot.create(Seq3Document())
        libraryStore.create("Untitled diagram", "", sameLogIdentity, snapshot)
        // A same-named diagram under a DIFFERENT log's identity must not affect this log's numbering.
        libraryStore.create("Untitled diagram", "", otherLogIdentity, snapshot)
        val session = Seq3Session(state, libraryStore = libraryStore)

        val id = session.begin("log", setOf(1, 2))!!

        assertEquals("Untitled diagram 2", session.sessions.single { it.id == id }.document.title)
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

    @Test
    fun snapshotAttachmentIsIndependentAfterWorkspaceEdits() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)

        val blockId = state.seq3Sessions.attachSnapshot(id)
        assertNotNull(blockId)
        val before = state.tab("log")!!.annotations.blocks.single { it.id == blockId }.let { block ->
            (block as com.indagium.model.AnnBlock.Note).text
        }
        assertEquals(Seq3AttachmentMode.SNAPSHOT, parseSeq3Note(before)?.attachment?.mode)

        state.seq3Sessions.updateTitle(id, "Changed after snapshot")

        val after = (state.tab("log")!!.annotations.blocks.single { it.id == blockId } as com.indagium.model.AnnBlock.Note).text
        assertEquals(before, after, "snapshot note must not follow later workspace edits")
    }

    @Test
    fun liveLinkAttachmentRefreshesTheNoteAndReusesItsOpenSession() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)

        val blockId = state.seq3Sessions.attachLiveLink(id)
        assertNotNull(blockId)
        val before = (state.tab("log")!!.annotations.blocks.single { it.id == blockId } as com.indagium.model.AnnBlock.Note).text
        assertEquals(Seq3AttachmentMode.LINKED, parseSeq3Note(before)?.attachment?.mode)

        state.seq3Sessions.updateTitle(id, "Live title")

        val after = (state.tab("log")!!.annotations.blocks.single { it.id == blockId } as com.indagium.model.AnnBlock.Note).text
        assertTrue(after != before)
        assertEquals("Live title", parseSeq3Note(after)?.document?.title)

        val reopened = state.seq3Sessions.beginEdit("log", blockId)
        assertEquals(id, reopened, "opening a live-linked note must activate the existing session")
        assertEquals(1, state.seq3Sessions.sessions.size)
    }

    @Test
    fun reopeningLiveLinkFromNotesKeepsTheNewSessionLive() {
        val state = state()
        val originalId = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, originalId)
        val blockId = state.seq3Sessions.attachLiveLink(originalId)
        assertNotNull(blockId)
        state.seq3Sessions.close(originalId)

        val reopenedId = state.seq3Sessions.beginEdit("log", blockId)
        assertNotNull(reopenedId)
        assertTrue(reopenedId != originalId)
        state.seq3Sessions.updateTitle(reopenedId, "Reopened live title")

        val note = state.tab("log")!!.annotations.blocks.single { it.id == blockId } as com.indagium.model.AnnBlock.Note
        assertEquals("Reopened live title", parseSeq3Note(note.text)?.document?.title)
    }

    @Test
    fun reopeningAnOlderSnapshotDoesNotReuseAChangedWorkspace() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val blockId = state.seq3Sessions.attachSnapshot(id)
        assertNotNull(blockId)
        state.seq3Sessions.updateTitle(id, "Newer workspace title")

        val reopenedId = state.seq3Sessions.beginEdit("log", blockId)
        assertNotNull(reopenedId)
        assertTrue(reopenedId != id)
        assertEquals("Untitled diagram", state.seq3Sessions.sessions.single { it.id == reopenedId }.document.title)
    }

    @Test
    fun openingAnAlreadyOpenLibraryDiagramReusesItsSession() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        assertNotNull(state.seq3Sessions.attachSnapshot(id))
        val libraryId = state.seq3Sessions.sessions.single().libraryItemId
        assertNotNull(libraryId)

        assertTrue(state.seq3Sessions.openLibraryItem(libraryId, "log"))
        assertEquals(1, state.seq3Sessions.sessions.size)
        assertEquals(id, state.seq3Sessions.activeSessionId)
    }

    @Test
    fun deletingAnAttachedNoteRemovesItsLibraryBackReference() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val blockId = state.seq3Sessions.attachLiveLink(id)
        assertNotNull(blockId)
        val libraryId = state.seq3Sessions.sessions.single().libraryItemId
        assertNotNull(libraryId)
        assertEquals(1, state.seq3Sessions.libraryForTab(state.tab("log")!!).single { it.id == libraryId }.attachments.size)

        state.removeBlock("log", blockId)

        assertTrue(state.seq3Sessions.libraryForTab(state.tab("log")!!).single { it.id == libraryId }.attachments.isEmpty())
    }

    // ── revertMessage: "revert to generated" on one edited row (item 15) ───────────────────────────

    @Test
    fun revertMessageReplacesTheEditedMessageWithItsFreshCounterpartAsOneUndoStep() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val original = state.seq3Sessions.sessions.single().document.messages.first()
        state.seq3Sessions.applyCommand(id, Seq3Command.Bulk(setOf(original.id), Seq3BulkAction.SetLabel("hand-edited label")))
        assertEquals(
            "hand-edited label",
            state.seq3Sessions.sessions.single().document.messages.single { it.id == original.id }.labelTemplate,
        )

        state.seq3Sessions.revertMessage(id, original.id)

        await { state.seq3Sessions.sessions.single().document.messages.single { it.id == original.id }.labelTemplate != "hand-edited label" }
        val reverted = state.seq3Sessions.sessions.single().document.messages.single { it.id == original.id }
        assertEquals(original.labelTemplate, reverted.labelTemplate)
        assertTrue(state.seq3Sessions.canUndo(id))

        assertTrue(state.seq3Sessions.undo(id))
        assertEquals(
            "hand-edited label",
            state.seq3Sessions.sessions.single().document.messages.single { it.id == original.id }.labelTemplate,
            "undo must restore exactly the pre-revert (hand-edited) message",
        )
    }

    @Test
    fun revertMessageIsASafeNoOpWhenTheSourceTabIsClosed() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val before = state.seq3Sessions.sessions.single().document
        val messageId = before.messages.first().id
        state.closeTab("log")

        state.seq3Sessions.revertMessage(id, messageId)
        Thread.sleep(300)

        assertEquals(before, state.seq3Sessions.sessions.single().document)
        assertFalse(state.seq3Sessions.canUndo(id))
    }

    @Test
    fun revertMessageOnAnUnknownMessageIdIsASafeNoOp() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val before = state.seq3Sessions.sessions.single().document

        state.seq3Sessions.revertMessage(id, "not-a-real-message-id")
        Thread.sleep(300)

        assertEquals(before, state.seq3Sessions.sessions.single().document)
        assertFalse(state.seq3Sessions.canUndo(id))
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
