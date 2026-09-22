package com.indagium.capture

import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TEST_FRAME_RATE = 10.0
private const val TEST_SAMPLE_RATE = 8_000
private const val TEST_FRAME_COUNT = 40
private const val TEST_SAMPLES_PER_FRAME = TEST_SAMPLE_RATE / TEST_FRAME_RATE.toInt()
private const val TRAILER_STRIP_BYTES = 4_096
private const val NEAR_KEYFRAME_FRAME_BUDGET = 300

@Suppress("MagicNumber")
class FfmpegCaptureVideoExporterTest {
    @Test
    fun remuxesFromPrecedingKeyframeAndPreservesAudioAndRotation() {
        val source = syntheticCapture()
        val before = sha256(source)
        val sourceCodecs = codecs(source)
        val destination = tempFile("clip", ".mkv")

        val clip = FfmpegCaptureVideoExporter().export(source, destination, 1_500, 3_250)

        assertTrue(clip.actualStartMs in 0..1_500, "clip must begin on a keyframe preceding the request: $clip")
        assertTrue(clip.coveredEndMs in 3_100..3_250, "expected a complete frame before 3250ms, got $clip")
        assertEquals(clip.coveredEndMs - clip.actualStartMs, clip.durationMs)
        assertContentEquals(before, sha256(source), "export must never modify or restart the capture source")

        FFmpegFrameGrabber(destination).use { grabber ->
            grabber.start()
            assertTrue(grabber.videoStream >= 0, "export should retain video")
            assertTrue(grabber.audioStream >= 0, "export should retain audio")
            assertEquals(sourceCodecs.first, grabber.videoCodec, "video codec and extradata should be stream-copied")
            assertEquals(sourceCodecs.second, grabber.audioCodec, "audio codec should be stream-copied")
            assertTrue(grabber.grabImage() != null, "export should decode a real video frame")
            assertTrue(abs(abs(grabber.displayRotation) - 90.0) < 0.1, "display matrix should survive remux")
        }
    }

    @Test
    fun exportsAPlayableClipFromAMissingTrailerAndTruncatedPacketTail() {
        val complete = syntheticCapture()
        val bytes = complete.readBytes()
        val source = tempFile("truncated-capture", ".mkv").apply {
            writeBytes(bytes.copyOf(bytes.size - minOf(2_048, bytes.size / 10)))
        }
        val destination = tempFile("truncated-clip", ".mkv")

        val clip = FfmpegCaptureVideoExporter().export(source, destination, 0, 10_000)

        assertEquals(0L, clip.actualStartMs)
        assertTrue(clip.coveredEndMs > 2_500, "the committed prefix should retain most video: $clip")
        assertTrue(clip.coveredEndMs < 4_100)
        FFmpegFrameGrabber(destination).use { grabber ->
            grabber.start()
            assertTrue(grabber.grabImage() != null, "finalized export from truncated input must be playable")
        }
    }

    @Test
    fun fromStartUsesAndReportsTheFirstKeyframeWhenVideoBeginsAfterZero() {
        val source = syntheticCapture(videoStartOffsetUs = 500_000L)
        val destination = tempFile("offset-clip", ".mkv")

        val clip = FfmpegCaptureVideoExporter().export(source, destination, 0, 1_500)

        assertTrue(clip.actualStartMs in 400..600, "actual start should expose the source video offset: $clip")
        assertTrue(clip.coveredEndMs > clip.actualStartMs)
        FFmpegFrameGrabber(destination).use { grabber ->
            grabber.start()
            assertTrue(grabber.grabImage() != null)
        }
    }

