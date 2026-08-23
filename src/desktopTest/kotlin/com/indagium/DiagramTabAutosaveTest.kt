package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.ActiveSurface
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.TabRef
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

    // ── Tab strip order (WP-diagram-restore follow-up) ─────────────────────────────────────────
    //
    // TabBar itself renders the interleaved strip and isn't reachable from a plain unit test (no
    // Compose UI test harness in this project — see AppState.tabOrder's own doc for the "TabBar
    // mirrors into this field, AppState never drives rendering from it" split this relies on).
    // These instead drive AppState.tabOrder directly, exactly the way TabBar's three
    // `unifiedOrder = ...` sites do, and prove the round trip through tabOrderToken/restoreTabOrder
    // a real restart takes.

    @Test
    fun aDiagramTabDraggedBetweenTwoLogTabsStaysThereAfterRestore() {
        val dir = createTempDirectory("openlog-tab-order-restore").toFile()
        val logFile1 = File(dir, "app1.log").apply { writeText("x") }
        val logFile2 = File(dir, "app2.log").apply { writeText("x") }
        val cacheFile = File(dir, "state.cache")
        val libraryFile = File(dir, "library.cache")

        val state = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile))
        state.tabs = listOf(
            mkTab("log1", "app1.log", twoTagEntries()).copy(sourcePath = logFile1.absolutePath),
            mkTab("log2", "app2.log", twoTagEntries()).copy(sourcePath = logFile2.absolutePath),
        )
        state.activateTab("log1")
        val sessionId = state.seq3Sessions.begin("log1", setOf(1, 2))!!
        awaitGenerated(state, sessionId)
        val libraryItemId = state.seq3Sessions.sessions.single().libraryItemId!!

        // Mirrors what a drag that puts the diagram BETWEEN the two log tabs commits — same shape
        // TabBar.commitReorder writes into unifiedOrder (and, since this feature, state.tabOrder).
        state.tabOrder = listOf(TabRef.Log("log1"), TabRef.Diagram(sessionId), TabRef.Log("log2"))
        state.autosaveNow()

        val restored = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile), restoreOnCreate = true)

        val restoredSessionId = restored.seq3Sessions.sessions.single().id
        assertEquals(
            listOf(TabRef.Log("log1"), TabRef.Diagram(restoredSessionId), TabRef.Log("log2")),
            restored.tabOrder,
            "the diagram must reopen in the SAME position between the two log tabs, not appended after both",
        )
        // And the resolved id is real, not a stale one held over from before the restart.
        assertTrue(restored.seq3Sessions.sessions.single { it.libraryItemId == libraryItemId }.id == restoredSessionId)
    }

    @Test
    fun aTabOrderEntryForATabThatNoLongerExistsIsDroppedNotCarriedOverAsAGhost() {
        val dir = createTempDirectory("openlog-tab-order-restore-stale").toFile()
        val logFile = File(dir, "app.log").apply { writeText("x") }
        val cacheFile = File(dir, "state.cache")
        val libraryFile = File(dir, "library.cache")

        val state = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile))
        state.tabs = listOf(mkTab("log", "app.log", twoTagEntries()).copy(sourcePath = logFile.absolutePath))
        state.activateTab("log")
        // A log tab that was open when tabOrder was captured but is gone by the time autosaveNow()
        // actually serializes — same "closed before quit" shape restoreTabsFromAutosave already
        // handles for state.tabs itself; tabOrder must not leave a dangling reference to it.
        state.tabOrder = listOf(TabRef.Log("log"), TabRef.Log("closed-before-quit"))
        state.autosaveNow()

        val restored = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile), restoreOnCreate = true)

        assertEquals(listOf(TabRef.Log("log")), restored.tabOrder)
    }

    // ── Queue-panel view state (Wave 2.5) ───────────────────────────────────────────────────────
    //
    // The Messages/Lifelines/Artifacts section open/expanded flags, their drag-resized heights,
    // and the panel width used to be documented as deliberately ephemeral (Seq3ViewState's own
    // header) — these prove the "seq3View" autosave key carved out for them round-trips, and that
    // a cache written before the key existed degrades to the same constructed defaults rather than
    // crashing or leaving a partially-decoded state.

    @Test
    fun seq3ViewStatePersistsQueuePanelLayoutAcrossARestart() {
        val dir = createTempDirectory("openlog-diagram-view-restore").toFile()
        val logFile = File(dir, "app.log").apply { writeText("x") }
        val cacheFile = File(dir, "state.cache")
        val libraryFile = File(dir, "library.cache")

        val state = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile))
        state.tabs = listOf(mkTab("log", "app.log", twoTagEntries()).copy(sourcePath = logFile.absolutePath))
        state.activateTab("log")
        val sessionId = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, sessionId)
        val libraryItemId = state.seq3Sessions.sessions.single().libraryItemId!!

        // Flip every persisted field away from its constructed default so a bug that silently no-
        // ops the restore (leaving defaults in place) can't pass this test by accident.
        val view = state.seq3Sessions.viewState(sessionId)!!
        view.messagesSectionOpen = false
        view.messagesExpanded = false
        view.lifelinesSectionOpen = false
        view.lifelinesExpanded = false
        view.artifactsSectionOpen = false
        view.artifactsExpanded = true
        view.lifelinesSectionHeightDp = 300f
        view.artifactsSectionHeightDp = 250f
        view.panelWidthDp = 450f
        state.autosaveNow()

        val restored = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile), restoreOnCreate = true)
        val restoredSessionId = restored.seq3Sessions.sessions.single { it.libraryItemId == libraryItemId }.id
        val restoredView = restored.seq3Sessions.viewState(restoredSessionId)!!

        assertEquals(false, restoredView.messagesSectionOpen)
        assertEquals(false, restoredView.messagesExpanded)
        assertEquals(false, restoredView.lifelinesSectionOpen)
        assertEquals(false, restoredView.lifelinesExpanded)
        assertEquals(false, restoredView.artifactsSectionOpen)
        assertEquals(true, restoredView.artifactsExpanded)
        assertEquals(300f, restoredView.lifelinesSectionHeightDp)
        assertEquals(250f, restoredView.artifactsSectionHeightDp)
        assertEquals(450f, restoredView.panelWidthDp)
    }

    @Test
    fun aStoredHeightOutsideTheLiveDragBoundsIsReClampedOnRestoreNotTrustedVerbatim() {
        // Guards the "re-clamp through the existing helpers rather than trusting stored values"
        // requirement: hand-write a seq3View token whose heights sit outside what a live divider
        // drag could ever produce, and confirm restore pulls them back in bounds instead of
        // reproducing the out-of-range value.
        val dir = createTempDirectory("openlog-diagram-view-restore-clamp").toFile()
        val logFile = File(dir, "app.log").apply { writeText("x") }
        val cacheFile = File(dir, "state.cache")
        val libraryFile = File(dir, "library.cache")

        val state = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile))
        state.tabs = listOf(mkTab("log", "app.log", twoTagEntries()).copy(sourcePath = logFile.absolutePath))
        state.activateTab("log")
        val sessionId = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, sessionId)
        val view = state.seq3Sessions.viewState(sessionId)!!
        view.lifelinesSectionHeightDp = 9999f
        view.artifactsSectionHeightDp = -50f
        view.panelWidthDp = 1f
        state.autosaveNow()

        val restored = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile), restoreOnCreate = true)
        val restoredView = restored.seq3Sessions.viewState(restored.seq3Sessions.sessions.single().id)!!

        assertTrue(restoredView.lifelinesSectionHeightDp < 9999f, "must be clamped to the live drag's max, not stored verbatim")
        assertTrue(restoredView.artifactsSectionHeightDp > -50f, "must be clamped to the live drag's min, not stored verbatim")
        assertTrue(restoredView.panelWidthDp > 1f, "panel width must be clamped to PANEL_WIDTH_MIN_DP, not stored verbatim")
    }

    @Test
    fun legacyCacheWithNoSeq3ViewKeyRestoresQueuePanelLayoutToConstructedDefaults() {
        val dir = createTempDirectory("openlog-diagram-view-restore-legacy").toFile()
        val logFile = File(dir, "app.log").apply { writeText("x") }
        val cacheFile = File(dir, "state.cache")
        val libraryFile = File(dir, "library.cache")

        val state = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile))
        state.tabs = listOf(mkTab("log", "app.log", twoTagEntries()).copy(sourcePath = logFile.absolutePath))
        state.activateTab("log")
        val sessionId = state.seq3Sessions.begin("log", setOf(1, 2))!!
        awaitGenerated(state, sessionId)
        state.autosaveNow()

        // Strip the "seq3View" line out of the cache file to simulate a cache written before this
        // key existed — same "delete one key, keep the rest" technique the sibling diagramTabs
        // tests above rely on implicitly via a fresh cache, made explicit here since this key must
        // specifically be ABSENT rather than merely empty.
        val lines = cacheFile.readLines().filterNot { it.substringBefore('\t') == "seq3View" }
        cacheFile.writeText(lines.joinToString("\n") + "\n")

        val restored = AppState(cacheFile, diagramLibraryStore = DiagramLibraryStore(libraryFile), restoreOnCreate = true)

        assertEquals(1, restored.seq3Sessions.sessions.size, "fixture precondition: the diagram tab itself must still reopen")
        val restoredView = restored.seq3Sessions.viewState(restored.seq3Sessions.sessions.single().id)!!
        assertEquals(true, restoredView.messagesSectionOpen)
        assertEquals(true, restoredView.messagesExpanded)
        assertEquals(true, restoredView.lifelinesSectionOpen)
        assertEquals(true, restoredView.lifelinesExpanded)
        assertEquals(true, restoredView.artifactsSectionOpen)
        assertEquals(false, restoredView.artifactsExpanded)
        assertEquals(220f, restoredView.lifelinesSectionHeightDp)
        assertEquals(200f, restoredView.artifactsSectionHeightDp)
        assertEquals(392f, restoredView.panelWidthDp)
    }
}
