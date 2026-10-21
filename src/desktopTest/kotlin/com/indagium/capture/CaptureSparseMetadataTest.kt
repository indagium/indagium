package com.indagium.capture

import com.indagium.utils.parseLogcatLines
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("MagicNumber")
class CaptureSparseMetadataTest {
    @Test
    fun startupEstimatorIsBoundedAndShortQuietCaptureFinalizesItsAvailableSamples() {
        val startedAt = epoch(2024, 1, 1)
        val tracker = CaptureSyncRecorder(startedAt)
        repeat(300) { index ->
            val elapsed = index * 5L
            tracker.observe(index + 1, logLine(startedAt + elapsed), elapsed, eligibleForAnchor = true)
        }

        val metadata = tracker.snapshot(logBytes = 9000, rowCount = 300, elapsedMs = 1500, finalize = true)

        assertEquals(1, metadata.segments.size)
        assertEquals(CaptureSyncReliability.RELIABLE, metadata.segments.single().reliability)
        assertEquals(1, metadata.segments.single().firstRow)
        assertEquals(301, metadata.segments.single().lastRowExclusive)
        assertTrue(metadata.toJson().length < 1500, "metadata should retain one anchor, not startup observations")

        val quiet = CaptureSyncRecorder(startedAt)
        quiet.observe(1, logLine(startedAt), 0, eligibleForAnchor = true)
        val persisted = quiet.snapshot(logBytes = 80, rowCount = 1, elapsedMs = 2500)
        assertEquals(CaptureSyncReliability.RELIABLE, persisted.segments.single().reliability)
        assertEquals(0L, persisted.segments.single().anchorCaptureElapsedMs)
    }

    @Test
    fun observationsAfterStartupSampleAndTimeLimitsDoNotMoveEstablishedAnchor() {
        val startedAt = epoch(2024, 1, 1)
        val byCount = CaptureSyncRecorder(startedAt)
        repeat(300) { index ->
            val elapsed = index * 5L
            val clockDrift = if (index >= 256) 100L else 0L
            byCount.observe(index + 1, logLine(startedAt + elapsed + clockDrift), elapsed, eligibleForAnchor = true)
        }
        val counted = byCount.snapshot(logBytes = 30_000, rowCount = 300, elapsedMs = 1500, finalize = true)
        assertEquals(0L, counted.segments.single().anchorCaptureElapsedMs)

        val byTime = CaptureSyncRecorder(startedAt)
        byTime.observe(1, logLine(startedAt), 0, eligibleForAnchor = true)
        byTime.observe(2, logLine(startedAt + 1500), 1500, eligibleForAnchor = true)
        byTime.observe(3, logLine(startedAt + 2101), 2001, eligibleForAnchor = true)
        val timed = byTime.snapshot(logBytes = 300, rowCount = 3, elapsedMs = 2001, finalize = true)
        assertEquals(0L, timed.segments.single().anchorCaptureElapsedMs)
    }

    @Test
    fun highRateClockJumpRetainsFirstCandidateUntilOneSecondHasElapsed() {
        val startedAt = epoch(2024, 1, 1)
        val tracker = CaptureSyncRecorder(startedAt)
        repeat(256) { index ->
            tracker.observe(index + 1, logLine(startedAt + index), index.toLong(), eligibleForAnchor = true)
        }
        repeat(1501) { index ->
            val elapsed = 500L + index
            tracker.observe(257 + index, logLine(startedAt + elapsed + 5000), elapsed, eligibleForAnchor = true)
        }
        val metadata = tracker.snapshot(logBytes = 175_700, rowCount = 1757, elapsedMs = 2000, finalize = true)

        assertEquals(3, metadata.segments.size)
        assertEquals(CaptureSyncReliability.UNCERTAIN, metadata.segments[1].reliability)
        assertEquals(257, metadata.segments[1].firstRow)
        assertEquals(1257, metadata.segments[1].lastRowExclusive)
        assertEquals(CaptureSyncReliability.RELIABLE, metadata.segments[2].reliability)
        val entry = parseLogcatLines(sequenceOf(logLine(startedAt + 6500))).single()
        assertEquals(1500L, sparseCaptureTimingPoint(entry, 1257, metadata)?.captureElapsedMs)
    }

