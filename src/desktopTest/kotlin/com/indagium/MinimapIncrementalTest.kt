@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.Highlighter
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.ui.MINIMAP_MAX_BUCKETS
import com.indagium.ui.MinimapBar
import com.indagium.ui.MinimapSampler
import com.indagium.ui.computeMinimapBars
import com.indagium.ui.minimapBucketOf
import com.indagium.ui.minimapItemIndexOf
import com.indagium.ui.minimapSampleStride
import com.indagium.utils.AppendOnlyList
import com.indagium.utils.appendMapped
import com.indagium.utils.isAppendOnlyExtensionOf
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The minimap's closed-form representative, ceil-based click mapping and incremental sampler.
// Ground truth for the bars is always computeMinimapBars on the same list (a fresh sampler), which
// uses the same sampling rule; separately each chosen representative is checked to lie in its row.
// Items carry their own display index in Row.indent, which lands in MinimapBar.indent, so a bar
// reveals which item it was resolved from.
class MinimapIncrementalTest {
    private val muted = Color(0xFF888888)

    private fun item(index: Int): LogItem.Row {
        val level = when {
            index % 13 == 0 -> LogLevel.E
            index % 29 == 0 -> LogLevel.W
            else -> LogLevel.I
        }
        return LogItem.Row(LogEntry(index + 1, "10:00:00.000", level, "Tag", "message $index with some words"), indent = index)
    }

    private fun items(range: IntRange): List<LogItem> = range.map(::item)

    private fun extend(base: List<LogItem>, range: IntRange): AppendOnlyList<LogItem> = appendMapped(base, items(range)) { it }

    private fun rowsFor(n: Int) = n.coerceAtMost(MINIMAP_MAX_BUCKETS)

    private fun hl(pattern: String, color: Color) =
        Highlighter(id = "hl_$pattern", pattern = pattern, regex = true, color = color, on = true)

    // ── closed-form representative / click mapping ───────────────────

    // The definition the old computeMinimapBars loop used: the first item whose bucket is b.
    private fun oldFirstItemOfBucket(n: Int, r: Int): IntArray {
        val rep = IntArray(r) { -1 }
        for (i in 0 until n) {
            val b = minimapBucketOf(i, n, r)
            if (rep[b] < 0) rep[b] = i
        }
        return rep
    }

    @Test
    fun itemIndexOfIsTheFirstItemOfItsBucketForManyShapes() {
        val shapes = listOf(
            1 to 1, 2 to 2, 5 to 3, 10 to 3, 1000 to 1000, 1001 to 1000, 2001 to 2000,
            3999 to 2000, 4000 to 2000, 12_345 to 2000, 999_983 to 2000, 7 to 1,
        )
        for ((n, r) in shapes) {
            val expected = oldFirstItemOfBucket(n, r)
            for (b in 0 until r) {
                assertEquals(expected[b], minimapItemIndexOf(b, n, r), "n=$n r=$r bucket=$b")
            }
        }
    }

    @Test
    fun clickOnARowLandsOnThatRowsFirstItemNotTheOneBefore() {
        // N=10, R=3: rows cover items [0..3] [4..6] [7..9] (floor(i*3/10)). The old floor mapping
        // sent a click on row 1 to item 3 (inside row 0) and row 2 to item 6 (inside row 1).
        assertEquals(0, minimapItemIndexOf(0, 10, 3))
        assertEquals(4, minimapItemIndexOf(1, 10, 3))
        assertEquals(7, minimapItemIndexOf(2, 10, 3))
        for ((n, r) in listOf(10 to 3, 2001 to 2000, 12_345 to 2000)) {
            for (b in 0 until r) {
                val first = minimapItemIndexOf(b, n, r)
                assertEquals(b, minimapBucketOf(first, n, r), "first item of row $b is inside it (n=$n r=$r)")
                if (first > 0) assertEquals(b - 1, minimapBucketOf(first - 1, n, r), "the item before it is in the previous row")
            }
        }
    }

    // ── sampling rule ────────────────────────────────────────────────

    @Test
    fun strideIsTheLargestPowerOfTwoNotAboveItemsPerRow() {
        assertEquals(1, minimapSampleStride(1, 1))
        assertEquals(1, minimapSampleStride(3999, 2000))
        assertEquals(2, minimapSampleStride(4000, 2000))
        assertEquals(2, minimapSampleStride(7999, 2000))
        assertEquals(4, minimapSampleStride(8000, 2000))
        assertEquals(256, minimapSampleStride(1_000_000, 2000))
        assertEquals(1, minimapSampleStride(5, 5))
        assertEquals(4, minimapSampleStride(5, 1)) // 5 items in one row: largest power of two <= 5
    }

