package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.mkTab
import com.indagium.ui.shareTabNote
import com.indagium.ui.stopSharing
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

/** A click on a diagram preview's occurrence selects the row in the diagram's own log, not in the tab the note is viewed from. */
class Seq3DiagramPreviewNavigationTest {
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

    /** The click handler launches this on the app's scope; a test waits for it. */
    private fun navigate(state: AppState, noteTabId: String, blockId: String, entryId: Int): Boolean =
        runBlocking { state.seq3Sessions.navigateToOccurrence(noteTabId, blockId, entryId) }

    /** Two logs whose rows share the ids 1 and 2, so a selection in the wrong tab would look the same by id alone. */
    private fun state(root: File): AppState = AppState(
        File(root, "state.cache"),
        notesDir = File(root, "notes"),
        diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
    ).also {
        val a = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", PID, TID),
            LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", PID, TID),
        )
        val b = listOf(LogEntry(1, "11:00:00.000", LogLevel.I, "Other", "unrelated"), LogEntry(2, "11:00:00.010", LogLevel.I, "More", "rows"))
        it.tabs = listOf(
            mkTab("a", "a.log", a).copy(sourcePath = File(root, "a.log").absolutePath),
            mkTab("b", "b.log", b).copy(sourcePath = File(root, "b.log").absolutePath),
        )
        it.activateTab("a")
    }

    private fun confirmedDiagram(state: AppState, tabId: String): String {
        val coordinator = state.seq3Sessions
        val id = assertNotNull(coordinator.begin(tabId, setOf(1, 2)))
        await { coordinator.sessions.firstOrNull { it.id == id }?.generating == false }
        val blockId = assertNotNull(coordinator.confirm(id))
        coordinator.close(id)
        return blockId
    }

    @Test
    fun aSharedNotesDiagramNavigatesInItsOwnLogNotInTheTabTheNoteIsViewedFrom() {
        val root = createTempDirectory("indagium-seq3-preview-nav").toFile()
        val state = state(root)
        try {
            val blockId = confirmedDiagram(state, "a")
            val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))
            state.activateTab("b")

            assertTrue(navigate(state, hostId, blockId, 2))

            assertEquals(setOf(2), state.tab("a")!!.selected)
            assertTrue(state.tab("b")!!.selected.isEmpty(), "the viewing tab's rows are not the diagram's")
            assertEquals("a", state.activeTabId)
            assertNull(state.pendingDiagramNotice)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aMovedDiagramNavigatesInItsLogAfterStopSharing() {
        val root = createTempDirectory("indagium-seq3-preview-nav-moved").toFile()
        val state = state(root)
        try {
            val blockId = confirmedDiagram(state, "a")
            val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))
            assertTrue(state.stopSharing(hostId, "b"))
            state.activateTab("b")

            assertTrue(navigate(state, "b", blockId, 1))

            assertEquals(setOf(1), state.tab("a")!!.selected)
            assertTrue(state.tab("b")!!.selected.isEmpty())
            assertEquals("a", state.activeTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aPrivateOwnLogDiagramStillNavigatesInItsOwnTab() {
        val root = createTempDirectory("indagium-seq3-preview-nav-own").toFile()
        val state = state(root)
        try {
            state.activateTab("b")
            val blockId = confirmedDiagram(state, "b")

            assertTrue(navigate(state, "b", blockId, 2))

            assertEquals(setOf(2), state.tab("b")!!.selected)
            assertTrue(state.tab("a")!!.selected.isEmpty())
            assertNull(state.pendingDiagramNotice)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aDiagramWhoseLogIsNotOpenSaysSoAndSelectsNothing() {
        val root = createTempDirectory("indagium-seq3-preview-nav-closed").toFile()
        val state = state(root)
        try {
            val blockId = confirmedDiagram(state, "a")
            val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))
            state.closeTab("a")
            state.activateTab("b")

            assertFalse(navigate(state, hostId, blockId, 1))
            assertEquals("The diagram's log is not open.", state.pendingDiagramNotice?.body)
            assertTrue(state.tab("b")!!.selected.isEmpty(), "never b's row 1")

            state.pendingDiagramNotice = null
            assertTrue(state.stopSharing(hostId, "b"))
            assertFalse(navigate(state, "b", blockId, 1))
            assertNotNull(state.pendingDiagramNotice)
            assertTrue(state.tab("b")!!.selected.isEmpty())
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aDiagramsLogWhoseRowsAreNotResidentIsLoadedAndTheEntryIsSelectedAfterwards() {
        val root = createTempDirectory("indagium-seq3-preview-nav-unloaded").toFile()
        var now = 0L
        val state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
            monotonicNowNanos = { now },
        )
        try {
            // The file holds the rows the diagram was made from (its occurrences are checked against them after the load).
            val file = File(root, "a.log").apply {
                writeText(
                    "06-26 10:00:00.000     $PID    $TID I Producer: start op\n" +
                        "06-26 10:00:00.050     $PID    $TID I Consumer: handle op\n" +
                        "06-26 10:00:00.060     $PID    $TID I Consumer: more\n",
                )
            }
            val rows = listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", PID, TID),
                LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", PID, TID),
            )
            state.tabs = listOf(
                mkTab("a", "a.log", rows).copy(sourcePath = file.absolutePath),
                mkTab("b", "b.log", rows.take(1)).copy(sourcePath = File(root, "b.log").absolutePath),
            )
            state.settings = state.settings.copy(inactiveTabUnloadMinutes = 30)
            state.activateTab("a")
            val blockId = confirmedDiagram(state, "a")
            val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))
            state.activateTab("b")
            state.sweepInactiveTabs()
            now = TimeUnit.MINUTES.toNanos(31)
            await {
                state.sweepInactiveTabs()
                state.tab("a")?.contentState == TabContentState.UNLOADED
            }
            assertTrue(state.tab("a")!!.rmap.isEmpty(), "fixture: the rows are not resident")

            assertTrue(navigate(state, hostId, blockId, 2))

            val loaded = state.tab("a")!!
            assertEquals(TabContentState.LOADED, loaded.contentState)
            assertEquals(setOf(2), loaded.selected)
            assertEquals("a", state.activeTabId)
            assertTrue(state.tab("b")!!.selected.isEmpty())
            assertNull(state.pendingDiagramNotice)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun anEntryTheDiagramsLogDoesNotHoldSaysSoAndSelectsNothing() {
        val root = createTempDirectory("indagium-seq3-preview-nav-missing-row").toFile()
        val state = state(root)
        try {
            val blockId = confirmedDiagram(state, "a")
            val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))
            state.activateTab("b")

            assertFalse(navigate(state, hostId, blockId, 99))

            assertEquals("Row not found", state.pendingDiagramNotice?.title)
            assertTrue(state.tab("a")!!.selected.isEmpty())
            assertTrue(state.tab("b")!!.selected.isEmpty())
            assertEquals("b", state.activeTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }
}
