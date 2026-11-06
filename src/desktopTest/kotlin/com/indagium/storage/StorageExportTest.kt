package com.indagium.storage

import com.indagium.model.AppSettings
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.utils.buildFilteredCsv
import com.indagium.utils.buildFilteredTxt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageExportTest {
    private val rows = listOf(
        LogEntry(10, "23:59:59.900", LogLevel.I, "hidden", "hidden", pid = 9, tid = 7),
        LogEntry(20, "00:00:00.100", LogLevel.W, "App", "denied, \"Привіт\"\nnext", pid = 9, tid = 7,
            dltEcuId = "ECU", dltAppId = "APP", dltContextId = "CTX", dltTimestamp = 42),
        LogEntry(30, "00:00:00.300", LogLevel.I, "Other", "denied no pid"),
    )
    private val settings = AppSettings().copy(
        copyRowNumber = true, showRowNumbers = true, copyTimeDelta = true, copyPidTid = true, copyPidAsName = true,
    )
    private val names = mapOf(9 to "proc")
    private val commit = StorageExportCommitFence { it() }

    private fun tab(format: LogFormat, selected: Set<Int>) = LogTab(
        id = "test", filename = "test.log", logData = rows, rmap = rows.associateBy { it.id },
        filter = Filter(mode = FilterMode.KEYWORD, kwText = "denied"),
        analysis = LogAnalysis(processNames = names), selected = selected, showTimeDelta = true, logFormat = format,
    )

    @Test
    fun filteredExportMatchesLegacyAndExactTxtAndCsvWithHiddenAnchor() = runBlocking {
        val directory = Files.createTempDirectory("storage-export-")
        val source = LegacyInMemorySource(SourceId("generated"), LogFormat.LOGCAT, rows, maxPageRows = 1)
        val query = StorageQuery(source.snapshot(), 1, 1)
        val anchor = source.snapshot()
        try {
            assertEquals(2L, query.scan { it.msg.contains("denied") })
            val spec = StorageExportSpec(LogFormat.LOGCAT, settings, true, 10, names, csv = false)
            val output = directory.resolve("export.txt").toFile()
            exportStorageQuery(query, anchor, output, spec, 1, commit)
            val txt = output.readText()
            assertEquals(
                "20  +0.200  00:00:00.100  proc 7  W/App  denied, \"Привіт\"\nnext\n" +
                    "30  +0.400  00:00:00.300  I/Other  denied no pid\n",
                txt,
            )
            assertEquals(buildFilteredTxt(tab(LogFormat.LOGCAT, setOf(10)), settings), txt)
            exportStorageQuery(query, anchor, output, spec.copy(csv = true), 1, commit)
            val csv = output.readText()
            assertEquals(
                "row_number,time_delta,ts,pid,tid,pid_name,level,tag,msg\n" +
                    "20,+0.200,00:00:00.100,9,7,proc,W,App,\"denied, \"\"Привіт\"\"\nnext\"\n" +
                    "30,+0.400,00:00:00.300,,,,I,Other,denied no pid\n",
                csv,
            )
            assertEquals(buildFilteredCsv(tab(LogFormat.LOGCAT, setOf(10)), settings), csv)
        } finally {
            anchor.close()
            query.close()
            source.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingSelectedAnchorFallsBackToPreviousFilteredRowAndDltCsvMatchesLegacy() = runBlocking {
        val directory = Files.createTempDirectory("storage-export-dlt-")
        val source = LegacyInMemorySource(SourceId("generated"), LogFormat.DLT, rows, maxPageRows = 1)
        val query = StorageQuery(source.snapshot(), 1, 1)
        val anchor = source.snapshot()
        try {
            query.scan { it.msg.contains("denied") }
            val spec = StorageExportSpec(LogFormat.DLT, settings, true, -99, names, csv = true)
            val output = directory.resolve("export.csv").toFile()
            exportStorageQuery(query, anchor, output, spec, 1, commit)
            val result = output.readText()
            assertEquals(buildFilteredCsv(tab(LogFormat.DLT, setOf(-99)), settings), result)
            assertTrue(result.contains("20,,00:00:00.100,9,7,proc,W,App,ECU,APP,CTX,,42,,"))
            assertTrue(result.contains("30,+0.200,00:00:00.300,,,,I,Other,,,,,,,denied no pid"))
        } finally {
            anchor.close()
            query.close()
            source.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun finalValidationFailurePreservesDestinationAndCleansTemporaryFile(): Unit = runBlocking {
        val directory = Files.createTempDirectory("storage-export-failure-")
        val source = LegacyInMemorySource(SourceId("generated"), LogFormat.LOGCAT, rows)
        val query = StorageQuery(source.snapshot(), 2, 2)
        val original = source.snapshot()
        val failing = object : ReadSnapshot by original {
            override suspend fun resolve(id: EntryId): RowOrdinal? {
                if (id.value == 0) error("source changed")
                return original.resolve(id)
            }
        }
        try {
            query.scan { true }
            val output = directory.resolve("export.txt").toFile().also { it.writeText("old content") }
            val spec = StorageExportSpec(LogFormat.LOGCAT, settings, true, null, names, csv = false)
            assertFailsWith<IllegalStateException> { exportStorageQuery(query, failing, output, spec, 2, commit) }
            assertEquals("old content", output.readText())
            assertEquals(listOf("export.txt"), Files.list(directory).use { it.map { p -> p.fileName.toString() }.toList() })
        } finally {
            original.close()
            query.close()
            source.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancellationDuringPageReadLeavesOldDestinationIntact(): Unit = runBlocking {
        val directory = Files.createTempDirectory("storage-export-cancel-")
        val source = LegacyInMemorySource(SourceId("generated"), LogFormat.LOGCAT, rows, maxPageRows = 1)
        val original = source.snapshot()
        val entered = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        val armed = AtomicBoolean(false)
        val blocking = object : ReadSnapshot by original {
            override suspend fun rows(start: RowOrdinal, limit: Int): StoragePage<LegacyDecodedRow> {
                if (armed.get()) {
                    entered.complete(Unit)
                    waiting.await()
                }
                return original.rows(start, limit)
            }
        }
        val query = StorageQuery(blocking, 1, 1)
        val anchor = source.snapshot()
        try {
            query.scan { true }
            val output = directory.resolve("export.txt").toFile().also { it.writeText("old content") }
            val spec = StorageExportSpec(LogFormat.LOGCAT, settings, true, null, names, csv = false)
            armed.set(true)
            val job = launch(Dispatchers.Default) { exportStorageQuery(query, anchor, output, spec, 1, commit) }
            withTimeout(5_000) { entered.await() }
            job.cancelAndJoin()
            assertEquals("old content", output.readText())
            assertFalse(Files.list(directory).use { stream -> stream.anyMatch { it.fileName.toString().endsWith(".tmp") } })
        } finally {
            waiting.complete(Unit)
            anchor.close()
            query.close()
            source.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectedCommitPreservesDestinationAndRemovesTemporaryFile(): Unit = runBlocking {
        val directory = Files.createTempDirectory("storage-export-fence-")
        val source = LegacyInMemorySource(SourceId("generated"), LogFormat.LOGCAT, rows)
        val query = StorageQuery(source.snapshot(), 2, 2)
        val anchor = source.snapshot()
        try {
            query.scan { true }
            val output = directory.resolve("export.txt").toFile().also { it.writeText("old") }
            val spec = StorageExportSpec(LogFormat.LOGCAT, settings, true, null, names, csv = false)
            val invalidated = StorageExportCommitFence { throw StorageFailure.Closed() }
            assertFailsWith<StorageFailure.Closed> { exportStorageQuery(query, anchor, output, spec, 2, invalidated) }
            assertEquals("old", output.readText())
            assertEquals(listOf("export.txt"), Files.list(directory).use { it.map { p -> p.fileName.toString() }.toList() })
        } finally {
            anchor.close()
            query.close()
            source.close()
            directory.toFile().deleteRecursively()
        }
    }
}