    @Test
    fun everyRepresentativeLiesInsideItsRowAndAtMostOneStrideAfterItsFirstItem() {
        for (n in listOf(1, 2, 7, 1999, 2000, 2001, 3999, 4000, 4001, 7999, 8000, 12_345, 100_000)) {
            val r = rowsFor(n)
            val list = items(0 until n)
            val bars = computeMinimapBars(list, BitSet(), r, emptyList(), muted)
            assertEquals(r, bars.size)
            val stride = minimapSampleStride(n, r)
            for (b in 0 until r) {
                val index = bars[b].indent // the item index this bar was resolved from
                val first = minimapItemIndexOf(b, n, r)
                assertEquals(b, minimapBucketOf(index, n, r), "n=$n bucket=$b representative $index outside its row")
                assertTrue(index >= first && index - first < stride, "n=$n bucket=$b representative $index vs first $first stride $stride")
                assertEquals(0, index % stride)
            }
        }
    }

    @Test
    fun belowTwiceTheBucketCapEveryBarIsItsOwnItem() {
        for (n in listOf(1, 2, 500, 1999, 2000, 3999)) {
            val r = rowsFor(n)
            val bars = computeMinimapBars(items(0 until n), BitSet(), r, emptyList(), muted)
            for (b in 0 until r) assertEquals(minimapItemIndexOf(b, n, r), bars[b].indent, "n=$n b=$b")
        }
        // N <= 2000: row b IS item b.
        val bars = computeMinimapBars(items(0 until 1500), BitSet(), 1500, emptyList(), muted)
        for (b in 0 until 1500) assertEquals(b, bars[b].indent)
    }

    // ── incremental equals fresh ─────────────────────────────────────

    private fun fresh(list: List<LogItem>, crash: BitSet, highlighters: List<Highlighter>, color: Color = muted): List<MinimapBar> =
        computeMinimapBars(list, crash, rowsFor(list.size), highlighters, color)

    @Test
    fun incrementalBarsEqualAFreshComputationAcrossManyAppendsAndStrideDoublings() {
        val sampler = MinimapSampler()
        val highlighters = listOf(hl("message 1\\d*7 ", Color.Blue), hl("words$", Color.Green))
        val crash = BitSet()
        var list: List<LogItem> = emptyList()
        var next = 0
        val batchSizes = listOf(1, 1, 7, 60, 400, 1500, 31, 1, 2500, 700, 4000, 33, 1000, 9000, 3, 5000, 20_000, 1, 17)
        var previousList: List<LogItem>? = null
        var incrementalSteps = 0
        for (size in batchSizes) {
            list = extend(list, next until next + size)
            next += size
            val bars = sampler.update(list, crash, rowsFor(list.size), highlighters, muted)
            assertEquals(fresh(list, crash, highlighters), bars, "after $next items")
            val prev = previousList
            if (prev != null && list.isAppendOnlyExtensionOf(prev)) {
                assertTrue(sampler.lastWasIncremental, "a same-store append must reuse the memo (n=$next)")
                incrementalSteps++
            }
            previousList = list
        }
        assertTrue(incrementalSteps >= 10, "expected most steps to share a store, got $incrementalSteps")
    }

    @Test
    fun anIncrementalBatchResolvesOnlyTheNewSamples() {
        val sampler = MinimapSampler()
        var list: List<LogItem> = extend(emptyList(), 0 until 100_000)
        sampler.update(list, BitSet(), 2000, emptyList(), muted)
        assertFalse(sampler.lastWasIncremental)
        val stride = minimapSampleStride(100_000, 2000) // 32
        // The 100k-item store has 1.5x headroom, so batches extend it in place.
        var n = 100_000
        repeat(20) {
            list = extend(list, n until n + 200)
            n += 200
            sampler.update(list, BitSet(), 2000, emptyList(), muted)
            assertTrue(sampler.lastWasIncremental)
            assertTrue(sampler.lastResolvedCount <= 200 / stride + 1, "resolved ${sampler.lastResolvedCount} samples for a 200-item batch")
        }
    }

    @Test
    fun strideDoublingKeepsEveryOtherSampleWithoutResolvingAnything() {
        val sampler = MinimapSampler()
        val list3999 = extend(emptyList(), 0 until 3999)
        sampler.update(list3999, BitSet(), 2000, emptyList(), muted)
        val list4000 = extend(list3999, 3999..3999)
        val bars = sampler.update(list4000, BitSet(), 2000, emptyList(), muted)
        assertTrue(sampler.lastWasIncremental)
        assertEquals(0, sampler.lastResolvedCount, "the doubled stride's samples are all retained ones")
        assertEquals(fresh(list4000, BitSet(), emptyList()), bars)
    }

    @Test
    fun aHighlighterChangeRebuildsFromScratchAndEqualsFresh() {
        val sampler = MinimapSampler()
        val h1 = listOf(hl("message 1\\d*3 ", Color.Red))
        val h2 = listOf(hl("message 2\\d*4 ", Color.Yellow))
        var list: List<LogItem> = extend(emptyList(), 0 until 5000)
        sampler.update(list, BitSet(), 2000, h1, muted)
        list = extend(list, 5000 until 5100)
        val withOther = sampler.update(list, BitSet(), 2000, h2, muted)
        assertFalse(sampler.lastWasIncremental)
        assertEquals(fresh(list, BitSet(), h2), withOther)
        assertTrue(withOther != fresh(list, BitSet(), h1), "the test needs the highlighters to actually differ in output")
        // And back to incremental once the inputs are stable again.
        list = extend(list, 5100 until 5150)
        assertEquals(fresh(list, BitSet(), h2), sampler.update(list, BitSet(), 2000, h2, muted))
        assertTrue(sampler.lastWasIncremental)
    }

