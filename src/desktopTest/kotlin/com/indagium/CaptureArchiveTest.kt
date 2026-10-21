package com.indagium

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import com.indagium.capture.CAPTURE_DESCRIPTOR_NAME
import com.indagium.capture.CaptureArchiveException
import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureArchiveReader
import com.indagium.capture.CaptureAvailabilityInterval
import com.indagium.capture.CaptureCompactVideoTranscoder
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportFormat
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureTimingResolver
import com.indagium.capture.CaptureVideoClip
import com.indagium.capture.CaptureVideoCoverageProbe
import com.indagium.capture.CaptureVideoExporter
import com.indagium.capture.captureFilenameTemplateError
import com.indagium.capture.captureSettingsFromJson
import com.indagium.capture.captureSettingsToJson
import com.indagium.capture.importedCaptureSnapshot
import com.indagium.capture.markerHeader
import com.indagium.capture.markerHeadingLine
import com.indagium.capture.renderCaptureFilename
import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.VideoFrameReference
import com.indagium.model.VideoSource
import com.indagium.utils.ArchiveFormat
import com.indagium.utils.detectArchiveFormat
import com.indagium.utils.parseLogcat
import org.apache.commons.compress.archivers.zip.ZipMethod
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis
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
        // v5 stores one segment anchor and no dense timeline rows. The shared resolver reconstructs
        // the selected row's same clip-relative video position transiently.
        assertEquals(300_000L, resolvedTiming(imported).videoMsForOrdinal(1))
        assertEquals(emptyList(), requireNotNull(imported.timeline).rows)
        assertEquals(100L, imported.descriptor.manualOffsetMs)
        assertEquals(destination, imported.source)
        assertTrue(imported.videoFile?.isFile == true)
        assertEquals("logcat.log", imported.descriptor.log.path)
        assertNull(imported.descriptor.mapping)
        // Archive v3 defaults the exported container to MP4 (CaptureVideoContainer.MP4) — see
        // CaptureModels.kt.
        assertEquals("screen.mp4", imported.descriptor.video?.path)
    }

    @Test
    fun compactExportRoundTripsAsIndWithCompactVideoEntryAndCleansComparisonFiles() {
        val root = createTempDirectory("capture-archive-ind").toFile()
        val session = session(root, recordVideo = true).copy(
            status = com.indagium.capture.CaptureStatus.STOPPED,
            videoStartElapsedMs = 0,
            availabilityIntervals = listOf(
                CaptureAvailabilityInterval(150_000, 200_000, "old-boot", "new-boot", true),
            ),
        )
        writeCaptureInput(
            session,
            listOf(
                RawRow("01-01 10:00:01.000  1  1 I Tag: row one\n", 1_000, 1),
                RawRow("01-01 10:06:40.000  1  1 I Tag: row two\n", 400_000, 2),
            ),
        )
        session.videoFile.parentFile.mkdirs()
        val originalVideo = byteArrayOf(1, 2, 3, 4)
        session.videoFile.writeBytes(originalVideo)
        val screenshotBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        File(session.directory, "screenshots/screenshot-1000.png").apply {
            parentFile.mkdirs()
            writeBytes(screenshotBytes)
        }
        val marker = com.indagium.capture.CaptureMarker(
            id = "m1",
            elapsedMs = 1_000,
            firstOrdinal = 1,
            lastOrdinal = 2,
            videoMs = 1_000,
            label = "Compact marker",
            preMs = 0,
            postMs = 0,
            screenshotPath = null,
        )
        val frame = VideoFrameReference(
            source = VideoSource.LocalFile(session.videoFile.absolutePath),
            sourceLabel = "screen.mkv",
            positionMs = 1_000,
        )
        val notes = Annotations(
            blocks = listOf(
                AnnBlock.Note("note-1", "${markerHeader(marker)}\n${markerHeadingLine(1, marker.label)}\nDetails"),
                AnnBlock.LogRef("ref-1", logIds = listOf(1, 2), caption = "Marked rows"),
                AnnBlock.Image(
                    "image-1",
                    caption = "Captured video frame",
                    provenance = frame.provenanceLabel,
                    format = "png",
                    bytes = byteArrayOf(9, 8, 7),
                    videoFrame = frame,
                ),
            ),
        )
        val destination = File(root, "capture.ind.zip")
        val fakeVideo = CaptureVideoExporter { _, staged, start, end ->
            staged.writeBytes(ByteArray(128 * 1024) { (it % 127).toByte() })
            CaptureVideoClip(start.coerceAtMost(end - 1), end, end - start)
        }
        val fakeCompact = object : CaptureCompactVideoTranscoder {
            override fun ensureAvailable() = Unit

            override fun transcode(source: File, destination: File) {
                assertEquals(128 * 1024L, source.length())
                destination.writeBytes(ByteArray(2 * 1024) { (it % 31).toByte() })
            }
        }

        val result = CaptureArchiveExporter(
            videoExporter = fakeVideo,
            compactVideoTranscoder = fakeCompact,
        ).export(
            session,
            CaptureExportRequest(
                destination = destination,
                cutoffElapsedMs = session.elapsedMs,
                format = CaptureExportFormat.COMPACT,
            ),
            notes = notes,
        )

        assertTrue(result.message.contains("lossy; audio copied"))
        assertFalse(result.message.contains("zstd is unavailable"))
        assertTrue(CaptureArchiveReader.isCaptureArchive(destination))
        assertEquals(ArchiveFormat.Zip, detectArchiveFormat(destination))
        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))
        assertEquals("screen.mkv", imported.descriptor.video?.path)
        assertTrue(imported.logFile.readText().contains("row one\n"))
        assertTrue(imported.logFile.readText().contains("row two\n"))
        assertEquals(originalVideo.toList(), session.videoFile.readBytes().toList())
        assertEquals(
            listOf(CaptureAvailabilityInterval(150_000, 200_000, "old-boot", "new-boot", true)),
            imported.descriptor.availabilityIntervals,
        )
        assertEquals(imported.descriptor.availabilityIntervals, imported.timeline?.availabilityIntervals)
        assertEquals(emptyList(), requireNotNull(imported.timeline).rows)
        assertEquals("note-1", imported.descriptor.markers.single().noteBlockId)
        assertEquals(1, imported.descriptor.markers.single().firstOrdinal)
        assertEquals(2, imported.descriptor.markers.single().lastOrdinal)
        val importedNotes = requireNotNull(imported.notes)
        val importedMarker = importedNotes.blocks.filterIsInstance<AnnBlock.Note>().single()
        assertTrue(importedMarker.text.contains("Compact marker"))
        assertEquals(listOf(1, 2), importedNotes.blocks.filterIsInstance<AnnBlock.LogRef>().single().logIds)
        val importedFrame = requireNotNull(importedNotes.blocks.filterIsInstance<AnnBlock.Image>().single().videoFrame)
        assertEquals("screen.mkv", (importedFrame.source as VideoSource.ArchiveEntry).entryPath)
        assertTrue(importedFrame.sourceLabel.endsWith("/screen.mkv"))
        assertEquals("notes.ann", imported.descriptor.notes?.path)
        assertTrue(requireNotNull(imported.descriptor.notes).sha256.isNotBlank())
        assertEquals(1, imported.descriptor.screenshots.size)
        assertTrue(imported.descriptor.screenshots.single().sha256.isNotBlank())
        assertEquals(screenshotBytes.toList(), imported.screenshotFiles.single().readBytes().toList())
        commonsZip(destination).use { zip ->
            assertEquals(2 * 1024L, zip.getEntry("screen.mkv").size)
            val log = zip.getEntry("logcat.log")
            assertEquals(ZipMethod.ZSTD.code, log.method)
            val storedLog = zip.getInputStream(log).use { it.readBytes() }
            assertTrue(storedLog.contentEquals(imported.logFile.readBytes()))
            assertEquals(storedLog.size.toLong(), log.size)
            assertEquals(ZipMethod.DEFLATED.code, zip.getEntry(CAPTURE_DESCRIPTOR_NAME).method)
            val courtesy = zip.getInputStream(zip.getEntry("captured_with_indagium.txt")).bufferedReader().readText()
            assertTrue(courtesy.contains("Open this .ind.zip"))
            assertTrue(courtesy.contains("zstd (ZIP method 93)"))
        }
        assertFalse(root.listFiles().orEmpty().any { it.name.contains("zip-compare") || it.name.contains(".tmp-") })
    }

    @Test
    fun compactExportWithoutVideoSucceedsAndHasNoVideoAsset() {
        val root = createTempDirectory("capture-archive-ind-no-video").toFile()
        val session = session(root, recordVideo = false)
        writeCaptureInput(session, listOf(RawRow("row\n", 1_000, 1)))
        val destination = File(root, "no-video.ind.zip")
        val transcoder = object : CaptureCompactVideoTranscoder {
            override fun ensureAvailable() = error("no video must not require an encoder")

            override fun transcode(source: File, destination: File) = error("no video must not be encoded")
        }

        val result = CaptureArchiveExporter(compactVideoTranscoder = transcoder).export(
            session,
            CaptureExportRequest(
                destination,
                cutoffElapsedMs = session.elapsedMs,
                includeVideo = false,
                format = CaptureExportFormat.COMPACT,
            ),
        )

        assertTrue(result.message.startsWith("Compact capture exported without video"))
        assertTrue(CaptureArchiveReader.isCaptureArchive(destination))
        assertNull(result.videoCoveredEndMs)
        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))
        assertNull(imported.descriptor.video)
        commonsZip(destination).use { zip ->
            assertNull(zip.getEntry("screen.mkv"))
            assertNull(zip.getEntry("screen.mp4"))
        }
        assertTrue(root.listFiles().orEmpty().none { it.name.contains(".tmp-") || it.name.contains("-export-") })
    }

    @Test
    fun compactExportKeepsOriginalVideoWhenAv1IsNotSmaller() {
        val root = createTempDirectory("capture-archive-ind-not-smaller").toFile()
        val session = session(root, recordVideo = true).copy(
            status = com.indagium.capture.CaptureStatus.STOPPED,
            videoStartElapsedMs = 0,
        )
        writeCaptureInput(session, listOf(RawRow("row\n", 1_000, 1)))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        val destination = File(root, "not-smaller.ind.zip")
        val stagedBytes = ByteArray(1024) { (it % 97).toByte() }
        val fakeVideo = CaptureVideoExporter { _, staged, start, end ->
            staged.writeBytes(stagedBytes)
            CaptureVideoClip(start.coerceAtMost(end - 1), end, end - start)
        }
        val fakeCompact = object : CaptureCompactVideoTranscoder {
            override fun ensureAvailable() = Unit

            override fun transcode(source: File, destination: File) { destination.writeBytes(ByteArray(2048) { 1 }) }
        }

        val result = CaptureArchiveExporter(videoExporter = fakeVideo, compactVideoTranscoder = fakeCompact).export(
            session,
            CaptureExportRequest(destination, cutoffElapsedMs = session.elapsedMs, format = CaptureExportFormat.COMPACT),
        )

        assertTrue(result.message.startsWith("Compact capture exported; AV1 would not make this video smaller, so the original video was kept"))
        assertTrue(CaptureArchiveReader.isCaptureArchive(destination))
        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))
        assertEquals("screen.mp4", imported.descriptor.video?.path)
        commonsZip(destination).use { zip ->
            assertNull(zip.getEntry("screen.mkv"))
            assertNull(zip.getEntry(".compact-screen.mkv"))
            assertEquals(stagedBytes.toList(), zip.getInputStream(zip.getEntry("screen.mp4")).use { it.readBytes().toList() })
        }
        assertTrue(root.listFiles().orEmpty().none { it.name.contains(".tmp-") || it.name.contains("-export-") })
    }

    @Test
    fun compactExportCopiesAlreadyAv1WithoutEncoder() {
        val root = createTempDirectory("capture-archive-ind-av1-passthrough").toFile()
        val session = session(root, recordVideo = true).copy(
            status = com.indagium.capture.CaptureStatus.STOPPED,
            videoStartElapsedMs = 0,
        )
        writeCaptureInput(session, listOf(RawRow("row\n", 400_000, 1)))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        val stagedBytes = ByteArray(512) { (it % 97).toByte() }
        val destination = File(root, "existing-av1.ind.zip")
        val statuses = mutableListOf<String>()
        val videoExporter = CaptureVideoExporter { _, staged, start, end ->
            staged.writeBytes(stagedBytes)
            CaptureVideoClip(start, end, end - start)
        }
        val passthrough = object : CaptureCompactVideoTranscoder {
            override fun ensureAvailable() = error("an existing AV1 stream must not require an encoder")

            override fun isAlreadyAv1(source: File): Boolean = true

            override fun copyAlreadyAv1(source: File, destination: File) {
                source.copyTo(destination, overwrite = true)
            }

            override fun transcode(source: File, destination: File) = error("an existing AV1 stream must not be re-encoded")
        }

        val result = CaptureArchiveExporter(videoExporter, compactVideoTranscoder = passthrough).export(
            session,
            CaptureExportRequest(destination, cutoffElapsedMs = session.elapsedMs, format = CaptureExportFormat.COMPACT),
            onStatus = { statuses += it },
        )

        assertTrue(result.message.contains("copied without conversion"))
        assertTrue(statuses.none { it.contains("Encoding AV1") })
        assertTrue(CaptureArchiveReader.isCaptureArchive(destination))
        commonsZip(destination).use { zip ->
            assertEquals(stagedBytes.toList(), zip.getInputStream(zip.getEntry("screen.mkv")).use { it.readBytes().toList() })
        }
        assertTrue(root.listFiles().orEmpty().none { it.name.contains("zip-compare") || it.name.contains(".tmp-") })
    }

    @Test
    fun compactExportKeepsOriginalVideoWhenEncoderIsUnavailableAfterCheckingForAv1Passthrough() {
        val root = createTempDirectory("capture-archive-ind-unavailable").toFile()
        val session = session(root, recordVideo = true).copy(
            status = com.indagium.capture.CaptureStatus.STOPPED,
            videoStartElapsedMs = 0,
        )
        writeCaptureInput(session, listOf(RawRow("row\n", 400_000, 1)))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        var videoStagingStarted = false
        val stagedBytes = byteArrayOf(4, 5, 6)
        val transcoder = object : CaptureCompactVideoTranscoder {
            override fun ensureAvailable() { throw IOException("bundled AV1 encoder unavailable") }

            override fun transcode(source: File, destination: File) = error("unavailable encoder must not transcode")
        }
        val destination = File(root, "unavailable.ind.zip")

        val result = CaptureArchiveExporter(
            videoExporter = CaptureVideoExporter { _, staged, start, end ->
                videoStagingStarted = true
                staged.writeBytes(stagedBytes)
                CaptureVideoClip(start, end, end - start)
            },
            compactVideoTranscoder = transcoder,
        ).export(
            session,
            CaptureExportRequest(destination, cutoffElapsedMs = session.elapsedMs, format = CaptureExportFormat.COMPACT),
        )

        assertTrue(videoStagingStarted)
        assertTrue(
            result.message.startsWith("Compact capture exported; the AV1 encoder is unavailable in this build, so the original video was kept"),
        )
        assertTrue(CaptureArchiveReader.isCaptureArchive(destination))
        commonsZip(destination).use { zip ->
            assertNull(zip.getEntry("screen.mkv"))
            assertEquals(stagedBytes.toList(), zip.getInputStream(zip.getEntry("screen.mp4")).use { it.readBytes().toList() })
        }
        assertTrue(root.listFiles().orEmpty().none { it.name.contains(".tmp-") || it.name.contains("-export-") })
    }

    @Test
    fun compactExportFallsBackToDeflatedLogWhenZstdIsUnavailable() {
        val root = createTempDirectory("capture-archive-ind-no-zstd").toFile()
        val session = session(root)
        writeCaptureInput(session, List(200) { RawRow("01-01 10:00:00.000  1  1 I Tag: repeated row\n", 1_000L + it, it + 1) })
        val destination = File(root, "no-zstd.ind.zip")

        val result = CaptureArchiveExporter(zstdAvailable = { false }).export(
            session,
            CaptureExportRequest(destination, cutoffElapsedMs = session.elapsedMs, includeVideo = false, format = CaptureExportFormat.COMPACT),
        )

        assertTrue(
            result.message.startsWith(
                "Compact capture exported without video (log stored with DEFLATE: zstd is unavailable on this system)",
            ),
            result.message,
        )
        assertTrue(CaptureArchiveReader.isCaptureArchive(destination))
        assertEquals(ArchiveFormat.Zip, detectArchiveFormat(destination))
        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))
        assertEquals(200, parseLogcat(imported.logFile).size)
        commonsZip(destination).use { zip ->
            assertEquals(ZipMethod.DEFLATED.code, zip.getEntry("logcat.log").method)
            val courtesy = zip.getInputStream(zip.getEntry("captured_with_indagium.txt")).bufferedReader().readText()
            assertFalse(courtesy.contains("zstd"))
        }
    }

    @Test
    fun plainZipExportKeepsADeflatedLog() {
        val root = createTempDirectory("capture-archive-zip-deflate").toFile()
        val zip = exportLogOnly(root)

        commonsZip(zip).use { archive ->
            assertEquals(ZipMethod.DEFLATED.code, archive.getEntry("logcat.log").method)
        }
        // A plain ZIP is still readable by the JDK's own reader.
        ZipFile(zip).use { assertTrue(it.getEntry("logcat.log") != null) }
    }

    @Test
    fun reexportingAnImportedCompactArchiveAsZipDeflatesTheLogWithIdenticalBytes() {
        val root = createTempDirectory("capture-archive-compact-reexport").toFile()
        val session = session(root)
        writeCaptureInput(
            session,
            List(50) { RawRow("01-01 10:00:%02d.000  1  1 I Tag: repeated row $it\n".format(it), 1_000L * (it + 1), it + 1) },
        )
        val compact = File(root, "source.ind.zip")
        CaptureArchiveExporter().export(
            session,
            CaptureExportRequest(compact, cutoffElapsedMs = session.elapsedMs, includeVideo = false, format = CaptureExportFormat.COMPACT),
        )
        commonsZip(compact).use { assertEquals(ZipMethod.ZSTD.code, it.getEntry("logcat.log").method) }
        val opened = CaptureArchiveReader.open(compact, File(root, "cache"))
        val snapshot = importedCaptureSnapshot(
            opened, compact, parseLogcat(opened.logFile),
            File(root, "indexes"), File(root, "cursors"),
        )

        val zip = File(root, "child.zip")
        CaptureArchiveExporter().export(
            snapshot.session,
            CaptureExportRequest(zip, CaptureRange.ALL, includeVideo = false, cutoffElapsedMs = snapshot.session.elapsedMs),
        )

        commonsZip(zip).use { assertEquals(ZipMethod.DEFLATED.code, it.getEntry("logcat.log").method) }
        val child = CaptureArchiveReader.open(zip, File(root, "child-cache"))
        assertTrue(opened.logFile.readBytes().contentEquals(child.logFile.readBytes()))
    }

    @Test
    fun legacyIndNamedCompactArchiveStillOpens() {
        val root = createTempDirectory("capture-archive-legacy-ind").toFile()
        val session = session(root)
        writeCaptureInput(session, listOf(RawRow("legacy row\n", 1_000, 1)))
        val compact = File(root, "capture.ind.zip")
        CaptureArchiveExporter().export(
            session,
            CaptureExportRequest(compact, cutoffElapsedMs = session.elapsedMs, includeVideo = false, format = CaptureExportFormat.COMPACT),
        )
        val legacy = File(root, "capture.ind")
        assertTrue(compact.renameTo(legacy))

        assertTrue(CaptureArchiveReader.isCaptureArchive(legacy))
        val opened = CaptureArchiveReader.open(legacy, File(root, "cache"))
        assertTrue(opened.logFile.readText().contains("legacy row\n"))
    }

    @Test
    fun compactEncodingFailurePreservesExistingDestinationAndSaveCheckpoints() {
        val root = createTempDirectory("capture-archive-ind-overwrite-failure").toFile()
        val session = session(root, recordVideo = true).copy(
            status = com.indagium.capture.CaptureStatus.STOPPED,
            videoStartElapsedMs = 0,
            logCheckpointMs = 300_000,
            videoCheckpointMs = 250_000,
        )
        writeCaptureInput(session, listOf(
            RawRow("already saved\n", 300_000, 1),
            RawRow("new since save\n", 400_000, 2),
        ))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        val destination = File(root, "existing.ind.zip").apply { writeBytes("previous archive".toByteArray()) }
        val priorBytes = destination.readBytes()
        val fakeVideo = CaptureVideoExporter { _, staged, start, end ->
            staged.writeBytes(ByteArray(128 * 1024) { (it % 127).toByte() })
            CaptureVideoClip(start, end, end - start)
        }
        val failingCompact = object : CaptureCompactVideoTranscoder {
            override fun ensureAvailable() = Unit

            override fun transcode(source: File, destination: File) {
                destination.writeBytes(byteArrayOf(9, 9, 9))
                throw IOException("synthetic AV1 failure")
            }
        }

        assertFailsWith<IOException> {
            CaptureArchiveExporter(fakeVideo, compactVideoTranscoder = failingCompact).export(
                session,
                CaptureExportRequest(
                    destination,
                    range = CaptureRange.SINCE_SAVE,
                    cutoffElapsedMs = session.elapsedMs,
                    overwriteExisting = true,
                    format = CaptureExportFormat.COMPACT,
                ),
            )
        }

        assertEquals(priorBytes.toList(), destination.readBytes().toList())
        assertEquals(300_000L, session.logCheckpointMs)
        assertEquals(250_000L, session.videoCheckpointMs)
        assertTrue(root.listFiles().orEmpty().none { it.name.contains("zip-compare") || it.name.contains(".tmp-") || it.name.contains("-export-") })
    }

    @Test
    fun compactCancellationCleansTemporaryFilesWithoutPublishing() {
        val root = createTempDirectory("capture-archive-ind-cancel").toFile()
        val session = session(root, recordVideo = true).copy(
            status = com.indagium.capture.CaptureStatus.STOPPED,
            videoStartElapsedMs = 0,
        )
        writeCaptureInput(session, listOf(RawRow("row\n", 400_000, 1)))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        val destination = File(root, "cancelled.ind.zip")
        val fakeVideo = CaptureVideoExporter { _, staged, start, end ->
            staged.writeBytes(ByteArray(128 * 1024) { (it % 127).toByte() })
            CaptureVideoClip(start, end, end - start)
        }
        val cancellingCompact = object : CaptureCompactVideoTranscoder {
            override fun ensureAvailable() = Unit

            override fun transcode(source: File, destination: File) {
                destination.writeBytes(ByteArray(2 * 1024) { (it % 31).toByte() })
                Thread.currentThread().interrupt()
            }
        }

        try {
            assertFailsWith<InterruptedException> {
                CaptureArchiveExporter(fakeVideo, compactVideoTranscoder = cancellingCompact).export(
                    session,
                    CaptureExportRequest(
                        destination,
                        cutoffElapsedMs = session.elapsedMs,
                        format = CaptureExportFormat.COMPACT,
                    ),
                )
            }
        } finally {
            Thread.interrupted()
        }

        assertFalse(destination.exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.contains("zip-compare") || it.name.contains(".tmp-") || it.name.contains("-export-") })
    }

    @Test
    fun explicitOfflineOnlyRangeExportsTimelineMetadataWithoutLogsOrVideo() {
        val root = createTempDirectory("capture-archive-offline-only").toFile()
        val offline = CaptureAvailabilityInterval(
            startElapsedMs = 2_000,
            endElapsedMs = 8_000,
            bootIdBefore = "old-boot",
            bootIdAfter = "new-boot",
            rebootConfirmed = true,
        )
        val session = session(root).copy(
            elapsedMs = 10_000,
            settings = CaptureSettings(recordVideo = false, showUnavailableFrames = false, freeSpaceReserveBytes = 0),
            availabilityIntervals = listOf(offline),
        )
        writeCaptureInput(session, emptyList())
        val destination = File(root, "offline-only.zip")

        CaptureArchiveExporter(CaptureVideoExporter { _, _, _, _ -> error("video was excluded") }).export(
            session,
            CaptureExportRequest(
                destination = destination,
                range = CaptureRange.EXPLICIT,
                includeVideo = false,
                cutoffElapsedMs = 10_000,
                explicitStartElapsedMs = 3_000,
                explicitEndElapsedMs = 5_000,
            ),
        )

        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))
        assertEquals("", imported.logFile.readText())
        assertNull(imported.videoFile)
        assertEquals(3_000L, imported.descriptor.timelineBaseElapsedMs)
        assertEquals(2_000L, imported.descriptor.timelineDurationMs)
        assertEquals(listOf(CaptureAvailabilityInterval(0, 2_000, "old-boot", "new-boot", true)), imported.descriptor.availabilityIntervals)
        assertEquals(emptyList(), requireNotNull(imported.timeline).rows)
        assertEquals(imported.descriptor.availabilityIntervals, imported.timeline?.availabilityIntervals)
        assertNull(imported.descriptor.timeline)
        val syncPath = requireNotNull(imported.descriptor.sparseSync).path
        val syncText = ZipFile(destination).use { zip ->
            zip.getInputStream(zip.getEntry(syncPath)).bufferedReader().readText()
        }
        assertTrue(syncText.contains("\"timelineBaseElapsedMs\":3000"))
        assertTrue(syncText.contains("\"timelineDurationMs\":2000"))
        val tampered = File(root, "offline-only-tampered.zip")
        rewriteZip(destination, tampered) { path, bytes ->
            if (path == syncPath) String(bytes, Charsets.UTF_8).replace("\"logBytes\":0", "\"logBytes\":1").toByteArray()
            else bytes
        }
        assertFailsWith<IllegalArgumentException> {
            CaptureArchiveReader.open(tampered, File(root, "tampered-cache"))
        }
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
        // These rows have no parseable log timestamp ("row-2"/"row-3" are not logcat-formatted), so
        // there is nothing to estimate a sync anchor from — the descriptor's own logStartMs/logEndMs
        // (derived straight from the selection's real elapsedMs bounds, not from row content) is what
        // still proves the temporal bounds this test's name is about.
        assertNull(imported.syncAnchor)
        assertEquals(emptyList(), requireNotNull(imported.timeline).rows)
        assertEquals(2_000L, imported.descriptor.logStartMs)
        assertEquals(3_000L, imported.descriptor.logEndMs)
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
    fun explicitRangeClipsRowsAndRebasesTimelineAndAvailabilityIntervals() {
        val root = createTempDirectory("capture-archive-explicit-range").toFile()
        val session = session(root).copy(
            availabilityIntervals = listOf(
                CaptureAvailabilityInterval(180_000, 250_000, "boot-a", "boot-b", true),
            ),
        )
        writeCaptureInput(session, listOf(
            RawRow("row-1\n", 100_000, 1),
            RawRow("row-2\n", 200_000, 2),
            RawRow("row-3\n", 300_000, 3),
            RawRow("row-4\n", 400_000, 4),
        ))
        val destination = File(root, "explicit.zip")
        CaptureArchiveExporter().export(
            session,
            CaptureExportRequest(
                destination = destination,
                range = CaptureRange.EXPLICIT,
                cutoffElapsedMs = 400_000,
                explicitStartElapsedMs = 150_000,
                explicitEndElapsedMs = 350_000,
            ),
        )

        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))
        assertEquals("row-2\nrow-3\n", imported.logFile.readText())
        assertEquals(150_000L, imported.descriptor.timelineBaseElapsedMs)
        assertEquals(200_000L, imported.descriptor.timelineDurationMs)
        assertEquals(emptyList(), requireNotNull(imported.timeline).rows)
        val gap = imported.timeline!!.availabilityIntervals.single()
        assertEquals(30_000L, gap.startElapsedMs)
        assertEquals(100_000L, gap.endElapsedMs)
        assertEquals(true, gap.rebootConfirmed)
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
            CaptureExportRequest(destination = File(root, "not-published.zip"), range = CaptureRange.SINCE_SAVE, cutoffElapsedMs = 31_000),
        )

        assertNull(preview.logStartMs)
        assertNull(preview.logEndMs)
    }

    @Test
    fun sinceSaveRejectsAnEqualEndpointUnlessItsIndependentVideoCursorHasWork() {
        val root = createTempDirectory("capture-since-save-equal-endpoint").toFile()
        val session = session(root).copy(snapshotCheckpointMs = 30_000)
        writeCaptureInput(session, listOf(RawRow("already saved\n", 30_000, 1)))
        val request = CaptureExportRequest(
            destination = File(root, "empty.zip"),
            range = CaptureRange.SINCE_SAVE,
            includeVideo = false,
            cutoffElapsedMs = 30_000,
        )
        assertFailsWith<IllegalArgumentException> { CaptureArchiveExporter().preview(session, request) }
        assertFailsWith<IllegalArgumentException> { CaptureArchiveExporter().export(session, request) }
        assertFalse(request.destination.exists())
    }

    @Test
    fun sinceSaveCanExportARealVideoOnlyCatchupAtTheSavedLogEndpoint() {
        val root = createTempDirectory("capture-since-save-video-only").toFile()
        val session = session(root, recordVideo = true).copy(
            elapsedMs = 8_000,
            snapshotCheckpointMs = 8_000,
            videoCheckpointMs = 5_000,
            videoStartElapsedMs = 0,
        )
        writeCaptureInput(session, listOf(RawRow("already saved\n", 8_000, 1)))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1))
        val fakeVideo = CaptureVideoExporter { _, destination, start, end ->
            destination.writeText("new video coverage")
            CaptureVideoClip(start, end, end - start)
        }
        val destination = File(root, "video-only.zip")
        val result = CaptureArchiveExporter(fakeVideo).export(
            session,
            CaptureExportRequest(destination, CaptureRange.SINCE_SAVE, cutoffElapsedMs = 8_000),
        )
        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))

        assertEquals(8_000L, result.logCoveredEndMs)
        assertEquals(8_000L, result.videoCoveredEndMs)
        assertEquals("", imported.logFile.readText())
        assertTrue(imported.videoFile?.isFile == true)
        assertEquals(3_000L, imported.descriptor.timelineDurationMs)
    }

    @Test
    fun sinceSaveDoesNotPublishOrAdvanceFromAnEmptyVideoOnlyCutWithoutCoverage() {
        val root = createTempDirectory("capture-since-save-empty-video").toFile()
        val session = session(root, recordVideo = true).copy(
            elapsedMs = 8_000,
            snapshotCheckpointMs = 8_000,
            videoCheckpointMs = 5_000,
            videoStartElapsedMs = 0,
        )
        writeCaptureInput(session, listOf(RawRow("already saved\n", 8_000, 1)))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1))
        val destination = File(root, "empty-video-only.zip")
        val request = CaptureExportRequest(destination, CaptureRange.SINCE_SAVE, cutoffElapsedMs = 8_000)
        val failure = assertFailsWith<IllegalArgumentException> {
            CaptureArchiveExporter(CaptureVideoExporter { _, _, _, _ -> error("no readable video interval") })
                .export(session, request)
        }

        assertTrue(failure.message.orEmpty().contains("Nothing new to save"))
        assertFalse(destination.exists())
        assertEquals(8_000L, session.effectiveSnapshotCheckpointMs)
        assertEquals(5_000L, session.effectiveVideoCheckpointMs)
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
    fun exportBeforeVideoStartsSucceedsWithLogOnlyArchiveAndNoSyncAnchor() {
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

        assertTrue(result.message.startsWith("Capture exported; video has no usable coverage for this range"))
        assertNull(imported.videoFile)
        assertNull(imported.descriptor.video)
        assertNull(imported.syncAnchor)
        assertEquals(emptyList(), requireNotNull(imported.timeline).rows)
    }

    // Archive v3: the anchor estimator must prefer a row whose PREDICTED video position actually
    // falls inside the video's coverage over an earlier ordinal that would predict a negative
    // position (a log row captured before recording started). Each row's ts is set so its
    // millis-of-day tracks its host elapsedMs 1:1 (a constant, zero-jitter transport latency),
    // matching how the OLD per-row mapping used to compute each row's own videoMs directly.
    @Test
    fun exportedVideoArchiveReopensWithASyncAnchorPreferringARowInsideVideoCoverage() {
        val root = createTempDirectory("capture-export-video-mapping").toFile()
        val session = session(root, recordVideo = true).copy(videoStartElapsedMs = 2_000, elapsedMs = 5_000)
        writeCaptureInput(session, listOf(
            RawRow("01-01 10:00:01.000  1  1 I Tag: row-before-video\n", 1_000, 1),
            RawRow("01-01 10:00:03.000  1  1 I Tag: row-in-video-1\n", 3_000, 2),
            RawRow("01-01 10:00:05.000  1  1 I Tag: row-in-video-2\n", 5_000, 3),
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
        assertEquals("screen.mp4", imported.descriptor.video?.path)
        // Row 1 ("row-before-video") predicts a negative video position and is skipped; row 2 is
        // the first row whose predicted position actually lands inside the video (matches exactly
        // what the old mapping computed for that same row: videoMs = 1_000).
        assertEquals(1_000L, resolvedTiming(imported).videoMsForOrdinal(2))
        assertEquals(emptyList(), requireNotNull(imported.timeline).rows)
    }

    @Test
    fun finalizedCaptureReopensWithASyncAnchorAndNoMappingFile() {
        val root = createTempDirectory("capture-finalize-video-mapping").toFile()
        val session = session(root, recordVideo = true).copy(
            status = com.indagium.capture.CaptureStatus.STOPPED,
            videoStartElapsedMs = 2_000,
            elapsedMs = 5_000,
        )
        writeCaptureInput(session, listOf(
            RawRow("01-01 10:00:01.000  1  1 I Tag: row-before-video\n", 1_000, 1),
            RawRow("01-01 10:00:03.000  1  1 I Tag: row-in-video\n", 3_000, 2),
        ))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(7, 8, 9))

        val imported = CaptureArchiveExporter().finalizeSessionInPlace(session)

        assertEquals(session.videoFile, imported.videoFile)
        assertNull(imported.descriptor.mapping)
        assertEquals("video/screen.mkv", imported.descriptor.video?.path)
        assertEquals(2, requireNotNull(imported.timeline).rows.size)
        // Same reasoning as exportedVideoArchiveReopensWithASyncAnchorPreferringARowInsideVideoCoverage:
        // row 1 predicts a negative video position (raw/unremuxed video, so there is no clip upper
        // bound — only the lower 0 bound applies) and is skipped in favor of row 2.
        assertEquals(2, imported.syncAnchor?.row)
        assertEquals(1_000L, imported.syncAnchor?.videoMs)
    }

    // A capture finalized before the anchor fix could pin its descriptor anchor to a row recorded
    // BEFORE the video started (a buffered, earlier-day row). Reopening the finalized folder must
    // replace that in memory with an anchor on a recorded row, without rewriting the descriptor.
    @Test
    fun reopeningAFolderWhoseDescriptorAnchorPointsBeforeTheVideoRecomputesItOnARecordedRow() {
        val root = createTempDirectory("capture-stale-anchor").toFile()
        val session = finalizedSessionWithThreeRows(root)
        val descriptorFile = File(session.directory, CAPTURE_DESCRIPTOR_NAME)
        setDescriptorAnchor(descriptorFile, row = 1, videoMs = 300)
        val staleText = descriptorFile.readText()

        val imported = CaptureArchiveReader.open(descriptorFile, File(root, "cache"))

        assertEquals(2, imported.syncAnchor?.row)
        assertEquals(1_000L, imported.syncAnchor?.videoMs)
        assertEquals(com.indagium.capture.CaptureSyncAnchor(row = 1, videoMs = 300), imported.staleSyncAnchor)
        assertEquals(1, imported.descriptor.syncAnchor?.row)
        assertEquals(staleText, descriptorFile.readText(), "the descriptor on disk must not be rewritten")
    }

    @Test
    fun reopeningAFolderWhoseDescriptorAnchorIsOnARecordedRowKeepsItUntouched() {
        val root = createTempDirectory("capture-good-anchor").toFile()
        val session = finalizedSessionWithThreeRows(root)
        val descriptorFile = File(session.directory, CAPTURE_DESCRIPTOR_NAME)
        setDescriptorAnchor(descriptorFile, row = 3, videoMs = 2_000)

        val imported = CaptureArchiveReader.open(descriptorFile, File(root, "cache"))

        assertEquals(com.indagium.capture.CaptureSyncAnchor(row = 3, videoMs = 2_000), imported.syncAnchor)
        assertNull(imported.staleSyncAnchor)
    }

    @Test
    fun aFolderWithoutItsIndexFileKeepsTheDescriptorAnchorAsIs() {
        val root = createTempDirectory("capture-stale-anchor-no-index").toFile()
        val session = finalizedSessionWithThreeRows(root)
        val descriptorFile = File(session.directory, CAPTURE_DESCRIPTOR_NAME)
        setDescriptorAnchor(descriptorFile, row = 1, videoMs = 300)
        assertTrue(session.indexFile.delete())

        val imported = CaptureArchiveReader.open(descriptorFile, File(root, "cache"))

        assertEquals(com.indagium.capture.CaptureSyncAnchor(row = 1, videoMs = 300), imported.syncAnchor)
        assertNull(imported.staleSyncAnchor)
    }

    // Video starts at elapsed 2_000; row 1 (elapsed 1_000) predates it, rows 2-3 are recorded.
    private fun finalizedSessionWithThreeRows(root: File): CaptureSession {
        val session = session(root, recordVideo = true).copy(
            status = com.indagium.capture.CaptureStatus.STOPPED,
            videoStartElapsedMs = 2_000,
            elapsedMs = 5_000,
        )
        writeCaptureInput(session, listOf(
            RawRow("01-01 10:00:01.000  1  1 I Tag: row-before-video\n", 1_000, 1),
            RawRow("01-01 10:00:03.000  1  1 I Tag: row-in-video-1\n", 3_000, 2),
            RawRow("01-01 10:00:05.000  1  1 I Tag: row-in-video-2\n", 5_000, 3),
        ))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(7, 8, 9))
        CaptureArchiveExporter().finalizeSessionInPlace(session)
        return session
    }

    private fun setDescriptorAnchor(descriptorFile: File, row: Int, videoMs: Long) {
        val text = descriptorFile.readText()
        val rewritten = text.replace(Regex("\"sync\":\\{[^}]*\\}"), "\"sync\":{\"row\":$row,\"videoMs\":$videoMs}")
        assertTrue(text.contains("\"sync\":{"), "sync key not found in descriptor")
        descriptorFile.writeText(rewritten, Charsets.UTF_8)
    }

    @Test
    fun finalizeStoppedSessionOpensInPlaceAndKeepsRawVideo() {
        val root = createTempDirectory("capture-finalize").toFile()
        val session = session(root, recordVideo = true).copy(
            status = com.indagium.capture.CaptureStatus.STOPPED,
            videoStartElapsedMs = 500,
        )
        writeCaptureInput(session, listOf(
            RawRow("01-01 10:00:00.000  1  1 I Tag: row-1\n", 1_000, 1),
            RawRow("----- separator\n", 1_500, null),
            RawRow("01-01 10:00:01.000  1  1 I Tag: row-2\n", 2_000, 2),
        ))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(7, 8, 9))

        val imported = CaptureArchiveExporter().finalizeSessionInPlace(session)

        assertEquals(session.logFile, imported.logFile)
        assertEquals(session.videoFile, imported.videoFile)
        assertEquals(
            "01-01 10:00:00.000  1  1 I Tag: row-1\n----- separator\n01-01 10:00:01.000  1  1 I Tag: row-2\n",
            imported.logFile.readText(),
        )
        // Archive v3: no row-by-row mapping file any more (requirement 4) — a single sync anchor
        // replaces it. Row 1 is the first (and here, only viable) candidate.
        assertEquals(1, imported.syncAnchor?.row)
        assertEquals(500L, imported.syncAnchor?.videoMs)
        assertEquals(2, requireNotNull(imported.timeline).rows.size)
        assertEquals(File(session.directory, CAPTURE_DESCRIPTOR_NAME), imported.source)
        assertEquals(byteArrayOf(7, 8, 9).toList(), imported.videoFile?.readBytes()?.toList())
        assertFalse(File(session.directory, "mapping/log-video.jsonl").exists())
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
            if (name == "logcat.log") bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() } else bytes
        }

        assertFails { CaptureArchiveReader.open(tampered, File(root, "cache")) }
    }

    @Test
    fun readerOpensAnArchiveWhoseLogEntryIsZstdMethod93WithIdenticalLogBytes() {
        val root = createTempDirectory("capture-archive-zstd").toFile()
        val original = exportLogOnly(root)
        val zstd = File(root, "zstd.zip")
        ZstdZipFixture.rewrite(original, zstd, zstdEntries = setOf("logcat.log"))

        val method = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(zstd).get().use { it.getEntry("logcat.log").method }
        assertEquals(org.apache.commons.compress.archivers.zip.ZipMethod.ZSTD.code, method)

        val expected = CaptureArchiveReader.open(original, File(root, "cache-original")).logFile.readBytes()
        val imported = CaptureArchiveReader.open(zstd, File(root, "cache-zstd"))
        assertTrue(expected.isNotEmpty())
        assertTrue(expected.contentEquals(imported.logFile.readBytes()))
        val restored = CaptureArchiveReader.extractVerifiedLogForRestore(zstd, "logcat.log", File(root, "cache-restore"))
        assertTrue(expected.contentEquals(requireNotNull(restored).readBytes()))
    }

    @Test
    fun readerReportsAZstdLogCorruptedMidStreamAsAFriendlyArchiveError() {
        val root = createTempDirectory("capture-archive-zstd-mid").toFile()
        val zstd = zstdArchiveOfBigLog(root)
        patchZstdLogFrame(zstd) { frame -> frame.withDecodeErrorFlippedByteInTheMiddle() }

        assertZstdLogCorruption(zstd, root)
    }

    @Test
    fun readerReportsAZstdLogWithABadFrameHeaderAsAFriendlyArchiveError() {
        val root = createTempDirectory("capture-archive-zstd-header").toFile()
        val zstd = zstdArchiveOfBigLog(root)
        patchZstdLogFrame(zstd) { frame -> frame.also { repeat(4) { i -> it[i] = 0 } } }

        assertZstdLogCorruption(zstd, root)
    }

    @Test
    fun zstdLogWithTheWrongChecksumKeepsTheExistingChecksumError() {
        val root = createTempDirectory("capture-archive-zstd-checksum").toFile()
        val original = exportBigLog(root)
        val entries = LinkedHashMap<String, ByteArray>()
        ZipFile(original).use { zip ->
            zip.entries().asSequence().forEach { entry -> entries[entry.name] = zip.getInputStream(entry).use { it.readBytes() } }
        }
        entries["logcat.log"] = entries.getValue("logcat.log").copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tampered = File(root, "zstd-tampered.zip")
        ZstdZipFixture.write(tampered, entries, zstdEntries = setOf("logcat.log"))

        // A well-formed zstd stream with the wrong content is a digest problem, not a decoding problem.
        val failure = assertFailsWith<IllegalArgumentException> { CaptureArchiveReader.open(tampered, File(root, "cache")) }
        assertTrue(failure.message.orEmpty().contains("checksum mismatch"), failure.message)
        assertNull(CaptureArchiveReader.extractVerifiedLogForRestore(tampered, "logcat.log", File(root, "cache-restore")))
    }

    private fun assertZstdLogCorruption(archive: File, root: File) {
        val failure = assertFailsWith<CaptureArchiveException> { CaptureArchiveReader.open(archive, File(root, "cache")) }
        assertEquals("The capture log in this archive is corrupted (zstd data could not be decoded)", failure.message)
        assertTrue(failure.cause is IOException, "the zstd decoding error stays attached as the cause: ${failure.cause}")
        assertTrue(File(root, "cache").listFiles().orEmpty().isEmpty(), "a failed import must not leave a half-extracted capture behind")
        assertNull(CaptureArchiveReader.extractVerifiedLogForRestore(archive, "logcat.log", File(root, "cache-restore")))
        assertTrue(File(root, "cache-restore").listFiles().orEmpty().isEmpty())
    }

    /** A capture archive whose log is big enough for zstd to emit several compressed blocks, stored as method 93. */
    private fun zstdArchiveOfBigLog(root: File): File {
        val zstd = File(root, "zstd-big.zip")
        ZstdZipFixture.rewrite(exportBigLog(root), zstd, zstdEntries = setOf("logcat.log"))
        return zstd
    }

    private fun exportBigLog(root: File): File {
        val session = session(root)
        val rows = (0 until BIG_LOG_ROWS).map { i ->
            RawRow("01-01 10:00:%02d.%03d  1  1 I Tag: row $i value ${(i * 7919L) % 1_009}\n".format(i / 1_000 % 60, i % 1_000), 1_000L + i, i + 1)
        }
        writeCaptureInput(session, rows)
        return File(root, "capture-big.zip").also { destination ->
            CaptureArchiveExporter(CaptureVideoExporter { _, _, _, _ -> error("unused") }).export(
                session,
                CaptureExportRequest(destination, CaptureRange.ALL, cutoffElapsedMs = 1_000L + BIG_LOG_ROWS),
            )
        }
    }

    /** Rewrites the stored zstd frame of `logcat.log` inside [archive] in place (ZIP stores method 93 data verbatim). */
    private fun patchZstdLogFrame(archive: File, patch: (ByteArray) -> ByteArray) {
        val logBytes = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(archive).get().use { zip ->
            zip.getInputStream(zip.getEntry("logcat.log")).use { it.readBytes() }
        }
        val frame = Zstd.compress(logBytes)
        val bytes = archive.readBytes()
        val offset = (0..bytes.size - frame.size).first { start -> frame.indices.all { bytes[start + it] == frame[it] } }
        val patched = patch(frame.copyOf())
        assertEquals(frame.size, patched.size)
        RandomAccessFile(archive, "rw").use { file ->
            file.seek(offset.toLong())
            file.write(patched)
        }
    }

    /**
     * Flips one byte somewhere in the middle of the frame, choosing the first position whose damage the
     * zstd decoder itself detects (a flipped literal byte can decode "successfully" to wrong data, which
     * is the checksum's business, not the decoder's).
     */
    private fun ByteArray.withDecodeErrorFlippedByteInTheMiddle(): ByteArray {
        for (position in size / 4 until size * 3 / 4) {
            val candidate = copyOf().also { it[position] = (it[position].toInt() xor 0xFF).toByte() }
            val decodes = runCatching { ZstdInputStream(ByteArrayInputStream(candidate)).use { it.readBytes() } }.isSuccess
            if (!decodes) return candidate
        }
        error("no mid-stream byte flip made the zstd frame undecodable")
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
                // v3 writes "version", not the old "formatVersion" key (still read for a v1/v2
                // archive) — parseDescriptor accepts 1..CAPTURE_ARCHIVE_VERSION, so "future" here
                // must be one past whatever that is.
                bytes.toString(Charsets.UTF_8)
                    .replace("\"version\":${com.indagium.capture.CAPTURE_ARCHIVE_VERSION}", "\"version\":99")
                    .toByteArray()
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
        assertEquals(2_000L, imported.descriptor.logStartMs)
        assertEquals(2_000L, imported.descriptor.logEndMs)
        // No video in this session, so there is nothing to anchor.
        assertNull(imported.syncAnchor)
        assertEquals(emptyList(), requireNotNull(imported.timeline).rows)
    }

    // Regression test for the "video checkpoint split" fix: before it, Since-last-save always
    // started the video range from the same cursor as the log range. That either re-remuxed the
    // whole recording from its first keyframe on every save (when the cursor was never advanced
    // past 0) or, once a shared cursor did advance, silently skipped whatever tail a lagging
    // exporter genuinely failed to cover (the log cursor moved past video that was never actually
    // exported). This exercises three consecutive snapshots where the first one's video export
    // covers LESS than what was requested (simulating the growing MKV's muxer/AVIO lag) and proves
    // snapshot 2 reaches back to recover exactly that gap instead of either point, and snapshot 3
    // continues from exactly where snapshot 2 left off — no restart from 0, no dropped span.
    @Test
    fun sinceSaveVideoRangeRecoversAPriorSnapshotsShortfallAndThenContinuesWithoutGaps() {
        val root = createTempDirectory("capture-archive-video-checkpoint-split").toFile()
        val requests = mutableListOf<Pair<Long, Long>>()
        var call = 0
        val laggingThenExactVideo = CaptureVideoExporter { _, destination, start, end ->
            call++
            requests += start to end
            destination.writeText("video-$call")
            // First export under-covers by 500ms (simulating muxer lag); every later export covers
            // exactly what was requested.
            val coveredEnd = if (call == 1) end - 500 else end
            CaptureVideoClip(actualStartMs = start, coveredEndMs = coveredEnd, durationMs = coveredEnd - start)
        }
        val base = session(root, recordVideo = true).copy(videoStartElapsedMs = 0)
        writeCaptureInput(base, listOf(
            RawRow("01-01 10:00:01.000  1  1 I Tag: a\n", 1_000, 1),
            RawRow("01-01 10:00:02.000  1  1 I Tag: b\n", 2_000, 2),
            RawRow("01-01 10:00:03.000  1  1 I Tag: c\n", 3_000, 3),
            RawRow("01-01 10:00:04.000  1  1 I Tag: d\n", 4_000, 4),
        ))
        base.videoFile.parentFile.mkdirs()
        base.videoFile.writeBytes(byteArrayOf(1))
        val exporter = CaptureArchiveExporter(laggingThenExactVideo)

        // Snapshot 1: ALL up to row b (elapsed 2000). Video under-covers: requested end 2000ms,
        // actually covers only up to 1500ms.
        val result1 = exporter.export(
            base,
            CaptureExportRequest(File(root, "s1.zip"), CaptureRange.ALL, cutoffElapsedMs = 2_000),
        )
        assertEquals(2_000, result1.logCoveredEndMs)
        assertEquals(1_500, result1.videoCoveredEndMs)
        val afterSnapshot1 = base.copy(
            logCheckpointMs = result1.logCoveredEndMs,
            videoCheckpointMs = requireNotNull(result1.videoCoveredEndMs),
        )

        // Snapshot 2: Since last save, up to row c (elapsed 3000). The video request must start at
        // 1500 (where video genuinely left off), not 2000 (where the log cursor is) — recovering
        // the 500ms snapshot 1's lag silently would otherwise have dropped forever.
        val result2 = exporter.export(
            afterSnapshot1,
            CaptureExportRequest(File(root, "s2.zip"), CaptureRange.SINCE_SAVE, cutoffElapsedMs = 3_000),
        )
        assertEquals(1_500L to 3_000L, requests[1])
        assertEquals(3_000, result2.videoCoveredEndMs, "a fully-covered export must reach the requested end")
        val afterSnapshot2 = afterSnapshot1.copy(
            logCheckpointMs = result2.logCoveredEndMs,
            videoCheckpointMs = requireNotNull(result2.videoCoveredEndMs),
        )

        // Snapshot 3: Since last save, up to row d (elapsed 4000). Must continue exactly from 3000,
        // never restarting from 0 the way a shared/stuck cursor used to.
        val result3 = exporter.export(
            afterSnapshot2,
            CaptureExportRequest(File(root, "s3.zip"), CaptureRange.SINCE_SAVE, cutoffElapsedMs = 4_000),
        )
        assertEquals(3_000L to 4_000L, requests[2])
        assertEquals(4_000, result3.videoCoveredEndMs)
    }

    // Regression coverage for waitForVideoCoverage (CaptureArchiveExporter): a still-recording
    // session's MKV grows while the export is in flight, and the real exporter must keep re-probing
    // as it grows (not just once) rather than giving up or racing ahead of the muxer. Growing the
    // fixture's video file on a background thread also exercises probeCoverageEndMs's
    // length-keyed cache doing the right thing: a poll against an unchanged length must not force a
    // fresh probe, but a poll after the file has genuinely grown must.
    @Test
    fun waitForVideoCoverageRetriesUntilTheGrowingRecordingCatchesUpThenExports() {
        val root = createTempDirectory("capture-archive-wait-coverage").toFile()
        val session = session(root, recordVideo = true).copy(videoStartElapsedMs = 0)
        writeCaptureInput(session, listOf(RawRow("01-01 10:00:03.000  1  1 I Tag: c\n", 3_000, 1)))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1))
        val fake = GrowthAwareCoverageFake(readyAtLength = 4L)
        val exporter = CaptureArchiveExporter(fake, videoCoverageWaitMs = 3_000, videoCoverageWaitPollMs = 10)
        val grower = thread(name = "wait-coverage-test-grower") {
            repeat(3) {
                Thread.sleep(30)
                session.videoFile.appendBytes(byteArrayOf(1))
            }
        }
        try {
            val result = exporter.export(
                session,
                CaptureExportRequest(File(root, "wait.zip"), CaptureRange.ALL, cutoffElapsedMs = 3_000),
            )
            assertEquals(3_000, result.videoCoveredEndMs)
            assertTrue(fake.probeCalls >= 2, "the wait must actually re-probe as the file grows, saw ${fake.probeCalls}")
            assertEquals(1, fake.exportCalls, "the real export must still run exactly once after coverage arrives")
        } finally {
            grower.join(5_000)
        }
    }

    // The wait must never hang the caller: a bound that expires still lets export() proceed with
    // whatever coverage genuinely exists (here, none) rather than blocking indefinitely.
    @Test
    fun waitForVideoCoverageGivesUpAfterItsBoundAndExportsWhateverIsActuallyAvailable() {
        val root = createTempDirectory("capture-archive-wait-timeout").toFile()
        val session = session(root, recordVideo = true).copy(videoStartElapsedMs = 0)
        writeCaptureInput(session, listOf(RawRow("01-01 10:00:03.000  1  1 I Tag: c\n", 3_000, 1)))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1))
        val fake = GrowthAwareCoverageFake(readyAtLength = 1_000)
        val exporter = CaptureArchiveExporter(fake, videoCoverageWaitMs = 60, videoCoverageWaitPollMs = 10)

        val elapsedMs = measureTimeMillis {
            exporter.export(session, CaptureExportRequest(File(root, "timeout.zip"), CaptureRange.ALL, cutoffElapsedMs = 3_000))
        }

        assertTrue(elapsedMs < 2_000, "the wait must honour its configured bound (60ms), took ${elapsedMs}ms")
        assertEquals(1, fake.exportCalls, "export must still proceed once the wait bound expires")
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

    // Archive v3: the flat layout an export actually produces on disk, and every entry name is on
    // openZip's allowlist — including captured_with_indagium.txt, which carries no descriptor asset
    // entry of its own (see CaptureArchive.kt's scanZipEntries/writeZip).
    @Test
    fun v5ExportAddsSparseBlocksAndSyncMetadataToTheFlatLayout() {
        val root = createTempDirectory("capture-archive-v5-layout").toFile()
        val zip = exportLogOnly(root)

        val names = ZipFile(zip).use { it.entries().asSequence().map { entry -> entry.name }.toSet() }

        // Archive v5 stores a compact block index and checksummed sparse sync manifest.
        assertEquals(
            setOf(
                CAPTURE_DESCRIPTOR_NAME,
                "captured_with_indagium.txt",
                "logcat.log",
                "mapping/blocks.jsonl",
                "timeline/sync.json",
            ),
            names,
        )
        val blurb = ZipFile(zip).use { zipFile ->
            zipFile.getInputStream(zipFile.getEntry("captured_with_indagium.txt")).readBytes().toString(Charsets.UTF_8)
        }
        assertTrue(blurb.contains("Indagium"))
        assertTrue(blurb.contains("indagium.com"))

        // The descriptor itself carries "version": 5 (not the old "formatVersion" key) and a flat
        // sha256 map instead of one {path,sizeBytes,sha256} object per asset.
        val descriptorJson = ZipFile(zip).use { zipFile ->
            zipFile.getInputStream(zipFile.getEntry(CAPTURE_DESCRIPTOR_NAME)).readBytes().toString(Charsets.UTF_8)
        }
        assertTrue(descriptorJson.contains("\"version\":5"))
        assertTrue(descriptorJson.contains("\"sha256\""))

        val imported = CaptureArchiveReader.open(zip, File(root, "cache"))
        assertEquals(5, imported.descriptor.formatVersion)
        assertEquals("logcat.log", imported.descriptor.log.path)
        assertNull(imported.descriptor.mapping)
        assertNull(imported.syncAnchor, "exportLogOnly has no video, so there is nothing to anchor")
        assertEquals(emptyList(), requireNotNull(imported.timeline).rows)
        assertEquals(1, requireNotNull(imported.sparseSyncMetadata).rowCount)
    }

    // Archive v3: the reader keeps parsing the old nested logs/video/mapping layout and
    // {path,sizeBytes,sha256}-per-asset descriptor shape a pre-v3 build wrote — see
    // CaptureArchive.kt's parseDescriptorLegacy. v1 never had notes/markers keys at all.
    @Test
    fun v1LegacyNestedLayoutStillOpensWithNoNotes() {
        val root = createTempDirectory("capture-archive-v1-legacy").toFile()
        val logBytes = "01-01 10:00:00.000  1  1 I Tag: legacy row\n".toByteArray()
        val mappingBytes = "{\"ordinal\":1,\"elapsedMs\":1000,\"videoMs\":null}\n".toByteArray()
        val descriptorJson = """
            {"format":"indagium-capture","formatVersion":1,"sessionId":"legacy-v1-session",
             "device":{"serial":"serial","state":"device","model":"Pixel","emulator":false},
             "settings":{"formatVersion":1},
             "sessionStartedEpochMs":1700000000000,"exportedEpochMs":1700000001000,"range":"ALL",
             "coverage":{"logStartMs":1000,"logEndMs":1000,"videoRequestedStartMs":null,"videoActualStartMs":null,"videoEndMs":null},
             "mappingMetadata":{"quality":"estimated","uncertaintyMs":null,"manualOffsetMs":0},
             "interruptions":[],
             "log":{"path":"logs/logcat.log","sizeBytes":${logBytes.size},"sha256":"${sha256Hex(logBytes)}"},
             "mapping":{"path":"mapping/log-video.jsonl","sizeBytes":${mappingBytes.size},"sha256":"${sha256Hex(mappingBytes)}"},
             "video":null,"screenshots":[]}
        """.trimIndent()
        val zip = File(root, "legacy-v1.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(CAPTURE_DESCRIPTOR_NAME)); out.write(descriptorJson.toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("logs/logcat.log")); out.write(logBytes); out.closeEntry()
            out.putNextEntry(ZipEntry("mapping/log-video.jsonl")); out.write(mappingBytes); out.closeEntry()
        }

        val imported = CaptureArchiveReader.open(zip, File(root, "cache"))
        assertEquals(1, imported.descriptor.formatVersion)
        assertEquals("legacy-v1-session", imported.descriptor.sessionId)
        assertEquals("logs/logcat.log", imported.descriptor.log.path)
        assertEquals(1, requireNotNull(imported.timeline).rows.size)
        assertNull(imported.syncAnchor)
        assertNull(imported.videoFile)
        assertNull(imported.descriptor.notes)
        assertTrue(imported.descriptor.markers.isEmpty())
    }

    // v2 (Phase 4: snapshot archive + import) added the OPTIONAL notes/markers keys to the same
    // nested v1 layout, still under the old per-asset-object shape (not v3's bare path + sha256 map).
    @Test
    fun v2LegacyNestedLayoutStillOpensWithNotesAndMarkers() {
        val root = createTempDirectory("capture-archive-v2-legacy").toFile()
        val logBytes = "01-01 10:00:00.000  1  1 I Tag: legacy row\n".toByteArray()
        val mappingBytes = "{\"ordinal\":1,\"elapsedMs\":1000,\"videoMs\":null}\n".toByteArray()
        val notesBytes = "n1|Plain legacy note".toByteArray()
        val descriptorJson = """
            {"format":"indagium-capture","formatVersion":2,"sessionId":"legacy-v2-session",
             "device":{"serial":"serial","state":"device","model":"Pixel","emulator":false},
             "settings":{"formatVersion":1},
             "sessionStartedEpochMs":1700000000000,"exportedEpochMs":1700000001000,"range":"ALL",
             "coverage":{"logStartMs":1000,"logEndMs":1000,"videoRequestedStartMs":null,"videoActualStartMs":null,"videoEndMs":null},
             "mappingMetadata":{"quality":"estimated","uncertaintyMs":null,"manualOffsetMs":0},
             "interruptions":[],
             "log":{"path":"logs/logcat.log","sizeBytes":${logBytes.size},"sha256":"${sha256Hex(logBytes)}"},
             "mapping":{"path":"mapping/log-video.jsonl","sizeBytes":${mappingBytes.size},"sha256":"${sha256Hex(mappingBytes)}"},
             "video":null,"screenshots":[],
             "notes":{"path":"notes/capture.ann","sizeBytes":${notesBytes.size},"sha256":"${sha256Hex(notesBytes)}"},
             "markers":[{"noteBlockId":"n1","firstOrdinal":1,"lastOrdinal":1}]}
        """.trimIndent()
        val zip = File(root, "legacy-v2.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(CAPTURE_DESCRIPTOR_NAME)); out.write(descriptorJson.toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("logs/logcat.log")); out.write(logBytes); out.closeEntry()
            out.putNextEntry(ZipEntry("mapping/log-video.jsonl")); out.write(mappingBytes); out.closeEntry()
            out.putNextEntry(ZipEntry("notes/capture.ann")); out.write(notesBytes); out.closeEntry()
        }

        val imported = CaptureArchiveReader.open(zip, File(root, "cache"))
        assertEquals(2, imported.descriptor.formatVersion)
        assertEquals(1, imported.descriptor.markers.size)
        assertEquals("n1", imported.descriptor.markers.single().noteBlockId)
        assertNull(imported.videoFile)
    }

    @Test
    fun stoppedSessionExportReadsTheFinalVideoDirectlyAndRecordingSessionSnapshotsIt() {
        val calls = mutableListOf<String>()
        val recordingVideo = CallRecordingVideoExporter(calls)

        listOf(
            com.indagium.capture.CaptureStatus.RECORDING to "export",
            com.indagium.capture.CaptureStatus.STOPPED to "exportFinal",
            // INTERRUPTED is only trusted as final for a quiet file (the file below is made old and idle).
            com.indagium.capture.CaptureStatus.INTERRUPTED to "exportFinal",
        ).forEach { (status, expectedCall) ->
            val root = createTempDirectory("capture-archive-final-$status").toFile()
            val session = session(root, recordVideo = true).copy(status = status)
            writeCaptureInput(session, listOf(RawRow("01-01 10:00:00.000  1  1 I Tag: row\n", 10_000, 1)))
            session.videoFile.parentFile.mkdirs()
            session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
            session.videoFile.setLastModified(System.currentTimeMillis() - 60_000L)
            calls.clear()

            val destination = File(root, "export.zip")
            CaptureArchiveExporter(recordingVideo, videoStabilitySampleMs = 10L).export(
                session,
                CaptureExportRequest(destination, CaptureRange.ALL, cutoffElapsedMs = 400_000),
            )

            assertEquals(listOf(expectedCall), calls, "status $status")
            // Either path still produces an archive the reader opens, video included.
            assertTrue(CaptureArchiveReader.open(destination, File(root, "cache")).videoFile?.isFile == true)
        }
    }

    // An INTERRUPTED status read from disk can be stale: another recorder's recoverSessions() marks a
    // session that is still recording INTERRUPTED until its owner persists again. The MKV keeps
    // growing, so reading it directly (exportFinal) would race the writer; only the snapshot is safe.
    @Test
    fun anInterruptedSessionWhoseVideoIsStillBeingWrittenIsSnapshottedNotReadDirectly() {
        val calls = mutableListOf<String>()
        val root = createTempDirectory("capture-archive-stale-interrupted").toFile()
        val session = session(root, recordVideo = true).copy(status = com.indagium.capture.CaptureStatus.INTERRUPTED)
        writeCaptureInput(session, listOf(RawRow("01-01 10:00:00.000  1  1 I Tag: row\n", 10_000, 1)))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        // Just written: the file was touched moments ago, i.e. a writer may still be appending to it.
        session.videoFile.setLastModified(System.currentTimeMillis())

        CaptureArchiveExporter(CallRecordingVideoExporter(calls), videoStabilitySampleMs = 10L).export(
            session,
            CaptureExportRequest(File(root, "export.zip"), CaptureRange.ALL, cutoffElapsedMs = 400_000),
        )

        assertEquals(listOf("export"), calls)
    }

    @Test
    fun anInterruptedSessionWhoseVideoGrowsDuringTheStabilityCheckIsSnapshotted() {
        val calls = mutableListOf<String>()
        val root = createTempDirectory("capture-archive-growing-interrupted").toFile()
        val session = session(root, recordVideo = true).copy(status = com.indagium.capture.CaptureStatus.INTERRUPTED)
        writeCaptureInput(session, listOf(RawRow("01-01 10:00:00.000  1  1 I Tag: row\n", 10_000, 1)))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        session.videoFile.setLastModified(System.currentTimeMillis() - 60_000L)
        // The writer appends while the exporter waits between its two samples, then looks idle again.
        val appender = thread {
            Thread.sleep(100)
            session.videoFile.appendBytes(byteArrayOf(4))
            session.videoFile.setLastModified(System.currentTimeMillis() - 60_000L)
        }

        CaptureArchiveExporter(CallRecordingVideoExporter(calls), videoStabilitySampleMs = 400L).export(
            session,
            CaptureExportRequest(File(root, "export.zip"), CaptureRange.ALL, cutoffElapsedMs = 400_000),
        )
        appender.join()

        assertEquals(listOf("export"), calls)
    }

    @Test
    fun aSessionStillRecordingInThisProcessIsNeverReadDirectlyEvenWithAQuietFileAndAStoppedStatus() {
        val calls = mutableListOf<String>()
        val root = createTempDirectory("capture-archive-live-owner").toFile()
        val session = session(root, recordVideo = true).copy(status = com.indagium.capture.CaptureStatus.INTERRUPTED)
        writeCaptureInput(session, listOf(RawRow("01-01 10:00:00.000  1  1 I Tag: row\n", 10_000, 1)))
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        session.videoFile.setLastModified(System.currentTimeMillis() - 60_000L)

        com.indagium.capture.LiveCaptureSessions.add(session.directory)
        try {
            CaptureArchiveExporter(CallRecordingVideoExporter(calls), videoStabilitySampleMs = 10L).export(
                session,
                CaptureExportRequest(File(root, "live.zip"), CaptureRange.ALL, cutoffElapsedMs = 400_000),
            )
        } finally {
            com.indagium.capture.LiveCaptureSessions.remove(session.directory)
        }
        assertEquals(listOf("export"), calls)

        // Once the owner is gone and the file is quiet, the same session takes the fast path.
        calls.clear()
        CaptureArchiveExporter(CallRecordingVideoExporter(calls), videoStabilitySampleMs = 10L).export(
            session,
            CaptureExportRequest(File(root, "stopped.zip"), CaptureRange.ALL, cutoffElapsedMs = 400_000),
        )
        assertEquals(listOf("exportFinal"), calls)
    }

    @Test
    fun zipStoresAlreadyCompressedMediaAndStillDeflatesText() {
        val root = createTempDirectory("capture-archive-zip-levels").toFile()
        val session = session(root, recordVideo = true).copy(status = com.indagium.capture.CaptureStatus.STOPPED)
        val repetitiveRow = "01-01 10:00:00.000  1  1 I Tag: the same repetitive logcat line again and again\n"
        writeCaptureInput(session, List(2_000) { RawRow(repetitiveRow, 10_000L + it, it + 1) })
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1))
        // Random bytes stand in for H.264/Opus: DEFLATE cannot shrink them, it can only cost CPU.
        val videoBytes = ByteArray(300_000).also { java.util.Random(7).nextBytes(it) }
        val fakeVideo = CaptureVideoExporter { _, target, start, end ->
            target.writeBytes(videoBytes)
            CaptureVideoClip(start, end, end - start)
        }
        val destination = File(root, "levels.zip")

        CaptureArchiveExporter(fakeVideo).export(
            session,
            CaptureExportRequest(destination, CaptureRange.ALL, cutoffElapsedMs = 400_000),
        )

        ZipFile(destination).use { zip ->
            val video = zip.getEntry("screen.mp4")
            assertEquals(videoBytes.size.toLong(), video.size)
            assertTrue(video.compressedSize >= video.size * 99 / 100, "video must not be re-deflated: ${video.compressedSize}/${video.size}")
            val log = zip.getEntry("logcat.log")
            assertTrue(log.compressedSize < log.size / 4, "text must still be deflated: ${log.compressedSize}/${log.size}")
        }
        // The archive still round-trips through the reader (hash-verified assets).
        assertTrue(CaptureArchiveReader.open(destination, File(root, "cache")).videoFile?.isFile == true)
    }

    private class CallRecordingVideoExporter(private val calls: MutableList<String>) : CaptureVideoExporter {
        override fun export(source: File, destination: File, requestedStartMs: Long, requestedEndMs: Long): CaptureVideoClip {
            calls += "export"
            destination.writeText("video")
            return CaptureVideoClip(requestedStartMs, requestedEndMs, requestedEndMs - requestedStartMs)
        }

        override fun exportFinal(source: File, destination: File, requestedStartMs: Long, requestedEndMs: Long): CaptureVideoClip {
            calls += "exportFinal"
            destination.writeText("video")
            return CaptureVideoClip(requestedStartMs, requestedEndMs, requestedEndMs - requestedStartMs)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun commonsZip(file: File) = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(file).get()

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

    private fun resolvedTiming(imported: com.indagium.capture.ImportedCapture): CaptureTimingResolver =
        CaptureTimingResolver.forTimeline(
            parseLogcat(imported.logFile),
            requireNotNull(imported.timeline),
        )

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

    /** A fake that reports coverage only once its backing file has grown to [readyAtLength] bytes,
     * for exercising CaptureArchiveExporter's waitForVideoCoverage against a genuinely growing
     * source instead of a static fixture. */
    private class GrowthAwareCoverageFake(private val readyAtLength: Long) : CaptureVideoExporter, CaptureVideoCoverageProbe {
        var probeCalls = 0
            private set
        var exportCalls = 0
            private set

        override fun coverageEndMs(source: File, requestedStartMs: Long, requestedEndMs: Long): Long {
            probeCalls++
            return if (source.length() >= readyAtLength) requestedEndMs else requestedStartMs
        }

        override fun export(source: File, destination: File, requestedStartMs: Long, requestedEndMs: Long): CaptureVideoClip {
            exportCalls++
            destination.writeText("video")
            return CaptureVideoClip(requestedStartMs, requestedEndMs, requestedEndMs - requestedStartMs)
        }
    }
}

// Enough rows that zstd emits several compressed blocks, so a mid-stream flip lands in real block data.
private const val BIG_LOG_ROWS = 3_000
