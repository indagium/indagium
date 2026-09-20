package com.indagium

import com.indagium.capture.CAPTURE_DESCRIPTOR_NAME
import com.indagium.capture.CaptureArchiveException
import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureArchiveReader
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureVideoClip
import com.indagium.capture.CaptureVideoExporter
import com.indagium.capture.captureFilenameTemplateError
import com.indagium.capture.captureSettingsFromJson
import com.indagium.capture.captureSettingsToJson
import com.indagium.capture.renderCaptureFilename
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("MagicNumber")
class CaptureArchiveTest {
    @Test
    fun exportRoundTripPreservesRawRangeAndRebasesMappingOrdinals() {
        val root = createTempDirectory("capture-archive-roundtrip").toFile()
        val session = session(root, recordVideo = true, manualOffsetMs = 100)
        val rows = listOf(
            RawRow("01-01 10:00:00.000  1  1 I First: old\n", 10_000, 1),
            RawRow("--------- beginning of system\n", 99_000, null),
            RawRow("01-01 10:06:30.000  1  1 I Last: kept\n", 400_000, 2),
        )
        writeCaptureInput(session, rows)
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        val fakeVideo = CaptureVideoExporter { _, destination, start, end ->
            destination.writeText("fake-video")
            CaptureVideoClip(actualStartMs = start, coveredEndMs = end, durationMs = end - start)
        }
        val destination = File(root, "export.zip")

        CaptureArchiveExporter(fakeVideo).export(
            session,
            CaptureExportRequest(destination, CaptureRange.LAST_FIVE, cutoffElapsedMs = 400_000),
        )
        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))

        assertEquals(rows.last().text, imported.logFile.readText())
        assertEquals(1, imported.timeline.rows.size)
        assertEquals(1, imported.timeline.rows.single().ordinal)
        assertEquals(300_000, imported.timeline.rows.single().videoMs)
        assertEquals(100, imported.timeline.manualOffsetMs)
        assertEquals(destination, imported.source)
        assertTrue(imported.videoFile?.isFile == true)
        assertEquals("logs/logcat.log", imported.descriptor.log.path)
        assertEquals("mapping/log-video.jsonl", imported.descriptor.mapping.path)
        assertEquals("video/screen.mkv", imported.descriptor.video?.path)
        assertFalse(imported.descriptor.settings.adbPath.isNotEmpty())
    }

    @Test
    fun selectionRangeKeepsRawRecordsBetweenSelectedRowsAndUsesTheirTemporalBounds() {
        val root = createTempDirectory("capture-archive-selection").toFile()
        val session = session(root, recordVideo = true).copy(videoStartElapsedMs = 0)
        writeCaptureInput(session, listOf(
            RawRow("row-1\n", 1_000, 1),
            RawRow("----- separator before\n", 1_500, null),
            RawRow("row-2\n", 2_000, 2),
            RawRow("----- separator inside\n", 2_500, null),
            RawRow("row-3\n", 3_000, 3),
            RawRow("----- separator after\n", 3_500, null),
            RawRow("row-4\n", 4_000, 4),
        ))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1))
        session.directory.resolve("screenshots").mkdirs()
        session.directory.resolve("screenshots/screenshot-1500.png").writeBytes(byteArrayOf(1))
        session.directory.resolve("screenshots/screenshot-2500.png").writeBytes(byteArrayOf(2))
        session.directory.resolve("screenshots/screenshot-3500.png").writeBytes(byteArrayOf(3))
        var requestedStart = Long.MIN_VALUE
        var requestedEnd = Long.MIN_VALUE
        val destination = File(root, "selection.zip")
        val fakeVideo = CaptureVideoExporter { _, target, start, end ->
            requestedStart = start
            requestedEnd = end
            target.writeText("video")
            CaptureVideoClip(start, end, end - start)
        }

        CaptureArchiveExporter(fakeVideo).export(
            session,
            CaptureExportRequest(
                destination = destination,
                range = CaptureRange.SELECTION,
                cutoffElapsedMs = 4_000,
                selectedFirstRowOrdinal = 2,
                selectedLastRowOrdinal = 3,
            ),
        )
        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))

        assertEquals("row-2\n----- separator inside\nrow-3\n", imported.logFile.readText())
        assertEquals(listOf(2_000L, 3_000L), imported.timeline.rows.map { it.elapsedMs })
        assertEquals(CaptureRange.SELECTION, imported.descriptor.range)
        assertEquals(2_000L, requestedStart)
        assertEquals(3_000L, requestedEnd)
        assertEquals(1, imported.descriptor.screenshots.size)
    }

    @Test
    fun selectionRangeRequiresValidOrderedBounds() {
        val root = createTempDirectory("capture-archive-selection-invalid").toFile()
        val session = session(root)
        writeCaptureInput(session, listOf(RawRow("row\n", 1_000, 1)))
        val exporter = CaptureArchiveExporter(CaptureVideoExporter { _, _, _, _ -> error("unused") })
        assertFails {
            exporter.export(session, CaptureExportRequest(
                File(root, "missing.zip"), CaptureRange.SELECTION, cutoffElapsedMs = 1_000,
            ))
        }
        assertFails {
            exporter.export(session, CaptureExportRequest(
                File(root, "reversed.zip"), CaptureRange.SELECTION, cutoffElapsedMs = 1_000,
                selectedFirstRowOrdinal = 2, selectedLastRowOrdinal = 1,
            ))
        }
    }

    @Test
    fun previewReportsActualGrowingVideoCoverageWithoutPublishingOrMutatingSession() {
        val root = createTempDirectory("capture-preview-video").toFile()
        val session = session(root, recordVideo = true, manualOffsetMs = 100)
        writeCaptureInput(session, listOf(
            RawRow("row-1\n", 100_000, 1),
            RawRow("row-2\n", 400_000, 2),
        ))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        var requestedStart = Long.MIN_VALUE
        var requestedEnd = Long.MIN_VALUE
        var previewDestination: File? = null
        val fakeVideo = CaptureVideoExporter { _, destination, start, end ->
            requestedStart = start
            requestedEnd = end
            previewDestination = destination
            destination.writeText("preview-only")
            CaptureVideoClip(actualStartMs = 25_000, coveredEndMs = 250_000, durationMs = 225_000)
        }
        val request = CaptureExportRequest(
            destination = File(root, "not-published.zip"),
            range = CaptureRange.LAST_FIVE,
            cutoffElapsedMs = 400_000,
            includeVideo = true,
        )

        val preview = CaptureArchiveExporter(fakeVideo).preview(session, request)

        assertEquals(50_100, requestedStart)
        assertEquals(350_100, requestedEnd)
        assertEquals(299_900, preview.videoCoveredEndMs)
        assertEquals(100_100, preview.videoShortfallMs)
        assertTrue(preview.includeVideo)
        assertFalse(preview.destinationExists)
        assertFalse(previewDestination?.exists() == true)
        assertEquals(byteArrayOf(1, 2, 3).toList(), session.videoFile.readBytes().toList())
    }

    @Test
    fun previewReportsFullVideoShortfallWhenGrowingVideoIsMissing() {
        val root = createTempDirectory("capture-preview-video-missing").toFile()
        val session = session(root, recordVideo = true)
        writeCaptureInput(session, listOf(RawRow("row\n", 400_000, 1)))

        val preview = CaptureArchiveExporter().preview(
            session,
            CaptureExportRequest(
                destination = File(root, "missing-video.zip"),
                range = CaptureRange.LAST_FIVE,
                cutoffElapsedMs = 400_000,
                includeVideo = true,
            ),
        )

        assertNull(preview.videoCoveredEndMs)
        assertEquals(300_000, preview.videoShortfallMs)
        assertTrue(preview.includeVideo)
    }

    // Regression for the "No complete log rows in this range" bug: CaptureRange.ALL preview
    // reported logStartMs == null unconditionally, because rangeStartMs(ALL, …) returns
    // Long.MIN_VALUE (meaning "no lower bound requested") and that sentinel used to be mapped
    // straight to null for display — even when the index held real rows. Observed live with 1,613
    // rows and range=All: the log half of the popover claimed no rows while the video half
    // correctly reported coverage.
    @Test
    fun previewReportsRealLogCoverageForRangeAllEvenThoughItsLowerBoundIsUnbounded() {
        val root = createTempDirectory("capture-preview-log-all").toFile()
        val session = session(root)
        writeCaptureInput(session, listOf(
            RawRow("row-1\n", 10_000, 1),
            RawRow("row-2\n", 20_000, 2),
            RawRow("row-3\n", 30_000, 3),
        ))

        val preview = CaptureArchiveExporter().preview(
            session,
            CaptureExportRequest(destination = File(root, "not-published.zip"), range = CaptureRange.ALL, cutoffElapsedMs = 30_000),
        )

        assertEquals(10_000, preview.logStartMs)
        assertEquals(30_000, preview.logEndMs)
    }

    // The flip side of the bug above: a range that requests a real, non-sentinel boundary (so the
    // old null-mapping never triggered) but that genuinely contains no rows must still report
    // logStartMs == null. Before this fix, the non-SELECTION branch never inspected the index at
    // all, so this case fabricated a timestamp instead of reporting "no rows."
    @Test
    fun previewReportsNoLogCoverageWhenSinceSaveRangeIsGenuinelyEmpty() {
        val root = createTempDirectory("capture-preview-log-empty").toFile()
        val session = session(root).copy(logCheckpointMs = 30_000)
        writeCaptureInput(session, listOf(
            RawRow("row-1\n", 10_000, 1),
            RawRow("row-2\n", 20_000, 2),
            RawRow("row-3\n", 30_000, 3),
        ))

        val preview = CaptureArchiveExporter().preview(
            session,
            CaptureExportRequest(destination = File(root, "not-published.zip"), range = CaptureRange.SINCE_SAVE, cutoffElapsedMs = 30_000),
        )

        assertNull(preview.logStartMs)
        assertNull(preview.logEndMs)
    }

    @Test
    fun sinceSaveRejectsAFirstSaveWithoutACanonicalOrLegacyCheckpoint() {
        val root = createTempDirectory("capture-since-save-without-checkpoint").toFile()
        val session = session(root)
        writeCaptureInput(session, listOf(RawRow("row\n", 1_000, 1)))
        val request = CaptureExportRequest(
            destination = File(root, "missing-checkpoint.zip"),
            range = CaptureRange.SINCE_SAVE,
            cutoffElapsedMs = 1_000,
        )
        val exporter = CaptureArchiveExporter()

        assertFailsWith<IllegalArgumentException> { exporter.preview(session, request) }
        assertFailsWith<IllegalArgumentException> { exporter.export(session, request) }
        assertFalse(request.destination.exists())
    }

    @Test
    fun exportBeforeVideoStartsSucceedsWithLogOnlyArchiveAndNullMapping() {
        val root = createTempDirectory("capture-export-before-video").toFile()
        val session = session(root, recordVideo = true).copy(videoStartElapsedMs = 5_000, elapsedMs = 40_000)
        writeCaptureInput(session, listOf(
            RawRow("row-before-1\n", 1_000, 1),
            RawRow("row-before-2\n", 3_000, 2),
        ))
        val destination = File(root, "before-video.zip")
        val exporter = CaptureArchiveExporter(CaptureVideoExporter { _, _, _, _ -> error("must not remux") })

        val result = exporter.export(
            session,
            CaptureExportRequest(destination, CaptureRange.ALL, cutoffElapsedMs = 3_000),
        )
        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))

        assertEquals("Capture exported; video has no usable coverage for this range", result.message)
        assertNull(imported.videoFile)
        assertNull(imported.descriptor.video)
        assertEquals(listOf(null, null), imported.timeline.rows.map { it.videoMs })
    }

    @Test
    fun exportedVideoArchiveReopensWithRowsMappedOnlyInsideVideoCoverage() {
        val root = createTempDirectory("capture-export-video-mapping").toFile()
        val session = session(root, recordVideo = true).copy(videoStartElapsedMs = 2_000, elapsedMs = 5_000)
        writeCaptureInput(session, listOf(
            RawRow("row-before-video\n", 1_000, 1),
            RawRow("row-in-video-1\n", 3_000, 2),
            RawRow("row-in-video-2\n", 5_000, 3),
        ))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        val fakeVideo = CaptureVideoExporter { _, target, start, end ->
            target.writeText("video")
            CaptureVideoClip(start, end, end - start)
        }
        val destination = File(root, "video.zip")

        CaptureArchiveExporter(fakeVideo).export(
            session,
            CaptureExportRequest(destination, CaptureRange.ALL, cutoffElapsedMs = 5_000),
        )
        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))

        assertTrue(imported.videoFile?.isFile == true)
        assertEquals("video/screen.mkv", imported.descriptor.video?.path)
        assertEquals(listOf(null, 1_000L, 3_000L), imported.timeline.rows.map { it.videoMs })
    }

    @Test
    fun finalizedCaptureReopensWithRawVideoMappingAndUnmappedPreVideoRows() {
        val root = createTempDirectory("capture-finalize-video-mapping").toFile()
        val session = session(root, recordVideo = true).copy(
            status = com.indagium.capture.CaptureStatus.STOPPED,
            videoStartElapsedMs = 2_000,
            elapsedMs = 5_000,
        )
        writeCaptureInput(session, listOf(
            RawRow("row-before-video\n", 1_000, 1),
            RawRow("row-in-video\n", 3_000, 2),
        ))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(7, 8, 9))

        val imported = CaptureArchiveExporter().finalizeSessionInPlace(session)

        assertEquals(session.videoFile, imported.videoFile)
        assertEquals("mapping/log-video.jsonl", imported.descriptor.mapping.path)
        assertEquals("video/screen.mkv", imported.descriptor.video?.path)
        assertEquals(listOf(null, 1_000L), imported.timeline.rows.map { it.videoMs })
    }

    @Test
    fun finalizeStoppedSessionOpensInPlaceAndKeepsRawVideo() {
        val root = createTempDirectory("capture-finalize").toFile()
        val session = session(root, recordVideo = true).copy(
            status = com.indagium.capture.CaptureStatus.STOPPED,
            videoStartElapsedMs = 500,
        )
        writeCaptureInput(session, listOf(
            RawRow("row-1\n", 1_000, 1),
            RawRow("----- separator\n", 1_500, null),
            RawRow("row-2\n", 2_000, 2),
        ))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(7, 8, 9))

        val imported = CaptureArchiveExporter().finalizeSessionInPlace(session)

        assertEquals(session.logFile, imported.logFile)
        assertEquals(session.videoFile, imported.videoFile)
        assertEquals("row-1\n----- separator\nrow-2\n", imported.logFile.readText())
        assertEquals(listOf(500L, 1_500L), imported.timeline.rows.map { it.videoMs })
        assertEquals(File(session.directory, CAPTURE_DESCRIPTOR_NAME), imported.source)
        assertEquals(byteArrayOf(7, 8, 9).toList(), imported.videoFile?.readBytes()?.toList())
        assertTrue(File(session.directory, "mapping/log-video.jsonl").isFile)
        assertTrue(File(session.directory, CAPTURE_DESCRIPTOR_NAME).isFile)
        assertTrue(root.listFiles().orEmpty().none { it.extension == "zip" })
    }

    @Test
    fun finalizeRejectsActiveAndIncompleteSessionsWithoutPublishingDescriptor() {
        val root = createTempDirectory("capture-finalize-invalid").toFile()
        val exporter = CaptureArchiveExporter()
        val active = session(root).copy(status = com.indagium.capture.CaptureStatus.RECORDING)
        assertFailsWith<CaptureArchiveException> { exporter.finalizeSessionInPlace(active) }

        val incomplete = session(root).copy(
            id = "incomplete",
            directory = File(root, "incomplete"),
            status = com.indagium.capture.CaptureStatus.INTERRUPTED,
        )
        writeCaptureInput(incomplete, listOf(RawRow("row\n", 1_000, 1)))
        incomplete.indexFile.appendText("{\"byteOffset\":0")
        assertFailsWith<CaptureArchiveException> { exporter.finalizeSessionInPlace(incomplete) }
        assertFalse(File(incomplete.directory, CAPTURE_DESCRIPTOR_NAME).exists())
        assertFalse(File(incomplete.directory, "mapping/log-video.jsonl").exists())
    }

    @Test
    fun readerRejectsTamperedAsset() {
        val root = createTempDirectory("capture-archive-tamper").toFile()
        val original = exportLogOnly(root)
        val tampered = File(root, "tampered.zip")
        rewriteZip(original, tampered) { name, bytes ->
            if (name == "logs/logcat.log") bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() } else bytes
        }

        assertFails { CaptureArchiveReader.open(tampered, File(root, "cache")) }
    }

    @Test
    fun readerRejectsTraversalEvenWhenItIsAnUnreferencedEntry() {
        val root = createTempDirectory("capture-archive-traversal").toFile()
        val original = exportLogOnly(root)
        val traversal = File(root, "traversal.zip")
        rewriteZip(original, traversal, extra = "../outside.txt" to "nope".toByteArray()) { _, bytes -> bytes }

        assertFails { CaptureArchiveReader.open(traversal, File(root, "cache")) }
        assertFalse(File(root, "outside.txt").exists())
    }

    @Test
    fun unsupportedVersionIsRecognizedThenReportsTheVersionError() {
        val root = createTempDirectory("capture-archive-version").toFile()
        val original = exportLogOnly(root)
        val future = File(root, "future.zip")
        rewriteZip(original, future) { name, bytes ->
            if (name == CAPTURE_DESCRIPTOR_NAME) {
                bytes.toString(Charsets.UTF_8).replace("\"formatVersion\":1", "\"formatVersion\":99").toByteArray()
            } else {
                bytes
            }
        }

        assertTrue(CaptureArchiveReader.isCaptureArchive(future))
        assertFails { CaptureArchiveReader.open(future, File(root, "cache")) }
    }

    @Test
    fun failurePublishesNothingAndDoesNotAdvanceCallerOwnedCheckpoints() {
        val root = createTempDirectory("capture-archive-failure").toFile()
        val session = session(root, recordVideo = true).copy(
            videoStartElapsedMs = 10_000,
            logCheckpointMs = 12_000,
            videoCheckpointMs = 11_000,
        )
        writeCaptureInput(session, listOf(RawRow("01-01 10:00:00.000  1  1 I Tag: row\n", 13_000, 1)))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1))
        val destination = File(root, "failed.zip")
        val failingVideo = CaptureVideoExporter { _, _, _, _ -> error("clip failed") }

        assertFails {
            CaptureArchiveExporter(failingVideo).export(
                session,
                CaptureExportRequest(destination, CaptureRange.SINCE_SAVE, cutoffElapsedMs = 13_000),
            )
        }
        assertFalse(destination.exists())
        assertEquals(12_000, session.logCheckpointMs)
        assertEquals(11_000, session.videoCheckpointMs)
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".failed.zip.tmp-") })
    }

    @Test
    fun sinceSaveExcludesTheLastSuccessfullyExportedLogRow() {
        val root = createTempDirectory("capture-archive-checkpoint").toFile()
        val session = session(root).copy(logCheckpointMs = 1_000)
        writeCaptureInput(session, listOf(
            RawRow("01-01 10:00:00.000  1  1 I Tag: already saved\n", 1_000, 1),
            RawRow("01-01 10:00:01.000  1  1 I Tag: new\n", 2_000, 2),
        ))
        val destination = File(root, "since-save.zip")

        CaptureArchiveExporter(CaptureVideoExporter { _, _, _, _ -> error("unused") }).export(
            session,
            CaptureExportRequest(destination, CaptureRange.SINCE_SAVE, cutoffElapsedMs = 2_000),
        )
        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))

        assertEquals("01-01 10:00:01.000  1  1 I Tag: new\n", imported.logFile.readText())
        assertEquals(listOf(2_000L), imported.timeline.rows.map { it.elapsedMs })
    }

    @Test
    fun readerRejectsUnreferencedZipFilesBeforeExtractingThem() {
        val root = createTempDirectory("capture-archive-unreferenced").toFile()
        val original = exportLogOnly(root)
        val expanded = File(root, "expanded.zip")
        rewriteZip(original, expanded, extra = "payload.bin" to ByteArray(32)) { _, bytes -> bytes }

        assertFails { CaptureArchiveReader.open(expanded, File(root, "cache")) }
    }

    @Test
    fun captureSettingsCodecUsesDefaultsForMissingKeysAndRejectsFutureVersions() {
        val settings = CaptureSettings(buffers = listOf("main", "events"), recordVideo = true, label = "QA")
        assertEquals(settings, captureSettingsFromJson(captureSettingsToJson(settings)))
        assertEquals(CaptureSettings().maxFps, captureSettingsFromJson("{\"formatVersion\":1}")?.maxFps)
        assertNull(captureSettingsFromJson("{\"formatVersion\":2}"))
        assertNull(captureSettingsFromJson("{\"formatVersion\":1,\"buffers\":[\"main\", 4]}"))
        assertNull(captureSettingsFromJson("{\"formatVersion\":\"1\"}"))
    }

    @Test
    fun captureFilenameTemplateSupportsTheDeviceSerialToken() {
        val device = CaptureDevice(serial = "emulator:5554", state = "device", model = "Pixel")

        assertEquals(null, captureFilenameTemplateError("{serial}_{counter}.zip"))
        assertEquals("emulator_5554_3.zip", renderCaptureFilename("{serial}_{counter}.zip", device, 0, CaptureRange.ALL, 3))
    }

    private fun exportLogOnly(root: File): File {
        val session = session(root)
        writeCaptureInput(session, listOf(RawRow("01-01 10:00:00.000  1  1 I Tag: row\n", 1_000, 1)))
        return File(root, "capture.zip").also { destination ->
            CaptureArchiveExporter(CaptureVideoExporter { _, _, _, _ -> error("unused") }).export(
                session,
                CaptureExportRequest(destination, CaptureRange.ALL, cutoffElapsedMs = 1_000),
            )
        }
    }

    private fun session(root: File, recordVideo: Boolean = false, manualOffsetMs: Long = 0): CaptureSession = CaptureSession(
        id = "session-1",
        directory = File(root, "session"),
        device = CaptureDevice("serial", "device", "Pixel"),
        settings = CaptureSettings(recordVideo = recordVideo, freeSpaceReserveBytes = 0),
        startedEpochMs = 1_700_000_000_000,
        elapsedMs = 400_000,
        videoStartElapsedMs = if (recordVideo) 50_000 else null,
        manualOffsetMs = manualOffsetMs,
    )

    private fun writeCaptureInput(session: CaptureSession, rows: List<RawRow>) {
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        var offset = 0L
        session.logFile.outputStream().use { log ->
            session.indexFile.bufferedWriter().use { index ->
                rows.forEach { row ->
                    val bytes = row.text.toByteArray()
                    log.write(bytes)
                    index.append("{\"byteOffset\":$offset,\"byteLength\":${bytes.size},\"elapsedMs\":${row.elapsedMs},\"rowOrdinal\":")
                    index.append(row.ordinal?.toString() ?: "null")
                    index.append("}\n")
                    offset += bytes.size
                }
            }
        }
    }

    private fun rewriteZip(
        source: File,
        destination: File,
        extra: Pair<String, ByteArray>? = null,
        transform: (String, ByteArray) -> ByteArray,
    ) {
        ZipFile(source).use { input ->
            ZipOutputStream(destination.outputStream()).use { output ->
                input.entries().asSequence().forEach { entry ->
                    val bytes = input.getInputStream(entry).use { it.readBytes() }
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(transform(entry.name, bytes))
                    output.closeEntry()
                }
                extra?.let { (name, bytes) ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }
    }

    private data class RawRow(val text: String, val elapsedMs: Long, val ordinal: Int?)
}
