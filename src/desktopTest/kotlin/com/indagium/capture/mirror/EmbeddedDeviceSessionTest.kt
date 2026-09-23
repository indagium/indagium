@file:Suppress("MagicNumber")

package com.indagium.capture.mirror

import com.indagium.capture.StreamingMkvWriter
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [EmbeddedDeviceSession] end to end against a fake [EmbeddedMirrorTransport] producing
 * synthetic scrcpy frame-meta byte streams (built the same way [ScrcpyPacketReaderTest] does), so
 * the recording pump, PTS-continuity/reconnect logic, decoder fan-out and audio-unavailable
 * handling are all exercised without a device. Packet payloads use one real, tiny H.264
 * SPS/PPS/IDR/delta set (produced once via the bundled libopenh264 encoder — see [H264_FIXTURE])
 * because [StreamingMkvWriter] rejects structurally invalid H.264 extradata when it writes the
 * Matroska header; the actual pixel content is irrelevant to what this test verifies.
 */
class EmbeddedDeviceSessionTest {
    @org.junit.Test(timeout = 20_000)
    fun writesVideoStartingAtZeroAndReportsVideoStartElapsedOnFirstPacket() {
        val stream = videoStream(
            width = 100,
            height = 200,
            config = H264_FIXTURE.config,
            packets = listOf(Packet(5_000, true, H264_FIXTURE.key), Packet(38_000, false, H264_FIXTURE.delta)),
        )
        val transport = EmbeddedMirrorTransport { _, _ -> fakeConnection(stream) }
        val writtenPts = CopyOnWriteArrayList<Pair<Long, Boolean>>()
        val elapsed = AtomicLong(1_000)
        val session = EmbeddedDeviceSession(
            transport,
            StreamingMkvWriter(tempFile()),
            elapsedMillis = { elapsed.get() },
            onVideoPacketWrittenHook = { pts, key -> writtenPts += pts to key },
        )
        try {
            session.start("serial", MirrorStreamOptions())
            awaitTrue { writtenPts.size >= 2 }
            assertEquals(0L, writtenPts[0].first, "first video packet must anchor the output timeline at 0")
            assertTrue(writtenPts[0].second, "first written packet must be the key frame")
            assertEquals(33_000L, writtenPts[1].first)
        } finally {
            session.close()
        }
    }

    @org.junit.Test(timeout = 20_000)
    fun reconnectContinuesThePtsTimelineForwardAndReportsAVideoGap() {
        val oneConnectionStream = videoStream(
            width = 64,
            height = 64,
            config = H264_FIXTURE.config,
            packets = listOf(Packet(0, true, H264_FIXTURE.key)),
        )
        val connections = ConcurrentLinkedQueue(listOf(oneConnectionStream, oneConnectionStream))
        val transport = EmbeddedMirrorTransport { _, _ -> fakeConnection(requireNotNull(connections.poll())) }
        val writtenPts = CopyOnWriteArrayList<Long>()
        val diagnostics = CopyOnWriteArrayList<String>()
        // A fake clock that advances on every read (rather than a fixed value flipped mid-test)
        // deterministically simulates a real-world reconnect gap regardless of how fast this
        // in-memory fake transport actually reconnects — nothing here depends on wall-clock timing.
        val elapsedValue = AtomicLong(0)
        val session = EmbeddedDeviceSession(
            transport,
            StreamingMkvWriter(tempFile()),
            elapsedMillis = { elapsedValue.addAndGet(50) },
            reconnectDelay = Duration.ZERO,
            onDiagnostic = { diagnostics += it },
            onVideoPacketWrittenHook = { pts, _ -> writtenPts += pts },
        )
        try {
            session.start("serial", MirrorStreamOptions())
            awaitTrue { writtenPts.size >= 2 }
            assertTrue(writtenPts[1] > writtenPts[0], "reconnected video must continue forward, never rewind: $writtenPts")
            assertTrue(
                diagnostics.any { it.contains("video gap", ignoreCase = true) },
                "a reconnect must record an interruption diagnostic: $diagnostics",
            )
        } finally {
            session.close()
        }
    }

