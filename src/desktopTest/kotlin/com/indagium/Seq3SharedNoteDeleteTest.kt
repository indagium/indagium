package com.indagium

import com.indagium.diagram3.parseSeq3Note
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.deleteSharedNote
import com.indagium.ui.mkTab
import com.indagium.ui.shareTabNote
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A diagram opened from a shared note must not lose its saves when that note is deleted (see `Seq3Session.noteDeleted`). */
class Seq3SharedNoteDeleteTest {
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

    private fun twoTagEntries(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", SHARED_PID, SHARED_TID),
        LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", SHARED_PID, SHARED_TID),
    )

    private fun state(): AppState {
        val root = createTempDirectory("indagium-seq3-shared-delete").toFile()
        return AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also {
            it.tabs = listOf(mkTab("log", "sample.log", twoTagEntries()).copy(sourcePath = File("original/sample.log").absolutePath))
            it.activateTab("log")
        }
    }

    private fun awaitGenerated(state: AppState, id: String) =
        await { state.seq3Sessions.sessions.firstOrNull { it.id == id }?.generating == false }

    @Test
    fun confirmAfterTheSharedNoteWasDeletedWritesToTheSourceLogsOwnNote() {
        val state = state()
        try {
            val coordinator = state.seq3Sessions
            val original = assertNotNull(coordinator.begin("log", setOf(1, 2)))
            awaitGenerated(state, original)
            val blockId = assertNotNull(coordinator.confirm(original))
            val hostId = assertNotNull(state.shareTabNote("log", "Investigation", listOf("log")))
            coordinator.close(original)
            val editing = assertNotNull(coordinator.beginEdit(hostId, blockId))
            assertEquals(hostId, coordinator.sessions.single().noteTargetTabId)

            assertTrue(state.deleteSharedNote(hostId))

            assertEquals("log", coordinator.sessions.single().noteTargetTabId, "the diagram goes back to its log's own note")
            coordinator.updateTitle(editing, "Saved after the delete")
            val written = assertNotNull(coordinator.confirm(editing), "a confirm must never be a silent no-op")
            val block = assertIs<AnnBlock.Note>(state.tab("log")!!.annotations.blocks.single { it.id == written })
            assertEquals("Saved after the delete", parseSeq3Note(block.text)?.document?.title)
            assertNull(state.pendingDiagramNotice)
        } finally {
            state.close()
        }
    }

    @Test
    fun confirmAfterTheSharedNoteWasDeletedWithoutASourceLogRaisesANotice() {
        val state = state()
        try {
            val coordinator = state.seq3Sessions
            val original = assertNotNull(coordinator.begin("log", setOf(1, 2)))
            awaitGenerated(state, original)
            val blockId = assertNotNull(coordinator.confirm(original))
            val hostId = assertNotNull(state.shareTabNote("log", "Investigation", listOf("log")))
            coordinator.close(original)
            state.closeTab("log")
            state.tabs = listOf(mkTab("other", "sample.log", twoTagEntries()).copy(sourcePath = File("other/sample.log").absolutePath))
            val editing = assertNotNull(coordinator.beginEdit(hostId, blockId))
            assertNull(coordinator.sessions.single().sourceTabId)

            assertTrue(state.deleteSharedNote(hostId))

            assertNull(coordinator.sessions.single().noteTargetTabId)
            assertNull(coordinator.confirm(editing))
            val notice = assertNotNull(state.pendingDiagramNotice, "the lost note is reported, not swallowed")
            assertEquals("Note not found", notice.title)
            assertTrue(notice.body.contains("deleted"))
        } finally {
            state.close()
        }
    }
}
