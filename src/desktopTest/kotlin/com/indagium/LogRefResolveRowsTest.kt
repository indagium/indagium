package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.resolveRows
import com.indagium.ui.mkTab
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers AnnBlock.LogRef.resolveRows (Model.kt) — the single shared resolver that replaced four
 * duplicated copies of `block.sourceEntries ?: block.logIds.mapNotNull { tab.rmap[it] }` across
 * AnnotationPanel.kt/Filter.kt/AnnotationHtml.kt. Its precedence is baked-in sourceEntries first,
 * then the tab's live rmap, then the .md-recovered fallback (LogTab.recoveredNoteRows) — these
 * tests pin that order down directly rather than relying on a renderer to exercise it indirectly.
 */
class LogRefResolveRowsTest {
    private val sourceEntry = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "baked-in row")
    private val rmapEntry = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "live rmap row")
    private val recoveredEntry = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "recovered-from-md row")

    @Test
    fun sourceEntriesWinsEvenWhenRmapAndRecoveredRowsAreBothPresent() {
        val block = AnnBlock.LogRef("r1", listOf(1), "caption", sourceEntries = listOf(sourceEntry))
        val tab = mkTab("t1", "app.log", listOf(rmapEntry))
            .copy(recoveredNoteRows = mapOf("r1" to listOf(recoveredEntry)))

        assertEquals(listOf(sourceEntry), block.resolveRows(tab))
    }

    @Test
    fun liveRmapWinsOverRecoveredRowsWhenSourceEntriesIsNull() {
        val block = AnnBlock.LogRef("r1", listOf(1), "caption", sourceEntries = null)
        val tab = mkTab("t1", "app.log", listOf(rmapEntry))
            .copy(recoveredNoteRows = mapOf("r1" to listOf(recoveredEntry)))

        assertEquals(listOf(rmapEntry), block.resolveRows(tab))
    }

    @Test
    fun recoveredRowsAreUsedOnlyWhenSourceEntriesIsNullAndRmapResolvesNothing() {
        val block = AnnBlock.LogRef("r1", listOf(1), "caption", sourceEntries = null)
        // No log attached at all — an empty rmap, exactly the log-less-tab scenario Change 2 fixes.
        val tab = mkTab("t1", "app.log", emptyList())
            .copy(recoveredNoteRows = mapOf("r1" to listOf(recoveredEntry)))

        assertEquals(listOf(recoveredEntry), block.resolveRows(tab))
    }

    @Test
    fun resolvesToEmptyWhenNoneOfTheThreeSourcesHaveAnything() {
        val block = AnnBlock.LogRef("r1", listOf(1), "caption", sourceEntries = null)
        val tab = mkTab("t1", "app.log", emptyList())

        assertEquals(emptyList(), block.resolveRows(tab))
    }
}
