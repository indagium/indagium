@file:Suppress("MagicNumber")

package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.utils.AppendOnlyLogList
import com.indagium.utils.ReleasedLogListException
import com.indagium.utils.appendLogEntries
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppendOnlyLogListTest {
    private fun entries(range: IntRange) = range.map { LogEntry(it, "10:00:00.000", LogLevel.I, "Tag", "msg $it") }

    private fun store(list: List<LogEntry>): Any = (list as AppendOnlyLogList).storeIdentity

    @Test
    fun emptyBatchReturnsTheBaseUnchanged() {
        val base = entries(1..3)
        assertSame(base, appendLogEntries(base, emptyList()))
        val view = appendLogEntries(base, entries(4..5))
        assertSame(view, appendLogEntries(view, emptyList()))
    }

    @Test
    fun wrapsAPlainListBaseWithoutMutatingIt() {
        val base = entries(1..3)
        val result = appendLogEntries(base, entries(4..6))
        assertTrue(result is AppendOnlyLogList)
        assertEquals(entries(1..6), result)
        assertEquals(entries(1..3), base)
    }

    @Test
    fun successiveAppendsShareTheBackingStoreAndNeverCopy() {
        val first = appendLogEntries(entries(1..10), entries(11..12))
        val second = appendLogEntries(first, entries(13..14))
        val third = appendLogEntries(second, entries(15..15))
        assertSame(store(first), store(second))
        assertSame(store(first), store(third))
        assertNotSame(first, second)
        assertEquals(12, first.size)
        assertEquals(14, second.size)
        assertEquals(15, third.size)
        assertEquals(entries(1..15), third)
    }

    @Test
    fun oldViewsNeverSeeNewerElements() {
        val v1 = appendLogEntries(entries(1..5), entries(6..7))
        val v2 = appendLogEntries(v1, entries(8..9))
        assertEquals(entries(1..7), v1)
        assertEquals(entries(1..9), v2)
        assertFailsWith<IndexOutOfBoundsException> { v1[7] }
        assertEquals(8, v2[7].id)
    }

    @Test
    fun branchAppendFromAnOlderViewCopiesAndLeavesBothViewsCorrect() {
        val v1 = appendLogEntries(entries(1..5), entries(6..7))
        val v2 = appendLogEntries(v1, entries(8..9))
        // v1 is no longer the newest view of its store: appending must not overwrite v2's slots.
        val branch = appendLogEntries(v1, entries(100..101))
        assertTrue(store(branch) !== store(v1), "a branch append has to copy into its own store")
        assertEquals(entries(1..7) + entries(100..101), branch)
        assertEquals(entries(1..9), v2)
        assertEquals(entries(1..7), v1)
        // The branch is itself newest in its store and can keep sharing.
        val next = appendLogEntries(branch, entries(102..102))
        assertSame(store(branch), store(next))
        assertEquals(entries(1..9), v2)
    }

    @Test
    fun growsByCopyingWhenCapacityIsExhaustedAndKeepsContentIntact() {
        var list: List<LogEntry> = appendLogEntries(emptyList(), entries(1..1))
        val stores = mutableSetOf<Any>(store(list))
        val views = mutableListOf(list)
        for (i in 2..5000) {
            list = appendLogEntries(list, entries(i..i))
            stores += store(list)
            views += list
        }
        assertEquals(entries(1..5000), list)
        // Geometric growth: a handful of stores for 5000 single-row appends, never one per append.
        assertTrue(stores.size in 2..12, "expected few growth steps, got ${stores.size}")
        assertTrue((list as AppendOnlyLogList).capacity >= 5000)
        // Every historical view still reads its own prefix, across stores.
        listOf(1, 1023, 1024, 1025, 2000, 4999).forEach { n ->
            assertEquals(n, views[n - 1].size)
            assertEquals(n, views[n - 1].last().id)
        }
    }

    @Test
    fun aLargeBatchLargerThanCapacityGetsAStoreBigEnough() {
        val big = appendLogEntries(entries(1..3), entries(4..10_000))
        assertEquals(10_000, big.size)
        assertTrue((big as AppendOnlyLogList).capacity >= 10_000)
        assertEquals(10_000, big.last().id)
    }

    @Test
    fun equalsAndHashCodeMatchAnArrayListWithTheSameContent() {
        val view = appendLogEntries(entries(1..4), entries(5..8))
        val plain = ArrayList(entries(1..8))
        assertEquals(plain, view)
        assertEquals(view, plain)
        assertEquals(plain.hashCode(), view.hashCode())
        assertTrue(view != ArrayList(entries(1..7)))
        // A shorter view of the same store is unequal to the longer one.
        val longer = appendLogEntries(view, entries(9..9))
        assertTrue(view != longer)
        assertEquals(view, appendLogEntries(entries(1..4), entries(5..8)))
    }

    @Test
    fun subListAndIteratorBehaveLikeAnArrayList() {
        val view = appendLogEntries(entries(1..10), entries(11..20))
        assertEquals(entries(5..9), view.subList(4, 9))
        assertEquals(entries(1..20), view.toList())
        assertContentEquals(entries(1..20), view.iterator().asSequence().toList())
        assertEquals(5, view.indexOfFirst { it.id == 6 })
        assertEquals(entries(1..20).reversed(), view.asReversed())
        assertEquals(19, view.lastIndex)
    }

    @Test
    fun getChecksBoundsAgainstTheViewSizeNotTheStoreCommitCount() {
        val v1 = appendLogEntries(entries(1..3), entries(4..4))
        appendLogEntries(v1, entries(5..6)) // commits slots 4 and 5 in the shared store
        assertEquals(4, v1.size)
        assertFailsWith<IndexOutOfBoundsException> { v1[4] }
        assertFailsWith<IndexOutOfBoundsException> { v1[-1] }
        assertEquals(4, v1[3].id)
    }

    @Test
    fun releaseEmptiesTheStoreAndKeepsReportingTheOldSize() {
        val list = appendLogEntries(entries(1..10), entries(11..12)) as AppendOnlyLogList
        assertFalse(list.isReleased)
        assertTrue(list.capacity >= 12)
        list.release()
        assertTrue(list.isReleased)
        assertEquals(0, list.capacity)
        assertEquals(12, list.size, "size is a plain val and keeps its pre-release value")
    }

    @Test
    fun readingAReleasedListThrowsACancellationException() {
        val v1 = appendLogEntries(entries(1..5), entries(6..7)) as AppendOnlyLogList
        val v2 = appendLogEntries(v1, entries(8..9)) as AppendOnlyLogList
        v2.release()
        // Every view of the store is unreadable, older ones included.
        val failure: Throwable = assertFailsWith<ReleasedLogListException> { v2[0] }
        assertTrue(failure is CancellationException, "a late reader must end like a cancelled job")
        assertFailsWith<ReleasedLogListException> { v1[3] }
        assertFailsWith<ReleasedLogListException> { v1.iterator().next() }
        // Index validation still comes first.
        assertFailsWith<IndexOutOfBoundsException> { v2[v2.size] }
    }

    @Test
    fun releaseIsIdempotent() {
        val list = appendLogEntries(entries(1..3), entries(4..4)) as AppendOnlyLogList
        list.release()
        list.release()
        assertTrue(list.isReleased)
        assertEquals(0, list.capacity)
        assertFailsWith<ReleasedLogListException> { list[0] }
    }

    @Test
    fun releasingOneStoreLeavesAnIndependentStoreReadable() {
        val released = appendLogEntries(entries(1..3), entries(4..5)) as AppendOnlyLogList
        val other = appendLogEntries(entries(1..3), entries(4..5)) as AppendOnlyLogList
        assertNotSame(store(released), store(other))
        released.release()
        assertFalse(other.isReleased)
        assertEquals(entries(1..5), other)
    }

    @Test
    fun appendingToAReleasedBaseThrowsTheDedicatedException() {
        val base = appendLogEntries(entries(1..3), entries(4..5)) as AppendOnlyLogList
        base.release()
        assertFailsWith<ReleasedLogListException> { appendLogEntries(base, entries(6..6)) }
        // An empty batch still returns the base untouched, released or not.
        assertSame(base, appendLogEntries(base, emptyList()))
    }

    @Test
    fun concurrentAppendsFromTheSameBaseNeverCorruptEachOther() {
        val base = appendLogEntries(entries(1..10), entries(11..12))
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            val futures = (0 until threads).map { t ->
                pool.submit<List<LogEntry>> {
                    start.await()
                    appendLogEntries(base, entries((1000 * (t + 1))..(1000 * (t + 1) + 4)))
                }
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            results.forEachIndexed { t, r ->
                assertEquals(entries(1..12) + entries((1000 * (t + 1))..(1000 * (t + 1) + 4)), r)
            }
            assertEquals(entries(1..12), base)
        } finally {
            pool.shutdownNow()
        }
    }
}
