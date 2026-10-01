package com.indagium.capture

import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WIDTH = 64
private const val HEIGHT = 48
private const val FRAME_COUNT = 24
private const val FRAME_STEP_US = 33_000L
private val AUDIO_PTS_US = listOf(251_000L, 251_300L, 251_600L, 251_900L, 271_000L, 271_100L, 291_000L, 335_000L, 335_400L)

/**
 * Verifies [StreamingMkvWriter] against real H.264 packets (produced by the bundled libopenh264
 * encoder, the same one [FfmpegCaptureVideoExporter] uses — see its `EXACT_START_ENCODER_CANDIDATES`
 * doc), split into device-style Annex-B NAL units so this test exercises the exact shape of data
 * [EmbeddedDeviceSession] will feed in from [ScrcpyPacketReader]: an SPS+PPS config packet once, then
 * a keyframe/delta packet per encoded frame.
 */
@Suppress("MagicNumber")
class StreamingMkvWriterTest {
    @Test
    fun growingFileIsReadableBeforeFinishAndFinalizedFileHasCuesAfter() {
        val (extradata, samples) = encodeSyntheticH264()
        val destination = tempFile("streaming-mkv", ".mkv")
        val writer = StreamingMkvWriter(destination, clusterTimeLimitMs = 100, clusterSizeLimitBytes = 2_048)
        try {
            writer.start(WIDTH, HEIGHT, extradata)
            // Write a little over half the samples, then read the still-open, still-growing file —
            // the whole point of this class is that a separate reader does not need to wait for
            // finish() (see FfmpegCaptureVideoExporter's videoCoverageWaitMs problem this replaces).
            val firstHalf = samples.take(samples.size * 2 / 3)
            firstHalf.forEach { sample -> writer.writeVideoPacket(sample.ptsUs, sample.keyFrame, sample.data) }
            assertTrue(writer.hasWrittenVideo())

            val readableDurationUs = FFmpegFrameGrabber(destination).use { grabber ->
                grabber.start()
                assertTrue(grabber.videoStream >= 0, "growing file must already expose a video stream")
                var lastTimestamp = -1L
                var frames = 0
                while (true) {
                    val frame = grabber.grabImage() ?: break
                    lastTimestamp = frame.timestamp
                    frames++
                }
                assertTrue(frames > 0, "growing file must already yield at least one decodable frame")
                lastTimestamp
            }
            val writtenSoFarUs = firstHalf.last().ptsUs
            // The trailing cluster (accumulating the most recently written packets) is only
            // guaranteed to close, and therefore reach disk, once cluster_time_limit's worth of
            // packet PTS span has been queued into it — see StreamingMkvWriter's doc. A bounded lag
            // here is expected and matches the redesign's own "~1s of real time" target; this must
            // not regress to whole seconds of buffering the way host scrcpy's own muxer did.
            val maxAcceptableLagUs = 1_000_000L
            assertTrue(
                readableDurationUs >= writtenSoFarUs - maxAcceptableLagUs,
                "readable end ($readableDurationUs us) lagged the written end ($writtenSoFarUs us) by more " +
                    "than the ~1s target",
            )

            // The rest of the samples, then finalize.
            samples.drop(firstHalf.size).forEach { sample -> writer.writeVideoPacket(sample.ptsUs, sample.keyFrame, sample.data) }
            writer.finish()
        } finally {
            writer.close()
        }

        FFmpegFrameGrabber(destination).use { grabber ->
            grabber.start()
            assertEquals(AV_CODEC_ID_H264, grabber.videoCodec, "finalized file must carry the H.264 codec id")
            assertTrue(grabber.lengthInTime > 0, "a finalized (trailer-written) file must report a real duration from its Cues/SegmentInfo")
            var frames = 0
            while (grabber.grabImage() != null) frames++
            assertTrue(frames >= samples.size - 2, "finalized file should decode essentially every written frame, got $frames")
        }
    }