    @Test
    fun clockJumpLeavesTransitionUncertainUntilAStableNewAnchor() {
        val startedAt = epoch(2024, 1, 1)
        val tracker = CaptureSyncRecorder(startedAt)
        for (index in 0..3) {
            val elapsed = index * 500L
            tracker.observe(index + 1, logLine(startedAt + elapsed), elapsed, eligibleForAnchor = true)
        }
        tracker.snapshot(logBytes = 100, rowCount = 4, elapsedMs = 1500, finalize = true)

        for (index in 0..6) {
            val elapsed = 2000L + index * 250L
            val clockEpoch = startedAt + elapsed + 5000L
            tracker.observe(index + 5, logLine(clockEpoch), elapsed, eligibleForAnchor = true)
        }
        val metadata = tracker.snapshot(logBytes = 300, rowCount = 11, elapsedMs = 3500, finalize = true)

        assertEquals(listOf(1..4, 5..8, 9..11), metadata.segments.map { it.firstRow until it.lastRowExclusive })
        assertEquals(CaptureSyncReliability.RELIABLE, metadata.segments[0].reliability)
        assertEquals(CaptureSyncReliability.UNCERTAIN, metadata.segments[1].reliability)
        assertEquals(CaptureSyncReliability.RELIABLE, metadata.segments[2].reliability)
        val timeline = CaptureTimeline(
            rows = emptyList(),
            timelineDurationMs = 4000,
            sparseTiming = true,
            syncSegments = metadata.segments,
        )
        val transitionEntry = parseLogcatLines(sequenceOf(logLine(startedAt + 2000 + 5000))).single()
        val stableEntry = parseLogcatLines(sequenceOf(logLine(startedAt + 3000 + 5000))).single()
        assertNull(sparseCaptureTimingPoint(transitionEntry, 5, metadata, timeline))
        assertEquals(3000L, sparseCaptureTimingPoint(stableEntry, 9, metadata, timeline)?.captureElapsedMs)
    }

    @Test
    fun midnightReconnectAndUnusableRowsDoNotInventTiming() {
        val startedAt = epoch(2023, 12, 31, 23, 59, 59)
        val tracker = CaptureSyncRecorder(startedAt)
        tracker.observe(1, logLine(startedAt), 0, eligibleForAnchor = false) // historical buffer
        tracker.observe(2, "IndagiumCapture: availability marker", 500, eligibleForAnchor = false)
        tracker.observe(3, logLine(startedAt + 1000), 1000, eligibleForAnchor = true)
        tracker.observe(4, logLine(startedAt + 2000), 2000, eligibleForAnchor = true)
        tracker.snapshot(logBytes = 200, rowCount = 4, elapsedMs = 2000, finalize = true)

        tracker.startNewSegment(firstRow = 5, captureElapsedMs = 3000)
        tracker.observe(5, "unparseable row", 3000, eligibleForAnchor = true)
        tracker.observe(6, logLine(startedAt + 4000), 4000, eligibleForAnchor = true)
        tracker.observe(7, logLine(startedAt + 5000), 5000, eligibleForAnchor = true)
        val metadata = tracker.snapshot(logBytes = 300, rowCount = 7, elapsedMs = 5000, finalize = true)

        assertEquals(2, metadata.segments.size)
        assertEquals(CaptureSyncReliability.RELIABLE, metadata.segments[0].reliability)
        assertEquals(CaptureSyncReliability.RELIABLE, metadata.segments[1].reliability)
        assertTrue(metadata.segments[0].anchorCaptureElapsedMs != null)
        assertEquals(3000L, clockDeltaMs(requireNotNull(metadata.segments[0].clockReference), parseLogcatLines(sequenceOf(logLine(startedAt + 4000))).single()))

        val timestampLess = parseLogcatLines(sequenceOf("unparseable row")).single()
        assertNull(sparseCaptureTimingPoint(timestampLess, 5, metadata, null))
        assertNull(sparseCaptureTimingPoint(timestampLess, 2, metadata, null))
    }

