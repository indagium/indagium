package com.indagium.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VisiblePageTest {
    @Test
    fun onlyReadsRequestedRowsAndHandlesExtremeBounds() {
        var reads = 0
        val source = object : AbstractList<Int>() {
            override val size = 1_000_000

            override fun get(index: Int): Int {
                require(index in indices)
                reads++
                return index
            }
        }
        assertEquals(listOf(5, 6), visiblePage(source, 5, 2).map { it })
        assertEquals(2, reads)
        assertEquals(emptyList(), visiblePage(source, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(listOf(999_999), visiblePage(source, 999_999, Int.MAX_VALUE).map { it })
        assertEquals(3, reads)
        assertFailsWith<IllegalArgumentException> { visiblePage(source, -1, 1) }
        assertFailsWith<IllegalArgumentException> { visiblePage(source, 0, -1) }
    }
}