    @Test
    fun packetsWithNonIncreasingPtsAreClampedToStayMonotonic() {
        val (extradata, samples) = encodeSyntheticH264()
        val destination = tempFile("streaming-mkv-clamp", ".mkv")
        StreamingMkvWriter(destination).use { writer ->
            writer.start(WIDTH, HEIGHT, extradata)
            val first = samples.first()
            writer.writeVideoPacket(first.ptsUs, first.keyFrame, first.data)
            // A pts that goes backwards (e.g. a slightly wrong reconnect offset) must not throw or
            // corrupt the file — it should just be clamped forward of the previous packet.
            writer.writeVideoPacket(0L, false, samples[1].data)
            writer.finish()
        }
        FFmpegFrameGrabber(destination).use { grabber ->
            grabber.start()
            assertTrue(grabber.grabImage() != null)
        }
    }

    @Test
    fun submillisecondAudioTimestampsStayStrictlyIncreasingInTheFile() {
        val (extradata, samples) = encodeSyntheticH264()
        val destination = tempFile("streaming-mkv-audio-dts", ".mkv")
        StreamingMkvWriter(destination).use { writer ->
            writer.start(WIDTH, HEIGHT, extradata)
            writer.addAudio(48_000, 2, opusHead())
            // Bursts of audio packets a few hundred microseconds apart (device AudioRecord jitter):
            // distinct in the microsecond clock, identical once rounded to Matroska's millisecond
            // block timestamps — exactly the pairs ffmpeg reported as "non monotonically increasing".
            val video = samples.take(4)
            writer.writeVideoPacket(video[0].ptsUs, video[0].keyFrame, video[0].data)
            AUDIO_PTS_US.forEach { writer.writeAudioPacket(it, byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())) }
            video.drop(1).forEach { writer.writeVideoPacket(it.ptsUs + 335_000L, it.keyFrame, it.data) }
            writer.finish()
        }

