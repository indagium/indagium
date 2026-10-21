package com.indagium.capture

import com.indagium.utils.logDaySlot
import com.indagium.utils.parseLogcat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("MagicNumber")
class CaptureSparseRecoveryTest {
    @Test
    fun recoversCompleteUnindexedTailWithoutInventingTimingOrKeepingPartialRecords() = withFixture { root ->
        val session = fixture(root, CaptureStatus.RECORDING, rows = 1)
        val firstRaw = session.logFile.readText()
        val tail = line(1) + "\n"
        session.logFile.appendText(tail + "10-21 10:00:02.000  100  101 I Tag: interrupted")
        session.blockIndexFile.appendText("{\"v\":1")

        val recovered = CaptureRecorder(root).use { it.recoverSessions().single() }
        val metadata = requireNotNull(recovered.syncMetadataOverride)
        val blocks = CaptureBlockIndex.read(recovered.blockIndexFile, recovered.logFile)

        assertEquals(CaptureStatus.INTERRUPTED, recovered.status)
        assertEquals(firstRaw + tail, recovered.logFile.readText())
        assertEquals(2, blocks.indexedRows)
        assertEquals(listOf(CaptureSyncReliability.RELIABLE, CaptureSyncReliability.UNCERTAIN), metadata.segments.map { it.reliability })
        assertTrue(metadata.uncertainBoundary)
        assertTrue(blocks.blocks.last().recovered)
        val entries = parseLogcat(recovered.logFile)
        assertEquals(0L, sparseCaptureTimingPoint(entries[0], 1, metadata)?.captureElapsedMs)
        assertNull(sparseCaptureTimingPoint(entries[1], 2, metadata))
        val reopened = CaptureRecorder(root).use { it.recoverSessions().single() }
        assertEquals(metadata, reopened.syncMetadataOverride)
        assertFalse(recovered.indexFile.exists())
    }

    @Test
    fun missingIndexRecoversCompleteRowsAsUncertain() = withFixture { root ->
        val session = fixture(root, CaptureStatus.RECORDING, rows = 2)
        session.blockIndexFile.delete()
        session.logFile.appendText("unfinished")

        val recovered = CaptureRecorder(root).use { it.recoverSessions().single() }
        val metadata = requireNotNull(recovered.syncMetadataOverride)

        assertEquals(2, metadata.rowCount)
        assertEquals(CaptureSyncReliability.UNCERTAIN, metadata.segments.single().reliability)
        assertNull(metadata.segments.single().anchorCaptureElapsedMs)
        assertEquals(2, CaptureBlockIndex.read(recovered.blockIndexFile, recovered.logFile).indexedRows)
        assertEquals(line(0) + "\n" + line(1) + "\n", recovered.logFile.readText())
    }

    @Test
    fun manualSegmentCorrectionSurvivesRetainedReloadAndExportWithoutRewritingAssets() = withFixture { root ->
        val session = fixture(root, CaptureStatus.STOPPED, rows = 3)
        CaptureArchiveExporter().finalizeSessionInPlace(session)
        val originalSync = session.syncFile.readBytes()
        val originalBlocks = session.blockIndexFile.readBytes()
        val descriptorFile = File(session.directory, CAPTURE_DESCRIPTOR_NAME)
        val originalDescriptor = descriptorFile.readBytes()
        val anchor = CaptureSegmentManualAnchor(
            segmentId = 1,
            rowOrdinal = 3,
            clockReference = CaptureClockReference(36_002_000, logDaySlot(10, 21).toInt(), 2026),
            videoMs = 5_000,
            captureElapsedMs = 5_000,
        )
        CaptureRecorder(root).use { it.updateManualSegmentAnchors(session.id, listOf(anchor)) }

        val reloaded = requireNotNull(readCaptureSessionDirectory(session.directory))
        assertEquals(listOf(anchor), reloaded.manualSegmentAnchors)
        assertTrue(originalSync.contentEquals(session.syncFile.readBytes()))
        assertTrue(originalBlocks.contentEquals(session.blockIndexFile.readBytes()))
        assertTrue(originalDescriptor.contentEquals(descriptorFile.readBytes()))
        val imported = CaptureArchiveReader.open(descriptorFile, File(root, "retained-reader"))
        val entries = parseLogcat(imported.logFile)
        val resolver = CaptureTimingResolver.forTimeline(entries, requireNotNull(imported.timeline))
        assertEquals(0L, resolver.pointForOrdinal(1)?.captureElapsedMs)
        assertEquals(1_000L, resolver.pointForOrdinal(2)?.captureElapsedMs)
        assertEquals(5_000L, resolver.pointForOrdinal(3)?.captureElapsedMs)

        val destination = File(root, "corrected.zip")
        CaptureArchiveExporter().export(reloaded, CaptureExportRequest(destination, includeVideo = false, cutoffElapsedMs = 10_000))
        val roundTrip = CaptureArchiveReader.open(destination, File(root, "portable-reader"))
        assertEquals(anchor, roundTrip.sparseSyncMetadata?.segments?.last()?.manualAnchor)
        assertFalse(File(roundTrip.logFile.parentFile, "timeline/rows.jsonl").exists())
    }

    private fun fixture(root: File, status: CaptureStatus, rows: Int): CaptureSession {
        val directory = File(root, "session").apply { mkdirs() }
        val session = CaptureSession(
            id = "session",
            directory = directory,
            device = CaptureDevice("test-device", "device"),
            settings = CaptureSettings(recordVideo = false, mirror = false, freeSpaceReserveBytes = 0),
            startedEpochMs = Instant.parse("2026-10-21T10:00:00Z").toEpochMilli(),
            elapsedMs = 10_000,
            status = status,
            storageVersion = CaptureStorageVersion.BLOCKS_V2,
        )
        session.logFile.parentFile.mkdirs()
        var offset = 0L
        CaptureBlockWriter(session.blockIndexFile).use { writer ->
            session.logFile.outputStream().use { output ->
                repeat(rows) { index ->
                    val raw = (line(index) + "\n").toByteArray()
                    output.write(raw)
                    writer.append(offset, raw, index + 1, index * 1_000L, if (index < 2) 0 else 1)
                    offset += raw.size
                }
            }
        }
        val segments = buildList {
            add(segment(0, 1, minOf(rows + 1, 3), 0))
            if (rows > 2) add(segment(1, 3, rows + 1, 2))
        }
        session.syncFile.parentFile.mkdirs()
        session.syncFile.writeText(CaptureSyncMetadata(offset, rows, 0, 10_000, segments).toJson())
        File(directory, "session.json").writeText(buildJsonObject {
            put("formatVersion", 1)
            put("storageVersion", 2)
            put("id", session.id)
            put("device", buildJsonObject { put("serial", session.device.serial) })
            put("settings", Json.parseToJsonElement(captureSettingsToJson(session.settings)))
            put("startedEpochMs", session.startedEpochMs)
            put("elapsedMs", session.elapsedMs)
            put("status", status.name)
        }.toString())
        return session
    }

    private fun segment(id: Int, first: Int, end: Int, second: Int) = CaptureSyncSegment(
        id, first, end,
        Instant.parse("2026-10-21T10:00:00Z").toEpochMilli() + second * 1_000,
        second * 1_000L,
        CaptureSyncReliability.RELIABLE,
        CaptureClockReference(36_000_000L + second * 1_000, logDaySlot(10, 21).toInt(), 2026),
    )

    private fun line(second: Int) = "10-21 10:00:0$second.000  100  101 I Tag: row-$second"

    private fun withFixture(test: (File) -> Unit) {
        val root = createTempDirectory("capture-sparse-recovery").toFile()
        try {
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
