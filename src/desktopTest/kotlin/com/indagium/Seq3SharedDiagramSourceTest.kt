package com.indagium

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
 * A diagram opened from a shared note, or from a private note it was moved into by Stop sharing, finds the log it was made from:
 * among several open tabs of the same file by the content, and never by the tab whose note it merely sits in.
 */
class Seq3SharedDiagramSourceTest {
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
    fun theTabWithTheDiagramsOwnContentWinsOverAnotherTabOfTheSameFile() {
        val root = createTempDirectory("indagium-seq3-shared-source").toFile()
        val path = File(root, "sample.log").absolutePath
        val state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also {
            // The same file read twice, as reparsing does: the copy listed first holds different rows.
            val reparsed = mkTab("reparsed", "sample.log · JSON", rows() + LogEntry(3, "10:00:00.100", LogLevel.W, "Other", "extra"))
                .copy(sourcePath = path)
            it.tabs = listOf(reparsed, mkTab("log", "sample.log", rows()).copy(sourcePath = path))
            it.activateTab("log")
        }
        try {
            val coordinator = state.seq3Sessions
            val original = assertNotNull(coordinator.begin("log", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
            val blockId = assertNotNull(coordinator.confirm(original))
            val hostId = assertNotNull(state.shareTabNote("log", "Investigation", listOf("log")))
            coordinator.close(original)

            // The library record keeps the log's path and fingerprint; two tabs share the path, and the fingerprint picks "log".
            assertNotNull(coordinator.beginEdit(hostId, blockId))
            assertEquals("log", coordinator.sessions.single().sourceTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

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

    @Test
    fun aDiagramMovedByStopSharingIntoAnotherLogsNoteStillUsesTheLogItWasMadeFrom() {
        val root = createTempDirectory("indagium-seq3-stop-sharing-source").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val original = assertNotNull(coordinator.begin("a", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
            val blockId = assertNotNull(coordinator.confirm(original))
            // The note also cites a's rows, which is how the note is "about" a once it sits in b's private note.
            state.upAnn("a") { it.copy(annotations = it.annotations.copy(blocks = it.annotations.blocks + AnnBlock.LogRef("r1", listOf(1), "rows"))) }
            val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))
            coordinator.close(original)
            assertTrue(state.stopSharing(hostId, "b"))

            // The diagram now sits in b's private note, but its rows are a's.
            val reopened = assertNotNull(coordinator.beginEdit("b", blockId))
            assertEquals("a", coordinator.sessions.single { it.id == reopened }.sourceTabId)
            coordinator.close(reopened)

            // With a closed, nothing else may stand in for it: b's own rows are not the diagram's.
            state.closeTab("a")
            val without = assertNotNull(coordinator.beginEdit("b", blockId))
            assertNull(coordinator.sessions.single { it.id == without }.sourceTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aDiagramMadeFromALogInThatLogsOwnNoteStillUsesIt() {
        val root = createTempDirectory("indagium-seq3-own-note-source").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            state.activateTab("b")
            val original = assertNotNull(coordinator.begin("b", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
            val blockId = assertNotNull(coordinator.confirm(original))
            coordinator.close(original)

            val reopened = assertNotNull(coordinator.beginEdit("b", blockId))
            assertEquals("b", coordinator.sessions.single { it.id == reopened }.sourceTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aDiagramInItsOwnLogsNoteStillUsesItAfterTheTabIsKnownByAnotherName() {
        val root = createTempDirectory("indagium-seq3-renamed-source").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val original = assertNotNull(coordinator.begin("a", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
            val blockId = assertNotNull(coordinator.confirm(original))
            coordinator.close(original)
            // The same log under another name: a capture tab titled by its device, a log relinked after the file was renamed.
            state.upTab("a") { it.copy(filename = "Pixel 7 · 10:42", sourcePath = File(root, "renamed.log").absolutePath) }

            assertNotNull(coordinator.beginEdit("a", blockId))
            assertEquals("a", coordinator.sessions.single().sourceTabId, "nothing says the diagram is about another log")
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }
}
