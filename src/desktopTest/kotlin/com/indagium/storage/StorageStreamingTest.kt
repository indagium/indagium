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

class StorageStreamingTest {
    @Test
    fun streamsInOrderAndStopsImmediatelyOnCancellation(): Unit = runBlocking {
        val rows = (1..5).map { LogEntry(it, "", LogLevel.I, "tag", "row $it") }
        val source = LegacyInMemorySource(SourceId("stream"), LogFormat.LOGCAT, rows, maxPageRows = 2)
        val query = StorageQuery(source.snapshot(), 2, 2)
        try {
            query.scan { it.id != 3 }
            val pages = mutableListOf<List<Int>>()
            query.forEachPage(2) { page -> pages.add(page.items.map { it.ref.id.value }) }
            assertEquals(listOf(listOf(1, 2), listOf(4, 5)), pages)
            val job = Job()
            var consumed = 0
            assertFailsWith<CancellationException> {
                withContext(job) {
                    query.forEachPage(1) { consumed++; job.cancel() }
                }
            }
            assertEquals(1, consumed)
            assertFailsWith<StorageFailure.InvalidRequest> { query.forEachPage(0) {} }
        } finally {
            query.close()
            source.close()
        }
    }
}
