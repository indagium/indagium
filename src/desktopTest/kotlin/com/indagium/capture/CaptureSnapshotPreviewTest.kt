package com.indagium.capture

import com.indagium.utils.logDaySlot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The snapshot popover re-previews every second; none of those previews may rescan, re-hash or litter the capture root. */
@Suppress("MagicNumber")
class CaptureSnapshotPreviewTest {
    @BeforeTest
    fun reset() {
        CaptureExportPreviewCache.clear()
        LegacySparseAdapterCache.clear()
        CapturePreviewDiagnostics.envelopeEnabled = true
    }

    @AfterTest
    fun restore() = reset()

    @Test
    fun envelopePreviewMatchesTheFullScanAndReadsFewerBlocks() = withFixture { root ->
        val session = sparseSession(root, rows = 12, rowsPerBlock = 3)
        val requests = listOf(
            CaptureExportRequest(File(root, "a.zip"), CaptureRange.ALL, includeVideo = false, cutoffElapsedMs = 12_000),
            CaptureExportRequest(
                File(root, "b.zip"), CaptureRange.EXPLICIT, includeVideo = false, cutoffElapsedMs = 12_000,
                explicitStartElapsedMs = 1_500, explicitEndElapsedMs = 10_500,
            ),
            CaptureExportRequest(File(root, "c.zip"), CaptureRange.CUSTOM, customMinutes = 1, includeVideo = false, cutoffElapsedMs = 12_000),
        )
        for (request in requests) {
            CaptureExportPreviewCache.clear()
            CapturePreviewDiagnostics.envelopeEnabled = true
            val readsBefore = CapturePreviewDiagnostics.blockReads.get()
            val shortcut = CaptureArchiveExporter().preview(session, request)
            val shortcutReads = CapturePreviewDiagnostics.blockReads.get() - readsBefore

            CaptureExportPreviewCache.clear()
            CapturePreviewDiagnostics.envelopeEnabled = false
            val readsBeforeFull = CapturePreviewDiagnostics.blockReads.get()
            val full = CaptureArchiveExporter().preview(session, request)
            val fullReads = CapturePreviewDiagnostics.blockReads.get() - readsBeforeFull

            assertEquals(full, shortcut, "range ${request.range}")
            assertNotNull(shortcut.logStartMs)
            assertTrue(shortcutReads < fullReads, "range ${request.range}: $shortcutReads reads vs $fullReads for the full scan")
        }
        // ALL: first and last block are read, the two interior ones are answered from their envelope.
        CapturePreviewDiagnostics.envelopeEnabled = true
        CaptureExportPreviewCache.clear()
        val before = CapturePreviewDiagnostics.blockReads.get()
        val all = CaptureArchiveExporter().preview(session, requests[0])
        assertEquals(2, CapturePreviewDiagnostics.blockReads.get() - before)
        assertEquals(0L, all.logStartMs)
        assertEquals(11_000L, all.logEndMs)
    }

    @Test
    fun aSecondPreviewOfAnUnchangedStoppedSessionDoesNoWorkAndStillReportsTheDestination() = withFixture { root ->
        val session = sparseSession(root, rows = 12, rowsPerBlock = 3)
        val destination = File(root, "out.zip")
        val request = CaptureExportRequest(destination, CaptureRange.ALL, includeVideo = false, cutoffElapsedMs = 12_000)

        val first = CaptureArchiveExporter().preview(session, request)
        val computations = CapturePreviewDiagnostics.computations.get()
        val reads = CapturePreviewDiagnostics.blockReads.get()
        destination.writeText("exists now")
        // A new exporter per call, exactly as AppState.previewCaptureSnapshot does for a stopped capture.
        val second = CaptureArchiveExporter().preview(session, request)

        assertEquals(computations, CapturePreviewDiagnostics.computations.get())
        assertEquals(reads, CapturePreviewDiagnostics.blockReads.get(), "the log must not be read again")
        assertFalse(first.destinationExists)
        assertTrue(second.destinationExists)
        assertEquals(first.copy(destinationExists = true), second)

        // A different request, or a log that changed, is not answered from the memo.
        CaptureArchiveExporter().preview(session, request.copy(cutoffElapsedMs = 6_000))
        assertEquals(computations + 1, CapturePreviewDiagnostics.computations.get())
        session.logFile.appendText("")
        session.logFile.setLastModified(session.logFile.lastModified() + 5_000)
        CaptureArchiveExporter().preview(session, request)
        assertEquals(computations + 2, CapturePreviewDiagnostics.computations.get())
    }

