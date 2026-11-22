package com.indagium

import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.parseSeq3Note
import com.indagium.diagram3.updateSeq3NoteSourcePath
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.mkTab
import com.indagium.ui.shareTabNote
import com.indagium.ui.stopSharing
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The note is the truth about which log a diagram is about. A session that wrote a stamped source into a shared note, or whose
 * block moved to another note, keeps that source, so its next Confirm never writes the diagram without it.
 */
class Seq3ConfirmAfterMoveTest {
    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val WAIT_MS = 4_000L
        const val PID = 7
        const val TID = 11
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_MS * NANOS_PER_MILLI
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not become true within ${WAIT_MS}ms")
    }

    private fun rows(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", PID, TID),
        LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", PID, TID),
    )

    private fun twoLogsState(root: File): AppState = AppState(
        File(root, "state.cache"),
        notesDir = File(root, "notes"),
        diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
    ).also {
        val other = listOf(LogEntry(1, "11:00:00.000", LogLevel.I, "Other", "unrelated"), LogEntry(2, "11:00:00.010", LogLevel.I, "More", "rows"))
        it.tabs = listOf(
            mkTab("a", "a.log", rows()).copy(sourcePath = File(root, "a.log").absolutePath),
            mkTab("b", "b.log", other).copy(sourcePath = File(root, "b.log").absolutePath),
        )
        it.activateTab("a")
    }

    private fun noteDocument(state: AppState, tabId: String, blockId: String): Seq3Document {
        val block = state.noteTab(tabId)!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note
        return assertNotNull(parseSeq3Note(block.text)).document
    }

    /** A diagram of a, in a's private note, shared with b; its stamp is then taken away, as in a note written before Share stamped. */
    private fun unstampedSharedDiagram(state: AppState): Pair<String, String> {
        val coordinator = state.seq3Sessions
        val original = assertNotNull(coordinator.begin("a", setOf(1, 2)))
        await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
        val blockId = assertNotNull(coordinator.confirm(original))
        coordinator.close(original)
        val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))
        val block = state.noteTab(hostId)!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note
        state.updateBlock(hostId, blockId, assertNotNull(updateSeq3NoteSourcePath(block.text, null)))
        assertNull(noteDocument(state, hostId, blockId).sourcePath, "fixture: an older, unstamped shared diagram")
        return hostId to blockId
    }

    @Test
    fun aStaleEditorKeepsTheSourceItWroteIntoASharedNoteThroughStopSharingAndTheNextConfirm() {
        val root = createTempDirectory("indagium-seq3-confirm-after-move").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val aPath = File(root, "a.log").absolutePath
            val (hostId, blockId) = unstampedSharedDiagram(state)

            val editing = assertNotNull(coordinator.beginEdit(hostId, blockId))
            assertEquals("a", coordinator.sessions.single { it.id == editing }.sourceTabId)
            assertNull(coordinator.sessions.single { it.id == editing }.document.sourcePath, "fixture: the editor opened without a stamp")
            coordinator.updateTitle(editing, "Edited while shared")
            assertEquals(blockId, coordinator.confirm(editing))
            assertEquals(aPath, noteDocument(state, hostId, blockId).sourcePath, "the shared note's Confirm stamps the source tab")

            assertTrue(state.stopSharing(hostId, "b"))
            coordinator.updateTitle(editing, "Edited again in b")
            assertEquals(blockId, coordinator.confirm(editing))

            assertEquals(aPath, noteDocument(state, "b", blockId).sourcePath, "the stamp the shared note held survives the next Confirm")
            assertEquals("a", coordinator.sourceTabIdForNote("b", blockId))
            state.closeTab("a")
            assertNull(coordinator.sourceTabIdForNote("b", blockId), "a's log is closed: b's rows are not the diagram's")
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aSessionWhoseBlockMovesWithoutAStampChangeTakesTheSourceTheMovedBlockNames() {
        val root = createTempDirectory("indagium-seq3-move-mirror").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val original = assertNotNull(coordinator.begin("a", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
            val blockId = assertNotNull(coordinator.confirm(original))
            assertNull(coordinator.sessions.single().document.sourcePath, "fixture: a private diagram of a names no source")

            // The moved block already names its log (no stamp is written by the move itself): the session follows the note.
            val block = state.tab("a")!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note
            val named = block.copy(text = assertNotNull(updateSeq3NoteSourcePath(block.text, "/logs/a.log")))
            synchronized(state.stateLock) { coordinator.moveNoteOwner("a", "b", listOf(named)) }

            assertEquals("/logs/a.log", coordinator.sessions.single().document.sourcePath)
            assertEquals("b", coordinator.sessions.single().noteOwnerTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aPrivateNoteConfirmNeverErasesTheSourceTheNotesBlockAlreadyNames() {
        val root = createTempDirectory("indagium-seq3-private-keeps-stamp").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            state.activateTab("b")
            val original = assertNotNull(coordinator.begin("b", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
            val blockId = assertNotNull(coordinator.confirm(original))
            val aPath = File(root, "a.log").absolutePath
            // The note gets a stamp the open session never heard of.
            val block = state.tab("b")!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note
            state.updateBlock("b", blockId, assertNotNull(updateSeq3NoteSourcePath(block.text, aPath)))
            assertNull(coordinator.sessions.single().document.sourcePath, "fixture: the session lost (never had) the stamp")

            coordinator.updateTitle(original, "Edited")
            assertEquals(blockId, coordinator.confirm(original))

            assertEquals(aPath, noteDocument(state, "b", blockId).sourcePath)
            assertEquals(aPath, coordinator.sessions.single().document.sourcePath, "and the session holds what the note carries")
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun confirmingDoesNotMarkTheSessionDirtyOrBreakUndo() {
        val root = createTempDirectory("indagium-seq3-confirm-clean").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val (hostId, blockId) = unstampedSharedDiagram(state)
            val editing = assertNotNull(coordinator.beginEdit(hostId, blockId))
            coordinator.updateTitle(editing, "One")
            coordinator.updateTitle(editing, "Two")
            assertEquals(blockId, coordinator.confirm(editing))
            val session = coordinator.sessions.single { it.id == editing }
            assertEquals(false, session.dirty)
            assertTrue(coordinator.undo(editing))
            assertEquals("One", coordinator.sessions.single { it.id == editing }.document.title)
            assertEquals(
                File(root, "a.log").absolutePath,
                coordinator.sessions.single { it.id == editing }.document.sourcePath,
                "undo keeps the source the session holds",
            )
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }
}
