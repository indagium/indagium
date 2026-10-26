@file:Suppress("MagicNumber", "LongMethod") // Device scenarios keep recording cleanup and evidence in one scope.

package com.indagium.capture.mirror

import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureArchiveReader
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExecutable
import com.indagium.capture.CaptureExportFormat
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.CaptureTools
import com.indagium.capture.CaptureVideoCodec
import com.indagium.capture.ProcessBuilderCaptureRunner
import com.indagium.capture.StreamingMkvWriter
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in device plumbing test. Set INDAGIUM_DIRECT_CODEC_SMOKE=1 to deploy the bundled server. */
class EmbeddedAv1DeviceSmokeTest {
    @Test
    fun recordsDecodesAndExportsExistingDeviceAv1WithoutReencoding() {
        assumeTrue(
            "Set INDAGIUM_DIRECT_CODEC_SMOKE=1 to run against an attached emulator/device",
            System.getenv("INDAGIUM_DIRECT_CODEC_SMOKE") == "1"
        )
        val serial = System.getenv("INDAGIUM_DIRECT_CODEC_SERIAL") ?: "emulator-5554"
        val adbPath = System.getenv("ADB") ?: "/opt/homebrew/bin/adb"
        val root = File("build/verification/direct-codec/embedded-av1-${System.currentTimeMillis()}").absoluteFile
        root.mkdirs()
        println("Direct AV1 smoke evidence: $root")
        val runner = ProcessBuilderCaptureRunner()
        val tools = CaptureTools(CaptureExecutable(adbPath), null, runner)
        val diagnostics = CopyOnWriteArrayList<String>()
        val decodedFrames = AtomicInteger()
        val video = File(root, "screen.mkv")
        val startedAt = System.nanoTime()
        val recorder = EmbeddedDeviceSession(
            transport = AdbScrcpyTransport(tools, runner, localRoot = File(root, "server")),
            muxer = StreamingMkvWriter(video),
            elapsedMillis = { (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND },
            maxReconnectAttempts = 0,
            onDiagnostic = diagnostics::add,
        )
        val packetSubscription = recorder.addVideoPacketListener { packet ->
            if (packet.codecId == ScrcpyCodecIds.AV1 && (packet.config || packet.keyFrame)) {
                val file = File(root, if (packet.config) "device-config.obu" else "device-key.obu")
                if (!file.exists()) file.writeBytes(packet.data)
            }
        }
        try {
            recorder.start(
                serial,
                MirrorStreamOptions(
                    maxSize = 1280,
                    maxFps = 30,
                    bitrateMbps = 3,
                    audio = false,
                    videoCodec = CaptureVideoCodec.AV1,
                    videoEncoder = "c2.android.av1.encoder",
                ),
            )
            recorder.attachDecoder(JavaCvH264Decoder()) { decodedFrames.incrementAndGet() }
            waitUntil(STARTUP_TIMEOUT_MS) { recorder.hasStartedVideo() || recorder.connectionSnapshot().state == EmbeddedMirrorState.FAILED }
            assertTrue(recorder.hasStartedVideo(), "AV1 did not start; diagnostics=${diagnostics.joinToString(" | ")}")
            Thread.sleep(RECORDING_SAMPLE_MS)
        } finally {
            recorder.close()
            packetSubscription.close()
            File(root, "diagnostics.txt").writeText(diagnostics.joinToString("\n"))
        }

        val decodedCount = FFmpegFrameGrabber(video).use { grabber ->
            grabber.start()
            assertEquals(AV_CODEC_ID_AV1, grabber.videoCodec)
            assertTrue(grabber.imageWidth > 0 && grabber.imageHeight > 0)
            var count = 0
            while (grabber.grabImage() != null) count++
            assertTrue(count >= MIN_DECODED_FRAMES, "Only decoded $count AV1 frames")
            grabber.setTimestamp(SEEK_TIMESTAMP_US)
            assertNotNull(grabber.grabImage(), "The generated AV1 recording should decode after a seek")
            count
        }
        assertTrue(decodedFrames.get() > 0, "The attached JavaCV mirror decoder should receive device AV1 frames")
        assertTrue(decodedCount >= MIN_DECODED_FRAMES)

        val capture = createStoppedCapture(root, serial, video)
        val zipStarted = System.nanoTime()
        val zip = File(root, "device-av1.zip")
        CaptureArchiveExporter().export(capture, CaptureExportRequest(zip, cutoffElapsedMs = capture.elapsedMs))
        val zipDurationMs = (System.nanoTime() - zipStarted) / NANOS_PER_MILLISECOND
        val archive = File(root, "device-av1.ind")
        val indStarted = System.nanoTime()
        val result = CaptureArchiveExporter().export(
            capture,
            CaptureExportRequest(
                destination = archive,
                cutoffElapsedMs = capture.elapsedMs,
                format = CaptureExportFormat.COMPACT,
            ),
        )
        val indDurationMs = (System.nanoTime() - indStarted) / NANOS_PER_MILLISECOND
        File(root, "export-times.txt").writeText(
            "ZIP: ${zip.length()} bytes / $zipDurationMs ms\nIND: ${archive.length()} bytes / $indDurationMs ms\n${result.message}\n",
        )
        assertTrue(result.message.contains("copied without conversion"))
        assertTrue(CaptureArchiveReader.isCaptureArchive(archive))
        val imported = CaptureArchiveReader.open(archive, File(root, "import-cache"))
        assertEquals("screen.mkv", imported.descriptor.video?.path)
        assertNotNull(imported.videoFile)
        FFmpegFrameGrabber(requireNotNull(imported.videoFile)).use { grabber ->
            grabber.start()
            assertEquals(AV_CODEC_ID_AV1, grabber.videoCodec)
            assertNotNull(grabber.grabImage())
        }
        File(root, "diagnostics.txt").writeText(diagnostics.joinToString("\n"))
    }

    private fun createStoppedCapture(root: File, serial: String, video: File): CaptureSession {
        val directory = File(root, "archive-session")
        val log = File(directory, "logs/logcat.log")
        val index = File(directory, "mapping/capture-index.jsonl")
        log.parentFile.mkdirs()
        index.parentFile.mkdirs()
        val row = "01-01 00:00:01.000  1  1 I Device: av1 smoke\n"
        val rowBytes = row.toByteArray()
        log.writeBytes(rowBytes)
        index.writeText("{\"byteOffset\":0,\"byteLength\":${rowBytes.size},\"elapsedMs\":1000,\"rowOrdinal\":1}\n")
        val sessionVideo = File(directory, "video/screen.mkv")
        sessionVideo.parentFile.mkdirs()
        video.copyTo(sessionVideo, overwrite = true)
        return CaptureSession(
            id = "device-av1-smoke",
            directory = directory,
            device = CaptureDevice(serial, "device", "AV1 smoke device"),
            settings = CaptureSettings(
                recordVideo = true,
                freeSpaceReserveBytes = 0
            ),
            startedEpochMs = System.currentTimeMillis(),
            elapsedMs = RECORDING_SAMPLE_MS,
            status = CaptureStatus.STOPPED,
            videoStartElapsedMs = 0,
        )
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_INTERVAL_MS)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val STARTUP_TIMEOUT_MS = 30_000L
        const val RECORDING_SAMPLE_MS = 5_000L
        const val SEEK_TIMESTAMP_US = 1_000_000L
        const val MIN_DECODED_FRAMES = 3
        const val POLL_INTERVAL_MS = 20L
    }
}
