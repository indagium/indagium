package com.indagium.storage.projection

import com.indagium.storage.EntryId
import com.indagium.storage.SourceVersion
import com.indagium.storage.StorageFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayProjectionTest {
    private val generation = ProjectionGeneration(SourceVersion("lifetime", 3), "query", "projection", 9)

    private fun row(ordinal: Long, id: Int) = ProjectionEvent.Row(ordinal, EntryId(id))

    @Test
    fun pagesCrossRunsHeadersAndReorderedRows(): Unit = runBlocking {
        val members = longArrayOf(1, 7, 12)
        val header = HeaderDescriptor("g", "sequence", 1, 9, 12, 2, true, members)
        members[0] = 99
        val projection = DisplayProjection.build(generation, listOf(
            row(1, 10), row(2, 20), ProjectionEvent.Header(header), row(7, 70), row(3, 30), row(4, 40),
        ), 3)
        val page = projection.page(1, 3)
        assertEquals(generation, page.generation)
        assertEquals(listOf(DisplayEvent.Row(2, EntryId(20)), DisplayEvent.Header(header), DisplayEvent.Row(7, EntryId(70))), page.items)
        assertEquals(listOf(DisplayEvent.Row(3, EntryId(30)), DisplayEvent.Row(4, EntryId(40))), projection.page(4, 3).items)
        assertTrue(projection.page(4, 3).endReached)
        assertTrue(projection.page(Long.MAX_VALUE, 1).endReached)
        assertContentEquals(longArrayOf(1, 7, 12), header.membership())
        header.membership()[0] = 100
        assertContentEquals(longArrayOf(1, 7, 12), header.membership())
        assertEquals(9L, header.declaredEndFilteredPosition)
        assertEquals(12L, header.hostedEndFilteredPosition)
        assertFailsWith<StorageFailure.InvalidRequest> { projection.page(0, 4) }
        assertFailsWith<StorageFailure.InvalidRequest> { projection.page(-1, 1) }
    }

    @Test
    fun duplicateIdsHaveExplicitOccurrences(): Unit = runBlocking {
        val projection = DisplayProjection.build(generation, listOf(row(5, 42), row(6, 2), row(5, 42)), 2)
        assertEquals(0L, projection.locate(EntryId(42), 0))
        assertEquals(2L, projection.locate(EntryId(42), 1))
        assertNull(projection.locate(EntryId(42), 2))
        assertFailsWith<StorageFailure.InvalidRequest> { projection.locate(EntryId(42), -1) }
    }

    @Test
    fun rangeOccurrencesCountHeadersAndRowsWithTheSameId(): Unit = runBlocking {
        val header = HeaderDescriptor("g", "stack", 5, -1, -1, 0, true, longArrayOf(), EntryId(42))
        val projection = DisplayProjection.build(generation, listOf(
            ProjectionEvent.Header(header), row(5, 42), row(6, 2), row(5, 42),
        ), 2)
        assertEquals(0L, projection.occurrenceAt(0, EntryId(42)))
        assertEquals(1L, projection.occurrenceAt(1, EntryId(42)))
        assertEquals(2L, projection.occurrenceAt(3, EntryId(42)))
        assertFailsWith<StorageFailure.InvalidRequest> { projection.occurrenceAt(2, EntryId(42)) }
        assertFailsWith<StorageFailure.InvalidRequest> { projection.occurrenceAt(4, EntryId(42)) }
    }

    @Test
    fun emptyAndOverflowEdge() = runBlocking {
        val empty = DisplayProjection.build(generation, emptyList(), 1)
        assertEquals(0L, empty.size)
        assertTrue(empty.page(0, 1).endReached)
        val projection = DisplayProjection.build(generation, listOf(row(Long.MAX_VALUE, 1), row(0, 2)), 2)
        assertEquals(listOf(DisplayEvent.Row(Long.MAX_VALUE, EntryId(1)), DisplayEvent.Row(0, EntryId(2))), projection.page(0, 2).items)
    }

    @Test
    fun streamingBuilderProducesPagesAndRejectsFurtherUse(): Unit = runBlocking {
        val builder = DisplayProjection.Builder(generation, 2)
        val members = longArrayOf(4, 9)
        val header = HeaderDescriptor("g", "stack", 4, 9, 9, 0, true, members)
        builder.appendRow(4, EntryId(50))
        builder.appendHeader(header)
        builder.appendRow(9, EntryId(50))
        members[0] = 100
        val projection = builder.finish()
        assertEquals(generation, projection.generation)
        assertEquals(listOf(DisplayEvent.Row(4, EntryId(50)), DisplayEvent.Header(header)), projection.page(0, 2).items)
        assertEquals(listOf(DisplayEvent.Row(9, EntryId(50))), projection.page(2, 2).items)
        assertContentEquals(longArrayOf(4, 9), header.membership())
        assertEquals(2L, projection.locate(EntryId(50), 1))
        assertFailsWith<StorageFailure.InvalidRequest> { builder.appendRow(10, EntryId(1)) }
        assertFailsWith<StorageFailure.InvalidRequest> { builder.appendHeader(header) }
        assertFailsWith<StorageFailure.InvalidRequest> { builder.finish() }
    }

    @Test
    fun cancellationDuringBuildDoesNotReturnPartialResult() {
        assertFailsWith<CancellationException> {
            runBlocking {
                val context = currentCoroutineContext()
                val events = object : Iterable<ProjectionEvent> {
                    override fun iterator() = object : Iterator<ProjectionEvent> {
                        var next = 0

                        override fun hasNext() = next < 10

                        override fun next(): ProjectionEvent {
                            if (!hasNext()) throw NoSuchElementException()
                            if (next == 3) context.cancel()
                            return row((next++).toLong(), next)
                        }
                    }
                }
                DisplayProjection.build(generation, events, 2)
            }
        }
    }
}
