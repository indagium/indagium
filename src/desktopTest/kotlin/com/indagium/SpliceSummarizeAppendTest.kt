@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.ui.ItemsSummary
import com.indagium.ui.spliceSummarize
import com.indagium.ui.summarizeItems
import com.indagium.utils.AppendOnlyList
import com.indagium.utils.appendMapped
import com.indagium.utils.isAppendOnlyExtensionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// spliceSummarize's two append-era shortcuts: `===` (a batch that added no visible rows) and the
// same-store extension (the identity prefix walk is skipped; only the tail is summarized). Both must
// be indistinguishable from summarizeItems on the new list.
class SpliceSummarizeAppendTest {
    private fun entry(id: Int, level: LogLevel = LogLevel.I) = LogEntry(id, "10:00:00.000", level, "T", "m $id", pid = 1)

    private fun row(id: Int) = LogItem.Row(entry(id), 0)

    private fun header(id: Int, expanded: Boolean) =
        LogItem.SeqHeader(entry(id), gid = "g$id", indent = 0, expanded = expanded, count = 3, color = Color.Red)

    /** Rows with a collapsed and an expanded header sprinkled in, ids strictly ascending from [from]. */
    private fun items(from: Int, count: Int): List<LogItem> = (from until from + count).map { id ->
        when {
            id % 7 == 0 -> header(id, expanded = id % 14 == 0)
            else -> row(id)
        }
    }

    private fun extend(base: List<LogItem>, batch: List<LogItem>): AppendOnlyList<LogItem> = appendMapped(base, batch) { it }

    private fun assertSummariesEqual(expected: ItemsSummary, actual: ItemsSummary?) {
        assertNotNull(actual)
        assertTrue(expected.allIds.contentEquals(actual.allIds), "allIds differ")
        assertTrue(expected.rowIds.contentEquals(actual.rowIds), "rowIds differ")
        assertEquals(expected.idBits, actual.idBits, "idBits differ")
        assertEquals(expected.collapsedGroupCount, actual.collapsedGroupCount)
        assertEquals(expected.expandedGroupCount, actual.expandedGroupCount)
    }

    @Test
    fun theSameListInstanceReturnsTheOldSummaryItself() {
        val list = extend(emptyList(), items(1, 50))
        val summary = summarizeItems(list)
        assertSame(summary, spliceSummarize(list, summary, list))
        // Also for a plain list.
        val plain = items(1, 20)
        val plainSummary = summarizeItems(plain)
        assertSame(plainSummary, spliceSummarize(plain, plainSummary, plain))
    }

    @Test
    fun theIdentityShortcutStillRequiresASummaryThatMatchesTheOldList() {
        val list = extend(emptyList(), items(1, 10))
        val wrong = summarizeItems(items(1, 4))
        assertNull(spliceSummarize(list, wrong, list))
    }

    @Test
    fun sameStoreExtensionsMatchAFullSummarizeAcrossManyBatches() {
        var list = extend(emptyList(), items(1, 40))
        var summary = summarizeItems(list)
        var next = 41
        for (size in listOf(1, 3, 17, 1, 60, 200, 5, 1500)) {
            val grown = extend(list, items(next, size))
            next += size
            // Under the 1024-slot floor every batch extends in place, which is what arms the shortcut.
            if (size < 1000) assertTrue(grown.isAppendOnlyExtensionOf(list), "batch of $size should share the store")
            val spliced = spliceSummarize(list, summary, grown)
            assertSummariesEqual(summarizeItems(grown), spliced)
            summary = spliced!!
            list = grown
        }
    }

    @Test
    fun sameStoreExtensionPreservesHeaderCountsFromTheOldPrefix() {
        val base = extend(emptyList(), items(1, 100))
        val baseSummary = summarizeItems(base)
        assertTrue(baseSummary.collapsedGroupCount > 0 && baseSummary.expandedGroupCount > 0)
        val grown = extend(base, listOf(header(101, expanded = false), row(102), header(103, expanded = true)))
        val spliced = spliceSummarize(base, baseSummary, grown)
        assertSummariesEqual(summarizeItems(grown), spliced)
    }

    @Test
    fun aBranchAppendFromAnOlderViewOfTheStoreStillMatches() {
        val v1 = extend(emptyList(), items(1, 30))
        val v1Summary = summarizeItems(v1)
        extend(v1, items(31, 10)) // makes v1 non-newest
        val branch = extend(v1, items(100, 5)) // copies into its own store
        assertSummariesEqual(summarizeItems(branch), spliceSummarize(v1, v1Summary, branch))
    }

    @Test
    fun anOlderViewAsTheNewListFallsBackToTheWalkAndStaysCorrect() {
        // newN < oldN on one store: the shortcut must not apply (it would claim a longer head).
        val older = extend(emptyList(), items(1, 30))
        val newer = extend(older, items(31, 10))
        val newerSummary = summarizeItems(newer)
        val result = spliceSummarize(newer, newerSummary, older)
        if (result != null) assertSummariesEqual(summarizeItems(older), result)
    }

    @Test
    fun mixedPlainAndAppendOnlyInputsAreEquivalent() {
        val plainOld = items(1, 50)
        val plainOldSummary = summarizeItems(plainOld)
        // plain -> append-only extension: appendMapped converts (copies) the base, but the Row objects
        // are shared by identity, so the walk finds the prefix.
        val grown = extend(plainOld, items(51, 20))
        assertNotSame<Any>(plainOld, grown)
        assertSummariesEqual(summarizeItems(grown), spliceSummarize(plainOld, plainOldSummary, grown))

        // append-only -> plain list sharing the prefix by identity.
        val grownSummary = summarizeItems(grown)
        val plainNew: List<LogItem> = ArrayList(grown) + items(71, 5)
        assertSummariesEqual(summarizeItems(plainNew), spliceSummarize(grown, grownSummary, plainNew))
    }

    @Test
    fun equalButNotIdenticalItemsOnADifferentStoreAreNullOrEquivalent() {
        val a = extend(emptyList(), items(1, 40))
        val aSummary = summarizeItems(a)
        val b = extend(emptyList(), items(1, 40) + items(41, 10)) // fresh objects, different store
        val result = spliceSummarize(a, aSummary, b)
        if (result != null) assertSummariesEqual(summarizeItems(b), result)
    }
}
