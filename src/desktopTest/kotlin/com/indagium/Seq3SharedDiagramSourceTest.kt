package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.mkTab
import com.indagium.ui.shareTabNote
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A diagram opened from a shared note finds its log among several open tabs of the same file by the content it was made from. */
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
}
