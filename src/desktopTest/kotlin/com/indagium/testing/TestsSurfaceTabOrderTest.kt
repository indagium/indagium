package com.indagium.testing

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.ActiveSurface
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.TESTS_TAB_RAW_ID
import com.indagium.ui.TabRef
import com.indagium.ui.mkTab
import com.indagium.ui.partitionTabOrder
import com.indagium.ui.rawId
import com.indagium.ui.reconcileTabOrder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Tests workspace as a tab: opening, focusing and closing it through AppState, where it sits in the strip's
 * merged order, and the "T" letter of the autosave tab-order token (appended-last, so older builds drop it).
 */
class TestsSurfaceTabOrderTest {
    private fun entries() = listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "Tag", "hello", 1, 1))

    private fun appState(dir: File, restore: Boolean = false) = AppState(
        autosaveFile = File(dir, "state.cache"),
        autoExportNotes = false,
        notesDir = File(dir, "notes"),
        archiveCacheDir = File(dir, "archive"),
        customCommandsDir = File(dir, "commands"),
        controlTokenFile = File(dir, "token"),
        sourceIndexFile = File(dir, "source-index"),
        testingDir = File(dir, "testing"),
        diagramLibraryStore = DiagramLibraryStore(File(dir, "library.cache")),
        restoreOnCreate = restore,
    )

    private fun withLogTabs(state: AppState, dir: File, vararg ids: String) {
        state.tabs = ids.map { id ->
            val file = File(dir, "$id.log").apply { writeText("x") }
            mkTab(id, "$id.log", entries()).copy(sourcePath = file.absolutePath)
        }
        state.activateTab(ids.first())
    }

    // ── AppState: open / close / focus ───────────────────────────────

    @Test
    fun openingTheTestsTabMakesItTheActiveSurfaceAndOpeningAgainJustFocusesIt() {
        val dir = createTempDirectory("tests-surface-open").toFile()
        val state = appState(dir)
        try {
            withLogTabs(state, dir, "log1", "log2")
            assertFalse(state.testsTabOpen)
            assertFalse(state.testsSurfaceActive)

            state.openTestsTab()
            assertTrue(state.testsTabOpen)
            assertEquals(ActiveSurface.Tests, state.activeSurface)
            assertTrue(state.testsSurfaceActive)

            state.activateTab("log2")
            assertTrue(state.testsTabOpen, "switching to a log tab does not close the Tests tab")
            assertFalse(state.testsSurfaceActive)

            state.openTestsTab()
            assertEquals(ActiveSurface.Tests, state.activeSurface, "opening an open Tests tab focuses it")
            assertEquals(listOf("log1", "log2"), state.tabs.map { it.id }, "it never touches the log tabs")
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun closingTheTestsTabReturnsToTheSurfaceItWasOpenedFrom() {
        val dir = createTempDirectory("tests-surface-close").toFile()
        val state = appState(dir)
        try {
            withLogTabs(state, dir, "log1", "log2")
            state.activateTab("log2")
            state.openTestsTab()

            state.closeTestsTab()

            assertFalse(state.testsTabOpen)
            assertEquals(ActiveSurface.Log("log2"), state.activeSurface)
            assertEquals("log2", state.activeTabId)
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun closingABackgroundTestsTabLeavesTheCurrentSurfaceAlone() {
        val dir = createTempDirectory("tests-surface-close-bg").toFile()
        val state = appState(dir)
        try {
            withLogTabs(state, dir, "log1", "log2")
            state.openTestsTab()
            state.activateTab("log1")

            state.closeTestsTab()

            assertEquals(ActiveSurface.Log("log1"), state.activeSurface)
            state.closeTestsTab()
            assertFalse(state.testsTabOpen, "closing twice is harmless")
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun closingTheTestsTabWithNothingElseOpenClearsTheSurface() {
        val dir = createTempDirectory("tests-surface-alone").toFile()
        val state = appState(dir)
        try {
            state.openTestsTab()
            assertTrue(state.tabs.isEmpty())

            state.closeTestsTab()

            assertNull(state.activeSurface)
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun theHomeTabIsNotAutoOpenedWhileTheTestsTabIsTheOnlyThingOpen() {
        val dir = createTempDirectory("tests-surface-home").toFile()
        val state = appState(dir)
        try {
            state.openTestsTab()

            state.ensureHomeTab()
            assertTrue(state.tabs.isEmpty(), "Tests counts as something to show")

            state.closeTestsTab()
            state.ensureHomeTab()
            assertEquals(1, state.tabs.size, "with nothing left the home tab opens as before")
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    // ── Strip order ──────────────────────────────────────────────────

    @Test
    fun reconcileAppendsANewlyOpenedTestsTabAndKeepsItsSlot() {
        val appended = reconcileTabOrder(listOf(TabRef.Log("a"), TabRef.Log("b")), listOf("a", "b"), emptyList(), testsOpen = true)
        assertEquals(listOf(TabRef.Log("a"), TabRef.Log("b"), TabRef.Tests), appended)

        val dragged = listOf(TabRef.Log("a"), TabRef.Tests, TabRef.Log("b"))
        assertEquals(dragged, reconcileTabOrder(dragged, listOf("a", "b"), emptyList(), testsOpen = true))
    }

    @Test
    fun reconcileDropsTheTestsTabOnceItIsClosedAndNeverInventsOne() {
        val previous = listOf(TabRef.Log("a"), TabRef.Tests)
        assertEquals(listOf(TabRef.Log("a")), reconcileTabOrder(previous, listOf("a"), emptyList(), testsOpen = false))
        assertEquals(listOf(TabRef.Log("a")), reconcileTabOrder(listOf(TabRef.Log("a")), listOf("a"), emptyList()))
    }

    @Test
    fun reconcileKeepsNewDiagramsAndTestsInTheirOwnOrder() {
        val reconciled = reconcileTabOrder(listOf(TabRef.Tests), listOf("a"), listOf("seq3-1"), testsOpen = true)

        assertEquals(listOf(TabRef.Tests, TabRef.Log("a"), TabRef.Diagram("seq3-1")), reconciled)
    }

    @Test
    fun theTestsTabHasItsOwnRawIdAndStaysOutOfBothBackingStores() {
        assertEquals(TESTS_TAB_RAW_ID, TabRef.Tests.rawId())
        val (logs, diagrams) = partitionTabOrder(listOf(TabRef.Log("a"), TabRef.Tests, TabRef.Diagram("seq3-1")))
        assertEquals(listOf("a"), logs)
        assertEquals(listOf("seq3-1"), diagrams)
    }

    // ── Autosave ─────────────────────────────────────────────────────

    @Test
    fun theTestsTabAndItsPositionSurviveARestart() {
        val dir = createTempDirectory("tests-surface-restore").toFile()
        val state = appState(dir)
        try {
            withLogTabs(state, dir, "log1", "log2")
            state.openTestsTab()
            // What TabBar mirrors after the user drags Tests between the two log tabs.
            state.tabOrder = listOf(TabRef.Log("log1"), TabRef.Tests, TabRef.Log("log2"))
            state.autosaveNow()
        } finally {
            state.close()
        }

        val restored = appState(dir, restore = true)
        try {
            assertTrue(restored.testsTabOpen, "a T entry reopens the Tests tab")
            assertEquals(listOf(TabRef.Log("log1"), TabRef.Tests, TabRef.Log("log2")), restored.tabOrder)
            assertFalse(restored.testsSurfaceActive, "restoring it does not steal the content area")
        } finally {
            restored.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun anOpenTestsTabThatTheStripHasNotMirroredYetIsStillWritten() {
        val dir = createTempDirectory("tests-surface-unmirrored").toFile()
        val state = appState(dir)
        try {
            withLogTabs(state, dir, "log1")
            state.openTestsTab()
            assertTrue(state.tabOrder.isEmpty(), "fixture precondition: no TabBar has mirrored the order")
            state.autosaveNow()
        } finally {
            state.close()
        }

        val restored = appState(dir, restore = true)
        try {
            assertTrue(restored.testsTabOpen)
            assertTrue(TabRef.Tests in restored.tabOrder)
        } finally {
            restored.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aCacheWithoutATestsEntryRestoresExactlyAsBefore() {
        val dir = createTempDirectory("tests-surface-no-t").toFile()
        val state = appState(dir)
        try {
            withLogTabs(state, dir, "log1", "log2")
            state.tabOrder = listOf(TabRef.Log("log2"), TabRef.Log("log1"))
            state.autosaveNow()
        } finally {
            state.close()
        }

        val restored = appState(dir, restore = true)
        try {
            assertFalse(restored.testsTabOpen)
            assertEquals(listOf(TabRef.Log("log2"), TabRef.Log("log1")), restored.tabOrder)
        } finally {
            restored.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aClosedTestsTabIsNotWrittenAndAnUnknownLetterIsDropped() {
        val dir = createTempDirectory("tests-surface-closed").toFile()
        val state = appState(dir)
        try {
            withLogTabs(state, dir, "log1")
            state.openTestsTab()
            state.tabOrder = listOf(TabRef.Log("log1"), TabRef.Tests)
            state.closeTestsTab()
            state.autosaveNow()
        } finally {
            state.close()
        }

        val restored = appState(dir, restore = true)
        try {
            assertFalse(restored.testsTabOpen, "a Tests entry in a stale order must not reopen a closed tab")
            assertEquals(listOf(TabRef.Log("log1")), restored.tabOrder)
        } finally {
            restored.close()
            dir.deleteRecursively()
        }
    }
}
