package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.ActiveSurface
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.mkTab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the autosave gap a user actually hit: reopening the app restored every log tab but never
 * which diagram workspace tab(s) were open on top of them, because `AppState.activeSurface` and
 * `Seq3Session.sessions` were both plain in-memory Compose state with no autosave key of their
 * own. See `AutosaveCodec.kt`'s own "Diagram workspace tabs" header for the design (a session
 * needs only its libraryItemId + sourceTabId to come back — the rest is rebuilt from the already-
 * durable `DiagramLibraryStore`).
 *
 * Same `stateFor`/`awaitGenerated` shape `Seq3SessionTest.kt` already uses, extended to build a
 * SECOND `AppState` sharing the same autosave/library files — the actual "quit, relaunch" shape a
 * production restart takes, not just a decode-in-isolation unit test.
 */
class DiagramTabAutosaveTest {
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

    // Same fixture shape as Seq3SessionTest's own twoTagEntries(): same-thread, two-tag, close
    // enough in time for Seq3Generator's thread-handoff inference to reliably yield >=2 lifelines.
    private fun twoTagEntries(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", SHARED_PID, SHARED_TID),
        LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", SHARED_PID, SHARED_TID),
    )

    private fun awaitGenerated(state: AppState, id: String) =
        await { state.seq3Sessions.sessions.firstOrNull { it.id == id }?.generating == false }

    @Test
    fun autosaveReopensAPreviouslyOpenDiagramTabAsTheActiveSurface() {
        val dir = createTempDirectory("openlog-diagram-tab-restore").toFile()
        // A real backing file: tabShellFromToken() drops any restored tab whose sourcePath doesn't
        // exist on disk (see AutosaveGoldenV1Test's own doc on that same limitation) — needed here
        // so restoreDiagramTabs' `appState.tab(tabId) != null` guard actually finds the tab.
        val logFile = File(dir, "app.log").apply { writeText("irrelevant: logData below is fixture-built, never re-parsed from this file") }
        val cacheFile = File(dir, "state.cache")
        val libraryFile = File(dir, "library.cache")

        val state = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile))
        state.tabs = listOf(mkTab("log", "app.log", twoTagEntries()).copy(sourcePath = logFile.absolutePath))
        state.activateTab("log")
        val sessionId = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, sessionId)
        val libraryItemId = state.seq3Sessions.sessions.single().libraryItemId
        assertTrue(libraryItemId != null, "fixture precondition: generation must have saved a library draft")
        assertEquals(ActiveSurface.Diagram3(sessionId), state.activeSurface, "fixture precondition")
        state.autosaveNow()

        val restored = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile), restoreOnCreate = true)

        assertEquals(1, restored.seq3Sessions.sessions.size, "the diagram tab must reopen on restore")
        val restoredSession = restored.seq3Sessions.sessions.single()
        assertEquals(libraryItemId, restoredSession.libraryItemId, "must reopen the SAME saved draft, not a fresh one")
        assertEquals("log", restoredSession.sourceTabId, "must relink to the same log tab (LogTab.id is stable across a restart), not reopen unlinked")
        assertEquals(
            ActiveSurface.Diagram3(restoredSession.id),
            restored.activeSurface,
            "the diagram must be the active surface again, not fall back to the log view",
        )
    }

    @Test
    fun autosaveWithNoDiagramTabsOpenLeavesActiveSurfaceOnTheLogView() {
        val dir = createTempDirectory("openlog-diagram-tab-restore-none").toFile()
        val cacheFile = File(dir, "state.cache")
        val libraryFile = File(dir, "library.cache")
        val state = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile))
        state.tabs = listOf(mkTab("log", "app.log", twoTagEntries()))
        state.activateTab("log")
        state.autosaveNow()

        val restored = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile), restoreOnCreate = true)

        assertTrue(restored.seq3Sessions.sessions.isEmpty(), "nothing to reopen — no diagram was ever open")
        assertNull(restored.activeSurface, "no diagram tab data at all (not even an empty \"diagramTabs\" line worth acting on) — activeSurface keeps its plain default, same as before this feature existed")
    }

    @Test
    fun theSessionActiveAtQuitStaysActiveAfterRestoreNotMerelyTheLastOneReopened() {
        // openLibraryItem's own side effect sets activeSurface on EVERY successful open — restoring
        // two sessions would otherwise always leave the LAST one reopened active, regardless of
        // which one the user actually had focused when they quit. This proves restoreActiveDiagram
        // corrects that back to the true one.
        val dir = createTempDirectory("openlog-diagram-tab-restore-two").toFile()
        val logFile = File(dir, "app.log").apply { writeText("x") }
        val cacheFile = File(dir, "state.cache")
        val libraryFile = File(dir, "library.cache")
        val state = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile))
        state.tabs = listOf(mkTab("log", "app.log", twoTagEntries()).copy(sourcePath = logFile.absolutePath))
        state.activateTab("log")

        val firstId = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, firstId)
        val secondId = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, secondId)
        assertEquals(ActiveSurface.Diagram3(secondId), state.activeSurface, "fixture precondition: begin() activates the session it just opened")
        val secondLibraryItemId = state.seq3Sessions.sessions.single { it.id == secondId }.libraryItemId
        state.autosaveNow()

        val restored = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile), restoreOnCreate = true)

        assertEquals(2, restored.seq3Sessions.sessions.size)
        val restoredSecond = restored.seq3Sessions.sessions.single { it.libraryItemId == secondLibraryItemId }
        assertEquals(
            ActiveSurface.Diagram3(restoredSecond.id),
            restored.activeSurface,
            "the session active at quit must stay active — not just whichever reopened last",
        )
    }
}
