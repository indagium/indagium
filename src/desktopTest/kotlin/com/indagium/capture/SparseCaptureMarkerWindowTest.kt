package com.indagium.capture

import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("MagicNumber")
class SparseCaptureMarkerWindowTest {
    @Test
    fun timestampWindowScansWithinABlockAndSkipsSeparators() = withFixture { root ->
        val source = fixture(root, listOf(timed(1), "--------- beginning of main", timed(2), "", timed(3)))
        assertEquals(2..2, ordinalRangeForElapsedWindow(source, 1_500, 2_500))
        assertNull(ordinalRangeForElapsedWindow(source, 4_000, 5_000))
        assertEquals(listOf(1, 3), captureLogEntriesForOrdinals(source, setOf(1, 3)).map { it.id })
    }

    @Test
    fun untimestampedRowsStaySelectableAndUseOverlappingBlockForWindow() = withFixture { root ->
        val source = fixture(root, listOf(timed(1), "No timestamp: café", timed(2), timed(3)))
        assertEquals(2..3, ordinalRangeForElapsedWindow(source, 2_000, 2_000))
        val entries = captureLogEntriesForOrdinals(source, setOf(2, 4))
        assertEquals(listOf(2, 4), entries.map { it.id })
        assertTrue(entries.first().msg.contains("café"))
        val metadata = requireNotNull(source.syncMetadataOverride)
        assertNull(CaptureTimingResolver.sparsePoint(entries.first(), 2, metadata))
    }

    @Test
    fun frozenPrefixExcludesLaterCompleteBlocksAndLogRows() = withFixture { root ->
        val source = fixture(root, listOf(timed(1), timed(2)))
        val oldLogBytes = source.logFile.length()
        val extraLine = (timed(3) + "\n").toByteArray()
        val extraIndex = File(root, "extra-block.jsonl")
        CaptureBlockWriter(extraIndex).use { writer ->
            writer.append(0, extraLine, 1, 3_000, 0)
            writer.freeze()
        }
        val extra = CaptureBlockIndex.read(extraIndex, maxLogBytes = extraLine.size.toLong(), verifyByteRange = LongRange.EMPTY)
            .blocks.single().copy(byteStart = oldLogBytes, byteEnd = oldLogBytes + extraLine.size, firstRow = 3, lastRowExclusive = 4)
        source.logFile.appendBytes(extraLine)
        source.blockIndexFile.appendBytes(CaptureBlockIndex(listOf(extra)).serialize())

        assertEquals(listOf(1, 2), captureLogEntriesForOrdinals(source, setOf(1, 2, 3)).map { it.id })
        assertNull(ordinalRangeForElapsedWindow(source, 3_000, 3_000))
    }

    @Test
    fun corruptCandidateBlockDoesNotReturnPartialMarkerEntries() = withFixture { root ->
        val source = fixture(root, listOf(timed(1), timed(2)))
        source.logFile.writeText(source.logFile.readText().replace("row1", "bad1"))
        assertTrue(captureLogEntriesForOrdinals(source, setOf(1, 2)).isEmpty())
        assertNull(ordinalRangeForElapsedWindow(source, 0, 3_000))
    }

    @Test
    fun unrelatedCorruptBlockIsNotReadForExactOrdinalSelection() = withFixture { root ->
        val source = fixture(root, listOf(timed(1), timed(2), timed(3)), maxRows = 1)
        source.logFile.writeText(source.logFile.readText().replace("row3", "bad3"))
        assertEquals(listOf(1), captureLogEntriesForOrdinals(source, setOf(1)).map { it.id })
        assertTrue(captureLogEntriesForOrdinals(source, setOf(3)).isEmpty())
    }

    @Test
    fun manualAnchorOnUncertainSegmentCanMoveWindowBeyondOriginalBlockTimes() = withFixture { root ->
        val original = fixture(root, listOf(timed(1), timed(2), timed(3)))
        val metadata = requireNotNull(original.syncMetadataOverride)
        val manual = CaptureSegmentManualAnchor(
            segmentId = 0,
            rowOrdinal = 2,
            clockReference = requireNotNull(parseCaptureClockReference(timed(2), original.startedEpochMs)),
            videoMs = 10_000,
            captureElapsedMs = 10_000,
        )
        val corrected = original.copy(
            syncMetadataOverride = metadata.copy(
                timelineDurationMs = 20_000,
                segments = metadata.segments.map { it.copy(reliability = CaptureSyncReliability.UNCERTAIN) },
            ),
            manualSegmentAnchors = listOf(manual),
        )
        assertEquals(2..2, ordinalRangeForElapsedWindow(corrected, 10_000, 10_000))
    }

    private fun timed(second: Int): String = "01-01 00:00:0$second.000  1  1 I Marker: row$second"

    private fun fixture(root: File, lines: List<String>, maxRows: Int = 1_024): CaptureSession {
        val started = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val source = CaptureSession(
            id = "marker-sparse",
            directory = root,
            device = CaptureDevice("test", "device"),
            settings = CaptureSettings(recordVideo = false),
            startedEpochMs = started,
            storageVersion = CaptureStorageVersion.BLOCKS_V2,
        )
        source.logFile.parentFile.mkdirs()
        var offset = 0L
        var ordinal = 0
        CaptureBlockWriter(source.blockIndexFile, maxRows = maxRows).use { writer ->
            source.logFile.outputStream().use { log ->
                lines.forEach { line ->
                    val bytes = "$line\n".toByteArray()
                    val row = if (line.isBlank() || line.trim().startsWith("-----")) null else ++ordinal
                    val elapsed = when {
                        line.contains("00:00:03") -> 3_000L
                        line.contains("00:00:02") || line.startsWith("No timestamp") -> 2_000L
                        else -> 1_000L
                    }
                    log.write(bytes)
                    writer.append(offset, bytes, row, elapsed, 0)
                    offset += bytes.size
                }
            }
            writer.freeze()
        }
        val metadata = CaptureSyncMetadata(
            logBytes = offset,
            rowCount = ordinal,
            timelineBaseElapsedMs = 0,
            timelineDurationMs = 4_000,
            segments = listOf(
                CaptureSyncSegment(
                    id = 0,
                    firstRow = 1,
                    lastRowExclusive = ordinal + 1,
                    anchorLogTimeMs = started + 1_000,
                    anchorCaptureElapsedMs = 1_000,
                    reliability = CaptureSyncReliability.RELIABLE,
                    clockReference = requireNotNull(parseCaptureClockReference(timed(1), started)),
                ),
            ),
        )
        return source.copy(
            syncMetadataOverride = metadata,
            frozenLogBytes = offset,
            frozenBlockIndexBytes = source.blockIndexFile.length(),
        )
    }

    private fun withFixture(block: (File) -> Unit) {
        val root = createTempDirectory("sparse-marker-window").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
