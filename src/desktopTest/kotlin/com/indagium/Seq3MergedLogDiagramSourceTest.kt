package com.indagium

import com.indagium.diagram3.parseSeq3Note
import com.indagium.diagram3.updateSeq3NoteSourcePath
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.mkTab
import com.indagium.ui.setSharedNoteMembers
import com.indagium.ui.shareTabNote
import com.indagium.ui.stopSharing
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A log with no file path (a merged tab) names itself by its tab name everywhere a diagram records or resolves its source: Share,
 * Confirm in a shared note, Stop sharing and the match back to a tab. Otherwise the diagram would depend on the members-only file-name
 * fallback and report its log as not open once that tab leaves the members.
 */
class Seq3MergedLogDiagramSourceTest {
    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val WAIT_MS = 4_000L
        const val PID = 7
        const val TID = 11
        const val MERGED = "merged.log"
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_MS * NANOS_PER_MILLI
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not become true within ${WAIT_MS}ms")
    }

    private fun state(root: File): AppState = AppState(
        File(root, "state.cache"),
        notesDir = File(root, "notes"),
        diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
    ).also {
        val merged = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", PID, TID),
            LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", PID, TID),
        )
        val other = listOf(LogEntry(1, "11:00:00.000", LogLevel.I, "Other", "unrelated"), LogEntry(2, "11:00:00.010", LogLevel.I, "More", "rows"))
        it.tabs = listOf(
            mkTab("m", MERGED, merged),
            mkTab("b", "b.log", other).copy(sourcePath = File(root, "b.log").absolutePath),
        )
        assertNull(it.tab("m")!!.sourcePath, "a merged tab has no file path")
        it.activateTab("m")
    }

    private fun confirmed(state: AppState, tabId: String): String {
        val coordinator = state.seq3Sessions
        val id = assertNotNull(coordinator.begin(tabId, setOf(1, 2)))
        await { coordinator.sessions.firstOrNull { it.id == id }?.generating == false }
        val blockId = assertNotNull(coordinator.confirm(id))
        coordinator.close(id)
        return blockId
    }

    private fun stampOf(state: AppState, noteTabId: String, blockId: String): String? {
        val block = state.noteTab(noteTabId)!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note
        return parseSeq3Note(block.text)?.document?.sourcePath
    }

    private fun navigate(state: AppState, noteTabId: String, blockId: String, entryId: Int): Boolean =
        runBlocking { state.seq3Sessions.navigateToOccurrence(noteTabId, blockId, entryId) }

    @Test
    fun sharingStampsAMergedLogsDiagramAndItStillResolvesOnceThatLogLeavesTheMembers() {
        val root = createTempDirectory("indagium-seq3-merged-share").toFile()
        val state = state(root)
        try {
            val blockId = confirmed(state, "m")
            val hostId = assertNotNull(state.shareTabNote("m", "Investigation", listOf("m", "b")))
            assertEquals(MERGED, stampOf(state, hostId, blockId), "the diagram is stamped with the merged log's key")

            assertTrue(state.setSharedNoteMembers(hostId, listOf("b")))
            state.activateTab("b")

            assertTrue(navigate(state, hostId, blockId, 2), "the diagram still names its open log")
            assertEquals(setOf(2), state.tab("m")!!.selected)
            assertTrue(state.tab("b")!!.selected.isEmpty())
            assertNull(state.pendingDiagramNotice)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun confirmingAMergedLogsDiagramInASharedNoteStampsItAndItResolvesAfterTheLogLeavesTheMembers() {
        val root = createTempDirectory("indagium-seq3-merged-confirm").toFile()
        val state = state(root)
        try {
            val firstBlock = confirmed(state, "m")
            val hostId = assertNotNull(state.shareTabNote("m", "Investigation", listOf("m", "b")))
            // The shared block is made to carry no stamp, as a diagram written by an older build does.
            val shared = state.sharedNoteHost(hostId)!!.annotations.blocks.single { it.id == firstBlock } as AnnBlock.Note
            state.updateBlock(hostId, firstBlock, assertNotNull(updateSeq3NoteSourcePath(shared.text, null)))
            assertNull(stampOf(state, hostId, firstBlock))

            val editing = assertNotNull(state.seq3Sessions.beginEdit(hostId, firstBlock))
            assertEquals("m", state.seq3Sessions.sessions.single { it.id == editing }.sourceTabId)
            state.seq3Sessions.updateTitle(editing, "Edited in the shared note")
            assertEquals(firstBlock, state.seq3Sessions.confirm(editing))
            assertEquals(MERGED, stampOf(state, hostId, firstBlock), "a diagram confirmed into the shared note names the merged log")
            state.seq3Sessions.close(editing)

            assertTrue(state.setSharedNoteMembers(hostId, listOf("b")))
            state.activateTab("b")

            assertTrue(navigate(state, hostId, firstBlock, 2))
            assertEquals(setOf(2), state.tab("m")!!.selected)
            assertNull(state.pendingDiagramNotice)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun stopSharingKeepsTheMergedLogsKeyAndItStillResolves() {
        val root = createTempDirectory("indagium-seq3-merged-stop").toFile()
        val state = state(root)
        try {
            val blockId = confirmed(state, "m")
            val hostId = assertNotNull(state.shareTabNote("m", "Investigation", listOf("m", "b")))
            assertTrue(state.stopSharing(hostId, "b", replaceExisting = true))

            assertEquals(MERGED, stampOf(state, "b", blockId))
            state.activateTab("b")
            assertTrue(navigate(state, "b", blockId, 2))
            assertEquals(setOf(2), state.tab("m")!!.selected)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }
}