    @Test
    fun previewSkipsBlockChecksumsButExportStillVerifiesThem() = withFixture { root ->
        val session = sparseSession(root, rows = 12, rowsPerBlock = 3)
        // Flip one byte inside the first block's row text without changing any length.
        val bytes = session.logFile.readBytes()
        val index = String(bytes, Charsets.UTF_8).indexOf("row-0")
        bytes[index + 4] = 'X'.code.toByte()
        session.logFile.writeBytes(bytes)
        val request = CaptureExportRequest(File(root, "out.zip"), CaptureRange.ALL, includeVideo = false, cutoffElapsedMs = 12_000)

        val preview = CaptureArchiveExporter().preview(session, request)
        assertNotNull(preview.logStartMs)

        val failure = assertFailsWith<Exception> { CaptureArchiveExporter().export(session, request) }
        assertTrue(failure.message.orEmpty().contains("checksum", ignoreCase = true), failure.message)
    }

    @Test
    fun aLegacySessionPreviewReusesOneConversionAndCreatesNothingInTheCaptureRoot() = withFixture { root ->
        val session = legacySession(root, rows = 6)
        val before = root.list().orEmpty().sorted()
        val sessionFilesBefore = session.directory.walkTopDown().map { it.relativeTo(root).path }.sorted().toList()
        val conversions = CapturePreviewDiagnostics.legacyConversions.get()

        val all = CaptureArchiveExporter().preview(
            session,
            CaptureExportRequest(File(root, "a.zip"), CaptureRange.ALL, includeVideo = false, cutoffElapsedMs = 6_000),
        )
        // A different range cannot come from the preview memo, but the converted index is reused.
        CaptureArchiveExporter().preview(
            session,
            CaptureExportRequest(File(root, "b.zip"), CaptureRange.LAST_FIVE, includeVideo = false, cutoffElapsedMs = 6_000),
        )

        assertNotNull(all.logStartMs)
        assertEquals(conversions + 1, CapturePreviewDiagnostics.legacyConversions.get(), "the conversion is cached")
        assertEquals(before, root.list().orEmpty().sorted(), "nothing may be created in the capture root")
        assertEquals(sessionFilesBefore, session.directory.walkTopDown().map { it.relativeTo(root).path }.sorted().toList())
    }

    @Test
    fun staleSparseSourceDirectoriesAreSweptFromACaptureRootButFreshAndLiveOnesSurvive() = withFixture { root ->
        val stale = File(root, ".sparse-source-aaa").apply { mkdirs() }
        File(stale, "mapping").mkdirs()
        File(stale, "mapping/blocks.jsonl").writeText("x")
        val fresh = File(root, ".sparse-source-bbb").apply { mkdirs() }
        val inUse = File(root, ".sparse-source-ccc").apply { mkdirs() }
        val session = File(root, "20260101-session").apply { mkdirs() }
        File(session, ".sparse-source-inside").mkdirs()
        val old = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        listOf(stale, File(stale, "mapping"), File(stale, "mapping/blocks.jsonl"), inUse, File(session, ".sparse-source-inside"))
            .forEach { it.setLastModified(old) }
        registerLegacyAdapterDirectory(inUse)
        try {
            assertEquals(1, sweepStaleLegacySparseSources(root))
        } finally {
            releaseLegacyAdapterDirectory(inUse)
        }

        assertFalse(stale.exists())
        assertTrue(fresh.exists(), "younger than the grace period")
        assertTrue(session.exists() && File(session, ".sparse-source-inside").exists(), "a session directory is never entered")
    }