    @Test
    fun snapshotsOnePrefixWhileTheCaptureContinuesAppending() {
        val completeBytes = syntheticCapture().readBytes()
        val initialLength = completeBytes.size * 4 / 5
        val source = tempFile("growing-capture", ".mkv").apply {
            writeBytes(completeBytes.copyOf(initialLength))
        }
        val destination = tempFile("growing-clip", ".mkv")
        val appender = thread(name = "capture-test-appender") {
            RandomAccessFile(source, "rw").use { file ->
                file.seek(initialLength.toLong())
                var offset = initialLength
                while (offset < completeBytes.size) {
                    val count = minOf(97, completeBytes.size - offset)
                    file.write(completeBytes, offset, count)
                    offset += count
                    Thread.sleep(1)
                }
            }
        }

        val clip = FfmpegCaptureVideoExporter().export(source, destination, 0, 10_000)
        appender.join(10_000)

        assertTrue(!appender.isAlive, "fixture append should finish")
        assertContentEquals(completeBytes, source.readBytes(), "export must not interfere with the writer")
        assertTrue(clip.coveredEndMs > 2_000)
        assertTrue(clip.coveredEndMs <= 4_000)
        FFmpegFrameGrabber(destination).use { grabber ->
            grabber.start()
            assertTrue(grabber.grabImage() != null)
        }
    }

    // Regression test for the "exact-start clip" fix: a recording with sparse keyframes (here, one
    // keyframe for the entire fixture — exactly the shape the review found on a real Android
    // emulator, whose encoder produced one keyframe at pts 0 and none for ~20s afterwards) used to
    // force every Since-last-save export to restart from that single keyframe, re-exporting
    // whatever came before the actual request every time. Once the keyframe-to-request gap exceeds
    // the 500ms tolerance, the exporter must decode from that keyframe and re-encode starting at
    // (or within one frame of) the request instead.
    @Test
    fun exactStartReencodesWhenThePrecedingKeyframeIsFarBeforeTheRequest() {
        val source = syntheticCapture(gopSize = TEST_FRAME_COUNT, sparseKeyframes = true)
        val before = sha256(source)
        val destination = tempFile("exact-start-clip", ".mkv")
        val requestedStartMs = 2_000L
        val requestedEndMs = 3_500L

        val clip = FfmpegCaptureVideoExporter().export(source, destination, requestedStartMs, requestedEndMs)

        assertEquals(requestedStartMs, clip.actualStartMs, "an exact-start clip should land on the very frame requested")
        assertEquals(requestedEndMs, clip.coveredEndMs)
        assertContentEquals(before, sha256(source), "export must never modify or restart the capture source")
        FFmpegFrameGrabber(destination).use { grabber ->
            grabber.start()
            assertTrue(grabber.videoStream >= 0, "the re-encoded clip must still have a video stream")
            assertEquals(
                "h264", grabber.videoCodecName,
                "the exact-start path must have actually re-encoded to one of its H.264 candidates, not stream-copied mpeg4",
            )
            var lastTimestamp = -1L
            var frames = 0
            while (true) {
                val frame = grabber.grabImage() ?: break
                lastTimestamp = frame.timestamp
                frames++
            }
            // Regression coverage for a real bug this test previously missed: FFmpegFrameRecorder
            // .record(Frame) ignores Frame.timestamp entirely (verified against javacv 1.5.13
            // source) and auto-increments at 1/recorder.frameRate per call unless the recorder's
            // own `timestamp` is set explicitly first — without that, a source whose grabbed frame
            // rate doesn't reflect real spacing (an emulator/device recording routinely doesn't)
            // gets its ~1.5s of requested video squashed into a few milliseconds of output, while
            // the reported actualStartMs/coveredEndMs (built from decode-side timestamps, not the
            // recorder's output) kept claiming the correct span — caught only by comparing the
            // written file's own playback duration against the requested span, as this does.
            assertTrue(frames > 1, "expected more than one frame in the requested 1.5s span, got $frames")
            val playedMs = lastTimestamp / 1_000L
            assertTrue(
                playedMs in 1_000..1_600,
                "the encoded clip's own timestamps must span close to the requested 1500ms, got ${playedMs}ms over $frames frame(s)",
            )
        }
    }

