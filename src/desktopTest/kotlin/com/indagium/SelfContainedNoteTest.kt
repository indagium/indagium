package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.annotationsFromToken
import com.indagium.ui.mkTab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Selected note excerpts retain their rows and presentation metadata at creation, so closing the
 * source tab while the editor is open or later reopening the note without a log cannot drop the
 * evidence. This also keeps the existing save-time materialization path covered.
 */
class SelfContainedNoteTest {
    private var openState: AppState? = null

    @AfterTest
    fun tearDown() {
        openState?.close()
    }

    private fun newState(): AppState {
        // notesDir isolated from AppState()'s default (shared across this whole gradle test run —
        // see build.gradle.kts' testHomeDir sandboxing comment): upAnn's overwrite gate now defers
        // a mutation instead of committing it whenever its export target already exists on disk
        // (see AppState.upAnn/PendingNoteOverwrite), so another test elsewhere in the suite that
        // already exported this same tab's default "sample_analysis.md" into the shared sandbox
        // notes dir would make confirmAddAnn below non-deterministically defer instead of commit —
        // this file is about sourceEntries materialization, not the overwrite prompt.
        val state = AppState(
            autosaveFile = File.createTempFile("openlog-self-contained-note-test", ".cache"),
            notesDir = createTempDirectory("openlog-self-contained-notes-dir").toFile(),
        )
        openState = state
        return state
    }

    private val sampleEntries = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "App", "first evidence line"),
        LogEntry(2, "10:00:00.100", LogLevel.W, "App", "second evidence line"),
    )

    @Test
    fun aSavedNotesLogRefBlockCarriesItsRowsAndStillRendersWithAnEmptyRmap() {
        val state = newState()
        state.tabs = listOf(mkTab("t1", "sample.log", sampleEntries))
        state.confirmAddAnn(targetTabId = "t1", sourceTabId = "t1", logIds = listOf(1, 2), caption = "evidence", sourceFilename = null)
        val captured = state.tab("t1")!!.annotations.blocks.single() as AnnBlock.LogRef
        assertEquals(sampleEntries, captured.sourceEntries, "the excerpt is frozen before the editor closes")
        assertTrue(captured.presentation != null, "presentation metadata is frozen with its rows")
        val outFile = File.createTempFile("openlog-self-contained-note", ".ann")

        assertTrue(state.saveAnnotationsTo("t1", outFile))

        val restored = requireNotNull(outFile.readText().annotationsFromToken())
        val restoredBlock = restored.blocks.single() as AnnBlock.LogRef
        assertEquals(sampleEntries, restoredBlock.sourceEntries, "the saved note must carry its own rows")

        // This is exactly the expression every renderer uses (AnnotationPanel.kt/utils/Filter.kt/
        // utils/AnnotationHtml.kt: `block.sourceEntries ?: block.logIds.mapNotNull { tab.rmap[it] }`)
        // — with an EMPTY rmap, standing in for a log-less tab's own rmap, it must still resolve to
        // the real rows because sourceEntries now short-circuits the (empty) rmap lookup entirely.
        val loglessRmap = emptyMap<Int, LogEntry>()
        val resolvedInLoglessTab = restoredBlock.sourceEntries ?: restoredBlock.logIds.mapNotNull { loglessRmap[it] }
        assertEquals(sampleEntries, resolvedInLoglessTab)
    }

    @Test
    fun inMemorySnapshotIsUnchangedBySaving() {
        val state = newState()
        state.tabs = listOf(mkTab("t1", "sample.log", sampleEntries))
        state.confirmAddAnn(targetTabId = "t1", sourceTabId = "t1", logIds = listOf(1, 2), caption = "evidence", sourceFilename = null)
        val outFile = File.createTempFile("openlog-self-contained-note-inmemory", ".ann")

        assertTrue(state.saveAnnotationsTo("t1", outFile))

        // Captured rows are already part of the live snapshot. Save may materialize other legacy
        // rows into its output, but it must leave this selected excerpt unchanged in memory.
        val liveBlock = state.tab("t1")!!.annotations.blocks.single() as AnnBlock.LogRef
        assertEquals(sampleEntries, liveBlock.sourceEntries)
        assertTrue(liveBlock.presentation != null)
    }

    @Test
    fun aBlockThatAlreadyCarriesSourceEntriesKeepsWhatItHas() {
        // Cross-file/compare-mode blocks already carry sourceEntries from a DIFFERENT tab's rmap
        // (confirmAddAnn's crossFile branch) — saving must never overwrite that with a re-resolve
        // against the saving tab's own rmap, which could point at entirely different rows.
        val state = newState()
        val otherTabEntries = listOf(LogEntry(9, "11:00:00.000", LogLevel.E, "Other", "from a different file"))
        state.tabs = listOf(
            mkTab("t1", "sample.log", sampleEntries),
            mkTab("t2", "other.log", otherTabEntries),
        )
        state.confirmAddAnn(targetTabId = "t1", sourceTabId = "t2", logIds = listOf(9), caption = "cross-file evidence", sourceFilename = "other.log")
        val outFile = File.createTempFile("openlog-self-contained-note-crossfile", ".ann")

        assertTrue(state.saveAnnotationsTo("t1", outFile))

        val restored = requireNotNull(outFile.readText().annotationsFromToken())
        val restoredBlock = restored.blocks.single() as AnnBlock.LogRef
        assertEquals(otherTabEntries, restoredBlock.sourceEntries)
    }
}
