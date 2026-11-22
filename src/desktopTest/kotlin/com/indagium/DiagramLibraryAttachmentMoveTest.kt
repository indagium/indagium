package com.indagium

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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The library remembers which note blocks hold an item (an attachment names a tab and a block). Share and Stop sharing move blocks
 * to another note under the same ids, so the attachments follow, or a later delete of the block finds nothing and the item stays
 * "Attached" and keeps an attachment slot.
 */
class DiagramLibraryAttachmentMoveTest {
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

    @Test
    fun attachmentsFollowTheirBlocksThroughShareAndStopSharingAndDeletingThemDetaches() {
        val root = createTempDirectory("indagium-library-attachment-move").toFile()
        val store = DiagramLibraryStore(File(root, "library.cache"))
        val state = AppState(File(root, "state.cache"), notesDir = File(root, "notes"), diagramLibraryStore = store)
        try {
            val other = listOf(LogEntry(1, "11:00:00.000", LogLevel.I, "Other", "unrelated"))
            state.tabs = listOf(
                mkTab("a", "a.log", rows()).copy(sourcePath = File(root, "a.log").absolutePath),
                mkTab("b", "b.log", other).copy(sourcePath = File(root, "b.log").absolutePath),
            )
            state.activateTab("a")
            val coordinator = state.seq3Sessions
            val session = assertNotNull(coordinator.begin("a", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == session }?.generating == false }
            val snapshotBlock = assertNotNull(coordinator.attachSnapshot(session))
            val linkBlock = assertNotNull(coordinator.attachLiveLink(session))
            val itemId = assertNotNull(coordinator.sessions.single().libraryItemId)

            fun attachments() = assertNotNull(store.get(itemId)).attachments
            assertEquals(setOf("a" to snapshotBlock, "a" to linkBlock), attachments().map { it.tabId to it.blockId }.toSet())

            val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))
            assertEquals(
                setOf(hostId to snapshotBlock, hostId to linkBlock),
                attachments().map { it.tabId to it.blockId }.toSet(),
                "Share moved both notes to the host: the library names the host",
            )

            state.removeBlock(hostId, snapshotBlock)
            assertEquals(listOf(hostId to linkBlock), attachments().map { it.tabId to it.blockId }, "deleting a note in the shared note detaches it")
            assertTrue(assertNotNull(store.get(itemId)).isAttached)

            assertTrue(state.stopSharing(hostId, "b"))
            assertEquals(listOf("b" to linkBlock), attachments().map { it.tabId to it.blockId }, "Stop sharing moved the note to b")

            state.removeBlock("b", linkBlock)
            assertFalse(assertNotNull(store.get(itemId)).isAttached, "the last attachment is gone with its note")
            // A fresh store reading the file sees the same: every step was persisted.
            assertFalse(assertNotNull(DiagramLibraryStore(File(root, "library.cache")).get(itemId)).isAttached)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }
}
