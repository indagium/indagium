@file:Suppress("MagicNumber") // Fixture sizes and timestamps, not tunable constants.

package com.indagium.testing.authoring

import com.indagium.capture.mirror.MirrorVideoPacket
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertTrue

// A real, small H.264 recording (five flat-colour scenes) for the tests of stored recording evidence: finished like the one a mirror
// recording produces, so saving copies real segment files and reopening decodes real frames.

internal const val VIDEO_FIXTURE_BASE_WALL_MS = 1_800_000_000_000L
private const val BASE_NANOS = 100_000_000_000L
private const val NANOS_PER_MS = 1_000_000L
private const val WIDTH = 240
private const val HEIGHT = 400
private val SCENE_COLORS = listOf(Color(37, 52, 74), Color(155, 42, 37), Color(49, 71, 61), Color(42, 61, 96), Color(37, 54, 85))

/** The first scene is a launcher frame that predates pressing Record by a minute (no packet arrives during that static time). */
private val SCENE_OFFSETS_MS = listOf(-60_000L, 100L, 250L, 400L, 800L)

/** The colour of scene [index]; the colours differ, so a decoded frame tells which scene it is. */
internal fun fixtureSceneColor(index: Int): Int = SCENE_COLORS[index].rgb

/** A finished timeline of the five scenes at -60 s, 100, 250, 400 and 800 ms of a session that started at [VIDEO_FIXTURE_BASE_WALL_MS]. */
internal fun finishedFixtureTimeline(): RecordedVideoTimeline {
    val wall = AtomicLong(VIDEO_FIXTURE_BASE_WALL_MS)
    val monotonic = AtomicLong(BASE_NANOS)
    val timeline = RecordedVideoTimeline(wallClockMs = wall::get, monotonicNanos = monotonic::get)
    val (config, frames) = encodedScenes
    timeline.offer(packet(config, SCENE_OFFSETS_MS.first(), config = true))
    frames.forEachIndexed { index, frame ->
        timeline.offer(packet(frame.first, SCENE_OFFSETS_MS[index], keyFrame = frame.second, sequence = index + 1L))
    }
    wall.set(VIDEO_FIXTURE_BASE_WALL_MS + 1_000)
    monotonic.set(BASE_NANOS + 1_000 * NANOS_PER_MS)
    timeline.stop()
    assertTrue(timeline.awaitFinished(15_000), "video remux worker should drain")
    return timeline
}

private fun packet(bytes: ByteArray, elapsedMs: Long, config: Boolean = false, keyFrame: Boolean = false, sequence: Long = 0) = MirrorVideoPacket(
    sourcePtsUs = sequence * 100_000L,
    config = config,
    keyFrame = keyFrame,
    data = bytes,
    width = WIDTH,
    height = HEIGHT,
    receivedAtMs = VIDEO_FIXTURE_BASE_WALL_MS + elapsedMs,
    receivedAtNanos = BASE_NANOS + elapsedMs * NANOS_PER_MS,
    connectionEpoch = 1,
    sequence = sequence,
)

private val encodedScenes: Pair<ByteArray, List<Pair<ByteArray, Boolean>>> by lazy {
    val file = Files.createTempFile("stored-video-fixture-", ".h264").toFile()
    val recorder = FFmpegFrameRecorder(file, WIDTH, HEIGHT, 0).apply {
        format = "h264"
        frameRate = 10.0
        videoCodec = AV_CODEC_ID_H264
        videoCodecName = "libopenh264"
        videoBitrate = 500_000
        gopSize = 1
    }
    recorder.start()
    try {
        SCENE_COLORS.forEachIndexed { index, color ->
            val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB).also { picture ->
                val graphics = picture.createGraphics()
                graphics.color = color
                graphics.fillRect(0, 0, WIDTH, HEIGHT)
                graphics.dispose()
            }
            val pixels = ByteBuffer.allocate(WIDTH * HEIGHT * 3)
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                val rgb = image.getRGB(x, y)
                pixels.put((rgb and 0xff).toByte())
                pixels.put((rgb shr 8 and 0xff).toByte())
                pixels.put((rgb shr 16 and 0xff).toByte())
            }
            pixels.flip()
            recorder.timestamp = index * 100_000L
            recorder.recordImage(WIDTH, HEIGHT, 8, 3, WIDTH * 3, AV_PIX_FMT_BGR24, pixels)
        }
    } finally {
        recorder.stop()
        recorder.release()
    }
    val units = splitNalUnits(file.readBytes())
    file.delete()
    val config = units.filter { nalType(it) in setOf(7, 8) }.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
    val frames = units.mapNotNull { unit ->
        val type = nalType(unit)
        if (type == 5 || type == 1) unit to (type == 5) else null
    }.take(SCENE_COLORS.size)
    assertTrue(config.isNotEmpty() && frames.size == SCENE_COLORS.size, "the fixture keeps one decodable frame per scene")
    config to frames
}

private fun nalType(bytes: ByteArray): Int {
    val start = if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0, 0, 0, 1))) 4 else 3
    return bytes[start].toInt() and 0x1f
}

private fun splitNalUnits(bytes: ByteArray): List<ByteArray> {
    val starts = mutableListOf<Int>()
    var index = 0
    while (index + 3 < bytes.size) {
        val four = bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()
        val three = !four && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 1.toByte()
        if (four || three) {
            starts += index
            index += if (four) 4 else 3
        } else {
            index++
        }
    }
    return starts.indices.map { position -> bytes.copyOfRange(starts[position], starts.getOrNull(position + 1) ?: bytes.size) }
}
