package com.indagium.storage

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StorageScanLimiterTest {
    @Test
    fun queuedScansCancelOrCloseAndPermitsAreReleased() = runBlocking {
        val source = LegacyInMemorySource(
            SourceId("scan-limiter"), LogFormat.LOGCAT,
            List(32) { LogEntry(it, "12:00:00.000", LogLevel.I, "test", "row $it") },
            maxPageRows = 8,
        )
        val release = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val bothEntered = CompletableDeferred<Unit>()

        suspend fun blockingQuery(): StorageQuery {
            val delegate = source.snapshot()
            val snapshot = object : ReadSnapshot by delegate {
                override suspend fun rows(start: RowOrdinal, limit: Int): StoragePage<LegacyDecodedRow> {
                    if (start.value == 0L) {
                        if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
                        release.await()
                    }
                    return delegate.rows(start, limit)
                }
            }
            return StorageQuery(snapshot, 8, 8)
        }
        val first = blockingQuery()
        val second = blockingQuery()
        val cancelled = blockingQuery()
        val closed = blockingQuery()
        try {
            val firstScan = async { first.scan { true } }
            val secondScan = async { second.scan { true } }
            withTimeout(5_000) { bothEntered.await() }
            val cancelledScan = async { cancelled.scan { true } }
            val closedScan = async { runCatching { closed.scan { true } } }
            assertNull(withTimeoutOrNull(100) { cancelledScan.await() })
            assertEquals(2, entered.get())
            cancelledScan.cancelAndJoin()
            closed.close()
            release.complete(Unit)
            assertEquals(32L, withTimeout(5_000) { firstScan.await() })
            assertEquals(32L, withTimeout(5_000) { secondScan.await() })
            val failure = withTimeout(5_000) { closedScan.await() }.exceptionOrNull()
            kotlin.test.assertIs<StorageFailure.Closed>(failure)
            assertEquals(2, entered.get())
            assertFailsWith<StorageFailure.InvalidRequest> { cancelled.rows(0, 1) }
            assertEquals(32L, withTimeout(5_000) { cancelled.scan { true } })
        } finally {
            release.complete(Unit)
            first.close()
            second.close()
            cancelled.close()
            closed.close()
            source.close()
        }
    }

    @Test
    fun failedScanReleasesPermitAndAllowsRetry() = runBlocking {
        val source = LegacyInMemorySource(
            SourceId("scan-failure"), LogFormat.LOGCAT,
            List(16) { LogEntry(it, "12:00:00.000", LogLevel.I, "test", "row $it") },
            maxPageRows = 4,
        )
        val query = StorageQuery(source.snapshot(), 4, 4)
        try {
            repeat(2) {
                assertFailsWith<IllegalStateException> { query.scan { error("predicate failed") } }
            }
            assertEquals(16L, withTimeout(5_000) { query.scan { true } })
        } finally {
            query.close()
            source.close()
        }
    }
}