        val dtsByStream = readPacketDts(destination)
        val audio = dtsByStream.getValue(1)
        assertEquals(AUDIO_PTS_US.size, audio.size, "every audio packet must be present")
        audio.zipWithNext().forEach { (a, b) -> assertTrue(b > a, "audio dts must be strictly increasing: $audio") }
        assertEquals(251L, audio.first(), "the first packet keeps its own millisecond")
        // Bumped forward only as far as strict ordering needs, never dragged ahead of real time.
        assertTrue(audio.last() <= 336L, "audio timeline drifted: $audio")
        val video = dtsByStream.getValue(0)
        video.zipWithNext().forEach { (a, b) -> assertTrue(b > a, "video dts must be strictly increasing: $video") }
    }

    @Test
    fun finishFinalizesTheTrailerAndIsSafeToCallTwice() {
        val (extradata, samples) = encodeSyntheticH264()
        val destination = tempFile("streaming-mkv-finish", ".mkv")
        val writer = StreamingMkvWriter(destination, clusterTimeLimitMs = 100, clusterSizeLimitBytes = 2_048)
        writer.start(WIDTH, HEIGHT, extradata)
        samples.forEach { writer.writeVideoPacket(it.ptsUs, it.keyFrame, it.data) }
        assertTrue(!MkvStructure.read(destination).isFinalized, "a growing file has no trailer yet")

        writer.finish()
        writer.finish() // exactly once: the second call is a no-op
        writer.close()

        val structure = MkvStructure.read(destination)
        assertTrue(structure.segmentSizeKnown, "finalized Segment size must be known: $structure")
        assertTrue(structure.hasCues, "finalized file must carry Cues: $structure")
        assertTrue(structure.isFinalized, "finalized file must report a duration: $structure")
    }

    /** dts of every packet per stream index, in file order, in the stream's own time base (ms). */
    private fun readPacketDts(file: File): Map<Int, List<Long>> {
        val result = HashMap<Int, MutableList<Long>>()
        FFmpegFrameGrabber(file).use { grabber ->
            grabber.start()
            while (true) {
                val packet = grabber.grabPacket() ?: break
                if (packet.size() <= 0) break
                result.getOrPut(packet.stream_index()) { mutableListOf() } += packet.dts()
            }
        }
        return result
    }

    /** Minimal valid OpusHead (stereo, 48 kHz) used as the audio stream's extradata. */
    private fun opusHead(): ByteArray = ByteBuffer.allocate(19).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put("OpusHead".toByteArray(Charsets.US_ASCII))
        put(1)
        put(2)
        putShort(312)
        putInt(48_000)
        putShort(0)
        put(0)
    }.array()

    private data class Sample(val ptsUs: Long, val keyFrame: Boolean, val data: ByteArray)

    /** Encodes [FRAME_COUNT] tiny frames with libopenh264, then splits the resulting Annex-B
     * elementary stream into NAL units the same way a device's MediaCodec output — and therefore
     * [ScrcpyPacketReader]'s config/keyframe/delta packets — is already shaped. */
    private fun encodeSyntheticH264(): Pair<ByteArray, List<Sample>> {
        val raw = tempFile("synthetic-h264-source", ".h264")
        val pixels = ByteBuffer.allocate(WIDTH * HEIGHT * 3)
        val recorder = FFmpegFrameRecorder(raw, WIDTH, HEIGHT, 0).apply {
            format = "h264"
            frameRate = 1_000_000.0 / FRAME_STEP_US
            videoCodec = AV_CODEC_ID_H264
            videoCodecName = "libopenh264"
            videoBitrate = 300_000
            gopSize = FRAME_COUNT // one keyframe at the very start is enough for this test
        }
        recorder.start()
        try {
            repeat(FRAME_COUNT) { frameIndex ->
                pixels.clear()
                repeat(WIDTH * HEIGHT) {
                    val shade = ((frameIndex * 7) % 256).toByte()
                    pixels.put(shade).put(shade).put(shade)
                }
                pixels.flip()
                recorder.timestamp = frameIndex * FRAME_STEP_US
                recorder.recordImage(WIDTH, HEIGHT, 8, 3, WIDTH * 3, AV_PIX_FMT_BGR24, pixels)
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        val bytes = raw.readBytes()
        raw.delete()
        val nalUnits = splitAnnexBNalUnits(bytes)
        val configUnits = mutableListOf<ByteArray>()
        val samples = mutableListOf<Sample>()
        var frameIndex = 0
        for (nal in nalUnits) {
            val type = nal[nal.startCodeLength()].toInt() and 0x1f
            when (type) {
                7, 8 -> configUnits += nal // SPS, PPS
                5, 1 -> {
                    samples += Sample(frameIndex * FRAME_STEP_US, type == 5, nal)
                    frameIndex++
                }
                else -> Unit
            }
        }
        assertTrue(configUnits.isNotEmpty(), "encoder must have produced an SPS/PPS config header")
        assertTrue(samples.isNotEmpty(), "encoder must have produced at least one slice NAL")
        val extradata = configUnits.reduce { acc, bytes2 -> acc + bytes2 }
        return extradata to samples
    }

    @Suppress("ComplexCondition")
    private fun ByteArray.startCodeLength(): Int =
        if (size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 0.toByte() && this[3] == 1.toByte()) 4 else 3

    /** Splits a raw Annex-B stream into individual NAL units, each still prefixed by its own start
     * code (3- or 4-byte), matching what a device MediaCodec buffer / [ScrcpyPacketReader] packet
     * payload looks like. */
    private fun splitAnnexBNalUnits(bytes: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
            val isFourByte = bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                bytes[index + 2] == 0.toByte() && index + 3 < bytes.size && bytes[index + 3] == 1.toByte()
            val isThreeByte = !isFourByte && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 1.toByte()
            if (isFourByte || isThreeByte) {
                starts += index
                index += if (isFourByte) 4 else 3
            } else {
                index++
            }
        }
        return starts.indices.map { i ->
            val from = starts[i]
            val to = if (i + 1 < starts.size) starts[i + 1] else bytes.size
            bytes.copyOfRange(from, to)
        }
    }

    private fun tempFile(prefix: String, suffix: String): File =
        Files.createTempFile("indagium-$prefix-", suffix).toFile().apply { deleteOnExit() }
}