    @org.junit.Test(timeout = 20_000)
    fun attachedMirrorDecoderNeverBlocksTheRecordingPump() {
        val packets = (1..40).map { index ->
            Packet(index * 33_000L, index == 1, if (index == 1) H264_FIXTURE.key else H264_FIXTURE.delta)
        }
        val stream = videoStream(64, 64, H264_FIXTURE.config, packets)
        val transport = EmbeddedMirrorTransport { _, _ -> fakeConnection(stream) }
        val writtenPts = CopyOnWriteArrayList<Long>()
        val session = EmbeddedDeviceSession(
            transport,
            StreamingMkvWriter(tempFile()),
            elapsedMillis = { 0 },
            onVideoPacketWrittenHook = { pts, _ -> writtenPts += pts },
        )
        try {
            // A decoder that never reads its input — the stand-in for a stalled/slow mirror.
            val stalledDecoder = object : H264Decoder {
                override fun decode(input: InputStream, onFrame: (MirrorFrame) -> Unit) {
                    Thread.sleep(10_000)
                }
            }
            session.attachDecoder(stalledDecoder) {}
            session.start("serial", MirrorStreamOptions())
            awaitTrue(timeoutMs = 5_000) { writtenPts.size >= packets.size }
        } finally {
            session.close()
        }
    }

    @org.junit.Test(timeout = 20_000)
    fun directMirrorDecoderReceivesRecorderPacketMetadataWithoutBlockingMkvWrites() {
        val packets = (1..12).map { index ->
            Packet(index * 33_000L, index == 1, if (index == 1) H264_FIXTURE.key else H264_FIXTURE.delta)
        }
        val transport = EmbeddedMirrorTransport { _, _ -> fakeConnection(videoStream(64, 64, H264_FIXTURE.config, packets)) }
        val writtenPts = CopyOnWriteArrayList<Long>()
        val received = CopyOnWriteArrayList<BoundedScrcpyPacketFeed.Packet>()
        val session = EmbeddedDeviceSession(
            transport,
            StreamingMkvWriter(tempFile()),
            elapsedMillis = { 0 },
            onVideoPacketWrittenHook = { pts, _ -> writtenPts += pts },
        )
        val directDecoder = DirectH264Decoder { feed, onFrame ->
            while (true) {
                val packet = feed.nextPacket() ?: return@DirectH264Decoder
                received += packet
                if (!packet.config) onFrame(MirrorFrameInfo(64, 64, packet.ptsUs))
            }
        }
        try {
            session.attachDirectDecoder(directDecoder, onFrame = { })
            session.start("serial", MirrorStreamOptions())
            awaitTrue(timeoutMs = 5_000) { writtenPts.size >= packets.size }
            awaitTrue(timeoutMs = 5_000) { received.any { !it.config && it.keyFrame } }

            val keyPacket = requireNotNull(received.firstOrNull { !it.config && it.keyFrame })
            assertEquals(packets.first().ptsUs, keyPacket.ptsUs, "direct decoder must receive source PTS unchanged")
            assertTrue(received.any { it.config }, "direct decoder must receive SPS/PPS configuration")
        } finally {
            session.close()
        }
    }

    @org.junit.Test(timeout = 20_000)
    fun audioDisabledHeaderIsDiagnosedAsVideoOnlyNotAFailure() {
        val video = videoStream(64, 64, H264_FIXTURE.config, listOf(Packet(0, true, H264_FIXTURE.key)))
        val audio = ByteArrayOutputStream().also { out -> DataOutputStream(out).writeInt(ScrcpyCodecIds.STREAM_DISABLED) }.toByteArray()
        val transport = EmbeddedMirrorTransport { _, _ -> fakeConnection(video, audio) }
        val diagnostics = CopyOnWriteArrayList<String>()
        val writtenPts = CopyOnWriteArrayList<Long>()
        val session = EmbeddedDeviceSession(
            transport,
            StreamingMkvWriter(tempFile()),
            elapsedMillis = { 0 },
            onDiagnostic = { diagnostics += it },
            onVideoPacketWrittenHook = { pts, _ -> writtenPts += pts },
        )
        try {
            session.start("serial", MirrorStreamOptions(audio = true))
            awaitTrue { writtenPts.isNotEmpty() }
            awaitTrue { diagnostics.any { it.contains("audio", ignoreCase = true) } }
            assertTrue(
                diagnostics.none { it.contains("fail", ignoreCase = true) },
                "an unavailable audio stream must never be reported as a failure: $diagnostics",
            )
        } finally {
            session.close()
        }
    }

