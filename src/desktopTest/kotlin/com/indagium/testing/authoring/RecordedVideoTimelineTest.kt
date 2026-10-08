@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.indagium.testing.authoring

import com.indagium.capture.mirror.MirrorVideoPacket
import com.indagium.debug.toCallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordedVideoTimelineTest {
    @Test
    fun aStaticPreRollAndAppTransitionReachRewriteAsActualImageEvidence() {
        val fixture = syntheticScenes
        val wall = AtomicLong(BASE_WALL_MS)
        val monotonic = AtomicLong(BASE_NANOS)
        val timeline = RecordedVideoTimeline(wallClockMs = wall::get, monotonicNanos = monotonic::get)
        val inputs = inputRows(BASE_WALL_MS)
        try {
            // A launcher frame and its retained config predate pressing Record by a minute. No packets arrive during that
            // static interval; later H.264 frames show YouTube opening, then a paused player and settled state.
            timeline.offer(packet(fixture.config, BASE_WALL_MS - 60_000, BASE_NANOS - 60_000_000_000, 0, config = true))
            fixture.samples.forEachIndexed { index, sample ->
                val elapsed = listOf(-60_000L, 100L, 250L, 400L, 800L)[index]
                timeline.offer(
                    packet(
                        sample.data,
                        BASE_WALL_MS + elapsed,
                        BASE_NANOS + elapsed * NANOS_PER_MS,
                        (index + 1).toLong(),
                        keyFrame = sample.keyFrame,
                    ),
                )
            }
            wall.set(BASE_WALL_MS + 1_000)
            monotonic.set(BASE_NANOS + 1_000 * NANOS_PER_MS)
            timeline.stop()
            assertTrue(timeline.awaitFinished(15_000), "video remux worker should drain")
            assertTrue(timeline.summary().available)

            val inputSheet = assertNotNull(timeline.storyboardForInput(1, inputs))
            assertTrue(inputSheet.frames.any { it.relation == "before" && it.actualMs == -60_000L && it.ageMs >= 60_000L }, inputSheet.frames.toString())
            assertTrue(inputSheet.frames.any { it.relation == "after" && it.actualMs == 250L }, inputSheet.frames.toString())

            val tools = RecordingRewriteTools(inputs, timeline)
            val input = tools.gateway.execute("get_recorded_input", mapOf("index" to 2)) as Map<*, *>
            assertEquals(300L, input["videoInputStartMs"])
            assertEquals(350L, input["videoInputEndMs"])

            val range = tools.gateway.execute("get_recorded_video_storyboard", mapOf("startMs" to 0, "endMs" to 900)) as Map<*, *>
            assertTrue(range["imageBase64"].toString().isNotBlank())
            assertEquals(listOf(1, 2), range["coveredInputs"])
            assertEquals("approximate session-relative host packet-receipt mapping", range["timebase"])
            val mcp = toCallToolResult("get_recorded_video_storyboard", range, "unused")
            assertTrue(assertIs<TextContent>(mcp.content.first()).text.contains("actual"))
            assertIs<ImageContent>(mcp.content.last())
            assertFalse("imageBase64" in assertIs<TextContent>(mcp.content.first()).text)

            val proposal = parseRewriteResponse(
                """{"steps":[{"action":"Open YouTube","expected":"The YouTube app is showing","sourceInputs":[1]},""" +
                    """{"action":"Pause playback","expected":"The player shows its paused state","sourceInputs":[2]}]}""",
                inputCount = 2,
            )
            val rows = buildRewrittenRows(proposal, inputs, tools.videoInspectedInputs())
            assertEquals(listOf("Open YouTube", "Pause playback"), rows.map { it.action })
            assertEquals(listOf("The YouTube app is showing", "The player shows its paused state"), rows.map { it.expected })
            assertTrue(rows.all { it.reviewReason == null }, rows.map { it.reviewReason }.toString())

            // Keep one full-size contact sheet for manual layout inspection. This path is ignored by git.
            val artifactDirectory = File("temp_visuals_not_for_git").apply { mkdirs() }
            val artifact = File(artifactDirectory, "recorded-storyboard-static-launcher.png")
            val bytes = Base64.getDecoder().decode(range["imageBase64"] as String)
            assertTrue(ImageIO.write(ImageIO.read(ByteArrayInputStream(bytes)), "png", artifact))
            assertTrue(artifact.length() > 10_000L)
        } finally {
            timeline.close()
        }
    }

    @Test
    fun passwordVideoStaysWithheldAfterTheLastMaskedInputUntilRecordingStops() {
        val fixture = syntheticScenes
        val wall = AtomicLong(BASE_WALL_MS)
        val monotonic = AtomicLong(BASE_NANOS)
        val timeline = RecordedVideoTimeline(wallClockMs = wall::get, monotonicNanos = monotonic::get)
        val input = RewriteInput(
            1,
            RecordedTestStep(action = "Enter text: ••••", kind = RecordedInputKind.TEXT, inputAtMs = BASE_WALL_MS + 100, inputStartMs = BASE_WALL_MS + 100),
            null,
            null,
        )
        try {
            timeline.offer(packet(fixture.config, BASE_WALL_MS, BASE_NANOS, 0, config = true))
            timeline.offer(packet(fixture.samples[0].data, BASE_WALL_MS, BASE_NANOS, 1, keyFrame = fixture.samples[0].keyFrame))
            timeline.offer(packet(fixture.samples[1].data, BASE_WALL_MS + 7_000, BASE_NANOS + 7_000 * NANOS_PER_MS, 2, keyFrame = fixture.samples[1].keyFrame))
            wall.set(BASE_WALL_MS + 9_000)
            monotonic.set(BASE_NANOS + 9_000 * NANOS_PER_MS)
            timeline.stop()
            assertTrue(timeline.awaitFinished(15_000))

            assertNull(timeline.storyboardForRange(7_000, 8_500, listOf(input)), "frames after the final password input stay withheld >5s later")
            val tools = RecordingRewriteTools(listOf(input), timeline)
            val denied = tools.gateway.execute("get_recorded_video_storyboard", mapOf("startMs" to 7_000, "endMs" to 8_500)) as Map<*, *>
            assertTrue("No decoded video frames" in denied["error"].toString())
        } finally {
            timeline.close()
        }
    }

    @Test
    fun reconnectStartsANewSegmentAndKeepsEvidenceFromEarlierEpochs() {
        val fixture = syntheticScenes
        val wall = AtomicLong(BASE_WALL_MS)
        val monotonic = AtomicLong(BASE_NANOS)
        val timeline = RecordedVideoTimeline(wallClockMs = wall::get, monotonicNanos = monotonic::get)
        try {
            timeline.offer(packet(fixture.config, BASE_WALL_MS, BASE_NANOS, 0, config = true))
            timeline.offer(packet(fixture.samples[0].data, BASE_WALL_MS, BASE_NANOS, 1, keyFrame = true))
            timeline.offer(packet(fixture.samples[1].data, BASE_WALL_MS + 100, BASE_NANOS + 100 * NANOS_PER_MS, 2, keyFrame = true))
            timeline.offer(packet(fixture.samples[2].data, BASE_WALL_MS + 200, BASE_NANOS + 200 * NANOS_PER_MS, 3, keyFrame = true))
            timeline.offer(
                packet(
                    fixture.config,
                    BASE_WALL_MS + 300,
                    BASE_NANOS + 300 * NANOS_PER_MS,
                    4,
                    config = true,
                    connectionEpoch = 2,
                ),
            )
            timeline.offer(
                packet(
                    fixture.samples[3].data,
                    BASE_WALL_MS + 400,
                    BASE_NANOS + 400 * NANOS_PER_MS,
                    5,
                    keyFrame = true,
                    connectionEpoch = 2,
                ),
            )
            timeline.offer(
                packet(
                    fixture.samples[4].data,
                    BASE_WALL_MS + 500,
                    BASE_NANOS + 500 * NANOS_PER_MS,
                    6,
                    keyFrame = true,
                    connectionEpoch = 2,
                ),
            )
            wall.set(BASE_WALL_MS + 600)
            monotonic.set(BASE_NANOS + 600 * NANOS_PER_MS)
            timeline.stop()
            assertTrue(timeline.awaitFinished(15_000))

            val storyboard = assertNotNull(timeline.storyboardForRange(0, 600, emptyList()))
            assertTrue(storyboard.frames.any { it.actualMs == 200L }, storyboard.frames.toString())
            assertTrue(storyboard.frames.any { it.actualMs == 400L }, storyboard.frames.toString())
            assertTrue(storyboard.frames.any { it.actualMs == 500L }, storyboard.frames.toString())
            assertTrue(timeline.summary().gapCount >= 1)
        } finally {
            timeline.close()
        }
    }

    @Test
    fun equalAndOverlappingInputBoundariesDoNotClaimFalseBeforeAfterCoverage() {
        val fixture = syntheticScenes
        val equalBoundaryRows = listOf(
            row(1, start = 100, end = 200),
            row(2, start = 200, end = 300),
        )
        val overlappedRows = listOf(
            row(1, start = 100, end = 250),
            row(2, start = 200, end = 300),
        )
        for (rows in listOf(equalBoundaryRows, overlappedRows)) {
            val wall = AtomicLong(BASE_WALL_MS)
            val monotonic = AtomicLong(BASE_NANOS)
            val timeline = RecordedVideoTimeline(wallClockMs = wall::get, monotonicNanos = monotonic::get)
            try {
                timeline.offer(packet(fixture.config, BASE_WALL_MS, BASE_NANOS, 0, config = true))
                listOf(0L, 200L, 400L).forEachIndexed { index, elapsed ->
                    timeline.offer(
                        packet(
                            fixture.samples[index].data,
                            BASE_WALL_MS + elapsed,
                            BASE_NANOS + elapsed * NANOS_PER_MS,
                            (index + 1).toLong(),
                            keyFrame = true,
                        ),
                    )
                }
                wall.set(BASE_WALL_MS + 500)
                monotonic.set(BASE_NANOS + 500 * NANOS_PER_MS)
                timeline.stop()
                assertTrue(timeline.awaitFinished(15_000))

                val storyboard = assertNotNull(timeline.storyboardForRange(0, 400, rows))
                assertTrue(storyboard.frames.any { it.actualMs == 200L }, storyboard.frames.toString())
                assertTrue(storyboard.coveredInputs.isEmpty(), "touching/overlapping actions have no proven before+after pair")
            } finally {
                timeline.close()
            }
        }
    }

    @Test
    fun durationAndByteCapsKeepEarlierFramesAndReportTheStoppedTimeline() {
        val fixture = syntheticScenes
        val input = listOf(row(1, start = 20, end = 50))
        val durationWall = AtomicLong(BASE_WALL_MS)
        val durationNanos = AtomicLong(BASE_NANOS)
        val durationLimited = RecordedVideoTimeline(
            maxDurationMs = 150,
            wallClockMs = durationWall::get,
            monotonicNanos = durationNanos::get,
        )
        try {
            offerInitialFrames(durationLimited, fixture)
            durationLimited.offer(packet(fixture.samples[1].data, BASE_WALL_MS + 100, BASE_NANOS + 100 * NANOS_PER_MS, 2, keyFrame = true))
            durationWall.set(BASE_WALL_MS + 200)
            durationNanos.set(BASE_NANOS + 200 * NANOS_PER_MS)
            durationLimited.offer(packet(fixture.samples[2].data, BASE_WALL_MS + 200, BASE_NANOS + 200 * NANOS_PER_MS, 3, keyFrame = true))
            assertTrue(durationLimited.awaitFinished(15_000))
            assertTrue(durationLimited.summary().capReached)
            assertTrue(durationLimited.summary().warning.orEmpty().contains("duration"))
            assertTrue(assertNotNull(durationLimited.storyboardForInput(1, input)).frames.isNotEmpty())
        } finally {
            durationLimited.close()
        }

        val firstPayloadBytes = fixture.config.size + fixture.samples[0].data.size
        val byteLimit = firstPayloadBytes + fixture.samples[1].data.size - 1L
        val byteWall = AtomicLong(BASE_WALL_MS)
        val byteNanos = AtomicLong(BASE_NANOS)
        val byteLimited = RecordedVideoTimeline(
            maxBytes = byteLimit,
            wallClockMs = byteWall::get,
            monotonicNanos = byteNanos::get,
        )
        try {
            offerInitialFrames(byteLimited, fixture)
            byteWall.set(BASE_WALL_MS + 100)
            byteNanos.set(BASE_NANOS + 100 * NANOS_PER_MS)
            byteLimited.offer(packet(fixture.samples[1].data, BASE_WALL_MS + 100, BASE_NANOS + 100 * NANOS_PER_MS, 2, keyFrame = true))
            assertTrue(byteLimited.awaitFinished(15_000))
            assertTrue(byteLimited.summary().capReached)
            assertTrue(byteLimited.summary().warning.orEmpty().contains("file-size"))
            assertTrue(assertNotNull(byteLimited.storyboardForInput(1, input)).frames.isNotEmpty())
        } finally {
            byteLimited.close()
        }
    }

    @Test
    fun closeDoesNotWaitForVideoWorkAndTheWorkerEventuallyDeletesItsTemporaryFiles() {
        val timeline = RecordedVideoTimeline()
        val directory = timeline.storageDirectory
        val before = System.nanoTime()
        timeline.close()
        val elapsedMs = (System.nanoTime() - before) / NANOS_PER_MS

        assertTrue(elapsedMs < CLOSE_NONBLOCKING_LIMIT_MS, "close must only signal the writer and defer cleanup")
        assertTrue(timeline.awaitFinished(5_000))
        val deadline = System.nanoTime() + CLEANUP_WAIT_NS
        while (directory.exists() && System.nanoTime() < deadline) Thread.yield()
        assertFalse(directory.exists(), "temporary video directory should be deleted by the cleanup worker")
    }

    private fun inputRows(base: Long): List<RewriteInput> = listOf(
        RewriteInput(
            1,
            RecordedTestStep(action = "Tap at (50%, 50%)", kind = RecordedInputKind.TAP, inputStartMs = base, inputAtMs = base + 50),
            null,
            null,
        ),
        RewriteInput(
            2,
            RecordedTestStep(action = "Tap at (50%, 50%)", kind = RecordedInputKind.TAP, inputStartMs = base + 300, inputAtMs = base + 350),
            null,
            null,
        ),
    )

    private fun row(number: Int, start: Long, end: Long): RewriteInput = RewriteInput(
        number,
        RecordedTestStep(
            action = "Tap at (50%, 50%)",
            kind = RecordedInputKind.TAP,
            inputStartMs = BASE_WALL_MS + start,
            inputAtMs = BASE_WALL_MS + end,
        ),
        null,
        null,
    )

    private fun offerInitialFrames(timeline: RecordedVideoTimeline, fixture: EncodedScenes) {
        timeline.offer(packet(fixture.config, BASE_WALL_MS, BASE_NANOS, 0, config = true))
        timeline.offer(packet(fixture.samples[0].data, BASE_WALL_MS, BASE_NANOS, 1, keyFrame = true))
    }

    private fun packet(
        bytes: ByteArray,
        receivedAtMs: Long,
        receivedAtNanos: Long,
        sequence: Long,
        config: Boolean = false,
        keyFrame: Boolean = false,
        connectionEpoch: Long = 1,
    ) = MirrorVideoPacket(
        sourcePtsUs = sequence * 100_000L,
        config = config,
        keyFrame = keyFrame,
        data = bytes,
        width = WIDTH,
        height = HEIGHT,
        receivedAtMs = receivedAtMs,
        receivedAtNanos = receivedAtNanos,
        connectionEpoch = connectionEpoch,
        sequence = sequence,
    )

    private data class Sample(val data: ByteArray, val keyFrame: Boolean)

    private data class EncodedScenes(val config: ByteArray, val samples: List<Sample>)

    private companion object {
        const val WIDTH = 240
        const val HEIGHT = 400
        const val BASE_WALL_MS = 1_800_000_000_000L
        const val BASE_NANOS = 100_000_000_000L
        const val NANOS_PER_MS = 1_000_000L
        const val CLOSE_NONBLOCKING_LIMIT_MS = 250L
        const val CLEANUP_WAIT_NS = 3_000_000_000L

        val syntheticScenes: EncodedScenes by lazy(::encodeScenes)

        private fun encodeScenes(): EncodedScenes {
            val scenes = listOf(
                "Launcher" to Color(37, 52, 74),
                "YouTube Home" to Color(155, 42, 37),
                "Video Playing" to Color(49, 71, 61),
                "Video Paused" to Color(42, 61, 96),
                "Paused - settled" to Color(37, 54, 85),
            )
            val file = Files.createTempFile("recorded-video-fixture-", ".h264").toFile()
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
                scenes.forEachIndexed { index, (label, color) ->
                    val image = scene(label, color)
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
                if (type == 5 || type == 1) Sample(unit, type == 5) else null
            }
            assertTrue(config.isNotEmpty())
            assertTrue(frames.size >= scenes.size, "fixture should keep one independently decodable frame per scene")
            return EncodedScenes(config, frames.take(scenes.size))
        }

        private fun scene(title: String, color: Color): BufferedImage = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            graphics.color = color
            graphics.fillRect(0, 0, WIDTH, HEIGHT)
            graphics.color = Color(18, 22, 29)
            graphics.fillRect(0, 0, WIDTH, 44)
            graphics.color = Color.WHITE
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 21)
            graphics.drawString(title, 14, 30)
            graphics.color = Color(20, 24, 30)
            graphics.fillRoundRect(22, 92, WIDTH - 44, 190, 18, 18)
            graphics.color = Color(244, 55, 48)
            graphics.fillOval(WIDTH / 2 - 24, 160, 48, 48)
            graphics.color = Color.WHITE
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
            graphics.drawString(if (title.contains("Paused")) "PAUSED" else "Tap a video", 26, 322)
            graphics.fillRoundRect(18, 350, WIDTH - 36, 34, 8, 8)
            graphics.dispose()
        }

        private fun nalType(bytes: ByteArray): Int {
            val start = if (bytes.startsWithFourBytePrefix()) 4 else 3
            return bytes[start].toInt() and 0x1f
        }

        private fun ByteArray.startsWithFourBytePrefix(): Boolean =
            size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 0.toByte() && this[3] == 1.toByte()

        private fun splitNalUnits(bytes: ByteArray): List<ByteArray> {
            val starts = mutableListOf<Pair<Int, Int>>()
            var index = 0
            while (index + 3 < bytes.size) {
                val four = bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()
                val three = !four && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 1.toByte()
                if (four || three) {
                    starts += index to if (four) 4 else 3
                    index += if (four) 4 else 3
                } else {
                    index++
                }
            }
            return starts.indices.map { position ->
                val start = starts[position].first
                val end = starts.getOrNull(position + 1)?.first ?: bytes.size
                bytes.copyOfRange(start, end)
            }
        }
    }
}