    private fun sparseSession(root: File, rows: Int, rowsPerBlock: Int): CaptureSession {
        val directory = File(root, "session").apply { mkdirs() }
        val session = baseSession(directory, CaptureStorageVersion.BLOCKS_V2, elapsedMs = rows * 1_000L)
        session.logFile.parentFile.mkdirs()
        var offset = 0L
        CaptureBlockWriter(session.blockIndexFile, maxRows = rowsPerBlock).use { writer ->
            session.logFile.outputStream().use { output ->
                repeat(rows) { index ->
                    val raw = (line(index) + "\n").toByteArray()
                    output.write(raw)
                    writer.append(offset, raw, index + 1, index * 1_000L, 0, resolvedCaptureElapsedMs = index * 1_000L)
                    offset += raw.size
                }
            }
        }
        val segment = CaptureSyncSegment(
            0, 1, rows + 1,
            Instant.parse("2026-10-21T10:00:00Z").toEpochMilli(),
            0L,
            CaptureSyncReliability.RELIABLE,
            CaptureClockReference(36_000_000L, logDaySlot(10, 21).toInt(), 2026),
        )
        session.syncFile.parentFile.mkdirs()
        session.syncFile.writeText(CaptureSyncMetadata(offset, rows, 0, rows * 1_000L, listOf(segment)).toJson())
        writeSessionJson(directory, session)
        return session
    }

    private fun legacySession(root: File, rows: Int): CaptureSession {
        val directory = File(root, "session").apply { mkdirs() }
        val session = baseSession(directory, CaptureStorageVersion.LEGACY_DENSE, elapsedMs = rows * 1_000L)
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        var offset = 0L
        session.logFile.outputStream().use { log ->
            session.indexFile.bufferedWriter().use { index ->
                repeat(rows) { ordinal ->
                    val raw = (line(ordinal) + "\n").toByteArray()
                    log.write(raw)
                    index.appendLine("{\"byteOffset\":$offset,\"byteLength\":${raw.size},\"elapsedMs\":${ordinal * 1_000L},\"rowOrdinal\":${ordinal + 1}}")
                    offset += raw.size
                }
            }
        }
        return session
    }

    private fun baseSession(directory: File, storage: CaptureStorageVersion, elapsedMs: Long) = CaptureSession(
        id = "session",
        directory = directory,
        device = CaptureDevice("test-device", "device"),
        settings = CaptureSettings(recordVideo = false, mirror = false, freeSpaceReserveBytes = 0),
        startedEpochMs = Instant.parse("2026-10-21T10:00:00Z").toEpochMilli(),
        elapsedMs = elapsedMs,
        status = CaptureStatus.STOPPED,
        storageVersion = storage,
    )

    private fun writeSessionJson(directory: File, session: CaptureSession) {
        File(directory, "session.json").writeText(buildJsonObject {
            put("formatVersion", 1)
            put("storageVersion", 2)
            put("id", session.id)
            put("device", buildJsonObject { put("serial", session.device.serial) })
            put("settings", Json.parseToJsonElement(captureSettingsToJson(session.settings)))
            put("startedEpochMs", session.startedEpochMs)
            put("elapsedMs", session.elapsedMs)
            put("status", CaptureStatus.STOPPED.name)
        }.toString())
    }

    private fun line(second: Int) = "10-21 10:00:%02d.000  100  101 I Tag: row-%d".format(second, second)

    private fun withFixture(test: (File) -> Unit) {
        val root = createTempDirectory("capture-snapshot-preview").toFile()
        try {
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