    // Regression test for a real bug caught by review before this shipped: runSession() used to
    // call pumpVideo() (which blocks on the video socket for the connection's whole lifetime) and
    // only pump audio *after* it returned — meaning audio was never actually read while a
    // recording was live, only once the connection was already ending. This proves audio packets
    // reach the muxer while the video socket is still blocked mid-stream, not after it.
    @org.junit.Test(timeout = 20_000)
    fun audioIsPumpedConcurrentlyWithVideoNotOnlyAfterItEnds() {
        val videoHeader = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(ScrcpyCodecIds.H264)
                writeInt(0x80000000.toInt())
                writeInt(64)
                writeInt(64)
                writeLong(1L shl 62)
                writeInt(H264_FIXTURE.config.size)
                write(H264_FIXTURE.config)
                writeLong(0L or (1L shl 61))
                writeInt(H264_FIXTURE.key.size)
                write(H264_FIXTURE.key)
            }
        }.toByteArray()
        val releaseVideo = CountDownLatch(1)
        val opusHead = opusHeadExtradata()
        // Several packets, paced a few ms apart (PacedChunkInputStream below) rather than all
        // available at once: writeAudioFrame() deliberately drops any audio packet that arrives
        // before video's own key frame has anchored the output timeline (see its doc) — with every
        // byte available instantly, the audio thread could race through (and drop) every packet
        // before the video thread, itself waiting out this same coordination, ever reaches its key
        // frame. A real device audio stream is never delivered all-at-once like that either.
        val audioChunks = buildList {
            add(intBytes(ScrcpyCodecIds.OPUS))
            add(longBytes(1L shl 62) + intBytes(opusHead.size))
            add(opusHead)
            repeat(20) { index ->
                add(longBytes(1_000L * index) + intBytes(5))
                add(byteArrayOf(1, 2, 3, 4, index.toByte()))
            }
        }
        val transport = EmbeddedMirrorTransport { _, _ ->
            object : EmbeddedMirrorConnection {
                override val videoInput: InputStream = BlockingAfterPayloadInputStream(videoHeader, releaseVideo)
                override val audioInput: InputStream = PacedChunkInputStream(audioChunks, delayMs = 3)

                override fun sendControl(bytes: ByteArray) = Unit

                override fun close() = Unit
            }
        }
        val audioPts = CopyOnWriteArrayList<Long>()
        val session = EmbeddedDeviceSession(
            transport,
            StreamingMkvWriter(tempFile()),
            elapsedMillis = { 0 },
            onAudioPacketWrittenHook = { pts -> audioPts += pts },
        )
        try {
            session.start("serial", MirrorStreamOptions(audio = true))
            // The video stream is still blocked (releaseVideo not counted down yet) — if audio were
            // only pumped after pumpVideo() returns, this would time out.
            awaitTrue(timeoutMs = 2_000) { audioPts.isNotEmpty() }
        } finally {
            releaseVideo.countDown()
            session.close()
        }
    }

    /** A minimal, structurally valid 19-byte OpusHead (magic + version + channels + pre-skip +
     * sample rate + gain + channel mapping family), matching what Streamer.writeAudioHeader's
     * config-packet fixup extracts from the device's real AOPUSHDR buffer. */
    private fun opusHeadExtradata(): ByteArray {
        val buffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("OpusHead".toByteArray(Charsets.US_ASCII))
        buffer.put(1) // version
        buffer.put(2) // channel count
        buffer.putShort(0) // pre-skip
        buffer.putInt(48_000) // original sample rate
        buffer.putShort(0) // output gain
        buffer.put(0) // channel mapping family
        return buffer.array()
    }

    private fun intBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun longBytes(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()

    /** Delivers one pre-sliced chunk per read() call (never splitting or coalescing chunks),
     * pausing [delayMs] before every chunk after the first — see its call site's doc. */
    private class PacedChunkInputStream(chunks: List<ByteArray>, private val delayMs: Long) : InputStream() {
        private val queue = ArrayDeque(chunks)
        private var current: ByteArray? = null
        private var offset = 0
        private var deliveredFirst = false

        override fun read(): Int {
            val single = ByteArray(1)
            val count = read(single, 0, 1)
            return if (count <= 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (current == null || offset >= requireNotNull(current).size) {
                if (queue.isEmpty()) return -1
                if (deliveredFirst) Thread.sleep(delayMs)
                deliveredFirst = true
                current = queue.removeFirst()
                offset = 0
            }
            val chunk = requireNotNull(current)
            val count = minOf(len, chunk.size - offset)
            chunk.copyInto(buffer, off, offset, offset + count)
            offset += count
            return count
        }
    }

    private class BlockingAfterPayloadInputStream(
        private val payload: ByteArray,
        private val releaseEof: CountDownLatch,
    ) : InputStream() {
        private var offset = 0

        override fun read(): Int {
            if (offset < payload.size) return payload[offset++].toInt() and 0xff
            releaseEof.await()
            return -1
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (offset >= payload.size) {
                releaseEof.await()
                return -1
            }
            val count = minOf(len, payload.size - offset)
            payload.copyInto(buffer, off, offset, offset + count)
            offset += count
            return count
        }
    }

    private data class Packet(val ptsUs: Long, val keyFrame: Boolean, val data: ByteArray)

    private fun videoStream(width: Int, height: Int, config: ByteArray, packets: List<Packet>): ByteArray =
        ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(ScrcpyCodecIds.H264)
                writeInt(0x80000000.toInt())
                writeInt(width)
                writeInt(height)
                writeLong(1L shl 62)
                writeInt(config.size)
                write(config)
                packets.forEach { packet ->
                    var flags = packet.ptsUs
                    if (packet.keyFrame) flags = flags or (1L shl 61)
                    writeLong(flags)
                    writeInt(packet.data.size)
                    write(packet.data)
                }
            }
        }.toByteArray()

    private fun fakeConnection(video: ByteArray, audio: ByteArray? = null): EmbeddedMirrorConnection = object : EmbeddedMirrorConnection {
        override val videoInput: InputStream = ByteArrayInputStream(video)
        override val audioInput: InputStream? = audio?.let { ByteArrayInputStream(it) }

        override fun sendControl(bytes: ByteArray) = Unit

        override fun close() = Unit
    }

    private fun tempFile() = Files.createTempFile("embedded-device-session-", ".mkv").toFile().apply { deleteOnExit() }

    private fun awaitTrue(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition was not met before timeout")
    }

    private companion object {
        val H264_FIXTURE: H264Fixture by lazy { encodeH264Fixture() }
    }
}