    // A gap within tolerance must still use the cheap, lossless keyframe-aligned remux — re-encoding
    // is strictly a fallback for when that isn't good enough, not the default path.
    @Test
    fun exactStartLeavesAWithinToleranceGapToTheOrdinaryKeyframeRemux() {
        val source = syntheticCapture(gopSize = TEST_FRAME_COUNT, sparseKeyframes = true)
        val destination = tempFile("within-tolerance-clip", ".mkv")

        val clip = FfmpegCaptureVideoExporter().export(source, destination, 400, 1_500)

        assertEquals(0L, clip.actualStartMs, "a 400ms gap is within tolerance and should stay on the source's only keyframe")
        FFmpegFrameGrabber(destination).use { grabber ->
            grabber.start()
            assertEquals("mpeg4", grabber.videoCodecName, "a within-tolerance clip must be the stream-copied original codec")
        }
    }

    // Regression test for a real perf bug this review found: the exact-start path decoded every
    // frame from position 0, which for a long recording (and the short keyframe interval this
    // codebase now records with — see MirrorStreamOptions.keyFrameIntervalSeconds) means
    // software-decoding a large fraction of an hour-long session's video on essentially every
    // Since-last-save snapshot.
    // `positionAtOrBeforeKeyframe` must seek near the target keyframe instead. Verified against a
    // fixture with its trailer stripped off (no Matroska Cues/SeekHead) — a real growing scrcpy MKV
    // snapshot never has a trailer either, only once the recording stops, so a fully-finalized
    // synthetic fixture (with a seek index FFmpeg built in) wouldn't actually exercise the risk this
    // review flagged: whether FFmpeg's seek is trustworthy on exactly this file shape at all.
    @Test
    fun exactStartSeeksNearTheKeyframeInsteadOfDecodingALongTrailerlessRecordingFromItsStart() {
        val frameCount = 600
        val gopSize = 100
        val complete = syntheticCapture(gopSize = gopSize, sparseKeyframes = true, frameCount = frameCount)
        val bytes = complete.readBytes()
        val source = tempFile("long-trailerless-capture", ".mkv").apply {
            writeBytes(bytes.copyOf(bytes.size - TRAILER_STRIP_BYTES))
        }
        val destination = tempFile("long-exact-start-clip", ".mkv")
        var diagnostics: ReencodeDiagnostics? = null
        val exporter = FfmpegCaptureVideoExporter(reencodeDiagnosticsHook = { diagnostics = it })

        // 600 frames @ 10fps = 60s total; gopSize=100 puts keyframes at 0/10/20/30/40/50s. Request
        // starting at 55s — well past the 500ms exact-start tolerance from the 50s keyframe, and
        // deep enough into the file (frame 550 of 600) that decoding from 0 would be obvious in
        // framesReadBeforeStart.
        val clip = exporter.export(source, destination, 55_000, 58_000)

        assertTrue(clip.actualStartMs in 54_900..55_100, "expected an exact start near 55000ms, got $clip")
        val seen = assertNotNull(diagnostics, "the exact-start path must have run and reported diagnostics")
        assertTrue(seen.usedSeek, "seeking must be tried and verified on a trailer-less snapshot, not silently skipped")
        assertTrue(
            seen.framesReadBeforeStart < NEAR_KEYFRAME_FRAME_BUDGET,
            "seeking should land within about one gop of the target, not decode the whole recording " +
                "from 0 (${seen.framesReadBeforeStart} frames read before reaching the requested start, of $frameCount total)",
        )
    }

    @Test
    fun rejectsARealAudioOnlyContainerAsHavingNoReadableVideo() {
        val source = syntheticAudioOnlyCapture()
        val failure = assertFailsWith<IOException> {
            FfmpegCaptureVideoExporter().export(source, tempFile("no-video", ".mkv"), 0, 1_000)
        }
        assertTrue(failure.message.orEmpty().contains("no video stream", ignoreCase = true))
    }

