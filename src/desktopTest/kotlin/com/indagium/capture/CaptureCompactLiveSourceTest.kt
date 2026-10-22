package com.indagium.capture

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** A Compact export of a capture that is still recording must encode with the reduced, recording-friendly encoder. */
@Suppress("MagicNumber")
class CaptureCompactLiveSourceTest {
    @Test
    fun aRecordingSourceUsesTheLiveEncoderAndAStoppedOneDoesNot() {
        for ((status, expected) in listOf(CaptureStatus.RECORDING to "live", CaptureStatus.STOPPED to "regular")) {
            val root = createTempDirectory("capture-compact-live-source").toFile()
            try {
                val used = mutableListOf<String>()
                val live = Tagged("live", used, null)
                val regular = Tagged("regular", used, live)
                val session = session(root, status)

                CaptureArchiveExporter(videoExporter = fakeVideo(), compactVideoTranscoder = regular).export(
                    session,
                    CaptureExportRequest(File(root, "out.ind"), cutoffElapsedMs = session.elapsedMs, format = CaptureExportFormat.COMPACT),
                )

                assertEquals(listOf(expected), used, "status $status")
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private class Tagged(
        private val name: String,
        private val used: MutableList<String>,
        private val liveVariant: CaptureCompactVideoTranscoder?,
    ) : CaptureCompactVideoTranscoder {
        override fun ensureAvailable() = Unit

        override fun transcode(source: File, destination: File) {
            used += name
            destination.writeBytes(ByteArray(16) { 1 })
        }

        override fun forLiveCapture(): CaptureCompactVideoTranscoder = liveVariant ?: this
    }

    private fun fakeVideo() = CaptureVideoExporter { _, staged, start, end ->
        staged.writeBytes(ByteArray(4096) { (it % 127).toByte() })
        CaptureVideoClip(start.coerceAtMost(end - 1), end, end - start)
    }

    private fun session(root: File, status: CaptureStatus): CaptureSession {
        val session = CaptureSession(
            id = "session-1",
            directory = File(root, "session"),
            device = CaptureDevice("serial", "device", "Pixel"),
            settings = CaptureSettings(recordVideo = true, freeSpaceReserveBytes = 0),
            startedEpochMs = 1_700_000_000_000,
            elapsedMs = 400_000,
            videoStartElapsedMs = 0,
            status = status,
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
