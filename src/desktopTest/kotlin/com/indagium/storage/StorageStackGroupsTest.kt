package com.indagium.storage

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import com.indagium.utils.computeStackTraceGroups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StorageStackGroupsTest {
    private fun row(id: Int, message: String, pid: Int = 1, tid: Int = 1, tag: String = "app") =
        LogEntry(id, "", LogLevel.E, tag, message, pid, tid)

    @Test
    fun pageBoundariesPreservePreludeInterleavingAndSignatures() = runBlocking {
        val rows = listOf(
            row(20, "caught an exception"),
            row(21, "FATAL EXCEPTION: main"),
            row(22, "java.lang.IllegalStateException: failed"),
            row(23, "unrelated", pid = 2),
            row(24, "    at android.os.Framework.run(Framework.java:10)"),
            row(25, "    at com.example.Work.run(Work.kt:42)"),
            row(26, "done"),
            row(4, "java.lang.NullPointerException: second", pid = 3),
            row(5, "    at com.example.Other.go(Other.kt:2)", pid = 3),
        )
        val source = LegacyInMemorySource(SourceId("stack-fixture"), LogFormat.LOGCAT, rows, maxPageRows = 2)
        val snapshot = source.snapshot()
        try {
            val result = scanStorageStackGroups(snapshot, LogFormat.LOGCAT, 2)
            assertEquals(snapshot.version, result.version)
            assertEquals(listOf(4, 20), result.groups.map { it.rid })
            val first = result.groups[0]
            assertEquals("EXC:java.lang.NullPointerException|com.example.Other.go(Other.kt:2)", first.signature)
            assertContentEquals(intArrayOf(5), first.memberIds())
            val second = result.groups[1]
            assertTrue(second.isFatal)
            assertContentEquals(intArrayOf(21, 22, 24, 25), second.memberIds())
            assertEquals("EXC:java.lang.IllegalStateException|com.example.Work.run(Work.kt:42)", second.signature)
            assertEquals(computeStackTraceGroups(rows), result.groups.map { it.toLegacy() })
            val copied = second.memberIds()
            copied[0] = -999
            assertEquals(21, second.memberId(0))
        } finally {
            snapshot.close()
            source.close()
        }
    }

    @Test
    fun interleaveBoundaryIncludes64AndRejects65() = runBlocking {
        val rows = mutableListOf(row(1, "java.lang.IllegalStateException: accepted"))
        repeat(63) { rows.add(row(1000 + it, "other", pid = 2)) }
        rows.add(row(2, "    at example.Accepted.run(A.kt:1)"))
        rows.add(row(3, "finished"))
        rows.add(row(4, "java.lang.IllegalStateException: rejected"))
        repeat(64) { rows.add(row(2000 + it, "other", pid = 2)) }
        rows.add(row(5, "    at example.Rejected.run(B.kt:1)"))
        val source = LegacyInMemorySource(SourceId("gaps"), LogFormat.LOGCAT, rows, maxPageRows = 7)
        val snapshot = source.snapshot()
        try {
            val result = scanStorageStackGroups(snapshot, LogFormat.LOGCAT, 7)
            assertEquals(listOf(1), result.groups.map { it.rid })
            assertContentEquals(intArrayOf(2), result.groups.single().memberIds())
            assertEquals(computeStackTraceGroups(rows), result.groups.map { it.toLegacy() })
        } finally {
            snapshot.close()
            source.close()
        }
    }

    @Test
    fun duplicateRootIdsKeepStableFlushOrderInLegacyScanner() {
        val rows = listOf(
            row(100, "java.lang.IllegalStateException: first", pid = 1),
            row(100, "java.lang.NullPointerException: second", pid = 2),
            row(101, "    at app.Second.run(Second.kt:1)", pid = 2),
            row(102, "done", pid = 2),
            row(103, "    at app.First.run(First.kt:1)", pid = 1),
            row(104, "done", pid = 1),
        )
        val groups = computeStackTraceGroups(rows)
        assertEquals(listOf(100, 100), groups.map { it.rid })
        assertEquals(
            listOf(
                "EXC:java.lang.NullPointerException|app.Second.run(Second.kt:1)",
                "EXC:java.lang.IllegalStateException|app.First.run(First.kt:1)",
            ),
            groups.map { it.signature },
        )
        assertEquals(listOf(listOf(101), listOf(103)), groups.map { it.memberIds })
    }

    @Test
    fun dltAndCancellationNeverPublishGroups() = runBlocking {
        val rows = listOf(row(1, "FATAL EXCEPTION: main"), row(2, "    at app.Foo.run(Foo.kt:1)"))
        val source = LegacyInMemorySource(SourceId("dlt"), LogFormat.DLT, rows)
        val snapshot = source.snapshot()
        try {
            assertTrue(scanStorageStackGroups(snapshot, LogFormat.DLT, 1).groups.isEmpty())
            val cancelled = Job().apply { cancel() }
            assertFailsWith<CancellationException> {
                withContext(cancelled) { scanStorageStackGroups(snapshot, LogFormat.LOGCAT, 1) }
            }
            assertEquals(1, scanStorageStackGroups(snapshot, LogFormat.LOGCAT, 1).groups.size)
        } finally {
            snapshot.close()
            source.close()
        }
    }

    @Test
    fun oversizedTraceFailsExplicitlyWithoutPartialResult(): Unit = runBlocking {
        val rows = listOf(row(1, "FATAL EXCEPTION: main")) +
            (2..7).map { row(it, "    at app.Foo.run(Foo.kt:$it)") }
        val source = LegacyInMemorySource(SourceId("limit"), LogFormat.LOGCAT, rows, maxPageRows = 2)
        val snapshot = source.snapshot()
        try {
            val error = assertFailsWith<StackScanLimitExceeded> {
                scanStorageStackGroups(snapshot, LogFormat.LOGCAT, 2, StorageStackLimits(maxMembers = 2))
            }
            assertEquals("members", error.limitName)
            assertEquals(2L, error.limit)
            val complete = scanStorageStackGroups(snapshot, LogFormat.LOGCAT, 2)
            assertContentEquals(intArrayOf(2, 3, 4, 5, 6, 7), complete.groups.single().memberIds())
        } finally {
            snapshot.close()
            source.close()
        }
    }

    @Test
    fun rowAndOpenStateLimitsFailExplicitly(): Unit = runBlocking {
        val rows = listOf(row(1, "FATAL EXCEPTION: first", pid = 1), row(2, "FATAL EXCEPTION: second", pid = 2))
        val source = LegacyInMemorySource(SourceId("limits"), LogFormat.LOGCAT, rows, maxPageRows = 1)
        val snapshot = source.snapshot()
        try {
            val rowError = assertFailsWith<StackScanLimitExceeded> {
                scanStorageStackGroups(snapshot, LogFormat.LOGCAT, 1, StorageStackLimits(maxRows = 1))
            }
            assertEquals("rows", rowError.limitName)
            val stateError = assertFailsWith<StackScanLimitExceeded> {
                scanStorageStackGroups(snapshot, LogFormat.LOGCAT, 1, StorageStackLimits(maxOpenStates = 1))
            }
            assertEquals("open states", stateError.limitName)
        } finally {
            snapshot.close()
            source.close()
        }
    }

    @Test
    fun changedVersionDuringScanDoesNotReturnPartialGroups(): Unit = runBlocking {
        val rows = listOf(row(1, "FATAL EXCEPTION: main"), row(2, "    at app.Foo.run(Foo.kt:1)"))
        val source = LegacyInMemorySource(SourceId("version"), LogFormat.LOGCAT, rows, maxPageRows = 1)
        val original = source.snapshot()
        val changing = object : ReadSnapshot by original {
            override suspend fun rows(start: RowOrdinal, limit: Int): StoragePage<LegacyDecodedRow> {
                val page = original.rows(start, limit)
                return if (start.value == 1L) page.copy(version = page.version.copy(revision = 1)) else page
            }
        }
        try {
            assertFailsWith<IllegalStateException> { scanStorageStackGroups(changing, LogFormat.LOGCAT, 1) }
        } finally {
            original.close()
            source.close()
        }
    }
}
