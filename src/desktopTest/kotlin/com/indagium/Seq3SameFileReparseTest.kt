package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogFileSection
import com.indagium.model.LogLevel
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.mkTab
import com.indagium.ui.shareTabNote
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A diagram names its log by file path, but several open tabs can hold that path (a log and its reparse under another profile):
 * only a tab whose rows are the ones the diagram was made from is its log. The diagram's own occurrences are the evidence, since
 * the content fingerprint changes whenever the log grows.
 */
class Seq3SameFileReparseTest {
    private companion object {
        const val WAIT_MS = 4_000L
        const val PID = 7
        const val TID = 11
        const val OTHER_ID_BASE = 10
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not become true within ${WAIT_MS}ms")
    }

    private fun rowsOfTheDiagramsLog(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", PID, TID),
        LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", PID, TID),
    )

    /** The same file read under another profile: other ids, or the same ids with other text. */
    private fun reparsedRows(sameIds: Boolean): List<LogEntry> = listOf(
        LogEntry(if (sameIds) 1 else OTHER_ID_BASE + 1, "10:00:00.000", LogLevel.I, "Raw", "10:00:00.000 I Producer: start op"),
        LogEntry(if (sameIds) 2 else OTHER_ID_BASE + 2, "10:00:00.050", LogLevel.I, "Raw", "10:00:00.050 I Consumer: handle op"),
    )

    private fun state(root: File, path: String, reparseSameIds: Boolean = false): AppState = AppState(
        File(root, "state.cache"),
        notesDir = File(root, "notes"),
        diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
    ).also {
        it.tabs = listOf(
            mkTab("a", "a.log", rowsOfTheDiagramsLog()).copy(sourcePath = path),
            mkTab("a2", "a.log", reparsedRows(reparseSameIds)).copy(sourcePath = path),
        )
        it.activateTab("a")
    }

