package com.indagium.capture

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/** Stop finalizes the session in place; that must not queue behind a Compact export of the same session. */
class CaptureFinalizeWhileExportingTest {
    @Test
    fun finalizingAStoppedSessionDoesNotWaitForARunningCompactExportOfIt() {
        val root = createTempDirectory("capture-finalize-vs-export").toFile()
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val session = stoppedLegacySession(root)
            val fakeVideo = CaptureVideoExporter { _, staged, start, end ->
                staged.writeBytes(ByteArray(4096) { (it % 127).toByte() })
                CaptureVideoClip(start.coerceAtMost(end - 1), end, end - start)
            }
            val blockedCompact = object : CaptureCompactVideoTranscoder {
                override fun ensureAvailable() = Unit

                override fun transcode(source: File, destination: File) {
                    entered.countDown()
                    // Held "encoding" until the test has proven that finalization completed meanwhile.
                    check(release.await(30, TimeUnit.SECONDS)) { "test never released the encoder" }
                    destination.writeBytes(ByteArray(64) { 1 })
                }
            }
            val exporter = CaptureArchiveExporter(videoExporter = fakeVideo, compactVideoTranscoder = blockedCompact)
            val export = pool.submit<CaptureExportResult> {
                exporter.export(
                    session,
                    CaptureExportRequest(File(root, "out.ind"), cutoffElapsedMs = session.elapsedMs, format = CaptureExportFormat.COMPACT),
                )
            }
            assertTrue(entered.await(20, TimeUnit.SECONDS), "the compact export never reached the encoder")

            val finalize = pool.submit<ImportedCapture> { exporter.finalizeSessionInPlace(session) }
            // The export is still blocked inside the encoder: only a finalizer on its own lock gets here.
            val imported = finalize.get(20, TimeUnit.SECONDS)
            assertTrue(File(session.directory, CAPTURE_DESCRIPTOR_NAME).isFile)
            assertTrue(imported.logFile.isFile)
            assertTrue(!export.isDone, "the export must still be running while finalization is done")

            release.countDown()
            export.get(30, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
            root.deleteRecursively()
        }
    }

    private fun stoppedLegacySession(root: File): CaptureSession {
        val session = CaptureSession(
            id = "session-1",
            directory = File(root, "session"),
            device = CaptureDevice("serial", "device", "Pixel"),
            settings = CaptureSettings(recordVideo = true, freeSpaceReserveBytes = 0),
            startedEpochMs = 1_700_000_000_000,
            elapsedMs = 400_000,
            videoStartElapsedMs = 0,
            status = CaptureStatus.STOPPED,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        val raw = "01-01 10:00:00.000  1  1 I Tag: row\n".toByteArray()
        session.logFile.writeBytes(raw)
        session.indexFile.writeText("{\"byteOffset\":0,\"byteLength\":${raw.size},\"elapsedMs\":1000,\"rowOrdinal\":1}\n")
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        return session
    }
}
