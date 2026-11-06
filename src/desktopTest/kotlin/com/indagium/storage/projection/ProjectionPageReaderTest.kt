package com.indagium.storage.projection

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.storage.EntryId
import com.indagium.storage.LegacyDecodedRow
import com.indagium.storage.ReadSnapshot
import com.indagium.storage.RowOrdinal
import com.indagium.storage.RowRef
import com.indagium.storage.SourceId
import com.indagium.storage.SourceVersion
import com.indagium.storage.StorageFailure
import com.indagium.storage.StoragePage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectionPageReaderTest {
    private val version = SourceVersion("source", 1)
    private val generation = ProjectionGeneration(version, "q", "p", 7)

    private class Snapshot(override var version: SourceVersion) : ReadSnapshot {
        val calls = mutableListOf<Pair<Long, Int>>()
        var closed = false
        var failure: Throwable? = null
        var started: CompletableDeferred<Unit>? = null
        var proceed: CompletableDeferred<Unit>? = null
        var returnedVersion: SourceVersion? = null
        override val rowCount = 5L

        override suspend fun rows(start: RowOrdinal, limit: Int): StoragePage<LegacyDecodedRow> {
            calls.add(start.value to limit)
            started?.complete(Unit)
            proceed?.await()
            failure?.let { throw it }
            val rows = (start.value until minOf(start.value + limit, rowCount)).map { ordinal ->
                val id = ordinal.toInt() + 10
                LegacyDecodedRow(RowRef(SourceId("s"), EntryId(id)), LogEntry(id, "", LogLevel.I, "", "row$id"))
            }
            return StoragePage(returnedVersion ?: version, start.value, rows, start.value + limit >= rowCount)
        }

        override suspend fun resolve(id: EntryId): RowOrdinal? = null

        override fun close() { closed = true }
    }

    @Test
    fun readsOnlyRequestedSourceRowsAndPreservesDisplayOrder(): Unit = runBlocking {
        val header = HeaderDescriptor("g", "stack", 1, 3, 3, 0, true, longArrayOf(1), EntryId(11))
        val projection = DisplayProjection.build(generation, listOf(
            ProjectionEvent.Row(3, EntryId(13)), ProjectionEvent.Header(header),
            ProjectionEvent.Row(2, EntryId(12)), ProjectionEvent.Row(3, EntryId(13)),
        ), 4)
        val snapshot = Snapshot(version)
        val reader = ProjectionPageReader(projection, snapshot, 3)
        val page = reader.page(0, 4)
        assertEquals(generation, page.generation)
        assertEquals(listOf(13, 11, 12, 13), page.items.map {
            when (it) {
                is DecodedDisplayEvent.Row -> it.row.entry.id
                is DecodedDisplayEvent.Header -> it.anchor.entry.id
            }
        })
        assertEquals(listOf(1L to 3, 5L to 1), snapshot.calls)
        assertTrue(page.endReached)
        assertFailsWith<UnsupportedOperationException> { (page.items as MutableList<DecodedDisplayEvent>).clear() }
        assertFailsWith<StorageFailure.InvalidRequest> { reader.page(0, 5) }
        reader.close()
        assertTrue(snapshot.closed)
        assertFailsWith<StorageFailure.Closed> { reader.page(0, 1) }
    }

    @Test
    fun rejectsMismatchedVersionsAndIdsAndPropagatesReadFailure() = runBlocking {
        val projection = DisplayProjection.build(generation, listOf(ProjectionEvent.Row(0, EntryId(10))), 1)
        val mismatch = Snapshot(SourceVersion("other", 1))
        assertFailsWith<StorageFailure.InvalidRequest> { ProjectionPageReader(projection, mismatch) }
        assertTrue(mismatch.closed)
        val snapshot = Snapshot(version)
        val reader = ProjectionPageReader(projection, snapshot)
        snapshot.returnedVersion = SourceVersion("other", 1)
        assertFailsWith<StorageFailure.InvalidRequest> { reader.page(0, 1) }
        snapshot.returnedVersion = null
        val error = IllegalStateException("changed")
        snapshot.failure = error
        assertEquals(error.message, assertFailsWith<IllegalStateException> { reader.page(0, 1) }.message)
        reader.close()
        val wrong = DisplayProjection.build(generation, listOf(ProjectionEvent.Row(0, EntryId(99))), 1)
        val wrongReader = ProjectionPageReader(wrong, Snapshot(version))
        assertFailsWith<StorageFailure.InvalidRequest> { wrongReader.page(0, 1) }
        wrongReader.close()
    }

    @Test
    fun closeDuringReadPreventsReturningPage() = runBlocking {
        val projection = DisplayProjection.build(generation, listOf(ProjectionEvent.Row(0, EntryId(10))), 1)
        val snapshot = Snapshot(version)
        snapshot.started = CompletableDeferred()
        snapshot.proceed = CompletableDeferred()
        val reader = ProjectionPageReader(projection, snapshot)
        val result = async { runCatching { reader.page(0, 1) } }
        snapshot.started!!.await()
        reader.close()
        snapshot.proceed!!.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is StorageFailure.Closed)
    }

    @Test
    fun cancellationDoesNotReturnPartialPage() = runBlocking {
        val projection = DisplayProjection.build(generation, listOf(ProjectionEvent.Row(0, EntryId(10))), 1)
        val snapshot = Snapshot(version)
        snapshot.started = CompletableDeferred()
        snapshot.proceed = CompletableDeferred()
        val reader = ProjectionPageReader(projection, snapshot)
        val request = async { reader.page(0, 1) }
        snapshot.started!!.await()
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        reader.close()
    }

    @Test
    fun emptyDisplayPageStillChecksSourceValidity() = runBlocking {
        val projection = DisplayProjection.build(generation, emptyList(), 1)
        val snapshot = Snapshot(version)
        val error = IllegalStateException("source changed")
        snapshot.failure = error
        val reader = ProjectionPageReader(projection, snapshot)
        assertEquals(error.message, assertFailsWith<IllegalStateException> { reader.page(0, 1) }.message)
        assertEquals(listOf(5L to 1), snapshot.calls)
        reader.close()
    }
}
