package com.indagium.capture

import com.indagium.utils.parseLogcat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Suppress("MagicNumber")
class CaptureSparseArchiveIntegrationTest {
    @Test
    fun manualLinkDrivesSelectedAndExplicitRangeTimingAndVideo() {
        val root = createTempDirectory("capture-manual-range").toFile()
        val lines = listOf(
            "01-01 10:00:05.000  1  1 I Tag: first\n",
            "01-01 10:00:06.000  1  1 I Tag: second\n",
            "01-01 10:00:07.000  1  1 I Tag: third\n",
        )
        val session = sparseSession(root, lines, listOf(5_000L, 6_000L, 7_000L), videoStartMs = 0L)
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        val reference = requireNotNull(parseCaptureClockReference(lines.first(), SESSION_STARTED_EPOCH_MS + 5_000))
        val manual = CaptureSegmentManualAnchor(
            segmentId = 0,
            rowOrdinal = 1,
            clockReference = reference,
            videoMs = 8_000,
            captureElapsedMs = 8_000,
        )
        val video = RecordingVideoExporter()
        val exporter = CaptureArchiveExporter(video)

        val selectedFile = File(root, "selected.zip")
        exporter.export(
            session,
            CaptureExportRequest(
                destination = selectedFile,
                range = CaptureRange.SELECTION,
                cutoffElapsedMs = 9_000,
                selectedFirstRowOrdinal = 1,
                selectedLastRowOrdinal = 2,
                captureSegmentAnchors = listOf(manual),
            ),
        )

        assertEquals(8_000L to 9_000L, video.requestedRanges.last())
        assertEquals(lines.take(2).joinToString(""), CaptureArchiveReader.open(selectedFile, File(root, "selected-cache")).logFile.readText())

        val explicitFile = File(root, "explicit.zip")
        exporter.export(
            session,
            CaptureExportRequest(
                destination = explicitFile,
                range = CaptureRange.EXPLICIT,
                cutoffElapsedMs = 10_000,
                explicitStartElapsedMs = 8_000,
                explicitEndElapsedMs = 9_000,
                captureSegmentAnchors = listOf(manual),
            ),
        )

        assertEquals(8_000L to 9_000L, video.requestedRanges.last())
        assertEquals(lines.take(2).joinToString(""), CaptureArchiveReader.open(explicitFile, File(root, "explicit-cache")).logFile.readText())
    }

    @Test
    fun sinceSaveCursorHonorsCutoffThenResumesAtTheNextExactRow() {
        val root = createTempDirectory("capture-since-cursor-cutoff").toFile()
        val lines = listOf(
            "01-01 10:00:01.000  1  1 I Tag: saved\n",
            "01-01 10:00:02.000  1  1 I Tag: first new\n",
            "01-01 10:00:03.000  1  1 I Tag: later new\n",
        )
        val base = sparseSession(root, lines, listOf(1_000L, 2_000L, 3_000L), videoStartMs = null).copy(
            snapshotCheckpointMs = 1_000,
            logCheckpointMs = 1_000,
            logCheckpointByteOffset = lines.first().toByteArray().size.toLong(),
            logCheckpointRowOrdinal = 1,
        )
        val exporter = CaptureArchiveExporter()

        val firstFile = File(root, "first.zip")
        val first = exporter.export(
            base,
            CaptureExportRequest(firstFile, CaptureRange.SINCE_SAVE, includeVideo = false, cutoffElapsedMs = 2_500),
        )
        assertEquals(lines[1], CaptureArchiveReader.open(firstFile, File(root, "first-cache")).logFile.readText())

        val resumed = base.copy(
            snapshotCheckpointMs = first.logCoveredEndMs,
            logCheckpointMs = first.logCoveredEndMs,
            logCheckpointByteOffset = requireNotNull(first.logCoveredEndByteOffset),
            logCheckpointRowOrdinal = requireNotNull(first.logCoveredEndRowOrdinal),
        )
        val secondFile = File(root, "second.zip")
        exporter.export(
            resumed,
            CaptureExportRequest(secondFile, CaptureRange.SINCE_SAVE, includeVideo = false, cutoffElapsedMs = 3_500),
        )
        assertEquals(lines[2], CaptureArchiveReader.open(secondFile, File(root, "second-cache")).logFile.readText())
    }

