package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import com.indagium.utils.OrderedIntSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderedIntSetTest {
    @Test
    fun keepsTheGivenOrderEvenWhenIdsDescendOrJump() {
        val set = OrderedIntSet.of(intArrayOf(7, 3, 9, 4, 100))
        assertEquals(listOf(7, 3, 9, 4, 100), set.toList())
        assertEquals(5, set.size)
    }

    @Test
    fun dropsDuplicatesKeepingTheFirstOccurrence() {
        val set = OrderedIntSet.of(intArrayOf(5, 2, 5, 9, 2, 2, 1))
        assertEquals(listOf(5, 2, 9, 1), set.toList())
        assertEquals(4, set.size)
    }

    @Test
    fun containsIsExactIncludingIdsOutsideTheStoredSpan() {
        val set = OrderedIntSet.of(intArrayOf(10, 12, 15))
        assertTrue(10 in set && 12 in set && 15 in set)
        assertFalse(11 in set)
        assertFalse(9 in set)
        assertFalse(16 in set)
        assertFalse(Int.MIN_VALUE in set)
        assertFalse(Int.MAX_VALUE in set)
        assertFalse(0 in set)
    }

    @Test
    fun equalsAndHashCodeFollowTheSetContractInBothDirections() {
        val ordered = OrderedIntSet.of(intArrayOf(4, 2, 8, 2))
        val plain = linkedSetOf(2, 4, 8)
        assertEquals(plain, ordered)
        assertEquals(ordered, plain)
        assertEquals(plain.hashCode(), ordered.hashCode())
        assertEquals(setOf(8, 4, 2), ordered)
        assertTrue(ordered != setOf(2, 4))
        assertTrue(ordered != setOf(2, 4, 9))
        assertTrue(ordered != listOf(4, 2, 8))
    }

    @Test
    fun emptyRangeIsAnEmptySetEqualToEmptySet() {
        assertEquals(emptySet(), OrderedIntSet.of(intArrayOf()))
        assertEquals(emptySet(), OrderedIntSet.of(intArrayOf(1, 2, 3), 2, 2))
        assertTrue(OrderedIntSet.of(intArrayOf()).isEmpty())
    }

    @Test
    fun aSubRangeUsesOnlyThoseElements() {
        val set = OrderedIntSet.of(intArrayOf(1, 2, 3, 4, 5, 6), 2, 5)
        assertEquals(listOf(3, 4, 5), set.toList())
        assertFalse(2 in set)
        assertFalse(6 in set)
    }

    @Test
    fun negativeAndWidelySpreadIdsStillBehaveLikeALinkedHashSet() {
        val negative = OrderedIntSet.of(intArrayOf(-3, 5, -1, 5))
        assertEquals(listOf(-3, 5, -1), negative.toList())
        assertTrue(-1 in negative && -3 in negative && 5 in negative)
        assertFalse(-2 in negative)

        val spread = OrderedIntSet.of(intArrayOf(1, Int.MAX_VALUE, 0, Int.MIN_VALUE))
        assertEquals(listOf(1, Int.MAX_VALUE, 0, Int.MIN_VALUE), spread.toList())
        assertTrue(Int.MAX_VALUE in spread && Int.MIN_VALUE in spread)
        assertFalse(2 in spread)
    }

    @Test
    fun iteratorEndsWithNoSuchElement() {
        val iterator = OrderedIntSet.of(intArrayOf(1)).iterator()
        assertEquals(1, iterator.next())
        assertFalse(iterator.hasNext())
        var thrown = false
        try {
            iterator.next()
        } catch (_: NoSuchElementException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun plusAndMinusOnTheSetWorkLikeOnAnyOtherSet() {
        val set: Set<Int> = OrderedIntSet.of(intArrayOf(3, 1, 2))
        assertEquals(setOf(3, 1, 2, 9), set + 9)
        assertEquals(listOf(3, 1, 2, 9), (set + 9).toList())
        assertEquals(listOf(3, 2), (set - 1).toList())
        assertEquals(1, set.minOrNull())
        assertEquals(3, set.maxOrNull())
        assertEquals(2, set.last())
    }

    // The AppState entry points that hand this set to LogTab.selected must produce exactly what the
    // boxed List.toSet() versions did.
    @Test
    fun selectAllAndRangeSelectionKeepDisplayOrderAndEqualPlainSets() {
        val entries = (1..10).map { LogEntry(it, "10:00:%02d.000".format(it), LogLevel.I, "Tag", "msg$it") }
        val state = AppState()
        state.tabs = listOf(mkTab("t1", "test.log", entries))
        state.activeTabId = "t1"

        state.selectAll("t1")
        assertEquals((1..10).toList(), state.tabs.single().selected.toList())
        assertEquals((1..10).toSet(), state.tabs.single().selected)

        state.selRowRange("t1", 7, 3)
        assertEquals((3..7).toList(), state.tabs.single().selected.toList())

        state.selRow("t1", 2, multi = false, range = false)
        state.selRow("t1", 5, multi = false, range = true)
        assertEquals(listOf(2, 3, 4, 5), state.tabs.single().selected.toList())
        assertEquals(setOf(2, 3, 4, 5), state.tabs.single().selected)

        // Ctrl-click on top of the new set adds and removes like any other Set.
        state.selRow("t1", 9, multi = true, range = false)
        assertEquals(setOf(2, 3, 4, 5, 9), state.tabs.single().selected)
        state.selRow("t1", 3, multi = true, range = false)
        assertEquals(setOf(2, 4, 5, 9), state.tabs.single().selected)
    }
}
