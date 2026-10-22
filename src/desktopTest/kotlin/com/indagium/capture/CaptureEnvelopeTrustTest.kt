package com.indagium.capture

import com.indagium.utils.parseLogcatLines
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [CaptureBlock.envelopeSettled]: when a block's recorded time envelope may be trusted, and what the queries do otherwise. */
@Suppress("MagicNumber")
class CaptureEnvelopeTrustTest {
    @Test
    fun rowsWrittenBeforeTheAnchorExistsLeaveTheirBlockUnsettledAndLaterBlocksSettled() = withFixture { _, session ->
        val index = CaptureBlockIndex.read(session.blockIndexFile, session.logFile)

        assertEquals(listOf(false, false, true), index.blocks.map { it.envelopeSettled })
        val metadata = requireNotNull(session.syncMetadataOverride)
        val entry = checkNotNull(parseLogcatLines(sequenceOf(ROWS[2].text), startId = 3).singleOrNull())
        assertEquals(1_150L, CaptureTimingResolver.sparsePoint(entry, 3, metadata)?.captureElapsedMs)
    }

    @Test
    fun onlyBlocksWithAnUntrustworthyEnvelopeAreAddedToTheWindowCandidates() = withFixture { _, session ->
        val index = CaptureBlockIndex.read(session.blockIndexFile, session.logFile)
        val metadata = requireNotNull(session.syncMetadataOverride)
        val settled = index.blocks.last()

        // A window far from everything: only the unsettled blocks remain, the settled one is skipped by its envelope.
        val far = index.candidateBlocksForResolvedElapsed(metadata, 2_900L, 2_950L)
        assertEquals(index.blocks.filter { it.envelopeSettled != true }, far)
        assertTrue(settled !in far)
        // A settled block is still found by its envelope.
        assertTrue(settled in index.candidateBlocksForResolvedElapsed(metadata, settled.firstElapsedMs, settled.lastElapsedMs))
    }

    @Test
    fun aBlockIndexWithoutTheFlagIsUnknownAndNeverTrusted() = withFixture { _, session ->
        val stripped = session.blockIndexFile.readLines().joinToString("") { it.replace(Regex(",\"settled\":(true|false)"), "") + "\n" }
        assertFalse(stripped.contains("settled"))
        session.blockIndexFile.writeText(stripped)
        val parsed = CaptureBlockIndex.read(session.blockIndexFile, session.logFile)
        val metadata = requireNotNull(session.syncMetadataOverride)

        assertTrue(parsed.blocks.all { it.envelopeSettled == null })
        assertEquals(parsed.blocks, parsed.candidateBlocksForResolvedElapsed(metadata, 2_900L, 2_950L))
        assertFalse(parsed.blocks.any(metadata::blockEnvelopeBracketsRows))
    }

    @Test
    fun theEnvelopeShortcutNeverStandsInForAnUnsettledOrUnknownBlock() = withFixture { _, session ->
        val metadata = requireNotNull(session.syncMetadataOverride)
        val settled = CaptureBlockIndex.read(session.blockIndexFile, session.logFile).blocks.last()

        assertTrue(sparseBlockUsesEnvelope(metadata, settled, Long.MIN_VALUE, Long.MAX_VALUE, excludeStart = false))
        assertFalse(sparseBlockUsesEnvelope(metadata, settled.copy(envelopeSettled = false), Long.MIN_VALUE, Long.MAX_VALUE, false))
        assertFalse(sparseBlockUsesEnvelope(metadata, settled.copy(envelopeSettled = null), Long.MIN_VALUE, Long.MAX_VALUE, false))
    }

    @Test
    fun aManualAnchorOverASettledBlockStillDistrustsItsEnvelope() = withFixture { _, session ->
        val metadata = requireNotNull(session.syncMetadataOverride)
        val settled = CaptureBlockIndex.read(session.blockIndexFile, session.logFile).blocks.last()
        assertTrue(metadata.blockEnvelopeBracketsRows(settled))
        val manual = CaptureSegmentManualAnchor(
            segmentId = metadata.segments.single().id,
            rowOrdinal = 4,
            clockReference = requireNotNull(parseCaptureClockReference(ROWS[3].text, session.startedEpochMs)),
            videoMs = 0,
            captureElapsedMs = 0,
        )
        val corrected = metadata.copy(segments = metadata.segments.map { it.copy(manualAnchor = manual) })

        assertFalse(corrected.blockEnvelopeBracketsRows(settled))
    }

