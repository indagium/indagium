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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A finalized v5 capture FOLDER keeps its log in `logs/` but its block index in `mapping/`, both under the root. */
@Suppress("MagicNumber")
class ImportedInPlaceFolderTest {
    @Test
    fun anInPlaceV5FolderResolvesItsBlockIndexAgainstTheArchiveRootForPreviewAndExport() = withFixture { root ->
        val session = stoppedSparseSession(root, rows = 3)
        val finalized = CaptureArchiveExporter().finalizeSessionInPlace(session)
        val opened = CaptureArchiveReader.open(session.directory, File(root, "reader"))
        assertEquals(session.directory.canonicalFile, opened.root?.canonicalFile)
        assertEquals(finalized.logFile.canonicalFile, opened.logFile.canonicalFile)

        val snapshot = importedCaptureSnapshot(
            opened, session.directory, parseLogcat(opened.logFile), File(root, "indexes"), File(root, "cursors"),
        )
        assertEquals(
            File(session.directory, "mapping/blocks.jsonl").canonicalFile,
            snapshot.session.blockIndexFile.canonicalFile,
        )

        val request = CaptureExportRequest(File(root, "child.zip"), CaptureRange.ALL, includeVideo = false, cutoffElapsedMs = 10_000)
        val preview = CaptureArchiveExporter().preview(snapshot.session, request)
        assertNotNull(preview.logStartMs)
        assertNotNull(preview.logEndMs)

        CaptureArchiveExporter().export(snapshot.session, request)
        val child = CaptureArchiveReader.open(request.destination, File(root, "child-reader"))
        assertContentEquals(session.logFile.readBytes(), child.logFile.readBytes())
        assertTrue(parseLogcat(child.logFile).size == 3)
    }

    private fun stoppedSparseSession(root: File, rows: Int): CaptureSession {
        val directory = File(root, "session").apply { mkdirs() }
        val session = CaptureSession(
            id = "session",
            directory = directory,
            device = CaptureDevice("test-device", "device"),
            settings = CaptureSettings(recordVideo = false, mirror = false, freeSpaceReserveBytes = 0),
            startedEpochMs = Instant.parse("2026-10-21T10:00:00Z").toEpochMilli(),
            elapsedMs = 10_000,
            status = CaptureStatus.STOPPED,
            storageVersion = CaptureStorageVersion.BLOCKS_V2,
        )
        session.logFile.parentFile.mkdirs()
        var offset = 0L
        CaptureBlockWriter(session.blockIndexFile).use { writer ->
            session.logFile.outputStream().use { output ->
                repeat(rows) { index ->
                    val raw = "10-21 10:00:0$index.000  100  101 I Tag: row-$index\n".toByteArray()
                    output.write(raw)
                    writer.append(offset, raw, index + 1, index * 1_000L, 0)
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
        session.syncFile.writeText(CaptureSyncMetadata(offset, rows, 0, 10_000, listOf(segment)).toJson())
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
        return session
    }

    private fun withFixture(test: (File) -> Unit) {
        val root = createTempDirectory("imported-in-place-folder").toFile()
        try {
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
