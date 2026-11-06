package com.indagium.storage

import com.indagium.utils.LogcatLineParser
import com.indagium.utils.parseLogcat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexedFileSourceTest {
    private val sourceId = SourceId("generated")

    @Test
    fun pagesMatchLegacyAcrossPhysicalLinesAndMalformedUtf8() = runBlocking {
        val path = Files.createTempFile("indexed-log-", ".txt")
        try {
            val prefix = "\uFEFF----- beginning\r\n\r\n01-02 03:04:05.123 12 13 I Tag: hello\rRAW text\n----- skip\n".toByteArray()
            val malformed = byteArrayOf(0xc3.toByte(), 0x28, 10)
            val tail = ("x".repeat(300_000) + "\nlast without newline").toByteArray()
            Files.write(path, prefix + malformed + tail)
            val expected = parseLogcat(path.toFile())
            val source = IndexedFileSource.open(sourceId, path, maxPageRows = 2)
            val snapshot = source.snapshot()
            assertEquals(expected.size.toLong(), snapshot.rowCount)
            val actual = (0 until expected.size step 2).flatMap { snapshot.rows(RowOrdinal(it.toLong()), 2).items }
            assertEquals(expected, actual.map { it.entry })
            assertEquals(expected.map { it.id }, actual.map { it.ref.id.value })
            assertEquals(RowOrdinal(2), snapshot.resolve(EntryId(3)))
            assertNull(snapshot.resolve(EntryId(0)))
            assertNull(snapshot.resolve(EntryId(expected.size + 1)))
            assertTrue(snapshot.rows(RowOrdinal(Long.MAX_VALUE), 1).endReached)
            assertFailsWith<StorageFailure.InvalidRequest> { snapshot.rows(RowOrdinal(-1), 1) }
            assertFailsWith<StorageFailure.InvalidRequest> { snapshot.rows(RowOrdinal(0), 3) }
            snapshot.close()
            source.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun carriageReturnAndUnicodeCrossReadBufferBoundaries(): Unit = runBlocking {
        val path = Files.createTempFile("indexed-buffer-boundary-", ".txt")
        try {
            val first = "x".repeat(8191)
            val second = "y".repeat(8190) + "Привіт"
            Files.writeString(path, first + "\r\n" + second + "\r" + "last")
            val expected = parseLogcat(path.toFile())
            val source = IndexedFileSource.open(sourceId, path, maxPageRows = 2)
            val snapshot = source.snapshot()
            try {
                assertEquals(expected.take(2), snapshot.rows(RowOrdinal(0), 2).items.map { it.entry })
                assertEquals(expected.drop(2), snapshot.rows(RowOrdinal(2), 2).items.map { it.entry })
            } finally {
                snapshot.close()
                source.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun independentLeasesAndChangesAreExplicit() = runBlocking {
        val path = Files.createTempFile("indexed-log-", ".txt")
        try {
            Files.writeString(path, "one\ntwo\n")
            val source = IndexedFileSource.open(sourceId, path)
            val first = source.snapshot()
            val second = source.snapshot()
            val page = first.rows(RowOrdinal(0), 1)
            source.close()
            assertFailsWith<StorageFailure.Closed> { source.snapshot() }
            first.close()
            assertFailsWith<StorageFailure.Closed> { first.resolve(EntryId(1)) }
            assertEquals("one", page.items.single().entry.msg)
            assertEquals("two", second.rows(RowOrdinal(1), 1).items.single().entry.msg)
            Files.writeString(path, "one\ntwo\nchanged\n")
            assertFailsWith<IndexedFileFailure.Changed> { second.rows(RowOrdinal(0), 1) }
            assertFailsWith<IndexedFileFailure.Changed> { second.resolve(EntryId(1)) }
            val reopened = IndexedFileSource.open(sourceId, path)
            assertNotEquals(second.version, reopened.snapshot().version)
            second.close()
            reopened.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun mutationDuringIndexingAndUnsupportedEncodingAreRejected(): Unit = runBlocking {
        val path = Files.createTempFile("indexed-log-", ".txt")
        try {
            Files.writeString(path, "one\ntwo\n")
            var changed = false
            assertFailsWith<IndexedFileFailure.Changed> {
                IndexedFileSource.open(sourceId, path, onScanLine = {
                    if (!changed) {
                        changed = true
                        Files.writeString(path, "replacement is longer\n")
                    }
                })
            }
            Files.write(path, byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x61, 0))
            assertFailsWith<IndexedFileFailure.Unsupported> { IndexedFileSource.open(sourceId, path) }
            Files.write(path, "some text\n".toByteArray(Charsets.UTF_16LE))
            assertFailsWith<IndexedFileFailure.Unsupported> { IndexedFileSource.open(sourceId, path) }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun cancelledOperationsDoNotSucceed() = runBlocking {
        val path = Files.createTempFile("indexed-log-", ".txt")
        try {
            Files.writeString(path, "one\n")
            val source = IndexedFileSource.open(sourceId, path)
            val snapshot = source.snapshot()
            val cancelled = Job().apply { cancel() }
            assertFailsWith<CancellationException> { withContext(cancelled) { snapshot.rows(RowOrdinal(0), 1) } }
            assertFailsWith<CancellationException> { withContext(cancelled) { IndexedFileSource.open(sourceId, path) } }
            snapshot.close()
            source.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun queuedScansAndPagesCanBeCancelledAndPermitsAreReleased(): Unit = runBlocking {
        val path = Files.createTempFile("indexed-log-", ".txt")
        val entered = CountDownLatch(IndexedFileSource.MAX_CONCURRENT_READS)
        val release = CountDownLatch(1)
        try {
            Files.writeString(path, "one\n")
            val initial = IndexedFileSource.open(sourceId, path)
            val snapshot = initial.snapshot()
            val active = List(IndexedFileSource.MAX_CONCURRENT_READS) {
                async(Dispatchers.IO) {
                    IndexedFileSource.open(sourceId, path, onScanLine = {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    })
                }
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val additionalEntries = AtomicInteger()
            val queuedScan = async(Dispatchers.IO) {
                IndexedFileSource.open(sourceId, path, onScanLine = { additionalEntries.incrementAndGet() })
            }
            val queuedPage = async(Dispatchers.IO) { snapshot.rows(RowOrdinal(0), 1) }
            delay(100)
            assertEquals(0, additionalEntries.get())
            assertTrue(!queuedPage.isCompleted)
            withTimeout(5_000) {
                queuedScan.cancelAndJoin()
                queuedPage.cancelAndJoin()
            }
            release.countDown()
            withTimeout(5_000) { active.forEach { it.await().close() } }
            assertEquals("one", snapshot.rows(RowOrdinal(0), 1).items.single().entry.msg)
            snapshot.close()
            initial.close()
        } finally {
            release.countDown()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun missingAtOpenIsIoFailureAndDeletionAfterOpenIsChange(): Unit = runBlocking {
        val path = Files.createTempFile("indexed-log-", ".txt")
        try {
            Files.writeString(path, "one\n")
            val source = IndexedFileSource.open(sourceId, path)
            val snapshot = source.snapshot()
            Files.delete(path)
            assertFailsWith<IndexedFileFailure.Changed> { snapshot.rows(RowOrdinal(0), 1) }
            assertFailsWith<IndexedFileFailure.Io> { IndexedFileSource.open(sourceId, path) }
            snapshot.close()
            source.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun reusableParserPreservesSkipRawWrapperAndIdOverflow() {
        val parser = LogcatLineParser()
        assertNull(parser.parse("  ----- beginning", 17))
        assertNull(parser.parse("  ", 17))
        val wrapped = parser.parse("I/RAW: 01-02 03:04:05.123 12 13 I Tag: hello", Int.MAX_VALUE)!!
        assertEquals(Int.MAX_VALUE, wrapped.id)
        assertEquals("Tag", wrapped.tag)
        assertEquals("hello", wrapped.msg)
        val raw = parser.parse("  unstructured  ", Int.MIN_VALUE)!!
        assertEquals(Int.MIN_VALUE, raw.id)
        assertEquals("RAW", raw.tag)
        assertEquals("  unstructured", raw.msg)
        val sequence = com.indagium.utils.parseLogcatLines(
            sequenceOf("----- skipped", "first", "", "second"), Int.MAX_VALUE,
        )
        assertEquals(listOf(Int.MAX_VALUE, Int.MIN_VALUE), sequence.map { it.id })
    }
}