    @Test
    fun aRowWhileAClockJumpIsBeingConfirmedIsNotSettled() {
        val started = Instant.parse("2026-10-21T10:00:00Z").toEpochMilli()
        val tracker = CaptureSyncRecorder(started)
        var ordinal = 0

        fun observe(logMs: Int, arrival: Long): CaptureSyncRecorder.RowTiming {
            val text = "10-21 10:%02d:%02d.%03d  100  101 I Tag: r".format(logMs / 60_000, (logMs / 1_000) % 60, logMs % 1_000)
            tracker.observe(++ordinal, text, arrival, eligibleForAnchor = true)
            return tracker.rowTiming(text, arrival)
        }
        // Startup: no anchor yet, then the 2 s window passes and the anchor is established.
        assertFalse(observe(0, 0).settled)
        assertFalse(observe(500, 500).settled)
        assertTrue(observe(2_500, 2_500).settled, "the row that establishes the anchor resolves through it")
        assertTrue(observe(3_000, 3_000).settled)
        // The device clock jumps +1 h: until the jump is confirmed the rows are provisional, not settled.
        val jumped = (1..3).map { observe(3_600_000 + 3_000 + it * 100, 3_000L + it * 100) }
        assertTrue(jumped.all { !it.settled }, "an unconfirmed clock jump leaves the rows' times provisional")
    }

    @Test
    fun theWriterMarksABlockUnsettledForAnUnresolvedOrUnconfirmedRowButIgnoresSeparators() {
        val root = createTempDirectory("capture-envelope-writer").toFile()
        try {
            val file = File(root, "blocks.jsonl")
            var offset = 0L
            CaptureBlockWriter(file, maxRows = 1).use { writer ->
                fun add(text: String, row: Int?, resolved: Long?, settled: Boolean? = null) {
                    val raw = (text + "\n").toByteArray()
                    writer.append(offset, raw, row, 100L, 0, resolved, settled)
                    offset += raw.size
                }
                // A separator carries no row time; the block it opens closes with the resolved row 1 behind it.
                add("--------- beginning of main", null, null)
                add("10-21 10:00:00.100  1  1 I T: a", 1, 100L)
                add("10-21 10:00:00.200  1  1 I T: b", 2, null)
                add("10-21 10:00:00.300  1  1 I T: c", 3, 300L, settled = false)
            }

            val settledFlags = file.readLines().map { it.contains("\"settled\":true") }
            assertEquals(listOf(true, false, false), settledFlags)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theSettledFlagRoundTripsAndIsAbsentForUnknownBlocks() {
        val block = CaptureBlock(0, 10, 1, 2, 0, 5, 0, "0".repeat(64), envelopeSettled = true)
        assertTrue(block.toJson().toString().contains("\"settled\":true"))
        assertFalse(block.copy(envelopeSettled = null).toJson().toString().contains("settled"))
        assertNull(block.copy(envelopeSettled = null).envelopeSettled)
    }

    private class Row(val logSecondMs: Int, val arrivalMs: Long) {
        val text: String get() = "10-21 10:00:%02d.%03d  100  101 I Tag: row-%d".format(logSecondMs / 1_000, logSecondMs % 1_000, logSecondMs)
    }

    /** Same recording as [CaptureUnsettledEnvelopeTest], but through the production helper that carries the settled flag. */
    private fun recordedSession(root: File): CaptureSession {
        val directory = File(root, "session").apply { mkdirs() }
        val startedEpochMs = Instant.parse("2026-10-21T10:00:00Z").toEpochMilli()
        val session = CaptureSession(
            id = "trust",
            directory = directory,
            device = CaptureDevice("test-device", "device"),
            settings = CaptureSettings(recordVideo = false, mirror = false, freeSpaceReserveBytes = 0),
            startedEpochMs = startedEpochMs,
            elapsedMs = 3_000,
            status = CaptureStatus.STOPPED,
            storageVersion = CaptureStorageVersion.BLOCKS_V2,
        )
        session.logFile.parentFile.mkdirs()
        val tracker = CaptureSyncRecorder(startedEpochMs)
        var offset = 0L
        CaptureBlockWriter(session.blockIndexFile).use { writer ->
            session.logFile.outputStream().use { output ->
                ROWS.forEachIndexed { index, row ->
                    val raw = (row.text + "\n").toByteArray()
                    output.write(raw)
                    val ordinal = index + 1
                    val segmentId = tracker.observe(ordinal, row.text, row.arrivalMs, eligibleForAnchor = true)
                    val timing = tracker.rowTiming(row.text, row.arrivalMs)
                    writer.append(
                        offset, raw, ordinal, row.arrivalMs, segmentId,
                        resolvedCaptureElapsedMs = timing.resolvedElapsedMs, envelopeSettled = timing.settled,
                    )
                    offset += raw.size
                }
            }
            writer.freeze()
        }
        val metadata = tracker.snapshot(offset, ROWS.size, 3_000, finalize = true)
        return session.copy(
            syncMetadataOverride = metadata,
            frozenLogBytes = offset,
            frozenBlockIndexBytes = session.blockIndexFile.length(),
        )
    }

    private fun withFixture(test: (File, CaptureSession) -> Unit) {
        val root = createTempDirectory("capture-envelope-trust").toFile()
        try {
            test(root, recordedSession(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        val ROWS = listOf(Row(100, 150), Row(500, 700), Row(1_100, 1_200), Row(2_150, 2_300))
    }
}
