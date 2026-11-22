package com.indagium

import com.indagium.diagram3.updateSeq3NoteSourcePath
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogFileSection
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A shared diagram written before Share stamped its log (no source path, no library record) finds its log by file name. A log
 * showing another section of the file cannot be checked against the diagram's rows, so it is still the diagram's log; a loaded log
 * whose single section holds other rows is not.
 */
class Seq3UnstampedSectionedSourceTest {
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

    private fun otherRows(): List<LogEntry> = listOf(
        LogEntry(1, "11:00:00.000", LogLevel.I, "Other", "unrelated"),
        LogEntry(2, "11:00:00.010", LogLevel.I, "More", "rows"),
    )

    private fun state(root: File): AppState = AppState(
        File(root, "state.cache"),
        notesDir = File(root, "notes"),
        diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
    ).also {
        it.tabs = listOf(
            mkTab("a", "a.log", rows()).copy(sourcePath = File(root, "a.log").absolutePath),
            mkTab("b", "b.log", otherRows()).copy(sourcePath = File(root, "b.log").absolutePath),
        )
        it.activateTab("a")
    }

    /** A diagram made from a, shared with b, then taken back to the shape older builds wrote: no source path in the note. */
    private fun unstampedSharedDiagram(state: AppState): Pair<String, String> {
        val coordinator = state.seq3Sessions
        val original = assertNotNull(coordinator.begin("a", setOf(1, 2)))
        await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
        val blockId = assertNotNull(coordinator.confirm(original))
        coordinator.close(original)
        val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))
        val block = state.noteTab(hostId)!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note
        state.updateBlock(hostId, blockId, assertNotNull(updateSeq3NoteSourcePath(block.text, null)))
        return hostId to blockId
    }

    @Test
    fun anUnstampedSharedDiagramStillFindsItsLogWhenThatLogShowsAnotherSection() {
        val root = createTempDirectory("indagium-seq3-unstamped-section").toFile()
        val state = state(root)
        try {
            val coordinator = state.seq3Sessions
            val (hostId, blockId) = unstampedSharedDiagram(state)
            assertEquals("a", coordinator.sourceTabIdForNote(hostId, blockId), "fixture: the rows of section 1 agree")

            // The tab switched to its second section: the same ids hold that section's rows.
            val section2 = mkTab("a", "a.log", otherRows())
            state.upTab("a") {
                it.copy(
                    logData = section2.logData,
                    rmap = section2.rmap,
                    logSections = listOf(LogFileSection("s1", "Main", "", 2), LogFileSection("s2", "Events", "", 2)),
                    selectedLogSectionId = "s2",
                )
            }

            assertEquals("a", coordinator.sourceTabIdForNote(hostId, blockId), "a section switch cannot be checked against the rows")
            val opened = assertNotNull(coordinator.beginEdit(hostId, blockId))
            assertEquals("a", coordinator.sessions.single { it.id == opened }.sourceTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun anUnstampedSharedDiagramDoesNotTakeALoadedSingleSectionLogWithOtherRows() {
        val root = createTempDirectory("indagium-seq3-unstamped-other-rows").toFile()
        val state = state(root)
        try {
            val coordinator = state.seq3Sessions
            val (hostId, blockId) = unstampedSharedDiagram(state)
            val other = mkTab("a", "a.log", otherRows())
            state.upTab("a") { it.copy(logData = other.logData, rmap = other.rmap) }

            assertNull(coordinator.sourceTabIdForNote(hostId, blockId), "a loaded log whose rows disagree is another log")
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }
}
