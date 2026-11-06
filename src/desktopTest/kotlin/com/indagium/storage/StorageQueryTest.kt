package com.indagium.storage

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("MagicNumber")
class StorageQueryTest {
    private fun row(id: Int, message: String) = LogEntry(id, "12:00:00.000", LogLevel.I, "test", message)

    private fun source() = LegacyInMemorySource(
        SourceId("query"), LogFormat.LOGCAT,
        listOf(row(90, "keep"), row(-4, "skip"), row(10000, "keep"), row(2, "skip")),
        maxPageRows = 2,
    )

    @Test
    fun partialMatchesUseOrdinalsAndPagesRetainVersion() = runBlocking {
        val source = source()
        val snapshot = source.snapshot()
        val query = StorageQuery(snapshot, 2, 1)
        assertEquals(2L, query.scan { it.msg == "keep" })
        val first = query.rows(0, 1)
        val second = query.rows(1, 1)
        assertEquals(listOf(90), first.items.map { it.entry.id })
        assertEquals(listOf(10000), second.items.map { it.entry.id })
        assertEquals(snapshot.version, first.version)
        assertEquals(snapshot.version, second.version)
        assertEquals(false, first.endReached)
        assertTrue(second.endReached)
        assertTrue(query.rows(Long.MAX_VALUE, 1).items.isEmpty())
        query.close()
        assertEquals(90, first.items.single().entry.id)
        source.close()
    }

    @Test
    fun adjacentMatchesAreReadAsBoundedBatches() = runBlocking {
        val source = source()
        val delegate = source.snapshot()
        val requests = mutableListOf<Pair<Long, Int>>()
        val recording = object : ReadSnapshot by delegate {
            override suspend fun rows(start: RowOrdinal, limit: Int): StoragePage<LegacyDecodedRow> {
                requests.add(start.value to limit)
                return delegate.rows(start, limit)
            }
        }
        val query = StorageQuery(recording, 2, 4)
        try {
            assertEquals(4L, query.scan { true })
            requests.clear()
            assertEquals(listOf(90, -4, 10000, 2), query.rows(0, 4).items.map { it.entry.id })
            assertEquals(listOf(0L to 2, 2L to 2), requests)
        } finally {
            query.close()
            source.close()
        }
    }

    @Test
    fun zeroAndFullMatchesAndBounds() = runBlocking {
        val source = source()
        val empty = StorageQuery(source.snapshot(), 2, 2)
        assertFailsWith<StorageFailure.InvalidRequest> { empty.rows(0, 1) }
        assertEquals(0L, empty.scan { false })
        assertTrue(empty.rows(0, 2).endReached)
        assertTrue(empty.rows(0, 2).items.isEmpty())
        assertFailsWith<StorageFailure.InvalidRequest> { empty.scan { true } }
        for ((start, limit) in listOf(-1L to 1, 0L to 0, 0L to 3)) {
            assertFailsWith<StorageFailure.InvalidRequest> { empty.rows(start, limit) }
        }
        empty.close()
        val full = StorageQuery(source.snapshot(), 2, 2)
        assertEquals(4L, full.scan { true })
        assertEquals(listOf(90, -4), full.rows(0, 2).items.map { it.entry.id })
        assertEquals(listOf(10000, 2), full.rows(2, 2).items.map { it.entry.id })
        full.close()
        source.close()
    }

    @Test
    fun closeDuringScanPreventsPublicationAndIsIdempotent() = runBlocking {
        val source = source()
        val query = StorageQuery(source.snapshot(), 2, 2)
        assertFailsWith<StorageFailure.Closed> {
            query.scan { query.close(); true }
        }
        query.close()
        assertFailsWith<StorageFailure.Closed> { query.rows(0, 1) }
        assertFailsWith<StorageFailure.Closed> { query.scan { true } }
        source.close()
    }

    @Test
    fun cancelledNewCallDoesNotEraseAnExistingResult() = runBlocking {
        val source = source()
        val query = StorageQuery(source.snapshot(), 2, 2)
        try {
            assertEquals(2L, query.scan { it.msg == "keep" })
            val cancelled = Job().apply { cancel() }
            assertFailsWith<CancellationException> { withContext(cancelled) { query.scan { true } } }
            assertEquals(listOf(90, 10000), query.rows(0, 2).items.map { it.entry.id })
        } finally {
            query.close()
            source.close()
        }
    }

