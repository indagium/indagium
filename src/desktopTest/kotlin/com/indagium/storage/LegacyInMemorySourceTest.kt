package com.indagium.storage

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyInMemorySourceTest {
    private fun row(id: Int, message: String) = LogEntry(id, "12:00:00.000", LogLevel.I, "test", message)

    private val sourceId = SourceId("synthetic-source")

    @Test
    fun pagesAndSparseIdResolutionHaveIndependentExpectedValues() = runBlocking {
        val original = mutableListOf(row(17, "first"), row(-5, "second"), row(800, "third"))
        val source = LegacyInMemorySource(sourceId, LogFormat.LOGCAT, original, maxPageRows = 2)
        original.clear()
        val snapshot = source.snapshot()
        assertEquals(3L, snapshot.rowCount)
        val first = snapshot.rows(RowOrdinal(0), 2)
        assertEquals(listOf(17, -5), first.items.map { it.ref.id.value })
        assertEquals(listOf("first", "second"), first.items.map { it.entry.msg })
        assertEquals(listOf(sourceId, sourceId), first.items.map { it.ref.source })
        assertEquals(snapshot.version, first.version)
        assertEquals(false, first.endReached)
        val last = snapshot.rows(RowOrdinal(2), 2)
        assertEquals(listOf(800), last.items.map { it.entry.id })
        assertTrue(last.endReached)
        assertEquals(RowOrdinal(0), snapshot.resolve(EntryId(17)))
        assertEquals(RowOrdinal(1), snapshot.resolve(EntryId(-5)))
        assertEquals(RowOrdinal(2), snapshot.resolve(EntryId(800)))
        assertNull(snapshot.resolve(EntryId(1)))
        val end = snapshot.rows(RowOrdinal(Long.MAX_VALUE), 1)
        assertEquals(Long.MAX_VALUE, end.start)
        assertTrue(end.items.isEmpty())
        assertTrue(end.endReached)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (first.items as MutableList<LegacyDecodedRow>).clear()
        }
        snapshot.close()
        source.close()
    }

    @Test
    fun snapshotsAreIndependentLeasesAndReturnedPagesSurviveClosure() = runBlocking {
        val source = LegacyInMemorySource(sourceId, LogFormat.DLT, listOf(row(42, "owned")))
        val first = source.snapshot()
        val second = source.snapshot()
        assertEquals(first.version, second.version)
        val page = first.rows(RowOrdinal(0), 1)
        source.close()
        source.close()
        assertFailsWith<StorageFailure.Closed> { source.snapshot() }
        first.close()
        first.close()
        assertFailsWith<StorageFailure.Closed> { first.rows(RowOrdinal(0), 1) }
        assertFailsWith<StorageFailure.Closed> { first.resolve(EntryId(42)) }
        assertEquals("owned", page.items.single().entry.msg)
        assertEquals(RowOrdinal(0), second.resolve(EntryId(42)))
        second.close()
        val reopened = LegacyInMemorySource(sourceId, LogFormat.DLT, listOf(row(42, "owned")))
        val reopenedSnapshot = reopened.snapshot()
        assertNotEquals(first.version.lifetime, reopenedSnapshot.version.lifetime)
        assertEquals(0L, reopenedSnapshot.version.revision)
        reopenedSnapshot.close()
        reopened.close()
    }

    @Test
    fun invalidPagesAndDuplicateIdsFailExplicitly() = runBlocking {
        assertFailsWith<StorageFailure.InvalidRequest> {
            LegacyInMemorySource(sourceId, LogFormat.LOGCAT, emptyList(), maxPageRows = 0)
        }
        assertFailsWith<StorageFailure.InvalidRequest> {
            LegacyInMemorySource(sourceId, LogFormat.LOGCAT, listOf(row(3, "a"), row(3, "b")))
        }
        val source = LegacyInMemorySource(sourceId, LogFormat.LOGCAT, emptyList(), maxPageRows = 2)
        val snapshot = source.snapshot()
        for ((start, limit) in listOf(-1L to 1, 0L to 0, 0L to -1, 0L to 3)) {
            assertFailsWith<StorageFailure.InvalidRequest> { snapshot.rows(RowOrdinal(start), limit) }
        }
        val empty = snapshot.rows(RowOrdinal(0), 2)
        assertTrue(empty.items.isEmpty())
        assertTrue(empty.endReached)
        snapshot.close()
        source.close()
    }

    @Test
    fun cancelledCallerDoesNotReceiveASuccessfulPage() = runBlocking {
        val source = LegacyInMemorySource(sourceId, LogFormat.LOGCAT, listOf(row(1, "one")))
        val snapshot = source.snapshot()
        val cancelled = Job().apply { cancel() }
        assertFailsWith<CancellationException> {
            withContext(cancelled) { snapshot.rows(RowOrdinal(0), 1) }
        }
        assertEquals(1, snapshot.rows(RowOrdinal(0), 1).items.single().entry.id)
        snapshot.close()
        source.close()
    }
}