private data class H264Fixture(val config: ByteArray, val key: ByteArray, val delta: ByteArray)

/** Encodes two tiny frames with the bundled libopenh264 encoder (same one
 * [com.indagium.capture.FfmpegCaptureVideoExporter] uses) and splits the Annex-B output into NAL
 * units, exactly like [com.indagium.capture.StreamingMkvWriterTest] does — reused here so
 * [EmbeddedDeviceSessionTest]'s synthetic packets carry structurally valid H.264 bytes. */
@Suppress("MagicNumber")
private fun encodeH264Fixture(): H264Fixture {
    val width = 32
    val height = 32
    val raw = Files.createTempFile("embedded-session-h264-fixture-", ".h264").toFile().apply { deleteOnExit() }
    val pixels = ByteBuffer.allocate(width * height * 3)
    val recorder = FFmpegFrameRecorder(raw, width, height, 0).apply {
        format = "h264"
        frameRate = 30.0
        videoCodec = AV_CODEC_ID_H264
        videoCodecName = "libopenh264"
        videoBitrate = 300_000
        gopSize = 30
    }
    recorder.start()
    try {
        repeat(2) { frameIndex ->
            pixels.clear()
            repeat(width * height) {
                val shade = ((frameIndex + 1) * 60).toByte()
                pixels.put(shade).put(shade).put(shade)
            }
            pixels.flip()
            recorder.timestamp = frameIndex * 33_000L
            recorder.recordImage(width, height, 8, 3, width * 3, AV_PIX_FMT_BGR24, pixels)
        }
    } finally {
        recorder.stop()
        recorder.release()
    }
    val bytes = raw.readBytes()
    raw.delete()
    val nalUnits = splitAnnexBNalUnits(bytes)
    val configUnits = mutableListOf<ByteArray>()
    var key: ByteArray? = null
    var delta: ByteArray? = null
    for (nal in nalUnits) {
        val start = if (nal.size >= 4 && nal[2] == 0.toByte() && nal[3] == 1.toByte()) 4 else 3
        when (nal[start].toInt() and 0x1f) {
            7, 8 -> configUnits += nal
            5 -> if (key == null) key = nal
            1 -> if (delta == null) delta = nal
        }
    }
    return H264Fixture(
        config = configUnits.reduce { acc, bytes2 -> acc + bytes2 },
        key = requireNotNull(key) { "fixture encode did not produce a key frame" },
        delta = delta ?: requireNotNull(key),
    )
}

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