    @Test
    fun aThemeColorChangeRebuildsFromScratchAndEqualsFresh() {
        val sampler = MinimapSampler()
        var list: List<LogItem> = extend(emptyList(), 0 until 3000)
        sampler.update(list, BitSet(), 2000, emptyList(), muted)
        list = extend(list, 3000 until 3010)
        val other = Color(0xFF111111)
        val bars = sampler.update(list, BitSet(), 2000, emptyList(), other)
        assertFalse(sampler.lastWasIncremental)
        assertEquals(fresh(list, BitSet(), emptyList(), other), bars)
    }

    @Test
    fun crashSitesInTheTailKeepItIncrementalButAChangedRetainedSampleRebuilds() {
        val sampler = MinimapSampler()
        var list: List<LogItem> = extend(emptyList(), 0 until 5000)
        sampler.update(list, BitSet(), 2000, emptyList(), muted)

        // A crash on a row that only exists in the appended tail: old samples are unaffected.
        list = extend(list, 5000 until 5400)
        val tailCrash = BitSet().also { it.set(5100 + 1) } // entry ids are index + 1
        val withTail = sampler.update(list, tailCrash, 2000, emptyList(), muted)
        assertTrue(sampler.lastWasIncremental)
        assertEquals(fresh(list, tailCrash, emptyList()), withTail)

        // A crash on a retained, sampled row (index 0 is always a sample): must not reuse the memo.
        list = extend(list, 5400 until 5450)
        val oldCrash = BitSet().also { it.set(0 + 1); it.set(5100 + 1) }
        val withOld = sampler.update(list, oldCrash, 2000, emptyList(), muted)
        assertFalse(sampler.lastWasIncremental)
        assertEquals(fresh(list, oldCrash, emptyList()), withOld)
        assertTrue(withOld != withTail)
    }

    @Test
    fun aNonAppendChangeOfTheItemsRebuildsAndEqualsFresh() {
        val sampler = MinimapSampler()
        val a = extend(emptyList(), 0 until 6000)
        sampler.update(a, BitSet(), 2000, emptyList(), muted)
        // A plain-list derivative (what a splice returns): not a same-store extension.
        val spliced: List<LogItem> = ArrayList(a.subList(0, 3000)) + items(10_000 until 10_050) + a.subList(3000, 6000)
        assertEquals(fresh(spliced, BitSet(), emptyList()), sampler.update(spliced, BitSet(), 2000, emptyList(), muted))
        assertFalse(sampler.lastWasIncremental)
        // A shorter view of the same store (items removed from the end) is not an extension either.
        val shorter = a.subList(0, 100)
        assertEquals(fresh(shorter, BitSet(), emptyList()), sampler.update(shorter, BitSet(), 2000, emptyList(), muted))
        assertFalse(sampler.lastWasIncremental)
        // Completely unrelated store, even one with the same size.
        val b = extend(emptyList(), 0 until 6000)
        sampler.update(a, BitSet(), 2000, emptyList(), muted)
        assertEquals(fresh(b, BitSet(), emptyList()), sampler.update(b, BitSet(), 2000, emptyList(), muted))
        assertFalse(sampler.lastWasIncremental)
    }

    @Test
    fun anEmptyListResetsTheMemo() {
        val sampler = MinimapSampler()
        val a = extend(emptyList(), 0 until 100)
        sampler.update(a, BitSet(), 100, emptyList(), muted)
        assertEquals(emptyList(), sampler.update(emptyList(), BitSet(), 0, emptyList(), muted))
        assertEquals(fresh(a, BitSet(), emptyList()), sampler.update(a, BitSet(), 100, emptyList(), muted))
        assertFalse(sampler.lastWasIncremental)
    }

    @Test
    fun aCancelledUpdateLeavesThePreviousMemoUsable() {
        val sampler = MinimapSampler()
        var list: List<LogItem> = extend(emptyList(), 0 until 5000)
        sampler.update(list, BitSet(), 2000, emptyList(), muted)
        val grown = extend(list, 5000 until 5300)
        var checks = 0
        assertFailsWith<IllegalStateException> {
            sampler.update(grown, BitSet(), 2000, emptyList(), muted, cancellationCheck = { if (++checks > 1) error("cancelled") })
        }
        // The memo still describes the 5000-item list, so the retry is incremental and correct.
        list = grown
        val bars = sampler.update(list, BitSet(), 2000, emptyList(), muted)
        assertTrue(sampler.lastWasIncremental)
        assertEquals(fresh(list, BitSet(), emptyList()), bars)
    }

    @Test
    fun cancellationIsCheckedOncePerResolvedSample() {
        val sampler = MinimapSampler()
        var checks = 0
        sampler.update(extend(emptyList(), 0 until 500), BitSet(), 500, emptyList(), muted, cancellationCheck = { checks++ })
        assertEquals(500, checks)
    }
}
