package com.indagium

import com.indagium.diagram3.MAX_SEQ3_HEADER_CHARS
import com.indagium.diagram3.Seq3ArtifactStyle
import com.indagium.diagram3.Seq3AttachmentMode
import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3CustomMessageSpec
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3InsertionPosition
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Range
import com.indagium.diagram3.Seq3SourceImportResult
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.parseSeq3Note
import com.indagium.diagram3.toPlantUml
import com.indagium.diagram3.toMermaid
import com.indagium.diagram3.toSource
import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.ThemePreset
import com.indagium.ui.ActiveSurface
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibrarySnapshot
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.DiagramSourceIdentity
import com.indagium.ui.Seq3Session
import com.indagium.ui.Seq3ZoomMode
import com.indagium.ui.defaultSeq3Title
import com.indagium.ui.mkTab
import com.indagium.ui.seq3ViewToken
import com.indagium.utils.computeLogFingerprint
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
        const val MILLIS_PER_SECOND = 1_000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val MILLIS_PER_HOUR = 3_600_000L
        const val LONG_LINE_LENGTH = 600
    }

    private fun await(timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not become true within ${timeoutMs}ms")
    }

    private fun gatedOrder(ids: List<String>, snapshotted: CountDownLatch, release: CountDownLatch): List<String> =
        object : AbstractList<String>() {
            override val size: Int get() = ids.size
            private var held = false

            override fun get(index: Int): String {
                if (!held) {
                    held = true
                    snapshotted.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "test did not release the reorder gate" }
                }
                return ids[index]
            }
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

    private fun awaitGenerated(state: AppState, id: String, timeoutMs: Long = 4_000) =
        await(timeoutMs) { state.seq3Sessions.sessions.firstOrNull { it.id == id }?.generating == false }

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
    fun beginEmptySavesAReopenableBlankDraftWithoutGeneratingLogContent() {
        val root = createTempDirectory("indagium-seq3-empty").toFile()
        val libraryFile = File(root, "library.cache")
        val tab = mkTab("empty-log", "empty.log", emptyList())
        val state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(libraryFile),
        )
        state.tabs = listOf(tab)
        state.activateTab(tab.id)
        var restoredState: AppState? = null
        try {
            val id = state.seq3Sessions.beginEmpty(tab.id)!!
            val blank = state.seq3Sessions.sessions.single { it.id == id }

            assertEquals(ActiveSurface.Diagram3(id), state.activeSurface)
            assertFalse(blank.generating)
            assertEquals(0L, state.seq3Sessions.generateRunCount.get(), "empty creation must not run the log generator")
            assertNull(blank.document.sourceFile, "a blank draft does not claim a log-derived source")
            assertTrue(blank.document.lifelines.isEmpty())
            assertTrue(blank.document.messages.isEmpty())
            assertTrue(blank.document.fragments.isEmpty())
            assertTrue(blank.document.notes.isEmpty())
            assertTrue(blank.document.delays.isEmpty())
            assertTrue(blank.document.stateInvariants.isEmpty())
            assertTrue(blank.document.manualActivations.isEmpty())
            assertEquals(0, state.seq3Sessions.scannedEntryCount(id))
            assertNull(state.seq3Sessions.scannedTimeRange(id))
            val mermaid = blank.document.toSource(Seq3Dialect.MERMAID)
            val plantUml = blank.document.toSource(Seq3Dialect.PLANTUML)
            assertFalse(mermaid.contains("participant "))
            assertFalse(mermaid.contains("->"))
            assertFalse(plantUml.contains("participant "))
            assertFalse(plantUml.contains("->"))

            val initialItem = state.seq3Sessions.libraryForTab(tab).single()
            assertEquals(blank.document, initialItem.parsed?.document, "the blank draft is saved before the first edit")

            state.seq3Sessions.close(id)

            // A new store and AppState model a process restart: the initial empty snapshot must
            // already be on disk and reopenable before any lifeline has been added.
            val reopenedState = AppState(
                File(root, "restored-state.cache"),
                notesDir = File(root, "notes"),
                diagramLibraryStore = DiagramLibraryStore(libraryFile),
            ).also {
                it.tabs = listOf(tab)
                it.activateTab(tab.id)
            }
            restoredState = reopenedState
            assertTrue(reopenedState.seq3Sessions.openLibraryItem(initialItem.id, tab.id))
            val reopenedId = reopenedState.seq3Sessions.activeSessionId!!
            assertEquals(blank.document, reopenedState.seq3Sessions.activeSession!!.document)
            assertEquals(0L, reopenedState.seq3Sessions.generateRunCount.get())
            assertEquals(0, reopenedState.seq3Sessions.scannedEntryCount(reopenedId))

            reopenedState.seq3Sessions.updateTitle(reopenedId, "Blank draft")
            reopenedState.seq3Sessions.flush(reopenedId)
            assertEquals(
                "Blank draft",
                DiagramLibraryStore(libraryFile).get(initialItem.id)?.parsed?.document?.title,
                "metadata edits on a still-empty document autosave too",
            )

            val client = Seq3Lifeline("client-id", "Client", emptySet(), 0)
            val server = Seq3Lifeline("server-id", "Server", emptySet(), 1)
            assertTrue(reopenedState.seq3Sessions.applyCommand(reopenedId, Seq3Command.AddLifeline(client)))
            assertTrue(reopenedState.seq3Sessions.applyCommand(reopenedId, Seq3Command.AddLifeline(server)))
            assertTrue(
                reopenedState.seq3Sessions.applyCommand(
                    reopenedId,
                    Seq3Command.AddCustomMessage(
                        Seq3CustomMessageSpec(
                            fromLifelineId = client.id,
                            toLifelineId = server.id,
                            text = "request",
                            position = Seq3InsertionPosition.Start,
                        ),
                    ),
                ),
            )
            reopenedState.seq3Sessions.flush(reopenedId)

            val saved = DiagramLibraryStore(libraryFile).get(initialItem.id)!!.parsed!!.document
            assertEquals(reopenedState.seq3Sessions.activeSession!!.document, saved)
            assertEquals(listOf("Client", "Server"), saved.lifelines.map { it.name })
            assertEquals("request", saved.messages.single().labelTemplate)
            assertTrue(saved.messages.single().occurrences.isEmpty(), "manual messages must not fabricate log evidence")
            assertEquals(0L, reopenedState.seq3Sessions.generateRunCount.get())
            assertEquals(0, reopenedState.seq3Sessions.scannedEntryCount(reopenedId))
            assertNull(reopenedState.seq3Sessions.scannedTimeRange(reopenedId))
        } finally {
            state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
            restoredState?.let { restored ->
                restored.seq3Sessions.sessions.toList().forEach { restored.seq3Sessions.close(it.id) }
            }
            root.deleteRecursively()
        }
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

    // ── Per-diagram theme ───────────────────────────────────────────────────────────────────

    @Test
    fun beginLeavesANewDocumentsThemeNull() {
        val state = state()

        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!

        assertNull(state.seq3Sessions.sessions.single { it.id == id }.document.themePresetName, "null must mean follow the app theme")
    }

    @Test
    fun beginEditReopeningAnExistingDocumentKeepsItsOwnTheme() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        state.seq3Sessions.applyCommand(id, Seq3Command.SetDocumentTheme(ThemePreset.GRUVBOX.name))
        val blockId = state.seq3Sessions.attachSnapshot(id)!!
        state.seq3Sessions.close(id)

        val reopenedId = state.seq3Sessions.beginEdit("log", blockId)!!

        assertEquals(
            "GRUVBOX",
            state.seq3Sessions.sessions.single { it.id == reopenedId }.document.themePresetName,
            "reopening an existing document must keep the theme it was saved with",
        )
    }

    @Test
    fun aDocumentsThemeSurvivesAttachSaveAndReopenRoundTrip() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        state.seq3Sessions.applyCommand(id, Seq3Command.SetDocumentTheme(ThemePreset.TOKYO_NIGHT.name))
        val blockId = state.seq3Sessions.attachSnapshot(id)!!
        state.seq3Sessions.close(id)

        val reopenedId = state.seq3Sessions.beginEdit("log", blockId)!!

        assertEquals("TOKYO_NIGHT", state.seq3Sessions.sessions.single { it.id == reopenedId }.document.themePresetName)
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
    fun diagramViewKeepsFitWidthDefaultAndZoomAcrossSessionSwitches() {
        val state = state()
        val first = state.seq3Sessions.begin("log", setOf(1))!!
        val second = state.seq3Sessions.begin("log", setOf(2))!!
        val firstView = state.seq3Sessions.viewState(first)!!

        assertEquals(Seq3ZoomMode.FIT_WIDTH, firstView.zoomMode)
        firstView.zoom = 1.7f
        firstView.zoomMode = Seq3ZoomMode.MANUAL

        state.seq3Sessions.activate(second)
        state.seq3Sessions.activate(first)

        assertSame(firstView, state.seq3Sessions.viewState(first))
        assertEquals(1.7f, state.seq3Sessions.viewState(first)!!.zoom)
        assertEquals(Seq3ZoomMode.MANUAL, state.seq3Sessions.viewState(first)!!.zoomMode)
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

    @Test
    fun editWaitsForReorderSnapshotAndReorderingCannotReplaceItsDocumentOrUndoStack() {
        val root = createTempDirectory("seq3-session-rmw").toFile()
        val tab = mkTab("log", "sample.log", emptyList())
        val state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        )
        try {
            state.tabs = listOf(tab)
            state.activateTab(tab.id)
            val first = state.seq3Sessions.beginEmpty(tab.id)!!
            val second = state.seq3Sessions.beginEmpty(tab.id)!!
            val snapshotTaken = CountDownLatch(1)
            val releaseReorder = CountDownLatch(1)
            val editStarted = CountDownLatch(1)
            val editCompleted = CountDownLatch(1)
            val reorder = CompletableFuture.runAsync {
                state.seq3Sessions.reorderSessions(gatedOrder(listOf(second, first), snapshotTaken, releaseReorder))
            }
            lateinit var edit: CompletableFuture<Void>
            val editCompletedBeforeRelease = try {
                assertTrue(snapshotTaken.await(2, TimeUnit.SECONDS), "the reorder has already snapshotted both sessions")
                edit = CompletableFuture.runAsync {
                    editStarted.countDown()
                    check(state.seq3Sessions.applyCommand(first, Seq3Command.SetTitle("edited")))
                    editCompleted.countDown()
                }
                assertTrue(editStarted.await(2, TimeUnit.SECONDS))
                editCompleted.await(150, TimeUnit.MILLISECONDS)
            } finally {
                releaseReorder.countDown()
            }
            assertFalse(editCompletedBeforeRelease, "the edit waits for stateLock while reorder owns its snapshot")
            reorder.get(5, TimeUnit.SECONDS)
            edit.get(5, TimeUnit.SECONDS)

            assertEquals(setOf(first, second), state.seq3Sessions.sessions.map { it.id }.toSet())
            assertEquals("edited", state.seq3Sessions.sessions.single { it.id == first }.document.title)
            assertEquals(1, state.seq3Sessions.sessions.single { it.id == first }.undoStack.size, "reorder must not replace the edited undo stack")
        } finally {
            state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aConcurrentReorderCannotLoseAnOpenSessionOrResurrectAClosedOne() {
        val root = createTempDirectory("seq3-session-open-close").toFile()
        val tab = mkTab("log", "sample.log", emptyList())
        val state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        )
        try {
            state.tabs = listOf(tab)
            state.activateTab(tab.id)
            val original = state.seq3Sessions.beginEmpty(tab.id)!!
            val libraryId = state.seq3Sessions.sessions.single { it.id == original }.libraryItemId!!
            state.seq3Sessions.close(original)

            val openSnapshot = CountDownLatch(1)
            val releaseOpenReorder = CountDownLatch(1)
            val openStarted = CountDownLatch(1)
            val openCompleted = CountDownLatch(1)
            val openReorder = CompletableFuture.runAsync {
                state.seq3Sessions.reorderSessions(gatedOrder(listOf("not-open-yet"), openSnapshot, releaseOpenReorder))
            }
            lateinit var open: CompletableFuture<Boolean>
            val openCompletedBeforeRelease = try {
                assertTrue(openSnapshot.await(2, TimeUnit.SECONDS), "the stale reorder captured an empty session list")
                open = CompletableFuture.supplyAsync {
                    openStarted.countDown()
                    state.seq3Sessions.openLibraryItem(libraryId, tab.id).also { openCompleted.countDown() }
                }
                assertTrue(openStarted.await(2, TimeUnit.SECONDS))
                openCompleted.await(150, TimeUnit.MILLISECONDS)
            } finally {
                releaseOpenReorder.countDown()
            }
            assertFalse(openCompletedBeforeRelease, "open publication waits for the reorder's stateLock")
            openReorder.get(5, TimeUnit.SECONDS)
            assertTrue(open.get(5, TimeUnit.SECONDS))
            val reopened = state.seq3Sessions.sessions.single { it.libraryItemId == libraryId }

            val closeSnapshot = CountDownLatch(1)
            val releaseCloseReorder = CountDownLatch(1)
            val closeStarted = CountDownLatch(1)
            val closeCompleted = CountDownLatch(1)
            val closeReorder = CompletableFuture.runAsync {
                state.seq3Sessions.reorderSessions(gatedOrder(listOf(reopened.id), closeSnapshot, releaseCloseReorder))
            }
            lateinit var close: CompletableFuture<Void>
            val closeCompletedBeforeRelease = try {
                assertTrue(closeSnapshot.await(2, TimeUnit.SECONDS), "the reorder captured the open session")
                close = CompletableFuture.runAsync {
                    closeStarted.countDown()
                    state.seq3Sessions.close(reopened.id)
                    closeCompleted.countDown()
                }
                assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
                closeCompleted.await(150, TimeUnit.MILLISECONDS)
            } finally {
                releaseCloseReorder.countDown()
            }
            assertFalse(closeCompletedBeforeRelease, "close waits for the reorder's stateLock")
            closeReorder.get(5, TimeUnit.SECONDS)
            close.get(5, TimeUnit.SECONDS)

            assertTrue(
                state.seq3Sessions.sessions.none { it.libraryItemId == libraryId },
                "a close committed after the reorder cannot be overwritten by its stale session snapshot",
            )
        } finally {
            state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun autosaveSerializesALibraryBackedDiagramWithoutWaitingForStateLock() {
        val root = createTempDirectory("seq3-autosave-lock-order").toFile()
        val autosave = File(root, "state.cache")
        val tab = mkTab("log", "sample.log", emptyList())
        val state = AppState(
            autosave,
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        )
        val releaseLock = CountDownLatch(1)
        val lockHeld = CountDownLatch(1)
        val lockHolder = Thread {
            synchronized(state.stateLock) {
                lockHeld.countDown()
                releaseLock.await(5, TimeUnit.SECONDS)
            }
        }
        try {
            state.tabs = listOf(tab)
            state.activateTab(tab.id)
            state.seq3Sessions.beginEmpty(tab.id)
            assertTrue(state.seq3ViewToken().isNotBlank(), "the persisted library-backed session participates in seq3ViewToken")

            lockHolder.start()
            assertTrue(lockHeld.await(2, TimeUnit.SECONDS), "the competing thread holds stateLock")
            val save = CompletableFuture.runAsync(state::autosaveNow)
            val completedWhileLocked = try {
                save.get(2, TimeUnit.SECONDS)
                true
            } catch (_: TimeoutException) {
                false
            } finally {
                releaseLock.countDown()
            }
            save.get(5, TimeUnit.SECONDS)
            lockHolder.join(5_000)

            assertTrue(completedWhileLocked, "autosave's writer lock must not wait on stateLock during immutable session lookup")
            assertTrue(autosave.readText().contains("seq3View\t"))
        } finally {
            releaseLock.countDown()
            if (lockHolder.isAlive) lockHolder.join(5_000)
            state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
            state.close()
            root.deleteRecursively()
        }
    }

    // ── From notes (Diagram library's "+ diagram" → "From notes") ──────────────────────────────

    private fun taggedEntries(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Alpha", "alpha does a"),
        LogEntry(2, "10:00:00.100", LogLevel.I, "Beta", "beta does b"),
    )

    @Test
    fun beginFromNotesOnATabWithNoUsableNotedLinesOpensNothing() {
        // state()'s tab is built via mkTab, whose Annotations start with no blocks at all — nothing
        // for seq3NotesSelection to find.
        val state = state()

        val id = state.seq3Sessions.beginFromNotes("log")

        assertNull(id)
        assertTrue(state.seq3Sessions.sessions.isEmpty())
    }

    @Test
    fun beginFromNotesRangesOverExactlyTheNotedLinesAndAnchorsOneNotePerCuratedBlock() {
        val tab = mkTab("notes-log", "sample.log", taggedEntries()).copy(
            annotations = Annotations(
                blocks = listOf(
                    AnnBlock.Note(id = "n1", text = "investigate alpha"),
                    AnnBlock.LogRef(id = "b1", logIds = listOf(1), caption = "alpha ref"),
                    // A blank-captioned block with no preceding note still contributes its line to
                    // the range, but must not add a second Seq3Note.
                    AnnBlock.LogRef(id = "b2", logIds = listOf(2), caption = ""),
                ),
            ),
        )
        val state = stateFor(tab)

        val id = state.seq3Sessions.beginFromNotes(tab.id)!!

        assertEquals(
            Seq3Range.Ids(1, 2, setOf(1, 2)),
            state.seq3Sessions.sessions.single { it.id == id }.range,
            "range must be exactly the noted lines, not the span between them",
        )

        awaitGenerated(state, id)
        val document = state.seq3Sessions.sessions.single { it.id == id }.document

        val note = document.notes.single()
        assertEquals("investigate alpha\n\nalpha ref", note.text)
        val anchoredMessage = document.messages.single { it.id == note.messageIds.single() }
        assertTrue(anchoredMessage.occurrences.any { it.entryId == 1 }, "note must anchor to the message carrying b1's first (and only) log line")
    }

    @Test
    fun editingASeededNoteThenRegeneratingKeepsTheEditAndDoesNotDuplicateIt() {
        // Regression coverage for the bug where `publishGenerated` handed `applySeq3NoteSeeds` the
        // freshly generated document (which never carries notes of its own — see that function's
        // own doc) instead of carrying the session's already-seeded notes forward: the idempotent
        // "user edits win" branch could never engage on the real Seq3Session call path, only in the
        // pure-function unit tests. This drives the same scenario through Seq3Session itself.
        val tab = mkTab("notes-log", "sample.log", taggedEntries()).copy(
            annotations = Annotations(
                blocks = listOf(
                    AnnBlock.Note(id = "n1", text = "investigate alpha"),
                    AnnBlock.LogRef(id = "b1", logIds = listOf(1), caption = "alpha ref"),
                ),
            ),
        )
        val state = stateFor(tab)
        val id = state.seq3Sessions.beginFromNotes(tab.id)!!
        awaitGenerated(state, id)
        val seededNoteId = state.seq3Sessions.sessions.single { it.id == id }.document.notes.single().id

        assertTrue(
            state.seq3Sessions.applyCommand(id, Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetNoteText(seededNoteId, "the user's own rewrite"))),
        )

        state.seq3Sessions.requestGenerate(id)
        awaitGenerated(state, id)

        val document = state.seq3Sessions.sessions.single { it.id == id }.document
        assertEquals(1, document.notes.size, "a regenerate must not duplicate the seeded note")
        assertEquals(seededNoteId, document.notes.single().id)
        assertEquals("the user's own rewrite", document.notes.single().text, "a canvas edit must survive a regenerate")
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

    // ── W3: markDirty's own library write is debounced, not synchronous ────────────────────────

    @Test
    fun rapidEditsCoalesceIntoOneDraftSaveInsteadOfOnePerEdit() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val runsBeforeEdits = state.seq3Sessions.draftSaveRunCount.get()
        val lifelineIds = state.seq3Sessions.sessions.single().document.lifelines.map { it.id }
        assertTrue(lifelineIds.size >= 2, "need at least two lifelines to exercise a real edit")

        // A burst of edits inside the debounce window — dragging a canvas endpoint fires
        // applyCommand (and therefore markDirty) many times in quick succession.
        repeat(5) {
            state.seq3Sessions.applyCommand(
                id,
                Seq3Command.ReorderLifelines(if (it % 2 == 0) lifelineIds.reversed() else lifelineIds),
            )
        }
        // Immediately after the burst nothing has run yet — this is the actual measurement that
        // the save moved off the edit itself, not an assertion of intent: before W3 this would
        // already be non-zero here, since autoSaveDraftToLibrary ran synchronously inside markDirty.
        assertEquals(runsBeforeEdits, state.seq3Sessions.draftSaveRunCount.get(), "the draft save must not run synchronously with the edit")

        await(timeoutMs = 4_000) { state.seq3Sessions.draftSaveRunCount.get() > runsBeforeEdits }
        // Give a stray second dispatch (there should not be one) time to also land before asserting
        // the final count — a fixed sleep here, not a race, since we already know at least one run
        // happened and are only checking that a SECOND one doesn't sneak in afterward.
        Thread.sleep(200)

        assertEquals(
            runsBeforeEdits + 1,
            state.seq3Sessions.draftSaveRunCount.get(),
            "a burst of edits inside the debounce window must coalesce into exactly ONE draft save",
        )
        val session = state.seq3Sessions.sessions.single()
        assertEquals(
            lifelineIds.reversed(),
            session.document.lifelines.sortedBy { it.ordinal }.map { it.id },
            "the coalesced save must reflect the LATEST edit",
        )
    }

    @Test
    fun closingASessionFlushesAPendingDraftSaveInsteadOfDroppingIt() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val lifelineIds = state.seq3Sessions.sessions.single().document.lifelines.map { it.id }
        state.seq3Sessions.applyCommand(id, Seq3Command.ReorderLifelines(lifelineIds.reversed()))
        val libraryItemId = requireNotNull(state.seq3Sessions.sessions.single().libraryItemId)

        // Well inside the debounce window — nothing has been written to the library for THIS edit
        // yet (the item on disk still reflects generation's own original document).
        state.seq3Sessions.close(id)

        val stored = requireNotNull(state.seq3Sessions.libraryForTab(state.tab("log")!!).single { it.id == libraryItemId })
        assertEquals(
            lifelineIds.reversed(),
            stored.parsed?.document?.lifelines?.sortedBy { it.ordinal }?.map { it.id },
            "close() must flush a pending draft save rather than silently dropping the last edit",
        )
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
        assertEquals(1, after.undoStack.size, "a title update is one ordinary diagram command")
    }

    @Test
    fun titleNoOpDoesNotAddUndoAndTitleEditUndoesAsOneCommand() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val initial = state.seq3Sessions.sessions.single().document.title
        val before = state.seq3Sessions.sessions.single().undoStack.size

        state.seq3Sessions.updateTitle(id, "  Renamed  ")
        assertEquals("Renamed", state.seq3Sessions.sessions.single().document.title)
        assertEquals(before + 1, state.seq3Sessions.sessions.single().undoStack.size)
        state.seq3Sessions.updateTitle(id, "Renamed")
        state.seq3Sessions.updateTitle(id, "   ")
        assertEquals(before + 1, state.seq3Sessions.sessions.single().undoStack.size, "identical and blank commits are no-ops")

        assertTrue(state.seq3Sessions.undo(id))
        assertEquals(initial, state.seq3Sessions.sessions.single().document.title)
    }

    @Test
    fun sourceDraftIsInvalidatedBySourceChangingVisualEditsAndExplicitReloadRestoresIt() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        state.seq3Sessions.setDialect(id, Seq3Dialect.PLANTUML)
        awaitGenerated(state, id)
        val view = state.seq3Sessions.viewState(id)!!
        assertTrue(state.seq3Sessions.prepareSourceDraft(id))
        val baseSource = view.sourceDraft
        val titlePattern = Regex("(?m)^title .+$")
        val draftSource = if (titlePattern.containsMatchIn(baseSource)) {
            baseSource.replaceFirst(titlePattern, "title Source draft")
        } else {
            baseSource.replace("@startuml", "@startuml\ntitle Source draft")
        }
        view.sourceDraft = draftSource
        view.sourceDraftDirty = true

        assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.SetTitle("Visual change")))
        assertTrue(view.sourceDraftStale)
        assertEquals(draftSource, view.sourceDraft)
        assertIs<Seq3SourceImportResult.Failure>(
            state.seq3Sessions.importSource(id, view.sourceDraft, Seq3Dialect.PLANTUML),
        )
        assertTrue(state.seq3Sessions.undo(id), "diagram undo remains available while source is docked")
        assertFalse(view.sourceDraftStale, "undo restoring the source base makes the preserved draft safe to apply again")
        assertEquals(draftSource, view.sourceDraft)
        assertIs<Seq3SourceImportResult.Success>(
            state.seq3Sessions.importSource(id, view.sourceDraft, Seq3Dialect.PLANTUML),
        )
        assertEquals("Source draft", state.seq3Sessions.sessions.single { it.id == id }.document.title)
        assertFalse(view.sourceDraftDirty)
        assertFalse(view.sourceDraftStale)

        assertTrue(state.seq3Sessions.reloadSourceDraft(id))
        assertEquals(state.seq3Sessions.sessions.single { it.id == id }.document.toPlantUml(), view.sourceDraft)
        assertFalse(view.sourceDraftDirty)
        assertFalse(view.sourceDraftStale)
        assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.SetTitle("After reload")))
        assertEquals(state.seq3Sessions.sessions.single().document.toPlantUml(), view.sourceDraft)
    }

    @Test
    fun sourceDialectSwitchRebuildsTheDraftAndStalenessUsesTheSelectedLanguage() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        state.seq3Sessions.setDialect(id, Seq3Dialect.PLANTUML)
        awaitGenerated(state, id)
        val view = state.seq3Sessions.viewState(id)!!
        assertTrue(state.seq3Sessions.prepareSourceDraft(id))
        assertEquals(Seq3Dialect.PLANTUML, view.sourceEditorDialect)

        view.sourceDraft += "\n%% mermaid draft"
        state.seq3Sessions.updateSourceDraftText(id, view.sourceDraft)
        assertTrue(view.sourceDraftDirty)
        assertTrue(state.seq3Sessions.switchSourceDraftDialect(id, Seq3Dialect.MERMAID))
        assertEquals(Seq3Dialect.MERMAID, view.sourceEditorDialect)
        assertTrue(view.sourceDraft.startsWith("sequenceDiagram"))
        assertEquals(state.seq3Sessions.sessions.single { it.id == id }.document.toMermaid(), view.sourceDraft)
        assertFalse(view.sourceDraftDirty)

        view.sourceDraft += "\n%% edit"
        state.seq3Sessions.updateSourceDraftText(id, view.sourceDraft)
        assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.SetTitle("Changed visually")))
        assertTrue(view.sourceDraftStale, "the Mermaid base becomes stale after a source-changing visual edit")
        assertIs<Seq3SourceImportResult.Failure>(
            state.seq3Sessions.importSource(id, view.sourceDraft, Seq3Dialect.MERMAID),
        )
    }

    @Test
    fun liveSourceDraftFollowsValidEditsInBothDialectsAndKeepsAuthoredRowIdentity() {
        Seq3Dialect.entries.forEach { dialect ->
            val state = state()
            val id = state.seq3Sessions.beginEmpty("log")!!
            val view = state.seq3Sessions.viewState(id)!!
            assertTrue(state.seq3Sessions.prepareSourceDraft(id))
            assertTrue(state.seq3Sessions.switchSourceDraftDialect(id, dialect))
            assertTrue(view.sourceLiveEnabled)
            assertTrue(view.sourceAutocompleteEnabled)
            val editorGeneration = view.sourceEditorGeneration
            val source = when (dialect) {
                Seq3Dialect.MERMAID -> """
                    sequenceDiagram
                    participant A as Alpha
                    participant B as Beta
                    A->>B: first
                """.trimIndent()
                Seq3Dialect.PLANTUML -> """
                    @startuml
                    participant "Alpha" as A
                    participant "Beta" as B
                    A -> B: first
                    @enduml
                """.trimIndent()
            }
            assertTrue(state.seq3Sessions.applyLiveSourceDraft(id, source, dialect) is Seq3SourceImportResult.Success)
            assertEquals(source, view.sourceLiveLastAppliedText)
            val firstDocument = state.seq3Sessions.sessions.single { it.id == id }.document
            assertEquals(listOf("Alpha", "Beta"), firstDocument.lifelines.map { it.name })
            assertEquals(1, firstDocument.messages.size)
            val authoredId = firstDocument.messages.single().id

            val invalid = source.replace("first", "unfinished") + "\nnot valid source"
            state.seq3Sessions.updateSourceDraftText(id, invalid)
            assertIs<Seq3SourceImportResult.Failure>(state.seq3Sessions.applyLiveSourceDraft(id, invalid, dialect))
            assertEquals(firstDocument, state.seq3Sessions.sessions.single { it.id == id }.document)
            assertTrue(view.sourceDiagnostics.isNotEmpty())
            assertEquals(source, view.sourceLiveLastAppliedText, "invalid partial source is not marked as applied")

            val edited = source.replace("first", "second")
            state.seq3Sessions.updateSourceDraftText(id, edited)
            assertIs<Seq3SourceImportResult.Success>(state.seq3Sessions.applyLiveSourceDraft(id, edited, dialect))
            val editedDocument = state.seq3Sessions.sessions.single { it.id == id }.document
            assertEquals(2, editedDocument.lifelines.size, "participant declarations stay unique across source revisions")
            assertEquals(1, editedDocument.messages.size, "repeated imports replace the authored row instead of appending")
            assertEquals(authoredId, editedDocument.messages.single().id, "editing a row's label retains its identity")
            assertEquals("second", editedDocument.messages.single().labelTemplate)
            assertEquals(editorGeneration, view.sourceEditorGeneration, "live updates keep the editor state and cursor owner intact")
            assertEquals(edited, view.sourceDraft, "the editor keeps the exact text typed by the user")
            assertEquals(edited, view.sourceLiveLastAppliedText)

            // This mirrors undoing a source edit in the editor: the reverted text is imported
            // against the same stable baseline and therefore restores the previous diagram shape.
            state.seq3Sessions.updateSourceDraftText(id, source)
            assertIs<Seq3SourceImportResult.Success>(state.seq3Sessions.applyLiveSourceDraft(id, source, dialect))
            val reverted = state.seq3Sessions.sessions.single { it.id == id }.document
            assertEquals(1, reverted.messages.size)
            assertEquals(authoredId, reverted.messages.single().id)
            assertEquals("first", reverted.messages.single().labelTemplate)
            assertEquals(source, view.sourceLiveLastAppliedText)
        }
    }

    @Test
    fun liveSourceParticipantEditsDoNotAccumulateAndKeepIdentity() {
        Seq3Dialect.entries.forEach { dialect ->
            val state = state()
            val id = state.seq3Sessions.beginEmpty("log")!!
            assertTrue(state.seq3Sessions.prepareSourceDraft(id))
            assertTrue(state.seq3Sessions.switchSourceDraftDialect(id, dialect))
            val view = state.seq3Sessions.viewState(id)!!
            val generation = view.sourceEditorGeneration
            fun source(alias: String, name: String = "qweqwe", second: Boolean = false, reverse: Boolean = false): String {
                val first = if (dialect == Seq3Dialect.MERMAID) "participant \"$alias\" as \"$name\""
                    else "participant \"$name\" as $alias"
                val other = if (dialect == Seq3Dialect.MERMAID) "participant Other as Other"
                    else "participant \"Other\" as Other"
                val declarations = if (!second) listOf(first) else if (reverse) listOf(other, first) else listOf(first, other)
                return (listOf(if (dialect == Seq3Dialect.MERMAID) "sequenceDiagram" else "@startuml") + declarations +
                    (if (dialect == Seq3Dialect.PLANTUML) listOf("@enduml") else emptyList())).joinToString("\n")
            }
            fun apply(text: String) {
                assertIs<Seq3SourceImportResult.Success>(state.seq3Sessions.applyLiveSourceDraft(id, text, dialect), "$dialect: $text")
                assertEquals(text, view.sourceDraft)
                assertEquals(generation, view.sourceEditorGeneration)
            }
            var originalId = ""
            listOf("q", "qw", "qwe", "qweq", "qweqw", "qweqwe").forEachIndexed { index, alias ->
                apply(source(alias))
                val lifeline = state.seq3Sessions.sessions.single { it.id == id }.document.lifelines.single()
                if (index == 0) originalId = lifeline.id else assertEquals(originalId, lifeline.id)
            }
            apply(source("qweqwe", "renamed"))
            assertEquals(originalId, state.seq3Sessions.sessions.single { it.id == id }.document.lifelines.single().id)
            apply(source("qweqwe", "renamed", second = true))
            val beforeMove = state.seq3Sessions.sessions.single { it.id == id }.document.lifelines.associate { it.name to it.id }
            apply(source("qweqwe", "renamed", second = true, reverse = true))
            val moved = state.seq3Sessions.sessions.single { it.id == id }.document.lifelines
            assertEquals(beforeMove, moved.associate { it.name to it.id })
            assertEquals(listOf("Other", if (dialect == Seq3Dialect.MERMAID) "\"renamed\"" else "renamed"), moved.map { it.name })
            apply(source("qweqwe", "renamed"))
            assertEquals(originalId, state.seq3Sessions.sessions.single { it.id == id }.document.lifelines.single().id)
            apply(source("qweqwe", "renamed", second = true, reverse = true))
            assertEquals(2, state.seq3Sessions.sessions.single { it.id == id }.document.lifelines.size)
        }
    }

    @Test
    fun liveSourceAliasRenameKeepsMessageEndpointsAndRemovalIsAuthoritative() {
        Seq3Dialect.entries.forEach { dialect ->
            val state = state()
            val id = state.seq3Sessions.beginEmpty("log")!!
            assertTrue(state.seq3Sessions.prepareSourceDraft(id))
            assertTrue(state.seq3Sessions.switchSourceDraftDialect(id, dialect))
            fun source(alias: String): String = when (dialect) {
                Seq3Dialect.MERMAID -> "sequenceDiagram\nparticipant $alias as Alpha\nparticipant B as Beta\n$alias->>B: hello"
                Seq3Dialect.PLANTUML -> "@startuml\nparticipant \"Alpha\" as $alias\nparticipant \"Beta\" as B\n$alias -> B: hello\n@enduml"
            }
            fun apply(text: String) = assertIs<Seq3SourceImportResult.Success>(state.seq3Sessions.applyLiveSourceDraft(id, text, dialect))
            apply(source("A"))
            val before = state.seq3Sessions.sessions.single { it.id == id }.document
            val alpha = before.lifelines.single { it.name == "Alpha" }.id
            val message = before.messages.single().id
            apply(source("Alias"))
            val renamed = state.seq3Sessions.sessions.single { it.id == id }.document
            assertEquals(alpha, renamed.lifelines.single { it.name == "Alpha" }.id)
            assertEquals(alpha, renamed.messages.single().fromLifelineId)
            assertEquals(alpha, renamed.messages.single().match.tag)
            assertEquals(message, renamed.messages.single().id)
            val onlyBeta = when (dialect) {
                Seq3Dialect.MERMAID -> "sequenceDiagram\nparticipant B as Beta"
                Seq3Dialect.PLANTUML -> "@startuml\nparticipant \"Beta\" as B\n@enduml"
            }
            apply(onlyBeta)
            val deleted = state.seq3Sessions.sessions.single { it.id == id }.document
            assertEquals(listOf("Beta"), deleted.lifelines.map { it.name })
            assertTrue(deleted.messages.isEmpty())
            apply(source("Alias")) // editor undo restores the source structure
            assertEquals(2, state.seq3Sessions.sessions.single { it.id == id }.document.lifelines.size)
        }
    }

    @Test
    fun liveSourceRejectsAStaleDraftAfterAnExternalVisualEdit() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val view = state.seq3Sessions.viewState(id)!!
        assertTrue(state.seq3Sessions.prepareSourceDraft(id))
        val draft = view.sourceDraft + "\n' pending source change"
        state.seq3Sessions.updateSourceDraftText(id, draft)
        assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.SetTitle("External visual edit")))
        val externallyEdited = state.seq3Sessions.sessions.single { it.id == id }.document

        val result = state.seq3Sessions.applyLiveSourceDraft(id, draft, Seq3Dialect.PLANTUML)

        assertIs<Seq3SourceImportResult.Failure>(result)
        assertTrue(view.sourceDraftStale)
        assertEquals(externallyEdited, state.seq3Sessions.sessions.single { it.id == id }.document)
    }

    @Test
    fun emptyLiveSourceClearsAuthoredStructureAndEvidenceLossWaitsForConfirmation() {
        val manualState = state()
        val manualId = manualState.seq3Sessions.beginEmpty("log")!!
        manualState.seq3Sessions.setDialect(manualId, Seq3Dialect.PLANTUML)
        val manualView = manualState.seq3Sessions.viewState(manualId)!!
        manualState.seq3Sessions.prepareSourceDraft(manualId)
        val authored = """
            @startuml
            title Manual diagram
            participant "Alpha" as A
            participant "Beta" as B
            A -> B: note
            @enduml
        """.trimIndent()
        manualState.seq3Sessions.updateSourceDraftText(manualId, authored)
        assertIs<Seq3SourceImportResult.Success>(
            manualState.seq3Sessions.applyLiveSourceDraft(manualId, authored, Seq3Dialect.PLANTUML),
        )
        val title = manualState.seq3Sessions.sessions.single { it.id == manualId }.document.title
        manualState.seq3Sessions.updateSourceDraftText(manualId, "  \n\t")
        val cleared = assertIs<Seq3SourceImportResult.Success>(
            manualState.seq3Sessions.applyLiveSourceDraft(manualId, "  \n\t", Seq3Dialect.PLANTUML),
        )
        assertFalse(cleared.evidenceLoss)
        assertTrue(cleared.document.lifelines.isEmpty())
        assertTrue(cleared.document.messages.isEmpty())
        assertEquals(title, cleared.document.title)
        assertEquals("  \n\t", manualView.sourceDraft)

        val newDiagramWrapper = "@startuml\n@enduml"
        manualState.seq3Sessions.updateSourceDraftText(manualId, newDiagramWrapper)
        val untitled = assertIs<Seq3SourceImportResult.Success>(
            manualState.seq3Sessions.applyLiveSourceDraft(manualId, newDiagramWrapper, Seq3Dialect.PLANTUML),
        )
        assertEquals("", untitled.document.title, "a wrapper without a title clears old title metadata in one import")

        val generatedState = state()
        val generatedId = generatedState.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(generatedState, generatedId)
        val generatedView = generatedState.seq3Sessions.viewState(generatedId)!!
        generatedState.seq3Sessions.setDialect(generatedId, Seq3Dialect.PLANTUML)
        generatedState.seq3Sessions.prepareSourceDraft(generatedId)
        val withEvidence = generatedState.seq3Sessions.sessions.single { it.id == generatedId }.document
        generatedState.seq3Sessions.updateSourceDraftText(generatedId, "")
        val pending = assertIs<Seq3SourceImportResult.Success>(
            generatedState.seq3Sessions.applyLiveSourceDraft(generatedId, "", Seq3Dialect.PLANTUML),
        )
        assertTrue(pending.evidenceLoss)
        assertTrue(generatedView.sourceEvidenceLossPending)
        assertEquals(withEvidence, generatedState.seq3Sessions.sessions.single { it.id == generatedId }.document)

        val confirmed = assertIs<Seq3SourceImportResult.Success>(
            generatedState.seq3Sessions.importSource(generatedId, "", Seq3Dialect.PLANTUML, confirmEvidenceLoss = true),
        )
        assertTrue(confirmed.document.messages.isEmpty())
        assertTrue(confirmed.document.lifelines.isEmpty())
        assertEquals(1, generatedState.seq3Sessions.sessions.single { it.id == generatedId }.undoStack.size)
    }

    @Test
    fun newSourceDiagramReplacesAllOldStructureAndWaitsForEvidenceConfirmation() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val view = state.seq3Sessions.viewState(id)!!
        state.seq3Sessions.prepareSourceDraft(id)
        val oldDocument = state.seq3Sessions.sessions.single { it.id == id }.document
        assertTrue(oldDocument.messages.any { it.occurrences.isNotEmpty() })
        val source = """
            @startuml
            title Fresh diagram
            actor "New actor" as NewActor
            @enduml
        """.trimIndent()

        val pending = assertIs<Seq3SourceImportResult.Success>(
            state.seq3Sessions.startNewSourceDiagram(id, source, Seq3Dialect.PLANTUML),
        )
        assertTrue(pending.evidenceLoss)
        assertTrue(view.sourceEvidenceLossPending)
        assertEquals(oldDocument, state.seq3Sessions.sessions.single { it.id == id }.document)
        assertTrue(state.seq3Sessions.sessions.single { it.id == id }.undoStack.isEmpty())

        val confirmed = assertIs<Seq3SourceImportResult.Success>(
            state.seq3Sessions.startNewSourceDiagram(id, source, Seq3Dialect.PLANTUML, confirmEvidenceLoss = true),
        )
        val replacement = state.seq3Sessions.sessions.single { it.id == id }
        assertTrue(confirmed.evidenceLoss)
        assertEquals("Fresh diagram", replacement.document.title)
        assertNull(replacement.document.sourceFile)
        assertEquals(listOf("New actor"), replacement.document.lifelines.map { it.name })
        assertTrue(replacement.document.messages.isEmpty(), "omitted old messages do not survive a new-source import")
        assertEquals(1, replacement.undoStack.size)
        assertFalse(view.sourceEvidenceLossPending)
        assertEquals(Seq3Dialect.PLANTUML, view.sourceEditorDialect)
        assertEquals(replacement.document.toPlantUml(), view.sourceDraft)
        assertEquals(view.sourceDraft, view.sourceLiveLastAppliedText)

        assertTrue(state.seq3Sessions.undo(id))
        assertEquals(oldDocument, state.seq3Sessions.sessions.single { it.id == id }.document)
    }

    @Test
    fun presentationOnlyVisualChangesDoNotStalePlantUmlDraft() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        state.seq3Sessions.setDialect(id, Seq3Dialect.PLANTUML)
        awaitGenerated(state, id)
        val view = state.seq3Sessions.viewState(id)!!
        assertTrue(state.seq3Sessions.prepareSourceDraft(id))
        view.sourceDraft += "\n' edit"
        view.sourceDraftDirty = true

        val lifelineId = state.seq3Sessions.sessions.single { it.id == id }.document.lifelines.first().id
        assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.SetLifelineHeaderWidth(lifelineId, 240.0)))
        assertFalse(view.sourceDraftStale, "presentation changes excluded from emitted source do not invalidate a draft")
        assertTrue(view.sourceDraftDirty)

        assertIs<Seq3SourceImportResult.Success>(
            state.seq3Sessions.applyLiveSourceDraft(id, view.sourceDraft, Seq3Dialect.PLANTUML),
        )
        assertEquals(
            240.0,
            state.seq3Sessions.sessions.single { it.id == id }.document.lifelines.first { it.id == lifelineId }.headerWidthDp,
            "accepting the dirty source revision should retain presentation edits on baseline identities",
        )
    }

    @Test
    fun liveSourceRevisionsRetainPresentationForNewAuthoredIdentitiesAndCanvasArtifacts() {
        val state = state()
        val id = state.seq3Sessions.beginEmpty("log")!!
        try {
            val view = state.seq3Sessions.viewState(id)!!
            assertTrue(state.seq3Sessions.prepareSourceDraft(id))
            assertTrue(state.seq3Sessions.switchSourceDraftDialect(id, Seq3Dialect.PLANTUML))
            val firstSource = """
                @startuml
                participant "Alpha" as A
                participant "Beta" as B
                A -> B: first
                @enduml
            """.trimIndent()
            assertIs<Seq3SourceImportResult.Success>(
                state.seq3Sessions.applyLiveSourceDraft(id, firstSource, Seq3Dialect.PLANTUML),
            )

            val authored = state.seq3Sessions.sessions.single { it.id == id }.document
            val alphaId = authored.lifelines.first { it.name == "Alpha" }.id
            val messageId = authored.messages.single().id
            assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.SetLifelineHeaderWidth(alphaId, 240.0)))
            assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.SetDocumentTheme("TOKYO_NIGHT")))

            assertIs<Seq3SourceImportResult.Success>(
                state.seq3Sessions.applyLiveSourceDraft(
                    id,
                    firstSource.replace("first", "second"),
                    Seq3Dialect.PLANTUML,
                ),
            )
            var document = state.seq3Sessions.sessions.single { it.id == id }.document
            assertEquals(messageId, document.messages.single().id, "source-authored messages keep their stable id")
            assertEquals(240.0, document.lifelines.first { it.id == alphaId }.headerWidthDp)
            assertEquals("TOKYO_NIGHT", document.themePresetName)

            // Make a canvas note and fragment part of the import baseline, then edit their
            // source-invisible presentation while a later source revision is already dirty.
            assertTrue(state.seq3Sessions.reloadSourceDraft(id))
            val note = Seq3Note("canvas-note", "Call note", listOf(messageId))
            assertTrue(
                state.seq3Sessions.applyCommand(
                    id,
                    Seq3Command.Bulk(setOf(messageId), Seq3BulkAction.Note(note)),
                ),
            )
            val fragment = Seq3Fragment("canvas-fragment", Seq3FragmentKind.GROUP, "Boundary", listOf(messageId))
            assertTrue(
                state.seq3Sessions.applyCommand(
                    id,
                    Seq3Command.Bulk(setOf(messageId), Seq3BulkAction.Group(fragment)),
                ),
            )
            assertTrue(state.seq3Sessions.reloadSourceDraft(id))
            val dirtySource = view.sourceDraft.replace("second", "third")
            state.seq3Sessions.updateSourceDraftText(id, dirtySource)
            assertTrue(view.sourceDraftDirty)
            assertFalse(view.sourceDraftStale)

            val noteStyle = Seq3ArtifactStyle(fillArgb = 0xFF123456.toInt(), borderArgb = 0xFF654321.toInt())
            val fragmentStyle = Seq3ArtifactStyle(fillArgb = 0xFFABCDEF.toInt(), borderArgb = 0xFF112233.toInt())
            assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.SetNoteGeometry(note.id, 42.0, 84.0, 220.0, 64.0)))
            assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.SetNoteStyle(note.id, noteStyle)))
            assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.SetFragmentStyle(fragment.id, fragmentStyle)))
            assertTrue(
                state.seq3Sessions.applyCommand(
                    id,
                    Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetFragmentHideKindLabel(fragment.id, true)),
                ),
            )
            assertFalse(view.sourceDraftStale, "canvas-only changes do not make the pending source revision stale")

            assertIs<Seq3SourceImportResult.Success>(
                state.seq3Sessions.applyLiveSourceDraft(
                    id,
                    dirtySource.replace("third", "fourth"),
                    Seq3Dialect.PLANTUML,
                ),
            )
            document = state.seq3Sessions.sessions.single { it.id == id }.document
            assertEquals(240.0, document.lifelines.first { it.id == alphaId }.headerWidthDp)
            assertEquals("TOKYO_NIGHT", document.themePresetName)
            assertEquals(note.copy(x = 42.0, y = 84.0, width = 220.0, height = 64.0, style = noteStyle), document.notes.single())
            assertEquals(fragment.copy(hideKindLabel = true, style = fragmentStyle), document.fragments.single())
        } finally {
            state.close()
        }
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

    // WP14 regression guard: the hand-edit drift gate added to confirm() must never engage on the
    // COMMON path, where nothing was ever hand-edited. Model this directly on
    // confirmWritesANoteAndASecondConfirmUpdatesTheSameBlock above (also a same-session, two-confirm
    // sequence) — the only difference from that test is asserting pendingNoteOverwrite explicitly
    // stays null throughout. See Seq3NoteOverwriteGateTest.kt for the DRIFTED counterpart of this.
    @Test
    fun confirmOnANonDriftedNoteWritesImmediatelyWithoutPrompting() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)

        val firstBlockId = state.seq3Sessions.confirm(id)
        assertNotNull(firstBlockId)
        assertNull(state.pendingNoteOverwrite, "an ordinary first confirm must never raise a hand-edit prompt")

        state.seq3Sessions.updateTitle(id, "Regenerated title")
        val secondBlockId = state.seq3Sessions.confirm(id)

        assertEquals(firstBlockId, secondBlockId, "WP14's drift gate must not turn an un-hand-edited second confirm into a no-op")
        assertNull(state.pendingNoteOverwrite, "the common path — nothing was ever hand-edited — must never gate behind a prompt")
        val text = (state.tab("log")!!.annotations.blocks.single { it.id == firstBlockId } as com.indagium.model.AnnBlock.Note).text
        assertEquals("Regenerated title", parseSeq3Note(text)?.document?.title)
    }

    @Test
    fun generatedDiagramIsAutoSavedToLibraryBeforeAnyNoteIsAttached() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)

        val session = state.seq3Sessions.sessions.single { it.id == id }
        val libraryId = session.libraryItemId
        assertNotNull(libraryId)
        val item = state.seq3Sessions.libraryForTab(state.tab("log")!!).single { it.id == libraryId }
        assertTrue(item.attachments.isEmpty(), "auto-save must not imply note attachment")
        assertEquals(session.document, item.parsed?.document)
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
        // W3: syncLiveLinkedNote now rides the same debounce as autoSaveDraftToLibrary — settle it
        // deterministically rather than sleeping past DRAFT_SAVE_DEBOUNCE_MS.
        state.seq3Sessions.flush(id)

        val after = (state.tab("log")!!.annotations.blocks.single { it.id == blockId } as com.indagium.model.AnnBlock.Note).text
        assertTrue(after != before)
        assertEquals("Live title", parseSeq3Note(after)?.document?.title)

        val reopened = state.seq3Sessions.beginEdit("log", blockId)
        assertEquals(id, reopened, "opening a live-linked note must activate the existing session")
        assertEquals(1, state.seq3Sessions.sessions.size)
    }

    @Test
    fun undoingATitleCommandRefreshesItsLiveLinkedNote() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val previousTitle = state.seq3Sessions.sessions.single().document.title
        val blockId = requireNotNull(state.seq3Sessions.attachLiveLink(id))

        state.seq3Sessions.updateTitle(id, "Live title one step")
        state.seq3Sessions.flush(id)
        assertEquals("Live title one step", linkedNoteDocument(state, blockId).title)

        assertTrue(state.seq3Sessions.undo(id))
        state.seq3Sessions.flush(id)
        assertEquals(previousTitle, linkedNoteDocument(state, blockId).title)
    }

    private fun linkedNoteDocument(state: AppState, blockId: String): Seq3Document =
        parseSeq3Note((state.tab("log")!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note).text)!!.document

    @Test
    fun sameDocumentLinkedImportCanonicalizesDriftWithoutAddingUndo() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val blockId = requireNotNull(state.seq3Sessions.attachLiveLink(id))
        val original = (state.tab("log")!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note).text
        val drifted = original.replaceFirst("sequenceDiagram\n", "sequenceDiagram\n\n")
        state.updateBlock("log", blockId, drifted)
        val parsed = requireNotNull(parseSeq3Note(drifted))
        val undoCount = state.seq3Sessions.sessions.single { it.id == id }.undoStack.size

        val result = state.seq3Sessions.importSource(id, parsed.source, parsed.dialect)

        assertIs<Seq3SourceImportResult.Success>(result)
        assertEquals(undoCount, state.seq3Sessions.sessions.single { it.id == id }.undoStack.size)
        val canonical = (state.tab("log")!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note).text
        assertTrue(requireNotNull(parseSeq3Note(canonical)).sourceHashMatches)
    }

    @Test
    fun confirmedMarkerRemovalAppliesEvidenceFreeCandidateAsOneUndoStep() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val blockId = requireNotNull(state.seq3Sessions.attachLiveLink(id))
        val original = (state.tab("log")!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note).text
        val parsed = requireNotNull(parseSeq3Note(original))
        val lines = parsed.source.lines().toMutableList()
        val arrow = lines.indexOfFirst { it.contains("Producer->>Consumer: start op") }
        lines.removeAt(arrow - 1)
        val markerRemovedSource = lines.joinToString("\n")
        state.updateBlock("log", blockId, original.replace(parsed.source, markerRemovedSource))
        val undoCount = state.seq3Sessions.sessions.single { it.id == id }.undoStack.size

        val result = state.seq3Sessions.importSource(id, markerRemovedSource, parsed.dialect, confirmEvidenceLoss = true)

        assertIs<Seq3SourceImportResult.Success>(result)
        val session = state.seq3Sessions.sessions.single { it.id == id }
        assertEquals(undoCount + 1, session.undoStack.size)
        assertTrue(
            session.document.messages.any { message ->
                message.labelTemplate == "start op" &&
                    message.authoring == Seq3Authoring.EDITED &&
                    message.occurrences.isEmpty()
            },
        )
    }

    // WP14: syncLiveLinkedNote runs unattended (off publishGenerated/markDirty), so unlike confirm()
    // it cannot afford to prompt on a drifted link — it must SKIP just that one note (leaving it
    // byte-identical) while still syncing every OTHER linked note in the same pass. Two blocks
    // linked to the SAME library item (attachLiveLink, then a second attachLibraryLink onto the
    // same id) is what makes "one drifted, one not, same sync pass" observable in a single call.
    @Test
    fun syncLiveLinkedNoteSkipsADriftedLinkButStillSyncsAnUndriftedSiblingLink() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)

        val driftedBlockId = requireNotNull(state.seq3Sessions.attachLiveLink(id))
        val libraryId = requireNotNull(state.seq3Sessions.sessions.single().libraryItemId)
        val cleanBlockId = requireNotNull(state.seq3Sessions.attachLibraryLink("log", libraryId, afterBlockId = driftedBlockId))
        assertFalse(state.seq3Sessions.sessions.single { it.id == id }.linkedNoteDrifted, "sanity: nothing has drifted yet")

        val originalDriftedText = (state.tab("log")!!.annotations.blocks.single { it.id == driftedBlockId } as com.indagium.model.AnnBlock.Note).text
        val handEditedText = originalDriftedText.replaceFirst("sequenceDiagram\n", "sequenceDiagram\n    Note over X: hand-edited\n")
        assertTrue(handEditedText != originalDriftedText, "test setup sanity: the tamper must actually change the text")
        state.updateBlock("log", driftedBlockId, handEditedText)

        state.seq3Sessions.updateTitle(id, "Live title after drift")
        // W3: syncLiveLinkedNote rides the same debounce as autoSaveDraftToLibrary — settle it
        // deterministically rather than sleeping past DRAFT_SAVE_DEBOUNCE_MS.
        state.seq3Sessions.flush(id)

        val afterDrifted = (state.tab("log")!!.annotations.blocks.single { it.id == driftedBlockId } as com.indagium.model.AnnBlock.Note).text
        assertEquals(handEditedText, afterDrifted, "the hand-edited link must stay byte-identical, never re-derived from the document")

        val afterClean = (state.tab("log")!!.annotations.blocks.single { it.id == cleanBlockId } as com.indagium.model.AnnBlock.Note).text
        assertEquals(
            "Live title after drift",
            parseSeq3Note(afterClean)?.document?.title,
            "the un-hand-edited sibling link must still sync normally in the SAME pass",
        )
        assertTrue(
            state.seq3Sessions.sessions.single { it.id == id }.linkedNoteDrifted,
            "the session must surface the drift once a sync pass actually sees it",
        )
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
        state.seq3Sessions.flush(reopenedId)

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
    fun restoringAChangedMessageToItsGeneratedValuesClearsTheEditedBadge() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)
        val original = state.seq3Sessions.sessions.single().document.messages.first()

        state.seq3Sessions.applyCommand(id, Seq3Command.Bulk(setOf(original.id), Seq3BulkAction.SetLabel("temporary label")))
        assertEquals(Seq3Authoring.EDITED, state.seq3Sessions.sessions.single().document.messages.first { it.id == original.id }.authoring)

        state.seq3Sessions.applyCommand(id, Seq3Command.Bulk(setOf(original.id), Seq3BulkAction.SetLabel(original.labelTemplate)))

        val restored = state.seq3Sessions.sessions.single().document.messages.first { it.id == original.id }
        assertEquals(original.labelTemplate, restored.labelTemplate)
        assertEquals(Seq3Authoring.AUTO, restored.authoring)
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

    // ── W1: the blocker (docs/plans/prepare-plan-to-fix-binary-wreath.md) ──────────────────────────

    private fun formatMillisOfDay(millis: Long): String {
        val hh = millis / MILLIS_PER_HOUR
        val mm = (millis % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE
        val ss = (millis % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND
        val ms = millis % MILLIS_PER_SECOND
        return "%02d:%02d:%02d.%03d".format(hh, mm, ss, ms)
    }

    /** Fixed-length, fixed-width-counter synthetic log line — see Seq3GeneratorTest's identical
     *  helper for why the counter is zero-padded rather than left variable-width. */
    private fun paddedMessage(i: Int, targetLen: Int): String {
        val head = "processed item %05d of batch ".format(i)
        return head + "y".repeat((targetLen - head.length).coerceAtLeast(0))
    }

    @Test
    fun beginOverA3000EntryTabReachesGeneratingFalseWithANonNullLibraryItemId() {
        // This exact assertion FAILED before W1a/W1c: a range this size produced an over-budget
        // header, `DiagramLibraryStore.save`'s `require(item.snapshot.parsed() != null)` threw from
        // inside `publishGenerated`'s `replace(id) { ... }` lambda, the assignment never landed, and
        // `generating` stayed latched at `true` forever with a null `libraryItemId` — see the plan's
        // own PROBE output (`PROBE n=3000 stillGenerating=true lifelines=0 messages=0 libraryItemId=null`).
        val entries = (1..3_000).map { i -> LogEntry(i, formatMillisOfDay(i.toLong()), LogLevel.I, "A", "processed item $i of batch") }
        val state = stateFor(mkTab("log", "sample.log", entries))

        val id = state.seq3Sessions.begin("log")!!
        awaitGenerated(state, id)

        val session = state.seq3Sessions.sessions.single { it.id == id }
        assertFalse(session.generating)
        assertNotNull(session.libraryItemId, "an in-budget diagram must actually reach the library")
        assertNull(state.pendingDiagramNotice, "a diagram that fits the budget must never raise the W0 popup")
    }

    @Test
    fun applyingADeliberatelyOverBudgetDocumentSetsTheNoticeClearsGeneratingAndNeverThrows() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)

        // Built directly as Kotlin objects, not decoded through the codec — a decode this large
        // would just return null (MAX_SEQ3_HEADER_CHARS is a DECODE bound), so this is the only way
        // to get a live session actually holding an in-memory document too big to SAVE: the
        // "genuinely extreme case" W1c's pre-flight check exists for (many distinct message shapes,
        // which the W1a occurrence budget alone cannot bound — see Seq3Generator's own doc).
        val hugeDocument = Seq3Document(
            title = "Huge",
            lifelines = listOf(Seq3Lifeline("L", "L", setOf("L"), 0)),
            messages = (1..6_000).map { i ->
                Seq3Message(
                    id = "msg-$i",
                    match = Seq3Match("L", "distinct shape $i"),
                    fromLifelineId = "L",
                    toLifelineId = null,
                    labelTemplate = "distinct shape $i",
                    occurrences = listOf(Seq3Occurrence(i, i.toLong(), "00:00:00.000", 0, 0, 'I', "distinct shape number $i, padded out with extra text")),
                )
            },
        )
        assertTrue(encodeSeq3Note(hugeDocument).length > MAX_SEQ3_HEADER_CHARS, "fixture must actually be oversized")

        assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.ReplaceDocument(hugeDocument)), "applying must not throw and must report success")
        // W3: markDirty's own library write is now debounced off the edit itself — the rejection
        // (and the popup it raises) lands ~DRAFT_SAVE_DEBOUNCE_MS later, not synchronously.
        await { state.pendingDiagramNotice != null }

        val session = state.seq3Sessions.sessions.single { it.id == id }
        assertFalse(session.generating, "the canvas must never stay latched on \"Generating…\"")
        assertFalse(session.regenBuilding)
        val notice = assertNotNull(state.pendingDiagramNotice, "an over-budget save must raise the W0 popup instead of throwing")
        assertTrue(notice.title.isNotBlank())
        assertTrue(notice.body.isNotBlank())
    }

    @Test
    fun beginOverLongOrdinaryLogLinesEndsWithANonNullLibraryItemIdAndNoNotice() {
        // Pins the BYTE dimension specifically, not just occurrence COUNT: 600-char lines (a
        // stack trace, a JSON payload, dumpsys output — all ordinary logcat content) are exactly
        // the case a count-only occurrence budget missed — at this size each tag's occurrences fit
        // comfortably under the count-only allowance (nothing even got trimmed) and STILL encoded
        // well past MAX_SEQ3_HEADER_CHARS, so this diagram used to be refused outright instead of
        // degrading. Five tags, distinct pid/tid per tag, mirrors the reproduction fixture without
        // tripping the generator's (pre-existing, unrelated) thread-handoff inference cost.
        val tagCount = 5
        val tags = (0 until tagCount).map { ('A' + it).toString() }
        val entries = (1..1_000).map { i ->
            val tagIdx = i % tagCount
            LogEntry(i, formatMillisOfDay(i.toLong()), LogLevel.I, tags[tagIdx], paddedMessage(i, LONG_LINE_LENGTH), pid = 100 + tagIdx, tid = 200 + tagIdx)
        }
        val state = stateFor(mkTab("log", "sample.log", entries))

        val id = state.seq3Sessions.begin("log")!!
        // Long lines make a full generate pass (tokenizing/grouping 600-char text) noticeably
        // slower than the suite's other, short-line fixtures — a generous timeout, not a longer
        // debounce or extra work.
        awaitGenerated(state, id, timeoutMs = 15_000)

        val session = state.seq3Sessions.sessions.single { it.id == id }
        assertFalse(session.generating)
        assertNotNull(session.libraryItemId, "600-char log lines are ordinary content and must still reach the library")
        assertNull(state.pendingDiagramNotice, "an in-budget diagram must never raise the W0 popup")
    }

    @Test
    fun repeatedEditsOnAnOverBudgetSessionReportTheSameRejectionOnlyOnce() {
        val state = state()
        val id = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, id)

        val hugeDocument = Seq3Document(
            title = "Huge",
            lifelines = listOf(Seq3Lifeline("L", "L", setOf("L"), 0)),
            messages = (1..6_000).map { i ->
                Seq3Message(
                    id = "msg-$i",
                    match = Seq3Match("L", "distinct shape $i"),
                    fromLifelineId = "L",
                    toLifelineId = null,
                    labelTemplate = "distinct shape $i",
                    occurrences = listOf(Seq3Occurrence(i, i.toLong(), "00:00:00.000", 0, 0, 'I', "distinct shape number $i, padded out with extra text")),
                )
            },
        )
        assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.ReplaceDocument(hugeDocument)))
        // W3: wait for the (now debounced) draft save to actually run rather than asserting
        // synchronously — see applyingADeliberatelyOverBudgetDocumentSetsTheNoticeClearsGeneratingAndNeverThrows.
        await { state.pendingDiagramNotice != null }
        assertNotNull(state.pendingDiagramNotice, "the first rejection must surface the popup")
        val runsAfterFirstRejection = state.seq3Sessions.draftSaveRunCount.get()
        // Mirrors App.kt's OK button: dismissing only clears the field, never a permanent
        // "stop telling me" decision.
        state.pendingDiagramNotice = null

        // Still the same oversized document (only the title changed) -> autoSaveDraftToLibrary
        // re-attempts the save and is rejected again with the SAME category. Wait for THAT SPECIFIC
        // debounced attempt to actually complete (not just "hasn't fired yet") before asserting the
        // dedupe held — draftSaveRunCount is exact instrumentation for this, unlike a fixed sleep.
        state.seq3Sessions.updateTitle(id, "Still huge")
        await { state.seq3Sessions.draftSaveRunCount.get() > runsAfterFirstRejection }

        assertNull(state.pendingDiagramNotice, "the same rejection category must not re-nag on every subsequent edit")
        val session = state.seq3Sessions.sessions.single { it.id == id }
        assertFalse(session.generating, "generating must stay cleared regardless of the anti-nag dedupe")
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
    fun scannedEntryCountHonorsSparseExactSelectionInsteadOfItsInclusiveSpan() {
        val entries = (1..30).map { LogEntry(it, "10:00:00.$it", LogLevel.I, "T", "m$it") }
        val state = stateFor(mkTab("log", "sample.log", entries))
        val id = state.seq3Sessions.begin("log", setOf(3, 30))!!

        assertEquals(2, state.seq3Sessions.scannedEntryCount(id))
    }

    @Test
    fun scannedEntryCountForVisibleViewIsTheWholeTab() {
        val state = state()
        val id = state.seq3Sessions.begin("log")!!

        assertEquals(2, state.seq3Sessions.scannedEntryCount(id))
    }

    // ── Source editor: canvas edits and undo bursts ───────────────────────────────────────────

    private fun liveSource(label: String) = listOf(
        "@startuml",
        "participant \"Alpha\" as A",
        "participant \"Beta\" as B",
        "A -> B: $label",
        "@enduml",
    ).joinToString("\n")

    @Test
    fun aCanvasEditFollowsTheLiveSourceInsteadOfLockingIt() {
        val state = state()
        val id = state.seq3Sessions.beginEmpty("log")!!
        state.seq3Sessions.setDialect(id, Seq3Dialect.PLANTUML)
        val view = state.seq3Sessions.viewState(id)!!
        assertTrue(state.seq3Sessions.prepareSourceDraft(id))
        assertIs<Seq3SourceImportResult.Success>(state.seq3Sessions.applyLiveSourceDraft(id, liveSource("first"), Seq3Dialect.PLANTUML))
        val generation = view.sourceEditorGeneration
        val revision = view.sourceExternalRevision
        val ids = state.seq3Sessions.sessions.single { it.id == id }.document.lifelines.map { it.id }

        assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec(ids[0], ids[1], "Canvas"))))

        assertFalse(view.sourceDraftStale, "nothing is pending, so the draft follows the diagram")
        assertFalse(view.sourceDraftDirty)
        assertTrue(view.sourceDraft.contains("Canvas"), view.sourceDraft)
        assertEquals(generation, view.sourceEditorGeneration, "the editor is not restarted")
        assertEquals(revision + 1, view.sourceExternalRevision)
        assertEquals(view.sourceDraft, view.sourceExternalText)

        val typed = view.sourceDraft.replace("Canvas", "Typed")
        assertIs<Seq3SourceImportResult.Success>(state.seq3Sessions.applyLiveSourceDraft(id, typed, Seq3Dialect.PLANTUML))
        assertTrue(state.seq3Sessions.sessions.single { it.id == id }.document.messages.any { it.labelTemplate == "Typed" })
        assertFalse(view.sourceDraftStale)
    }

    @Test
    fun aCanvasEditKeepsTheHalfTypedWordOfATrimmedPreview() {
        val state = state()
        val id = state.seq3Sessions.beginEmpty("log")!!
        state.seq3Sessions.setDialect(id, Seq3Dialect.PLANTUML)
        val view = state.seq3Sessions.viewState(id)!!
        assertTrue(state.seq3Sessions.prepareSourceDraft(id))
        val preview = liveSource("first")
        // A suggestion list is open on the half-typed `/pa`: the draft has it, the import does not.
        val draft = preview.replace("@enduml", "/pa\n@enduml")
        state.seq3Sessions.updateSourceDraftText(id, draft)
        assertIs<Seq3SourceImportResult.Success>(state.seq3Sessions.applyLiveSourceDraft(id, draft, Seq3Dialect.PLANTUML, preview))
        assertEquals(preview, view.sourceLiveLastAppliedText, "what was imported, not the whole draft")
        val revision = view.sourceExternalRevision
        val ids = state.seq3Sessions.sessions.single { it.id == id }.document.lifelines.map { it.id }

        assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec(ids[0], ids[1], "Canvas"))))

        assertEquals(draft, view.sourceDraft, "the half-typed word is not replaced by the canonical text")
        assertTrue(view.sourceDraftStale, "the draft is pending, so the canvas edit marks it stale")
        assertEquals(revision, view.sourceExternalRevision, "the editor is not told to adopt anything")
    }

    @Test
    fun theNextLiveApplyAfterTheListClosesRecordsTheFullDraft() {
        val state = state()
        val id = state.seq3Sessions.beginEmpty("log")!!
        state.seq3Sessions.setDialect(id, Seq3Dialect.PLANTUML)
        val view = state.seq3Sessions.viewState(id)!!
        assertTrue(state.seq3Sessions.prepareSourceDraft(id))
        val preview = liveSource("first")
        val draft = preview.replace("@enduml", "/pa\n@enduml")
        state.seq3Sessions.updateSourceDraftText(id, draft)
        assertIs<Seq3SourceImportResult.Success>(state.seq3Sessions.applyLiveSourceDraft(id, draft, Seq3Dialect.PLANTUML, preview))
        assertEquals(preview, view.sourceLiveLastAppliedText)

        val closed = liveSource("second")
        assertIs<Seq3SourceImportResult.Success>(state.seq3Sessions.applyLiveSourceDraft(id, closed, Seq3Dialect.PLANTUML))
        assertEquals(closed, view.sourceLiveLastAppliedText, "an untrimmed apply records the full draft again")
    }

    @Test
    fun aCanvasEditStillLocksTheSourceWhileTypedTextIsPending() {
        val state = state()
        val id = state.seq3Sessions.beginEmpty("log")!!
        state.seq3Sessions.setDialect(id, Seq3Dialect.PLANTUML)
        val view = state.seq3Sessions.viewState(id)!!
        assertTrue(state.seq3Sessions.prepareSourceDraft(id))
        assertIs<Seq3SourceImportResult.Success>(state.seq3Sessions.applyLiveSourceDraft(id, liveSource("first"), Seq3Dialect.PLANTUML))
        val ids = state.seq3Sessions.sessions.single { it.id == id }.document.lifelines.map { it.id }
        val pending = liveSource("first") + "\nnot applied yet"
        state.seq3Sessions.updateSourceDraftText(id, pending)

        state.seq3Sessions.applyCommand(id, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec(ids[0], ids[1], "Canvas")))

        assertTrue(view.sourceDraftStale)
        assertEquals(pending, view.sourceDraft)
    }

    @Test
    fun reloadDeliversTheDiagramSourceThroughTheEditorChannel() {
        val state = state()
        val id = state.seq3Sessions.beginEmpty("log")!!
        val view = state.seq3Sessions.viewState(id)!!
        assertTrue(state.seq3Sessions.prepareSourceDraft(id))
        val generation = view.sourceEditorGeneration
        val canonical = view.sourceDraft
        state.seq3Sessions.updateSourceDraftText(id, "$canonical\n' draft")
        val revision = view.sourceExternalRevision

        assertTrue(state.seq3Sessions.reloadSourceDraft(id))

        assertEquals(canonical, view.sourceDraft)
        assertEquals(canonical, view.sourceExternalText)
        assertEquals(revision + 1, view.sourceExternalRevision)
        assertEquals(generation, view.sourceEditorGeneration, "Reload keeps the editor and its history")
    }

    @Test
    fun liveSourceEditsInOneBurstAreOneDiagramUndoStepLabelledEditSource() {
        val state = state()
        var now = 1_000_000L
        val session = Seq3Session(state, clock = { now })
        val id = session.beginEmpty("log")!!
        session.setDialect(id, Seq3Dialect.PLANTUML)
        assertTrue(session.prepareSourceDraft(id))
        val original = session.sessions.single { it.id == id }.document

        listOf("one", "two", "three").forEach { label ->
            assertIs<Seq3SourceImportResult.Success>(session.applyLiveSourceDraft(id, liveSource(label), Seq3Dialect.PLANTUML))
            now += 600
        }
        val burst = session.sessions.single { it.id == id }
        assertEquals(1, burst.undoStack.size, "three edits in one burst share one entry")
        assertEquals("Edit source", burst.undoStack.single().label)
        assertEquals("three", burst.document.messages.single().labelTemplate)

        assertTrue(session.undo(id))
        assertEquals(original, session.sessions.single { it.id == id }.document, "one undo reverts the whole burst")
    }

    @Test
    fun aPauseOrAnotherCommandEndsTheLiveSourceBurst() {
        val state = state()
        var now = 1_000_000L
        val session = Seq3Session(state, clock = { now })
        val id = session.beginEmpty("log")!!
        session.setDialect(id, Seq3Dialect.PLANTUML)
        assertTrue(session.prepareSourceDraft(id))
        fun stackSize() = session.sessions.single { it.id == id }.undoStack.size

        session.applyLiveSourceDraft(id, liveSource("one"), Seq3Dialect.PLANTUML)
        now += 1_500
        session.applyLiveSourceDraft(id, liveSource("two"), Seq3Dialect.PLANTUML)
        assertEquals(2, stackSize(), "a pause over a second starts a new entry")

        now += 100
        val ids = session.sessions.single { it.id == id }.document.lifelines.map { it.id }
        assertTrue(session.applyCommand(id, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec(ids[0], ids[1], "Canvas"))))
        now += 100
        session.applyLiveSourceDraft(id, session.viewState(id)!!.sourceDraft.replace("Canvas", "Typed"), Seq3Dialect.PLANTUML)
        assertEquals(4, stackSize(), "a canvas command in between keeps the next source edit separate")
        assertEquals(listOf("Edit source", "Edit source", "Add custom message", "Edit source"), session.sessions.single { it.id == id }.undoStack.map { it.label })
    }
}