    private fun sharedDiagram(state: AppState, libraryRoot: File): Pair<String, String> {
        val coordinator = state.seq3Sessions
        val id = assertNotNull(coordinator.begin("a", setOf(1, 2)))
        await { coordinator.sessions.firstOrNull { it.id == id }?.generating == false }
        assertNotNull(coordinator.confirm(id))
        coordinator.close(id)
        // The note holds a link to the library record, which names the log by path and content fingerprint.
        val itemId = DiagramLibraryStore(File(libraryRoot, "library.cache")).all().single().id
        val blockId = assertNotNull(coordinator.attachLibraryLink("a", itemId))
        return assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "a2"))) to blockId
    }

    private fun navigate(state: AppState, noteTabId: String, blockId: String, entryId: Int): Boolean =
        runBlocking { state.seq3Sessions.navigateToOccurrence(noteTabId, blockId, entryId) }

    @Test
    fun aReparseOfTheSameFileWithOtherIdsIsNotTheDiagramsLogOnceTheOriginalTabCloses() = reparseIsNotTheLog(sameIds = false)

    @Test
    fun aReparseOfTheSameFileWithTheSameIdsButOtherTextIsNotTheDiagramsLogEither() = reparseIsNotTheLog(sameIds = true)

    private fun reparseIsNotTheLog(sameIds: Boolean) {
        val root = createTempDirectory("indagium-seq3-reparse").toFile()
        val state = state(root, File(root, "a.log").absolutePath, sameIds)
        try {
            val (hostId, blockId) = sharedDiagram(state, root)
            assertEquals("a", state.seq3Sessions.sourceTabIdForNote(hostId, blockId))

            state.closeTab("a")

            assertNull(state.seq3Sessions.sourceTabIdForNote(hostId, blockId), "never the reparse")
            state.activateTab("a2")
            assertFalse(navigate(state, hostId, blockId, 1))
            assertEquals("The diagram's log is not open.", state.pendingDiagramNotice?.body)
            assertTrue(state.tab("a2")!!.selected.isEmpty(), "no row of the reparse is selected")
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun theDiagramsOwnLogStillResolvesAfterItGrewAndItsFingerprintChanged() {
        val root = createTempDirectory("indagium-seq3-reparse-grown").toFile()
        val state = state(root, File(root, "a.log").absolutePath)
        try {
            val (hostId, blockId) = sharedDiagram(state, root)
            val grown = mkTab(
                "a", "a.log",
                rowsOfTheDiagramsLog() + LogEntry(3, "10:00:01.000", LogLevel.I, "Producer", "later", PID, TID),
            )
            state.upTab("a") { it.copy(logData = grown.logData, rmap = grown.rmap) }

            assertEquals("a", state.seq3Sessions.sourceTabIdForNote(hostId, blockId))
            state.activateTab("a2")
            assertTrue(navigate(state, hostId, blockId, 2))
            assertEquals(setOf(2), state.tab("a")!!.selected)
            assertTrue(state.tab("a2")!!.selected.isEmpty())
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun anUnloadedSoleTabIsAcceptedAndARowThatChangedWhenItLoadsGivesTheNoticeAndSelectsNothing() {
        val root = createTempDirectory("indagium-seq3-reparse-unloaded").toFile()
        var now = 0L
        val state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
            monotonicNowNanos = { now },
        )
        try {
            // The file on disk is no longer the log the diagram was made from: row 2 reads differently.
            val file = File(root, "a.log").apply {
                writeText(
                    "06-26 10:00:00.000     $PID    $TID I Producer: start op\n" +
                        "06-26 10:00:00.050     $PID    $TID I Consumer: something else\n",
                )
            }
            state.tabs = listOf(
                mkTab("a", "a.log", rowsOfTheDiagramsLog()).copy(sourcePath = file.absolutePath),
                mkTab("b", "b.log", rowsOfTheDiagramsLog().take(1)).copy(sourcePath = File(root, "b.log").absolutePath),
            )
            state.settings = state.settings.copy(inactiveTabUnloadMinutes = 30)
            state.activateTab("a")
            val coordinator = state.seq3Sessions
            val id = assertNotNull(coordinator.begin("a", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == id }?.generating == false }
            val blockId = assertNotNull(coordinator.confirm(id))
            coordinator.close(id)
            val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))
            state.activateTab("b")
            state.sweepInactiveTabs()
            now = TimeUnit.MINUTES.toNanos(31)
            await {
                state.sweepInactiveTabs()
                state.tab("a")?.contentState == TabContentState.UNLOADED
            }
            assertEquals("a", coordinator.sourceTabIdForNote(hostId, blockId), "an unloaded tab cannot be checked: accepted as the sole tab")

            assertFalse(navigate(state, hostId, blockId, 2))

            assertEquals("The diagram's log has changed since it was made.", state.pendingDiagramNotice?.body)
            assertTrue(state.tab("a")!!.selected.isEmpty())
            assertTrue(state.tab("b")!!.selected.isEmpty())
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aSectionedTabShowingAnotherSectionIsStillTheDiagramsLogAndTheClickSaysWhichSectionIsMissing() {
        val root = createTempDirectory("indagium-seq3-reparse-section").toFile()
        val state = state(root, File(root, "a.log").absolutePath)
        try {
            val (hostId, blockId) = sharedDiagram(state, root)
            state.closeTab("a2")
            // The same tab after a switch to its other section: the same ids now hold that section's rows.
            val other = mkTab("a", "a.log", reparsedRows(sameIds = true))
            state.upTab("a") {
                it.copy(
                    logData = other.logData,
                    rmap = other.rmap,
                    logSections = listOf(LogFileSection("s1", "Main", "", 2), LogFileSection("s2", "Events", "", 2)),
                    selectedLogSectionId = "s2",
                )
            }

            assertEquals("a", state.seq3Sessions.sourceTabIdForNote(hostId, blockId), "a diagram does not record its section")
            assertFalse(navigate(state, hostId, blockId, 1))
            assertEquals("Row not in this section", state.pendingDiagramNotice?.title)
            assertTrue(state.tab("a")!!.selected.isEmpty())
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }
}
