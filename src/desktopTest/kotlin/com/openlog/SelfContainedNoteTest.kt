package com.openlog

import com.openlog.model.AnnBlock
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.ui.AppState
import com.openlog.ui.annotationsFromToken
import com.openlog.ui.mkTab
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers relink-log Change 1: a note opened without its log shows no log lines, because ordinary
 * (same-tab) AnnBlock.LogRef blocks are saved with sourceEntries == null and rely on the loading
 * tab's own rmap — which is empty for a log-less tab (Case Library's "Open notes only", or a log
 * that's since gone missing). The fix is save-time materialization (AppState's
 * Annotations.materializeLogRefs, chained into preparedForSave) — this file exercises that through
 * the public save/load API, not the private helper directly.
 */
class SelfContainedNoteTest {
    private var openState: AppState? = null

    @AfterTest
    fun tearDown() {
        openState?.close()
    }

    private fun newState(): AppState {
        val state = AppState(autosaveFile = File.createTempFile("openlog-self-contained-note-test", ".cache"))
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
        // A same-tab LogRef block, added the way confirmAddAnn always leaves one: logIds only, no
        // sourceEntries cache (that cache is only ever pre-populated for cross-file/compare-mode
        // blocks — see AnnBlock.LogRef's own doc comment).
        state.confirmAddAnn(targetTabId = "t1", sourceTabId = "t1", logIds = listOf(1, 2), caption = "evidence", sourceFilename = null)
        assertNull(
            (state.tab("t1")!!.annotations.blocks.single() as AnnBlock.LogRef).sourceEntries,
            "a same-tab block must NOT carry sourceEntries in memory before it's ever been saved",
        )
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
    fun inMemoryAnnotationsAreUnchangedBySaving() {
        val state = newState()
        state.tabs = listOf(mkTab("t1", "sample.log", sampleEntries))
        state.confirmAddAnn(targetTabId = "t1", sourceTabId = "t1", logIds = listOf(1, 2), caption = "evidence", sourceFilename = null)
        val outFile = File.createTempFile("openlog-self-contained-note-inmemory", ".ann")

        assertTrue(state.saveAnnotationsTo("t1", outFile))

        // The save materializes sourceEntries into the FILE only — the live tab's own in-memory
        // annotations (still resolved through its own rmap on every render) must be untouched, or
        // autosave (which serializes this exact same object on every debounced edit) would have
        // silently started inlining full log rows on every keystroke.
        val liveBlock = state.tab("t1")!!.annotations.blocks.single() as AnnBlock.LogRef
        assertNull(liveBlock.sourceEntries, "a same-tab annotation must still hold no sourceEntries after a save")
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
