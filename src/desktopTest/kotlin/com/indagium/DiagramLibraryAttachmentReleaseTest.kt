package com.indagium

import com.indagium.debug.IndagiumToolOperations
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.deleteSharedNote
import com.indagium.ui.mkTab
import com.indagium.ui.shareTabNote
import com.indagium.ui.shareTabNoteLocked
import com.indagium.ui.stopSharing
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A note block that is dropped without going through `removeBlock` (a private note replaced by Stop sharing, a deleted shared
 * note, "clear all notes") must release its library attachment, or the item stays "Attached" and keeps an attachment slot.
 */
class DiagramLibraryAttachmentReleaseTest {
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

    private fun rows(first: String, second: String): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, first, "start op", PID, TID),
        LogEntry(2, "10:00:00.050", LogLevel.I, second, "handle op", PID, TID),
    )

    private class Fixture(val root: File, val store: DiagramLibraryStore, val state: AppState) {
        fun close() {
            state.close()
            root.deleteRecursively()
        }
    }

    private fun fixture(): Fixture {
        val root = createTempDirectory("indagium-library-attachment-release").toFile()
        val store = DiagramLibraryStore(File(root, "library.cache"))
        val state = AppState(File(root, "state.cache"), notesDir = File(root, "notes"), diagramLibraryStore = store)
        state.tabs = listOf(
            mkTab("a", "a.log", rows("Producer", "Consumer")).copy(sourcePath = File(root, "a.log").absolutePath),
            mkTab("b", "b.log", rows("Sender", "Receiver")).copy(sourcePath = File(root, "b.log").absolutePath),
        )
        state.activateTab("a")
        return Fixture(root, store, state)
    }

    /** Opens a diagram on [tabId] and attaches a snapshot of it to that tab's note; returns the library item id and the note block id. */
    private fun Fixture.attachedSnapshot(tabId: String): Pair<String, String> {
        val coordinator = state.seq3Sessions
        val session = assertNotNull(coordinator.begin(tabId, setOf(1, 2)))
        await { coordinator.sessions.firstOrNull { it.id == session }?.generating == false }
        val block = assertNotNull(coordinator.attachSnapshot(session))
        val itemId = assertNotNull(coordinator.sessions.single { it.id == session }.libraryItemId)
        coordinator.close(session)
        return itemId to block
    }

    private fun Fixture.attachments(itemId: String) = assertNotNull(store.get(itemId)).attachments.map { it.tabId to it.blockId }

    @Test
    fun stopSharingReplacingARecipientsNoteReleasesItsAttachmentsButKeepsTheMovedOnes() {
        val f = fixture()
        try {
            val (movedItem, movedBlock) = f.attachedSnapshot("a")
            val (replacedItem, replacedBlock) = f.attachedSnapshot("b")
            assertEquals(listOf("b" to replacedBlock), f.attachments(replacedItem))

            val hostId = assertNotNull(f.state.shareTabNote("a", "Investigation", listOf("a", "b")))
            assertEquals(listOf(hostId to movedBlock), f.attachments(movedItem))

            assertTrue(f.state.stopSharing(hostId, "b", replaceExisting = true))

            assertFalse(assertNotNull(f.store.get(replacedItem)).isAttached, "b's replaced note no longer holds the item")
            assertEquals(listOf("b" to movedBlock), f.attachments(movedItem), "the moved block's attachment follows it to b")
            assertFalse(
                assertNotNull(DiagramLibraryStore(File(f.root, "library.cache")).get(replacedItem)).isAttached,
                "the release was persisted",
            )
        } finally {
            f.close()
        }
    }

    @Test
    fun deletingAMovedBlockBeforeTheShareFlushesKeepsItDetachedOnDisk() {
        val f = fixture()
        try {
            val (itemId, block) = f.attachedSnapshot("a")
            val move = synchronized(f.state.stateLock) {
                assertNotNull(f.state.shareTabNoteLocked("a", "Investigation", listOf("a", "b")))
            }

            // A delete can acquire the state lock before the sharing thread gets to its disk write.
            assertTrue(move.attachmentsChanged)
            f.state.removeBlock(move.hostId, block)
            f.state.seq3Sessions.persistAttachmentChanges()

            assertFalse(assertNotNull(f.store.get(itemId)).isAttached)
            assertFalse(assertNotNull(DiagramLibraryStore(File(f.root, "library.cache")).get(itemId)).isAttached)
        } finally {
            f.close()
        }
    }

    @Test
    fun deletingASharedNoteReleasesTheAttachmentsOfItsBlocks() {
        val f = fixture()
        try {
            val (itemId, block) = f.attachedSnapshot("a")
            val hostId = assertNotNull(f.state.shareTabNote("a", "Investigation", listOf("a", "b")))
            assertEquals(listOf(hostId to block), f.attachments(itemId))

            assertTrue(f.state.deleteSharedNote(hostId))

            assertFalse(assertNotNull(f.store.get(itemId)).isAttached)
            assertFalse(f.state.deleteSharedNote(hostId), "a second delete still reports that there is no such note")
        } finally {
            f.close()
        }
    }

    @Test
    fun clearAllNotesReleasesTheAttachmentsOfTheClearedBlocks() {
        val f = fixture()
        try {
            val (itemId, block) = f.attachedSnapshot("a")
            assertEquals(listOf("a" to block), f.attachments(itemId))

            val result = IndagiumToolOperations(f.state).toolGateway.execute("clear_all_notes", mapOf("tabId" to "a")) as Map<*, *>

            assertEquals(true, result["ok"], result.toString())
            assertFalse(assertNotNull(f.store.get(itemId)).isAttached)
        } finally {
            f.close()
        }
    }
}