    @Test
    fun sinceSaveDoesNotSkipAFileOrderRowWhoseCorrectedTimeFallsBeforeCutoff() {
        val root = createTempDirectory("capture-since-cursor-out-of-order").toFile()
        val lines = listOf(
            "01-01 10:00:01.000  1  1 I Tag: saved\n",
            "01-01 10:00:05.000  1  1 I Tag: future first\n",
            "01-01 10:00:03.000  1  1 I Tag: earlier second\n",
            "01-01 10:00:06.000  1  1 I Tag: final\n",
        )
        val automatic = sparseSession(root, lines, listOf(1_000L, 2_000L, 3_000L, 4_000L), videoStartMs = null)
        val manualReference = requireNotNull(parseCaptureClockReference(lines[1], SESSION_STARTED_EPOCH_MS + 5_000))
        val manual = CaptureSegmentManualAnchor(0, 2, manualReference, videoMs = 5_000, captureElapsedMs = 5_000)
        val firstLineBytes = lines.first().toByteArray(Charsets.UTF_8).size.toLong()
        val sinceSaved = automatic.copy(
            snapshotCheckpointMs = 1_000,
            logCheckpointMs = 1_000,
            logCheckpointByteOffset = firstLineBytes,
            logCheckpointRowOrdinal = 1,
            manualSegmentAnchors = listOf(manual),
        )
        val exporter = CaptureArchiveExporter()
        val firstFile = File(root, "bounded.zip")

        assertFailsWith<IllegalArgumentException> {
            exporter.export(
                sinceSaved,
                CaptureExportRequest(firstFile, CaptureRange.SINCE_SAVE, includeVideo = false, cutoffElapsedMs = 4_000),
            )
        }

        val resumedFile = File(root, "resumed.zip")
        exporter.export(
            sinceSaved,
            CaptureExportRequest(resumedFile, CaptureRange.SINCE_SAVE, includeVideo = false, cutoffElapsedMs = 6_000),
        )
        assertEquals(
            lines.drop(1).joinToString(""),
            CaptureArchiveReader.open(resumedFile, File(root, "resumed-cache")).logFile.readText(),
        )
    }

