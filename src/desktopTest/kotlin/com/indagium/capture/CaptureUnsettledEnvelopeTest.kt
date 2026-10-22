package com.indagium.capture

import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val WINDOW_START_MS = 50L
private const val WINDOW_END_MS = 1_150L
private const val SESSION_END_MS = 3_000L

/**
 * A block's recorded elapsed envelope holds ARRIVAL times for rows logged before their segment's clock anchor existed;
 * the settled anchor then resolves those rows earlier. Window queries (export, preview bounds, marker ordinals) must not
 * trust such an envelope, or a row that resolves inside the window is silently left out.
 *
 * The capture is recorded the way `CaptureRecorder.writeDeviceLogLines` does it: a [CaptureSyncRecorder]
 * observes each row, and the time it resolves (null before the anchor exists) feeds the [CaptureBlockWriter]. Rows (log time -> arrival
 * after capture start, both in ms): 100 -> 150, 500 -> 700, 1100 -> 1200, 2150 -> 2300. Row 4 arrives after the 2 s
 * startup window, which establishes the anchor "log 100 = elapsed 150" (the lowest-latency sample); rows 1-3 resolve to
 * 150, 550 and 1150, but row 3 sits alone in a block recorded as [1200, 1200].
 */
@Suppress("MagicNumber")
class CaptureUnsettledEnvelopeTest {
    @BeforeTest
    fun reset() {
        CaptureExportPreviewCache.clear()
        CapturePreviewDiagnostics.envelopeEnabled = true
    }

    @AfterTest
    fun restore() = reset()

    @Test
    fun theFixtureReallyHoldsARowWhoseResolvedTimeIsOutsideItsBlocksEnvelope() = withFixture { _, session ->
        val index = CaptureBlockIndex.read(session.blockIndexFile, session.logFile)
        val metadata = requireNotNull(session.syncMetadataOverride)
        val lateRowBlock = index.blocks.single { it.firstRow == 3 && it.lastRowExclusive == 4 }
        assertEquals(1_200L, lateRowBlock.firstElapsedMs)
        assertEquals(1_200L, lateRowBlock.lastElapsedMs)
        val entry = checkNotNull(com.indagium.utils.parseLogcatLines(sequenceOf(ROWS[2].text), startId = 3).singleOrNull())
        assertEquals(1_150L, CaptureTimingResolver.sparsePoint(entry, 3, metadata)?.captureElapsedMs)
    }

    @Test
    fun anExplicitTimeRangeExportKeepsTheRowThatResolvesInsideTheWindow() = withFixture { root, session ->
        val destination = File(root, "range.zip")

        CaptureArchiveExporter().export(session, explicitRequest(destination))

        val exported = CaptureArchiveReader.open(destination, File(root, "range-cache")).logFile.readLines()
        assertEquals(ROWS.take(3).map { it.text }, exported)
    }

    @Test
    fun theSnapshotPreviewBoundsIncludeThatRowWithAndWithoutTheEnvelopeShortcut() = withFixture { root, session ->
        for (shortcut in listOf(true, false)) {
            CaptureExportPreviewCache.clear()
            CapturePreviewDiagnostics.envelopeEnabled = shortcut

            val preview = CaptureArchiveExporter().preview(session, explicitRequest(File(root, "preview-$shortcut.zip")))

            assertEquals(150L, preview.logStartMs, "shortcut=$shortcut")
            assertEquals(1_150L, preview.logEndMs, "shortcut=$shortcut")
        }
    }

    @Test
    fun theMarkerOrdinalWindowIncludesThatRow() = withFixture { _, session ->
        assertEquals(1..3, ordinalRangeForElapsedWindow(session, WINDOW_START_MS, WINDOW_END_MS))
    }

    @Test
    fun aBlockIndexWrittenBeforeTheFlagExistedIsNeverTrusted() = withFixture { root, session ->
        val legacy = session.blockIndexFile.readLines().joinToString("") { it.replace(Regex(",\"settled\":(true|false)"), "") + "\n" }
        assertFalse(legacy.contains("settled"))
        session.blockIndexFile.writeText(legacy)
        val old = session.copy(frozenBlockIndexBytes = session.blockIndexFile.length())
        val destination = File(root, "legacy-range.zip")

        CaptureArchiveExporter().export(old, explicitRequest(destination))

        val exported = CaptureArchiveReader.open(destination, File(root, "legacy-cache")).logFile.readLines()
        assertEquals(ROWS.take(3).map { it.text }, exported)
        assertEquals(1..3, ordinalRangeForElapsedWindow(old, WINDOW_START_MS, WINDOW_END_MS))
    }

    private class Row(val logSecondMs: Int, val arrivalMs: Long) {
        val text: String get() = "10-21 10:00:%02d.%03d  100  101 I Tag: row-%d".format(logSecondMs / 1_000, logSecondMs % 1_000, logSecondMs)
    }

    private fun explicitRequest(destination: File) = CaptureExportRequest(
        destination,
        CaptureRange.EXPLICIT,
        includeVideo = false,
        cutoffElapsedMs = SESSION_END_MS,
        explicitStartElapsedMs = WINDOW_START_MS,
        explicitEndElapsedMs = WINDOW_END_MS,
    )

    private fun recordedSession(root: File): CaptureSession {
        val directory = File(root, "session").apply { mkdirs() }
        val startedEpochMs = Instant.parse("2026-10-21T10:00:00Z").toEpochMilli()
        val session = CaptureSession(
            id = "latency",
            directory = directory,
            device = CaptureDevice("test-device", "device"),
            settings = CaptureSettings(recordVideo = false, mirror = false, freeSpaceReserveBytes = 0),
            startedEpochMs = startedEpochMs,
            elapsedMs = SESSION_END_MS,
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
                    writer.append(
                        offset, raw, ordinal, row.arrivalMs, segmentId,
                        resolvedCaptureElapsedMs = tracker.resolvedCaptureElapsedMs(row.text, row.arrivalMs),
                    )
                    offset += raw.size
                }
            }
            writer.freeze()
        }
        val metadata = tracker.snapshot(offset, ROWS.size, SESSION_END_MS, finalize = true)
        require(metadata.segments.single().reliability == CaptureSyncReliability.RELIABLE)
        return session.copy(
            syncMetadataOverride = metadata,
            frozenLogBytes = offset,
            frozenBlockIndexBytes = session.blockIndexFile.length(),
        )
    }

    private fun withFixture(test: (File, CaptureSession) -> Unit) {
        val root = createTempDirectory("capture-unsettled-envelope").toFile()
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
