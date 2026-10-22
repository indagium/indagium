package com.indagium.capture

import com.indagium.utils.parseLogcat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An export result's video times feed the source session's Since-last-save cursors, which are
 * session-local. A re-exported imported capture whose own timeline base is ahead of its local zero
 * (a range cut) must therefore report them minus that base, not as the archive's absolute times.
 */
@Suppress("MagicNumber") // Fixture timings, not tunable constants.
class CaptureArchiveImportedVideoCheckpointTest {
    @Test
    fun sinceSaveVideoCheckpointOfAnImportedRangeCutIsSessionLocalAndLeavesNoGap() {
        val root = createTempDirectory("capture-imported-video-checkpoint").toFile()
        try {
            val requests = mutableListOf<Pair<Long, Long>>()
            var call = 0
            // The first child export under-covers by 500 ms (muxer lag); later ones cover what was asked.
            val laggingThenExact = CaptureVideoExporter { _, destination, start, end ->
                call++
                requests += start to end
                destination.writeText("video-$call")
                val coveredEnd = if (call == 1) end - 500 else end
                CaptureVideoClip(actualStartMs = start, coveredEndMs = coveredEnd, durationMs = coveredEnd - start)
            }
            val exporter = CaptureArchiveExporter(laggingThenExact)
            // Parent range cut 2000..5000 => the archive's timeline base is 2000, its local zero is 2000 on the parent.
            val parentArchive = exportParent(root, exporter)
            val opened = CaptureArchiveReader.open(parentArchive, File(root, "cache"))
            assertEquals(2_000L, opened.descriptor.timelineBaseElapsedMs)
            val snapshot = importedCaptureSnapshot(
                opened, parentArchive, parseLogcat(opened.logFile), File(root, "indexes"), File(root, "cursors"),
            )
            requests.clear()
            call = 0

            val first = exporter.export(
                snapshot.session,
                CaptureExportRequest(
                    destination = File(root, "first.zip"),
                    range = CaptureRange.EXPLICIT,
                    cutoffElapsedMs = snapshot.session.elapsedMs,
                    explicitStartElapsedMs = 0,
                    explicitEndElapsedMs = 1_500,
                ),
            )
            assertEquals(0L, first.videoActualStartMs, "video start is reported on the source session's local time")
            assertEquals(1_000L, first.videoCoveredEndMs, "video end is reported on the source session's local time")
            val firstArchive = CaptureArchiveReader.open(File(root, "first.zip"), File(root, "first-cache"))
            assertEquals(2_000L, firstArchive.descriptor.videoStartMs, "the archive descriptor stays on the absolute time base")
            assertEquals(3_000L, firstArchive.descriptor.videoEndMs, "the archive descriptor stays on the absolute time base")

            val advanced = snapshot.session.copy(
                logCheckpointMs = 1_500,
                snapshotCheckpointMs = 1_500,
                videoCheckpointMs = requireNotNull(first.videoCoveredEndMs),
            )
            exporter.export(
                advanced,
                CaptureExportRequest(
                    destination = File(root, "second.zip"),
                    range = CaptureRange.SINCE_SAVE,
                    cutoffElapsedMs = 3_000,
                ),
            )

            assertEquals(0L to 1_500L, requests[0])
            assertEquals(1_000L, requests[1].first, "the next Since-last-save video starts where the first one ended: no gap")
        } finally {
            root.deleteRecursively()
        }
    }

    /** Live session with rows at 1000/3000/4000 ms and a fake video; archive of the 2000..5000 range. */
    private fun exportParent(root: File, exporter: CaptureArchiveExporter): File {
        val session = CaptureSession(
            id = "session-1",
            directory = File(root, "session"),
            device = CaptureDevice("serial", "device", "Pixel"),
            settings = CaptureSettings(recordVideo = true, freeSpaceReserveBytes = 0),
            startedEpochMs = 1_700_000_000_000,
            elapsedMs = 6_000,
            status = CaptureStatus.STOPPED,
            videoStartElapsedMs = 0,
        )
        val rows = listOf(
            "01-01 10:00:01.000  1  1 I Tag: one\n" to 1_000L,
            "01-01 10:00:03.000  1  1 I Tag: two\n" to 3_000L,
            "01-01 10:00:04.000  1  1 I Tag: three\n" to 4_000L,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        var offset = 0L
        session.logFile.outputStream().use { log ->
            session.indexFile.bufferedWriter().use { index ->
                rows.forEachIndexed { i, (text, elapsed) ->
                    val bytes = text.toByteArray()
                    log.write(bytes)
                    index.append("{\"byteOffset\":$offset,\"byteLength\":${bytes.size},\"elapsedMs\":$elapsed,\"rowOrdinal\":${i + 1}}\n")
                    offset += bytes.size
                }
            }
        }
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1))
        val archive = File(root, "a.zip")
        exporter.export(
            session,
            CaptureExportRequest(
                destination = archive,
                range = CaptureRange.EXPLICIT,
                cutoffElapsedMs = 6_000,
                explicitStartElapsedMs = 2_000,
                explicitEndElapsedMs = 5_000,
            ),
        )
        return archive
    }
}