    @Test
    fun sinceSaveResolvesManualTimingWhenBlockClockContextIsMixed() {
        val root = createTempDirectory("capture-since-mixed-clock").toFile()
        try {
            val lines = listOf(1, 5, 6, 7).map { second ->
                "01-01 10:00:0$second.000  1  1 I Tag: row $second\n"
            }
            val automatic = sparseSession(root, lines, listOf(1_000L, 7_000L, 7_500L, 8_000L), videoStartMs = null)
            val index = CaptureBlockIndex.read(automatic.blockIndexFile, automatic.logFile)
            CaptureBlockIndex.write(automatic.blockIndexFile, index.blocks.map { it.copy(clockSegmentId = null) })
            val manual = CaptureSegmentManualAnchor(
                0, 2, requireNotNull(parseCaptureClockReference(lines[1], SESSION_STARTED_EPOCH_MS + 5_000)), 3_000, 3_000,
            )
            val source = automatic.copy(
                snapshotCheckpointMs = 1_000,
                logCheckpointByteOffset = lines[0].toByteArray().size.toLong(),
                logCheckpointRowOrdinal = 1,
                manualSegmentAnchors = listOf(manual),
                frozenBlockIndexBytes = automatic.blockIndexFile.length(),
            )
            val result = CaptureArchiveExporter().export(
                source,
                CaptureExportRequest(File(root, "bounded.zip"), CaptureRange.SINCE_SAVE, includeVideo = false, cutoffElapsedMs = 4_000),
            )

            assertEquals(lines[1] + lines[2], CaptureArchiveReader.open(result.file, File(root, "cache")).logFile.readText())
            assertEquals(3, result.logCoveredEndRowOrdinal)
            assertEquals(lines.take(3).sumOf { it.toByteArray().size }.toLong(), result.logCoveredEndByteOffset)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyAdapterUsesFrozenLogAndIndexPrefixesWhileSourceFilesContinueGrowing() {
        val root = createTempDirectory("capture-legacy-frozen-prefix").toFile()
        val directory = File(root, "legacy").apply { mkdirs() }
        val session = CaptureSession(
            id = "legacy-prefix",
            directory = directory,
            device = CaptureDevice("serial", "device", "fixture"),
            settings = CaptureSettings(recordVideo = false, freeSpaceReserveBytes = 0),
            startedEpochMs = SESSION_STARTED_EPOCH_MS,
            elapsedMs = 2_000,
            status = CaptureStatus.STOPPED,
        )
        val first = "first row\n".toByteArray(Charsets.UTF_8)
        val second = "later appended row\n".toByteArray(Charsets.UTF_8)
        session.logFile.parentFile.mkdirs()
        session.logFile.writeBytes(first + second)
        session.indexFile.parentFile.mkdirs()
        val firstRecord = "{\"byteOffset\":0,\"byteLength\":${first.size},\"elapsedMs\":1000,\"rowOrdinal\":1}\n"
        val secondRecord = "{\"byteOffset\":${first.size},\"byteLength\":${second.size},\"elapsedMs\":2000,\"rowOrdinal\":2}\n"
        session.indexFile.writeText(firstRecord + secondRecord)
        val frozen = session.copy(
            frozenLogBytes = first.size.toLong(),
            frozenBlockIndexBytes = firstRecord.toByteArray(Charsets.UTF_8).size.toLong(),
        )
        val destination = File(root, "frozen.zip")

        CaptureArchiveExporter().export(
            frozen,
            CaptureExportRequest(destination, cutoffElapsedMs = 1_000, includeVideo = false),
        )

        assertEquals("first row\n", CaptureArchiveReader.open(destination, File(root, "frozen-cache")).logFile.readText())
    }

    @Test
    fun availabilityCardExportsRebaseManualAnchorByVideoOriginsAcrossChildExports() {
        val root = createTempDirectory("capture-availability-manual-rebase").toFile()
        val lines = listOf(
            "01-01 10:00:55.000  1  1 I Tag: at video\n",
            "01-01 10:00:56.000  1  1 I Tag: after video\n",
        )
        val source = sparseSession(root, lines, listOf(55_000L, 56_000L), videoStartMs = 50_000L).copy(
            elapsedMs = 80_000,
            settings = CaptureSettings(recordVideo = true, showUnavailableFrames = true, freeSpaceReserveBytes = 0),
            availabilityIntervals = listOf(CaptureAvailabilityInterval(41_000, 44_000, "before", "after", true)),
        )
        source.videoFile.parentFile.mkdirs()
        source.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        val reference = requireNotNull(parseCaptureClockReference(lines.first(), SESSION_STARTED_EPOCH_MS + 55_000))
        val anchor = CaptureSegmentManualAnchor(0, 1, reference, videoMs = 5_000, captureElapsedMs = 55_000)
        val withAnchor = source.copy(manualSegmentAnchors = listOf(anchor))
        val cardVideo = AvailabilityRecordingVideoExporter()
        val firstFile = File(root, "cards.zip")
        CaptureArchiveExporter(cardVideo).export(
            withAnchor,
            CaptureExportRequest(
                destination = firstFile,
                range = CaptureRange.EXPLICIT,
                cutoffElapsedMs = 80_000,
                explicitStartElapsedMs = 40_000,
                explicitEndElapsedMs = 80_000,
            ),
        )
        assertEquals(40_000L to 80_000L, cardVideo.availabilityRequest)
        val firstImport = CaptureArchiveReader.open(firstFile, File(root, "first-cache"))
        val firstAnchor = requireNotNull(firstImport.sparseSyncMetadata).segments.single().manualAnchor
        assertEquals(15_000L, firstAnchor?.videoMs)
        assertEquals(15_000L, resolvedPoint(firstImport, 1).captureElapsedMs)
        assertEquals(15_000L, resolvedPoint(firstImport, 1).videoMs)

        val secondSession = reexportSession(firstImport)
        val secondFile = File(root, "child-cut.zip")
        val ordinaryVideo = RecordingVideoExporter()
        CaptureArchiveExporter(ordinaryVideo).export(
            secondSession,
            CaptureExportRequest(
                destination = secondFile,
                range = CaptureRange.EXPLICIT,
                cutoffElapsedMs = 40_000,
                explicitStartElapsedMs = 5_000,
                explicitEndElapsedMs = 25_000,
            ),
        )
        assertEquals(5_000L to 25_000L, ordinaryVideo.requestedRanges.single())
        val secondImport = CaptureArchiveReader.open(secondFile, File(root, "second-cache"))
        assertEquals(10_000L, requireNotNull(secondImport.sparseSyncMetadata).segments.single().manualAnchor?.videoMs)

        val thirdSession = reexportSession(secondImport)
        val thirdFile = File(root, "grandchild.zip")
        CaptureArchiveExporter(RecordingVideoExporter()).export(
            thirdSession,
            CaptureExportRequest(thirdFile, cutoffElapsedMs = 20_000),
        )
        val thirdImport = CaptureArchiveReader.open(thirdFile, File(root, "third-cache"))
        assertEquals(10_000L, requireNotNull(thirdImport.sparseSyncMetadata).segments.single().manualAnchor?.videoMs)
        assertEquals(10_000L, resolvedPoint(thirdImport, 1).captureElapsedMs)
    }

    private fun sparseSession(
        root: File,
        lines: List<String>,
        elapsed: List<Long>,
        videoStartMs: Long?,
    ): CaptureSession {
        val directory = File(root, "session-${System.nanoTime()}").apply { mkdirs() }
        val session = CaptureSession(
            id = directory.name,
            directory = directory,
            device = CaptureDevice("serial", "device", "fixture"),
            settings = CaptureSettings(recordVideo = videoStartMs != null, freeSpaceReserveBytes = 0),
            startedEpochMs = SESSION_STARTED_EPOCH_MS,
            elapsedMs = elapsed.maxOrNull() ?: 0L,
            status = CaptureStatus.STOPPED,
            videoStartElapsedMs = videoStartMs,
            storageVersion = CaptureStorageVersion.BLOCKS_V2,
        )
        val bytes = lines.joinToString("").toByteArray(Charsets.UTF_8)
        session.logFile.parentFile.mkdirs()
        session.logFile.writeBytes(bytes)
        session.blockIndexFile.parentFile.mkdirs()
        var byteOffset = 0L
        CaptureBlockWriter(session.blockIndexFile).use { writer ->
            lines.forEachIndexed { index, line ->
                val raw = line.toByteArray(Charsets.UTF_8)
                writer.append(byteOffset, raw, index + 1, elapsed[index], 0, elapsed[index])
                byteOffset += raw.size
            }
            writer.freeze()
        }
        val firstReference = requireNotNull(parseCaptureClockReference(lines.first(), SESSION_STARTED_EPOCH_MS + elapsed.first()))
        val metadata = CaptureSyncMetadata(
            logBytes = bytes.size.toLong(),
            rowCount = lines.size,
            timelineBaseElapsedMs = 0,
            timelineDurationMs = (elapsed.maxOrNull() ?: 0L) + 10_000,
            segments = listOf(
                CaptureSyncSegment(
                    id = 0,
                    firstRow = 1,
                    lastRowExclusive = lines.size + 1,
                    anchorLogTimeMs = SESSION_STARTED_EPOCH_MS + elapsed.first(),
                    anchorCaptureElapsedMs = elapsed.first(),
                    reliability = CaptureSyncReliability.RELIABLE,
                    clockReference = firstReference,
                ),
            ),
        )
        session.syncFile.parentFile.mkdirs()
        session.syncFile.writeText(metadata.toJson())
        if (videoStartMs != null) {
            session.videoFile.parentFile.mkdirs()
            session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        }
        return session.copy(
            syncMetadataOverride = metadata,
            frozenLogBytes = bytes.size.toLong(),
            frozenBlockIndexBytes = session.blockIndexFile.length(),
        )
    }

    private fun reexportSession(imported: ImportedCapture): CaptureSession {
        val timeline = requireNotNull(imported.timeline)
        val metadata = requireNotNull(imported.sparseSyncMetadata)
        val blocks = File(imported.logFile.parentFile, requireNotNull(imported.descriptor.blocks).path)
        return CaptureSession(
            id = "reexport-${imported.descriptor.sessionId}",
            directory = imported.logFile.parentFile,
            device = CaptureDevice(imported.descriptor.device.serial, imported.descriptor.device.state, imported.descriptor.device.model),
            settings = CaptureSettings(recordVideo = imported.videoFile != null, showUnavailableFrames = true, freeSpaceReserveBytes = 0),
            startedEpochMs = imported.descriptor.sessionStartedEpochMs,
            elapsedMs = imported.descriptor.timelineDurationMs,
            status = CaptureStatus.STOPPED,
            videoStartElapsedMs = timeline.videoOriginElapsedMs?.let { it - timeline.timelineBaseElapsedMs },
            availabilityIntervals = imported.descriptor.availabilityIntervals,
            logFileOverride = imported.logFile,
            videoFileOverride = imported.videoFile,
            storageVersion = CaptureStorageVersion.BLOCKS_V2,
            blockIndexFileOverride = blocks,
            syncMetadataOverride = metadata,
            manualSegmentAnchors = metadata.segments.mapNotNull { it.manualAnchor },
            frozenLogBytes = metadata.logBytes,
            frozenBlockIndexBytes = blocks.length(),
        )
    }

    private fun resolvedPoint(imported: ImportedCapture, ordinal: Int): CaptureTimingPoint =
        CaptureTimingResolver.forTimeline(parseLogcat(imported.logFile), requireNotNull(imported.timeline))
            .pointForOrdinal(ordinal) ?: error("No timing point for row $ordinal")

    private class RecordingVideoExporter : CaptureVideoExporter {
        val requestedRanges = mutableListOf<Pair<Long, Long>>()

        override fun export(source: File, destination: File, requestedStartMs: Long, requestedEndMs: Long): CaptureVideoClip {
            requestedRanges += requestedStartMs to requestedEndMs
            destination.writeText("video")
            return CaptureVideoClip(requestedStartMs, requestedEndMs, requestedEndMs - requestedStartMs)
        }
    }

    private class AvailabilityRecordingVideoExporter : CaptureVideoExporter, CaptureAvailabilityVideoExporter {
        var availabilityRequest: Pair<Long, Long>? = null

        override fun export(source: File, destination: File, requestedStartMs: Long, requestedEndMs: Long): CaptureVideoClip {
            destination.writeText("ordinary video")
            return CaptureVideoClip(requestedStartMs, requestedEndMs, requestedEndMs - requestedStartMs)
        }

        override fun exportWithAvailability(
            source: File?,
            destination: File,
            timelineStartElapsedMs: Long,
            timelineEndElapsedMs: Long,
            sourceElapsedOriginMs: Long,
            availabilityIntervals: List<CaptureAvailabilityInterval>,
            sourceIsFinal: Boolean,
        ): CaptureVideoClip {
            availabilityRequest = timelineStartElapsedMs to timelineEndElapsedMs
            destination.writeText("availability video")
            return CaptureVideoClip(0, timelineEndElapsedMs - timelineStartElapsedMs, timelineEndElapsedMs - timelineStartElapsedMs)
        }
    }

    private companion object {
        const val SESSION_STARTED_EPOCH_MS = 1_704_103_200_000L // 2024-01-01 10:00 UTC
    }
}