    @Test
    fun interruptedExportCleansItsStagingFiles() {
        val directory = Files.createTempDirectory("indagium-interrupted-export").toFile()
        val source = File(directory, "source.mkv").apply { writeBytes(syntheticCapture().readBytes()) }
        val destination = File(directory, "clip.mkv")

        Thread.currentThread().interrupt()
        try {
            assertFailsWith<IOException> {
                FfmpegCaptureVideoExporter().export(source, destination, 0, 1_000)
            }
        } finally {
            Thread.interrupted()
        }

        assertTrue(!destination.exists())
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".capture-") })
    }

    private fun syntheticCapture(
        videoStartOffsetUs: Long = 0L,
        gopSize: Int = TEST_FRAME_RATE.toInt(),
        // The default per-frame content (a large full-frame color swing every frame) makes
        // FFmpeg's native mpeg4 encoder insert a scene-cut keyframe on essentially every frame
        // regardless of gopSize — fine for the other fixtures below (they only need "keyframes
        // roughly every gopSize frames", asserted with a permissive range), but it defeats a sparse-
        // keyframe fixture outright: verified empirically that even gopSize == TEST_FRAME_COUNT
        // still produced a keyframe on every single frame. Setting this disables scene-cut
        // detection (sc_threshold) and uses a much gentler per-frame delta, matching a real mostly-
        // static device screen and actually respecting gopSize as the sole keyframe interval.
        sparseKeyframes: Boolean = false,
        frameCount: Int = TEST_FRAME_COUNT,
    ): File {
        val output = tempFile("synthetic-capture", ".mkv")
        val width = 64
        val height = 48
        val pixels = ByteBuffer.allocate(width * height * 3)
        val recorder = FFmpegFrameRecorder(output, width, height, 1).apply {
            format = "matroska"
            frameRate = TEST_FRAME_RATE
            this.gopSize = gopSize
            videoCodec = AV_CODEC_ID_MPEG4
            videoBitrate = 300_000
            sampleRate = TEST_SAMPLE_RATE
            audioCodec = AV_CODEC_ID_PCM_S16LE
            setDisplayRotation(90.0)
            setVideoMetadata("capture-test", "orientation-and-audio")
            if (sparseKeyframes) setVideoOption("sc_threshold", "1000000000")
        }
        try {
            recorder.start()
            if (videoStartOffsetUs > 0L) recorder.timestamp = videoStartOffsetUs
            repeat(frameCount) { frameIndex ->
                pixels.clear()
                repeat(width * height) {
                    if (sparseKeyframes) {
                        val shade = (frameIndex % 4).toByte()
                        pixels.put(shade)
                        pixels.put(shade)
                        pixels.put(shade)
                    } else {
                        pixels.put((frameIndex * 5 % 255).toByte())
                        pixels.put((frameIndex * 11 % 255).toByte())
                        pixels.put((frameIndex * 17 % 255).toByte())
                    }
                }
                pixels.flip()
                recorder.recordImage(width, height, 8, 3, width * 3, AV_PIX_FMT_BGR24, pixels)
                val samples = ShortArray(TEST_SAMPLES_PER_FRAME) { sampleIndex ->
                    val position = frameIndex * TEST_SAMPLES_PER_FRAME + sampleIndex
                    (sin(position * 2.0 * Math.PI * 440.0 / TEST_SAMPLE_RATE) * Short.MAX_VALUE / 8).toInt().toShort()
                }
                recorder.recordSamples(TEST_SAMPLE_RATE, 1, ShortBuffer.wrap(samples))
            }
            recorder.stop()
        } finally {
            runCatching { recorder.release() }
        }
        return output
    }

    private fun syntheticAudioOnlyCapture(): File {
        val output = tempFile("synthetic-audio", ".mkv")
        val recorder = FFmpegFrameRecorder(output, 1).apply {
            format = "matroska"
            sampleRate = TEST_SAMPLE_RATE
            audioCodec = AV_CODEC_ID_PCM_S16LE
        }
        try {
            recorder.start()
            recorder.recordSamples(TEST_SAMPLE_RATE, 1, ShortBuffer.wrap(ShortArray(TEST_SAMPLE_RATE)))
            recorder.stop()
        } finally {
            runCatching { recorder.release() }
        }
        return output
    }

    private fun tempFile(prefix: String, suffix: String): File =
        Files.createTempFile("indagium-$prefix-", suffix).toFile().apply { deleteOnExit() }

    private fun sha256(file: File): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun codecs(file: File): Pair<Int, Int> = FFmpegFrameGrabber(file).use { grabber ->
        grabber.start()
        grabber.videoCodec to grabber.audioCodec
    }
}