    @Test
    fun cancellationDoesNotPublishPartialMatchesAndAllowsRetry() = runBlocking {
        val source = source()
        val query = StorageQuery(source.snapshot(), 2, 2)
        val job = Job()
        assertFailsWith<CancellationException> {
            withContext(job) { query.scan { job.cancel(); true } }
        }
        assertFailsWith<StorageFailure.InvalidRequest> { query.rows(0, 1) }
        assertEquals(2L, query.scan { it.msg == "keep" })
        assertEquals(listOf(90, 10000), query.rows(0, 2).items.map { it.entry.id })
        query.close()
        source.close()
    }

    @Test
    fun cancellationBeforeScanReturnDoesNotLeavePublishedIndex() = runBlocking {
        val source = source()
        val query = StorageQuery(source.snapshot(), 2, 2)
        val pending = LinkedBlockingQueue<Runnable>()
        val callerDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                pending.add(block)
            }
        }
        val caller = launch(callerDispatcher, start = CoroutineStart.UNDISPATCHED) {
            query.scan { true }
        }
        // The worker has completed and queued the return to the caller, but the caller has not resumed.
        val continuation = pending.poll(5, TimeUnit.SECONDS)
        assertTrue(continuation != null, "scan did not dispatch its result")
        caller.cancel()
        continuation.run()
        caller.join()
        assertTrue(caller.isCancelled)
        assertFailsWith<StorageFailure.InvalidRequest> { query.rows(0, 1) }
        assertEquals(4L, query.scan { true })
        query.close()
        source.close()
    }

    @Test
    fun positionOfMapsSparseIdsToFilteredOrdinals() = runBlocking {
        val source = source()
        val query = StorageQuery(source.snapshot(), 2, 2)
        assertFailsWith<StorageFailure.InvalidRequest> { query.positionOf(EntryId(90)) }
        assertEquals(2L, query.scan { it.id == -4 || it.id == 10000 })
        assertEquals(0L, query.positionOf(EntryId(-4)))
        assertEquals(1L, query.positionOf(EntryId(10000)))
        assertEquals(null, query.positionOf(EntryId(90)))
        assertEquals(null, query.positionOf(EntryId(-999)))
        query.close()
        assertFailsWith<StorageFailure.Closed> { query.positionOf(EntryId(-4)) }
        source.close()
    }

    @Test
    fun positionOfChecksClosureAfterResolve() = runBlocking {
        val source = source()
        val delegate = source.snapshot()
        lateinit var query: StorageQuery
        val snapshot = object : ReadSnapshot by delegate {
            override suspend fun resolve(id: EntryId): RowOrdinal? {
                val resolved = delegate.resolve(id)
                query.close()
                return resolved
            }
        }
        query = StorageQuery(snapshot, 2, 2)
        assertEquals(4L, query.scan { true })
        assertFailsWith<StorageFailure.Closed> { query.positionOf(EntryId(90)) }
        source.close()
    }

    @Test
    fun positionOfPropagatesCancellationDuringResolve() = runBlocking {
        val source = source()
        val delegate = source.snapshot()
        val job = Job()
        val snapshot = object : ReadSnapshot by delegate {
            override suspend fun resolve(id: EntryId): RowOrdinal? {
                val resolved = delegate.resolve(id)
                job.cancel()
                return resolved
            }
        }
        val query = StorageQuery(snapshot, 2, 2)
        assertEquals(4L, query.scan { true })
        assertFailsWith<CancellationException> { withContext(job) { query.positionOf(EntryId(90)) } }
        query.close()
        source.close()
    }

    @Test
    fun ordinalPagesAreBoundedVersionedAndSurviveClose() = runBlocking {
        val source = source()
        val snapshot = source.snapshot()
        val query = StorageQuery(snapshot, 2, 2)
        assertFailsWith<StorageFailure.InvalidRequest> { query.ordinals(0, 1) }
        assertEquals(2L, query.scan { it.msg == "keep" })
        val first = query.ordinals(0, 1)
        val second = query.ordinals(1, 2)
        assertEquals(snapshot.version, first.version)
        assertEquals(0L, first.start)
        assertEquals(1, first.size)
        assertEquals(RowOrdinal(0), first[0])
        assertEquals(false, first.endReached)
        assertEquals(RowOrdinal(2), second[0])
        assertTrue(second.endReached)
        val end = query.ordinals(Long.MAX_VALUE, 1)
        assertEquals(0, end.size)
        assertTrue(end.endReached)
        for ((start, limit) in listOf(-1L to 1, 0L to 0, 0L to 3)) {
            assertFailsWith<StorageFailure.InvalidRequest> { query.ordinals(start, limit) }
        }
        query.close()
        assertEquals(RowOrdinal(0), first[0])
        assertEquals(RowOrdinal(2), second[0])
        assertFailsWith<StorageFailure.Closed> { query.ordinals(0, 1) }
        source.close()
    }
}