    @Test
    fun deviceJanuaryFirstNearUtcDecemberThirtyFirstUsesTheNearestCalendarYear() {
        val startedAt = epoch(2024, 12, 31, 18)
        val tracker = CaptureSyncRecorder(startedAt)
        val deviceNewYear = logLine(epoch(2025, 1, 1))
        tracker.observe(1, deviceNewYear, 0, eligibleForAnchor = true)
        val metadata = tracker.snapshot(
            logBytes = deviceNewYear.length.toLong(),
            rowCount = 1,
            elapsedMs = 0,
            finalize = true,
        )

        assertEquals(2025, metadata.segments.single().clockReference?.anchorYear)
    }

    @Test
    fun blocksCloseAtRowByteAndReceiveTimeThresholdsWithoutPerRowTimingRecords() {
        val root = createTempDirectory("capture-sparse-block-thresholds").toFile()
        try {
            val byRows = writeBlocks(root, "rows", (1..3).map { "row-$it\n" }, maxRows = 2)
            assertEquals(listOf(1..2, 3..3), byRows.blocks.map { it.firstRow..(it.lastRowExclusive - 1) })

            val byDefaultRows = writeBlocks(
                root,
                "default-rows",
                (1..1025).map { "row-$it\n" },
                elapsed = List(1025) { 0L },
            )
            assertEquals(listOf(1024, 1), byDefaultRows.blocks.map { it.lastRowExclusive - it.firstRow })

            val large = "x".repeat(600 * 1024 - 1) + "\n"
            val byBytes = writeBlocks(root, "bytes", listOf(large, large), maxBytes = 1024L * 1024L)
            assertEquals(2, byBytes.blocks.size)
            assertTrue(byBytes.blocks.all { it.byteEnd - it.byteStart <= 1024L * 1024L })

            val oversized = "x".repeat(1024 * 1024 + 1) + "\n"
            val oneOversizedRow = writeBlocks(root, "oversized", listOf(oversized))
            assertEquals(1, oneOversizedRow.indexedRows)
            assertEquals(oversized.toByteArray().size.toLong(), oneOversizedRow.indexedLogBytes)

            val byTime = writeBlocks(root, "time", listOf("row-1\n", "----- separator\n", "row-2\n"), elapsed = listOf(0L, 1000L, 2001L))
            assertTrue(byTime.blocks.size >= 2)
            assertTrue(byTime.blocks.any { it.firstRow == it.lastRowExclusive })

            val delayed = writeBlocks(
                root,
                "delayed",
                (1..8).map { "row-$it\n" },
                elapsed = List(8) { 5000 },
                resolvedElapsed = (1000L..1700L step 100L).toList(),
            )
            assertEquals(1, delayed.blocks.size, "receive-time closure must stay sparse during a delayed batch")
            assertEquals(1000L, delayed.blocks.single().firstElapsedMs)
            assertEquals(5000L, delayed.blocks.single().lastElapsedMs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun frozenBlockPrefixRemainsReadableAfterWriterAppendsAndCandidateHashIsChecked() {
        val root = createTempDirectory("capture-sparse-prefix").toFile()
        try {
            val log = File(root, "logcat.log")
            val index = File(root, "blocks.jsonl")
            val writer = CaptureBlockWriter(index)
            var offset = 0L

            fun append(text: String, row: Int) {
                val bytes = text.toByteArray()
                log.appendBytes(bytes)
                writer.append(offset, bytes, row, row.toLong() * 100, 0)
                offset += bytes.size
            }
            append("row-1\n", 1)
            val prefix = writer.freeze()
            val frozenIndexBytes = index.length()
            val frozenLogBytes = log.length()
            append("row-2\n", 2)
            writer.freeze()
            writer.close()

            val prefixRead = CaptureBlockIndex.read(
                index,
                log,
                maxIndexBytes = frozenIndexBytes,
                maxLogBytes = frozenLogBytes,
            )
            assertEquals(prefix.indexedRows, prefixRead.indexedRows)
            assertEquals(1, prefixRead.indexedRows)

            val originalIndex = index.readText()
            index.writeText(originalIndex.replace("\"v\":1", "\"v\":99"))
            assertFailsWith<IllegalArgumentException> { CaptureBlockIndex.read(index, log) }
            index.writeText(originalIndex)

            log.writeBytes("tamper\n".toByteArray())
            assertFailsWith<IllegalArgumentException> { CaptureBlockIndex.read(index, log) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedSparseMetadataAndBoundsAreRejected() {
        val clock = CaptureClockReference(0, 0, 2024)
        val valid = CaptureSyncMetadata(
            logBytes = 8,
            rowCount = 1,
            timelineBaseElapsedMs = 0,
            timelineDurationMs = 100,
            segments = listOf(
                CaptureSyncSegment(0, 1, 2, 1_704_067_200_000, 0, CaptureSyncReliability.RELIABLE, clock),
            ),
        )
        assertEquals(valid, CaptureSyncMetadata.parse(valid.toJson()))
        assertFailsWith<IllegalArgumentException> {
            CaptureSyncMetadata.parse(valid.toJson().replace("\"timelineDurationMs\":100", "\"timelineDurationMs\":101"))
        }
        assertFailsWith<IllegalArgumentException> {
            CaptureSyncMetadata.parse(valid.toJson().replace("\"version\":1", "\"version\":99"))
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(timelineBaseElapsedMs = Long.MAX_VALUE, timelineDurationMs = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(segments = listOf(valid.segments.single(), valid.segments.single().copy(firstRow = 2, lastRowExclusive = 3)))
        }
        assertFailsWith<IllegalArgumentException> {
            CaptureBlock(0, 0, 1, 2, 0, 0, null, "0".repeat(64))
        }
    }

    private fun writeBlocks(
        root: File,
        name: String,
        lines: List<String>,
        maxRows: Int = 1024,
        maxBytes: Long = 1024L * 1024L,
        elapsed: List<Long> = lines.indices.map { it.toLong() },
        resolvedElapsed: List<Long?> = List(lines.size) { null },
    ): CaptureBlockIndex {
        val log = File(root, "$name.log")
        val index = File(root, "$name.jsonl")
        var offset = 0L
        var nextOrdinal = 1
        CaptureBlockWriter(index, maxRows = maxRows, maxBytes = maxBytes, maxDurationMs = 1000).use { writer ->
            log.outputStream().buffered().use { output ->
                lines.forEachIndexed { lineIndex, line ->
                    val bytes = line.toByteArray()
                    output.write(bytes)
                    val trimmed = line.trim()
                    val row = if (trimmed.startsWith("-----")) null else nextOrdinal++
                    writer.append(
                        byteOffset = offset,
                        rawLine = bytes,
                        rowOrdinal = row,
                        elapsedMs = elapsed[lineIndex],
                        segmentId = row?.let { 0 },
                        resolvedCaptureElapsedMs = resolvedElapsed[lineIndex],
                    )
                    offset += bytes.size
                }
            }
        }
        return CaptureBlockIndex.read(index, log)
    }

    private fun logLine(epochMs: Long): String {
        val dateTime = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC)
        return "${dateTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS"))}  100  101 I Tag: event"
    }

    private fun epoch(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long =
        java.time.LocalDate.of(year, month, day).atTime(hour, minute, second).toInstant(ZoneOffset.UTC).toEpochMilli()
}
