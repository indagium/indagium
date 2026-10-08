@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.indagium.testing.authoring

import com.indagium.capture.StreamingMkvWriter
import com.indagium.capture.mirror.MirrorVideoPacket
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

data class RecordedVideoTimelineSummary(
    val available: Boolean,
    val finished: Boolean,
    val alignment: String,
    val durationMs: Long,
    val packetCount: Long,
    val gapCount: Int,
    val capReached: Boolean,
    val warning: String?,
)

internal data class RecordedVideoFrameInfo(
    val requestedMs: Long,
    val actualMs: Long,
    val relation: String,
    val inputNumber: Int?,
    val ageMs: Long,
)

internal data class RecordedVideoStoryboard(
    val imageBase64: String,
    val mimeType: String,
    val frames: List<RecordedVideoFrameInfo>,
    val coveredInputs: List<Int>,
    val message: String,
)

/**
 * Bounded remux of H.264 packets from the mirror's existing stream. It uses packet receipt as an
 * approximate host-time anchor, stores source PTS separately, and starts each reconnect/config
 * epoch in a separate MKV segment so timestamps and dimensions are never silently mixed.
 */
internal class RecordedVideoTimeline(
    private val maxDurationMs: Long = MAX_DURATION_MS,
    private val maxBytes: Long = MAX_VIDEO_BYTES,
    private val maxQueueBytes: Long = MAX_QUEUE_BYTES,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : Closeable {
    private sealed interface Work {
        data class Packet(val packet: MirrorVideoPacket) : Work
    }

    private data class Segment(
        val file: File,
        val epoch: Long,
        val startElapsedMs: Long,
        val startAtNanos: Long,
        var endElapsedMs: Long,
        val packetMappings: MutableList<PacketTimestampMapping> = ArrayList(),
    )

    private data class PacketTimestampMapping(
        val segmentPtsUs: Long,
        val sessionElapsedMs: Long,
        val receivedAtMs: Long,
        val receivedAtNanos: Long,
        val sourcePtsUs: Long,
        val keyFrame: Boolean,
    )

    private data class FrameRequest(
        val requestedMs: Long,
        val relation: String,
        val inputNumber: Int? = null,
        val inputStartMs: Long? = null,
        val inputEndMs: Long? = null,
        val previousEndMs: Long? = null,
        val nextStartMs: Long? = null,
        val preferPredecessor: Boolean = false,
    )

    private data class DecodedFrame(
        val image: BufferedImage,
        val requestedMs: Long,
        val actualMs: Long,
        val relation: String,
        val inputNumber: Int?,
        val ageMs: Long,
    )

    private data class SelectedFrame(val ptsUs: Long, val sessionMs: Long)

    internal val storageDirectory = Files.createTempDirectory("indagium-recording-video-").toFile()
    private val sessionStartedAtMs = wallClockMs()
    private val sessionStartedAtNanos = monotonicNanos()
    private val indexFile = File(storageDirectory, "timeline.jsonl")
    private val queue = ArrayBlockingQueue<Work>(MAX_QUEUE_PACKETS)
    private val queuedBytes = AtomicLong(0)
    private val accepting = AtomicBoolean(true)
    private val stopRequested = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val deleteRequested = AtomicBoolean(false)
    private val cleanupStarted = AtomicBoolean(false)
    private val writtenPackets = AtomicLong(0)
    private val stoppedDurationMs = AtomicLong(-1L)
    private val stoppedWallClockMs = AtomicLong(-1L)
    private val gapCounter = AtomicLong(0)
    private val storyboardLock = Any()

    @Volatile private var warning: String? = null

    @Volatile private var lastGlobalElapsedMs = 0L

    // Writer state is touched only by [worker].
    private val segments = ArrayList<Segment>()
    private var currentWriter: StreamingMkvWriter? = null
    private var currentIndex: BufferedWriter? = null
    private var currentSegment: Segment? = null
    private var pendingConfig: ByteArray? = null
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var waitingForKeyFrame = true
    private var segmentNumber = 0
    private var writtenBytes = 0L
    private var lastSegmentPtsUs = -1L
    private var lastPacketAtNanos: Long? = null
    private var lastConnectionEpoch: Long? = null
    private val capped = AtomicBoolean(false)

    private val worker = Thread(::writePackets, "recorded-video-timeline").apply { isDaemon = true; start() }

    /** Called on the stream pump. It only reserves/enqueues bounded memory and never does disk or decoder work. */
    fun offer(packet: MirrorVideoPacket) {
        if (!accepting.get() || finished.get()) return
        val pending = queuedBytes.addAndGet(packet.data.size.toLong())
        if (pending > maxQueueBytes || queue.remainingCapacity() == 0 || !queue.offer(Work.Packet(packet))) {
            queuedBytes.addAndGet(-packet.data.size.toLong())
            capped.set(true)
            stopWithWarning("Video timeline stopped at its bounded packet queue limit; earlier frames remain available.")
        }
    }

    fun stop() {
        accepting.set(false)
        stopRequested.set(true)
        freezeDuration()
    }

    fun awaitFinished(timeoutMs: Long = DRAIN_TIMEOUT_MS): Boolean {
        if (Thread.currentThread() !== worker) worker.join(timeoutMs)
        return finished.get()
    }

    fun summary(): RecordedVideoTimelineSummary {
        val hasSegment = finished.get() && segments.any { it.file.isFile && it.file.length() > 0L }
        return RecordedVideoTimelineSummary(
            available = hasSegment,
            finished = finished.get(),
            alignment = "Approximate host packet-receipt alignment; original scrcpy source PTS is retained separately. Pre-roll may predate session time zero.",
            durationMs = stoppedDurationMs.get().takeIf { it >= 0L }
                ?: ((monotonicNanos() - sessionStartedAtNanos).coerceAtLeast(0L) / 1_000_000L).coerceAtMost(maxDurationMs),
            packetCount = writtenPackets.get(),
            gapCount = gapCounter.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            capReached = capped.get(),
            warning = warning,
        )
    }

    /** Approximate host-time offset in the session timeline for a recorded input wall-clock timestamp. */
    fun sessionOffsetMs(wallClockTimestampMs: Long): Long = wallClockTimestampMs - sessionStartedAtMs

    /** Five decoded frames around one input; actual timestamps are checked against its input interval and neighbours. */
    fun storyboardForInput(inputNumber: Int, inputs: List<RewriteInput>): RecordedVideoStoryboard? {
        if (!finished.get()) return null
        val index = inputNumber - 1
        val row = inputs.getOrNull(index)?.row ?: return null
        val sensitive = sensitiveIntervals(inputs)
        if (sensitive.any { row.inputAtMs in it || row.inputStartMs in it }) return null
        val duration = summary().durationMs
        val inputStart = row.inputStartMs - sessionStartedAtMs
        val inputEnd = (row.inputAtMs - sessionStartedAtMs).coerceAtLeast(inputStart)
        val previousEnd = previousInputBoundary(inputs, index)
        val nextStart = nextInputBoundary(inputs, index)
        val afterEnd = minOf(duration, inputEnd + MAX_SIDE_WINDOW_MS, nextStart ?: Long.MAX_VALUE)
        val afterRoom = afterEnd - inputEnd
        val requests = buildList {
            // A static screen may produce no packets for minutes; select the latest decoded predecessor at this boundary.
            add(inputFrameRequest(inputNumber, inputStart, inputStart, inputEnd, previousEnd, nextStart, preferPredecessor = true))
            if (previousEnd != null && inputStart - previousEnd > MIN_SIDE_WINDOW_MS) {
                val offset = minOf(BEFORE_SAMPLE_OFFSET_MS, (inputStart - previousEnd) / 2)
                add(inputFrameRequest(inputNumber, inputStart - offset, inputStart, inputEnd, previousEnd, nextStart, preferPredecessor = true))
            }
            addAll(afterFrameRequests(inputNumber, inputStart, inputEnd, previousEnd, nextStart, afterRoom))
        }.distinctBy { it.requestedMs }.filter { it.requestedMs <= duration }
        return createStoryboard(requests, inputs, sensitive, inputNumber)
    }

    private fun inputFrameRequest(
        inputNumber: Int,
        requestedMs: Long,
        inputStart: Long,
        inputEnd: Long,
        previousEnd: Long?,
        nextStart: Long?,
        relation: String = "before",
        preferPredecessor: Boolean = false,
    ) = FrameRequest(
        requestedMs,
        relation,
        inputNumber,
        inputStart,
        inputEnd,
        previousEnd,
        nextStart,
        preferPredecessor,
    )

    private fun afterFrameRequests(
        inputNumber: Int,
        inputStart: Long,
        inputEnd: Long,
        previousEnd: Long?,
        nextStart: Long?,
        afterRoom: Long,
    ): List<FrameRequest> {
        if (afterRoom <= MIN_SIDE_WINDOW_MS) return emptyList()
        return listOf(
            afterFrameRequest(inputNumber, inputEnd + minOf(EARLY_SAMPLE_MS, afterRoom / 4), inputStart, inputEnd, previousEnd, nextStart),
            afterFrameRequest(inputNumber, inputEnd + minOf(MIDDLE_SAMPLE_MS, afterRoom / 2), inputStart, inputEnd, previousEnd, nextStart),
            afterFrameRequest(inputNumber, inputEnd + minOf(LATE_SAMPLE_MS, afterRoom * 3 / 4), inputStart, inputEnd, previousEnd, nextStart),
            afterFrameRequest(inputNumber, inputEnd + minOf(SETTLED_SAMPLE_MS, afterRoom * 7 / 8), inputStart, inputEnd, previousEnd, nextStart),
        )
    }

    private fun afterFrameRequest(
        inputNumber: Int,
        requestedMs: Long,
        inputStart: Long,
        inputEnd: Long,
        previousEnd: Long?,
        nextStart: Long?,
    ) = inputFrameRequest(inputNumber, requestedMs, inputStart, inputEnd, previousEnd, nextStart, relation = "after")

    /** A time range can reveal transitions across inputs; the range is bounded to thirty seconds and six images. */
    fun storyboardForRange(startMs: Long, endMs: Long, inputs: List<RewriteInput>): RecordedVideoStoryboard? {
        if (!finished.get() || startMs < 0 || endMs <= startMs || endMs - startMs > MAX_STORYBOARD_RANGE_MS) return null
        val duration = summary().durationMs
        val from = startMs.coerceAtMost(duration)
        val to = endMs.coerceAtMost(duration)
        if (to <= from) return null
        val span = to - from
        val requests = (0 until RANGE_FRAME_COUNT).map { index ->
            val time = if (RANGE_FRAME_COUNT == 1) from else from + span * index / (RANGE_FRAME_COUNT - 1)
            // Include the held screen at the range start (for example a static launcher that produced no packets near a tap).
            FrameRequest(time, "range", preferPredecessor = index == 0)
        }.distinctBy { it.requestedMs }
        return createStoryboard(requests, inputs, sensitiveIntervals(inputs), null)
    }

    private fun createStoryboard(
        requests: List<FrameRequest>,
        inputs: List<RewriteInput>,
        sensitive: List<LongRange>,
        inputNumber: Int?,
    ): RecordedVideoStoryboard? {
        if (!finished.get() || requests.isEmpty()) return null
        val decoded = synchronized(storyboardLock) { decodeFrames(requests, sensitive) } ?: return null
        if (decoded.isEmpty()) return null
        val attributed = attributeRangeFrames(decoded, inputs)
        val image = composeContactSheet(attributed)
        val output = java.io.ByteArrayOutputStream()
        if (!ImageIO.write(image, "png", output)) return null
        val coverage = coveredInputs(decoded, inputs)
        return RecordedVideoStoryboard(
            imageBase64 = Base64.getEncoder().encodeToString(output.toByteArray()),
            mimeType = "image/png",
            frames = attributed.map { RecordedVideoFrameInfo(it.requestedMs, it.actualMs, it.relation, it.inputNumber, it.ageMs) },
            coveredInputs = coverage,
            message = buildString {
                append("Decoded frames are labelled with actual MKV timestamps. Alignment to input wall-clock times is approximate packet-receipt mapping.")
                inputNumber?.let { append(" Input $it is marked; held predecessors show their actual age.") }
                if (sensitive.isNotEmpty()) append(" Password-sensitive time windows were excluded.")
                val timeline = summary()
                if (timeline.gapCount > 0) append(" The recorded stream contains ${timeline.gapCount} reconnect or timing gap(s).")
            },
        )
    }

    private fun decodeFrames(requests: List<FrameRequest>, sensitive: List<LongRange>): List<DecodedFrame>? = runCatching {
        val availableSegments = segments.toList()
        requests.take(MAX_STORYBOARD_FRAMES)
            .mapNotNull { request -> decodeRequest(request, availableSegments, sensitive) }
            .distinctBy { it.actualMs to it.inputNumber }
    }.getOrNull()

    private fun decodeRequest(
        request: FrameRequest,
        availableSegments: List<Segment>,
        sensitive: List<LongRange>,
    ): DecodedFrame? {
        val segment = segmentForTime(availableSegments, request.requestedMs, request.preferPredecessor) ?: return null
        val localTargetMs = (request.requestedMs - segment.startElapsedMs).coerceAtLeast(0L)
        val seekPtsUs = seekKeyframe(segment, localTargetMs)
        val selected = selectFrame(segment, seekPtsUs, localTargetMs, request) ?: return null
        val wallMs = sessionStartedAtMs + selected.sessionMs
        if (sensitive.any { wallMs in it }) return null
        val relation = actualRelation(request, selected.sessionMs) ?: return null
        val image = copySelectedFrame(segment, seekPtsUs, localTargetMs, selected.ptsUs) ?: return null
        val ageMs = when (relation) {
            "before" -> request.requestedMs - selected.sessionMs
            else -> selected.sessionMs - request.requestedMs
        }
        return DecodedFrame(
            image,
            request.requestedMs,
            selected.sessionMs,
            relation,
            request.inputNumber,
            ageMs.coerceAtLeast(0L),
        )
    }

    private fun seekKeyframe(segment: Segment, localTargetMs: Long): Long =
        segment.packetMappings.asSequence()
            .filter { it.keyFrame && it.segmentPtsUs / MICROS_PER_MILLI <= localTargetMs }
            .maxOfOrNull { it.segmentPtsUs }
            ?: segment.packetMappings.firstOrNull { it.keyFrame }?.segmentPtsUs
            ?: 0L

    private fun selectFrame(
        segment: Segment,
        seekPtsUs: Long,
        localTargetMs: Long,
        request: FrameRequest,
    ): SelectedFrame? {
        val grabber = FFmpegFrameGrabber(segment.file).apply { setVideoOption("threads", "2") }
        return try {
            grabber.start(false)
            grabber.setTimestamp(seekPtsUs)
            var selected: SelectedFrame? = null
            var count = 0
            var scanning = true
            while (scanning && count < MAX_DECODED_FRAMES_PER_REQUEST) {
                val frame = grabber.grabImage()
                if (frame == null) {
                    scanning = false
                } else {
                    count++
                    val actualUs = frameTimestamp(frame, grabber, localTargetMs)
                    if (actualUs >= 0L) {
                        val actualMs = mappedTimestamp(segment, actualUs)
                        if (request.selects(actualMs)) selected = SelectedFrame(actualUs, actualMs)
                        if (request.finishedScanning(actualMs, selected != null)) scanning = false
                    }
                }
            }
            selected
        } finally {
            closeGrabber(grabber)
        }
    }

    private fun copySelectedFrame(segment: Segment, seekPtsUs: Long, localTargetMs: Long, selectedPtsUs: Long): BufferedImage? {
        val grabber = FFmpegFrameGrabber(segment.file).apply { setVideoOption("threads", "2") }
        return try {
            grabber.start(false)
            grabber.setTimestamp(seekPtsUs)
            val converter = Java2DFrameConverter()
            var selected: BufferedImage? = null
            var count = 0
            var scanning = true
            while (scanning && count < MAX_DECODED_FRAMES_PER_REQUEST) {
                val frame = grabber.grabImage()
                if (frame == null) {
                    scanning = false
                } else {
                    count++
                    val actualUs = frameTimestamp(frame, grabber, localTargetMs)
                    if (actualUs == selectedPtsUs) {
                        selected = converter.convert(frame)?.let(::copyImage)
                        scanning = false
                    } else if (actualUs > selectedPtsUs) {
                        scanning = false
                    }
                }
            }
            selected
        } finally {
            closeGrabber(grabber)
        }
    }

    private fun frameTimestamp(frame: Frame, grabber: FFmpegFrameGrabber, localTargetMs: Long): Long =
        frame.timestamp.takeIf { it > 0L || localTargetMs == 0L } ?: grabber.timestamp

    private fun mappedTimestamp(segment: Segment, ptsUs: Long): Long =
        nearestMapping(segment.packetMappings, ptsUs)?.sessionElapsedMs
            ?: (segment.startElapsedMs + ptsUs / MICROS_PER_MILLI)

    private fun closeGrabber(grabber: FFmpegFrameGrabber) {
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
    }

    private fun FrameRequest.selects(actualMs: Long): Boolean = when {
        preferPredecessor -> actualMs <= requestedMs
        actualMs >= requestedMs -> true
        else -> false
    }

    private fun FrameRequest.finishedScanning(actualMs: Long, hasSelection: Boolean): Boolean = when {
        preferPredecessor && actualMs > requestedMs -> true
        !preferPredecessor && actualMs >= requestedMs -> true
        relation == "range" && hasSelection -> true
        else -> false
    }

    private fun segmentForTime(available: List<Segment>, requestedMs: Long, preferPredecessor: Boolean): Segment? {
        val containing = available.firstOrNull { requestedMs in it.startElapsedMs..it.endElapsedMs }
        if (containing != null) return containing
        if (preferPredecessor) return available.filter { it.startElapsedMs <= requestedMs }.maxByOrNull { it.endElapsedMs }
        return available.minByOrNull { segment ->
            when {
                requestedMs < segment.startElapsedMs -> segment.startElapsedMs - requestedMs
                else -> requestedMs - segment.endElapsedMs
            }
        }?.takeIf { segment ->
            val distance = if (requestedMs < segment.startElapsedMs) segment.startElapsedMs - requestedMs else requestedMs - segment.endElapsedMs
            distance <= SEGMENT_EDGE_TOLERANCE_MS
        }
    }

    private fun actualRelation(request: FrameRequest, actualMs: Long): String? {
        if (request.inputNumber == null) return request.relation
        val inputStart = request.inputStartMs ?: return null
        val inputEnd = request.inputEndMs ?: return null
        val previousEnd = request.previousEndMs
        val nextStart = request.nextStartMs
        return when {
            actualMs < inputStart && (previousEnd == null || actualMs > previousEnd) && (nextStart == null || actualMs < nextStart) -> "before"
            actualMs > inputEnd && (nextStart == null || actualMs < nextStart) && (previousEnd == null || actualMs > previousEnd) -> "after"
            actualMs in inputStart..inputEnd -> "during"
            else -> null
        }
    }

    private fun copyImage(source: BufferedImage): BufferedImage =
        BufferedImage(source.width, source.height, BufferedImage.TYPE_3BYTE_BGR).also { copy ->
            val graphics = copy.createGraphics()
            try {
                graphics.drawImage(source, 0, 0, null)
            } finally {
                graphics.dispose()
            }
        }

    /** packetMappings are appended in strictly increasing mux-PTS order. */
    private fun nearestMapping(mappings: List<PacketTimestampMapping>, ptsUs: Long): PacketTimestampMapping? {
        if (mappings.isEmpty()) return null
        var low = 0
        var high = mappings.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidate = mappings[middle].segmentPtsUs
            when {
                candidate < ptsUs -> low = middle + 1
                candidate > ptsUs -> high = middle - 1
                else -> return mappings[middle]
            }
        }
        val before = mappings.getOrNull(high)
        val after = mappings.getOrNull(low)
        return when {
            before == null -> after
            after == null -> before
            ptsUs - before.segmentPtsUs <= after.segmentPtsUs - ptsUs -> before
            else -> after
        }
    }

    private fun previousInputBoundary(inputs: List<RewriteInput>, index: Int): Long? =
        inputs.take(index).maxOfOrNull { it.row.inputAtMs - sessionStartedAtMs }

    private fun nextInputBoundary(inputs: List<RewriteInput>, index: Int): Long? =
        inputs.drop(index + 1).minOfOrNull { it.row.inputStartMs - sessionStartedAtMs }

    private fun attributeRangeFrames(frames: List<DecodedFrame>, inputs: List<RewriteInput>): List<DecodedFrame> = frames.map { frame ->
        if (frame.inputNumber != null) return@map frame
        val owner = inputs.indices.firstNotNullOfOrNull { index ->
            val input = inputs[index]
            val start = input.row.inputStartMs - sessionStartedAtMs
            val end = input.row.inputAtMs - sessionStartedAtMs
            frameOwner(index, frame.actualMs, start, end, previousInputBoundary(inputs, index), nextInputBoundary(inputs, index))
        }
        owner?.let { (number, relation, age) -> frame.copy(relation = relation, inputNumber = number, ageMs = age) } ?: frame
    }

    private fun frameOwner(index: Int, frameMs: Long, start: Long, end: Long, previousEnd: Long?, nextStart: Long?): Triple<Int, String, Long>? {
        val afterPrevious = previousEnd == null || frameMs > previousEnd
        val beforeNext = nextStart == null || frameMs < nextStart
        return when {
            frameMs < start && afterPrevious && beforeNext -> Triple(index + 1, "before", start - frameMs)
            frameMs > end && afterPrevious && beforeNext -> Triple(index + 1, "after", frameMs - end)
            frameMs in start..end -> Triple(index + 1, "during", 0L)
            else -> null
        }
    }

    private fun coveredInputs(frames: List<DecodedFrame>, inputs: List<RewriteInput>): List<Int> = inputs.mapIndexedNotNull { index, input ->
        val start = input.row.inputStartMs - sessionStartedAtMs
        val end = input.row.inputAtMs - sessionStartedAtMs
        val previousEnd = previousInputBoundary(inputs, index)
        val nextStart = nextInputBoundary(inputs, index)
        val before = frames.any { frame ->
            frame.actualMs < start && (previousEnd == null || frame.actualMs > previousEnd) && (nextStart == null || frame.actualMs < nextStart)
        }
        val after = frames.any { frame ->
            frame.actualMs > end && (nextStart == null || frame.actualMs < nextStart) && (previousEnd == null || frame.actualMs > previousEnd)
        }
        (index + 1).takeIf { before && after }
    }

    private fun composeContactSheet(frames: List<DecodedFrame>): BufferedImage {
        val columns = 3
        val rows = (frames.size + columns - 1) / columns
        val cellWidth = 480
        val imageHeight = 800
        val labelHeight = 42
        val sheet = BufferedImage(columns * cellWidth, rows * (imageHeight + labelHeight), BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        graphics.color = Color(17, 21, 29)
        graphics.fillRect(0, 0, sheet.width, sheet.height)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        frames.forEachIndexed { index, frame ->
            val x = index % columns * cellWidth
            val y = index / columns * (imageHeight + labelHeight)
            val source = frame.image
            val scale = minOf(cellWidth.toDouble() / source.width, imageHeight.toDouble() / source.height)
            val width = (source.width * scale).toInt()
            val height = (source.height * scale).toInt()
            graphics.drawImage(source, x + (cellWidth - width) / 2, y + (imageHeight - height) / 2, width, height, null)
            graphics.color = when (frame.relation) {
                "after" -> Color(127, 205, 160)
                "before" -> Color(235, 202, 111)
                else -> Color(185, 190, 205)
            }
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
            val age = if (frame.relation == "before" && frame.ageMs > 0L) " • held ${frame.ageMs} ms" else ""
            val label = "actual ${frame.actualMs} ms • ${frame.inputNumber?.let { "input $it " }.orEmpty()}${frame.relation}$age"
            graphics.drawString(label, x + 10, y + imageHeight + 27)
        }
        graphics.dispose()
        return sheet
    }

    private fun sensitiveIntervals(inputs: List<RewriteInput>): List<LongRange> {
        val passwordInputs = inputs.indices.filter { index ->
            val input = inputs[index]
            input.row.action.contains("••••") || input.before?.passwordFieldLikelyEdited() == true || input.after?.passwordFieldLikelyEdited() == true
        }
        if (passwordInputs.isEmpty()) return emptyList()
        val lastSensitiveAt = passwordInputs.maxOf { inputs[it].row.inputAtMs }
        val passwordScreens = passwordInputs.flatMap { index ->
            listOfNotNull(inputs[index].before, inputs[index].after)
                .filter(RecordingScreenState::passwordFieldLikelyEdited)
                .map { it.packageName to it.activity }
        }.toSet()
        val safeExit = inputs.asSequence().flatMap { input -> sequenceOf(input.before, input.after) }
            .filterNotNull()
            .filter { state ->
                val location = state.packageName to state.activity
                state.finishedAt >= lastSensitiveAt && state.nodes.isNotEmpty() && state.nodes.none { it.password } &&
                    location.first != null && location.second != null && location !in passwordScreens
            }
            .minOfOrNull { it.finishedAt }
        // Redact pre-roll too: a held mirror frame can predate Record while the same password screen remains visible.
        // If the recorder never proves that transition, withhold video through the end of the session.
        val recordingEnd = stoppedWallClockMs.get().takeIf { it >= 0L } ?: Long.MAX_VALUE
        return listOf(Long.MIN_VALUE..(safeExit ?: recordingEnd))
    }

    private fun writePackets() {
        try {
            while (true) {
                val item = queue.poll(100, TimeUnit.MILLISECONDS)
                if (item == null) {
                    if (stopRequested.get() && queue.isEmpty()) break
                    continue
                }
                val packet = (item as Work.Packet).packet
                queuedBytes.addAndGet(-packet.data.size.toLong())
                processPacket(packet)
            }
        } catch (failure: Throwable) {
            stopWithWarning("Video timeline writer stopped: ${failure.message ?: failure::class.simpleName}.")
        } finally {
            finishSegment()
            if (segments.isEmpty() && warning == null) {
                warning = "No usable initial H.264 configuration and key frame were captured; use available screen snapshots."
            }
            finished.set(true)
            if (deleteRequested.get()) scheduleCleanup()
        }
    }

    private fun processPacket(packet: MirrorVideoPacket) {
        if (capped.get()) return
        prepareEpoch(packet)
        recordPacketGap(packet)
        lastPacketAtNanos = packet.receivedAtNanos
        lastConnectionEpoch = packet.connectionEpoch
        if (packet.config) {
            acceptConfiguration(packet)
            return
        }
        if (needsNewSegment(packet) && !startSegment(packet)) return
        writePacket(packet)
    }

    private fun prepareEpoch(packet: MirrorVideoPacket) {
        val previousEpoch = lastConnectionEpoch
        if (previousEpoch != null && previousEpoch != packet.connectionEpoch) {
            gapCounter.incrementAndGet()
            finishSegment()
            pendingConfig = null
            pendingWidth = 0
            pendingHeight = 0
            waitingForKeyFrame = true
        }
    }

    private fun recordPacketGap(packet: MirrorVideoPacket) {
        val previousReceipt = lastPacketAtNanos
        if (previousReceipt != null && packet.receivedAtNanos - previousReceipt > MAX_GAP_NANOS) gapCounter.incrementAndGet()
    }

    private fun acceptConfiguration(packet: MirrorVideoPacket) {
        if (currentSegment != null) {
            gapCounter.incrementAndGet()
            finishSegment()
        }
        pendingConfig = packet.data
        pendingWidth = packet.width
        pendingHeight = packet.height
        waitingForKeyFrame = true
    }

    private fun needsNewSegment(packet: MirrorVideoPacket): Boolean =
        packet.keyFrame && (currentSegment == null || waitingForKeyFrame)

    private fun startSegment(packet: MirrorVideoPacket): Boolean {
        val config = pendingConfig ?: return false
        if (pendingWidth <= 0 || pendingHeight <= 0) return false
        val elapsed = sessionElapsedMs(packet.receivedAtNanos)
        if (exceedsTimelineLimits(elapsed, 0)) {
            stopAtLimit("Video timeline reached its duration or file-size limit; input recording continued.")
            return false
        }
        val file = File(storageDirectory, "timeline-${++segmentNumber}.mkv")
        currentWriter = StreamingMkvWriter(file).also { it.start(pendingWidth, pendingHeight, config) }
        currentIndex = FileWriter(indexFile, true).buffered()
        currentSegment = Segment(file, packet.connectionEpoch, elapsed, packet.receivedAtNanos, elapsed)
        segments += requireNotNull(currentSegment)
        waitingForKeyFrame = false
        lastSegmentPtsUs = -1L
        return true
    }

    private fun writePacket(packet: MirrorVideoPacket) {
        val activeWriter = currentWriter ?: return
        val segment = currentSegment ?: return
        val globalElapsed = sessionElapsedMs(packet.receivedAtNanos)
        val segmentPtsUs = ((packet.receivedAtNanos - segment.startAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLI) * MICROS_PER_MILLI
        val safePtsUs = maxOf(segmentPtsUs, lastSegmentPtsUs + MICROS_PER_MILLI).coerceAtLeast(0L)
        val payload = if (packet.keyFrame) pendingConfig?.plus(packet.data) ?: packet.data else packet.data
        if (exceedsTimelineLimits(globalElapsed, payload.size)) {
            stopAtLimit("Video timeline reached its duration, file-size, or packet-index limit; input recording continued.")
            return
        }
        activeWriter.writeVideoPacket(safePtsUs, packet.keyFrame, payload)
        lastSegmentPtsUs = safePtsUs
        segment.endElapsedMs = maxOf(segment.endElapsedMs, globalElapsed)
        lastGlobalElapsedMs = maxOf(lastGlobalElapsedMs, globalElapsed.coerceAtLeast(0L))
        segment.packetMappings += PacketTimestampMapping(
            safePtsUs,
            globalElapsed,
            packet.receivedAtMs,
            packet.receivedAtNanos,
            packet.sourcePtsUs,
            packet.keyFrame,
        )
        writtenBytes += payload.size
        writtenPackets.incrementAndGet()
        writeIndex(packet, segment, safePtsUs, globalElapsed)
        if (packet.keyFrame) pendingConfig = null
    }

    private fun exceedsTimelineLimits(elapsedMs: Long, nextPacketBytes: Int): Boolean =
        elapsedMs > maxDurationMs || writtenBytes + nextPacketBytes > maxBytes ||
            writtenPackets.get() >= MAX_WRITTEN_PACKETS

    private fun stopAtLimit(message: String) {
        capped.set(true)
        stopWithWarning(message)
    }

    private fun writeIndex(packet: MirrorVideoPacket, segment: Segment, segmentPtsUs: Long, globalElapsedMs: Long) {
        val entry = buildString {
            append("{\"segment\":$segmentNumber,\"segmentStartElapsedMs\":${segment.startElapsedMs},")
            append("\"segmentPtsUs\":$segmentPtsUs,\"sessionElapsedMs\":$globalElapsedMs,")
            append("\"sourcePtsUs\":${packet.sourcePtsUs},\"receivedAtMs\":${packet.receivedAtMs},")
            append("\"receivedAtNanos\":${packet.receivedAtNanos},\"connectionEpoch\":${packet.connectionEpoch},")
            append("\"sequence\":${packet.sequence},\"width\":${packet.width},\"height\":${packet.height},")
            append("\"keyFrame\":${packet.keyFrame}}\n")
        }
        currentIndex?.append(entry)
    }

    private fun sessionElapsedMs(receivedAtNanos: Long): Long = (receivedAtNanos - sessionStartedAtNanos) / 1_000_000L

    private fun freezeDuration() {
        val elapsed = ((monotonicNanos() - sessionStartedAtNanos).coerceAtLeast(0L) / 1_000_000L).coerceAtMost(maxDurationMs)
        stoppedDurationMs.compareAndSet(-1L, maxOf(elapsed, lastGlobalElapsedMs))
        stoppedWallClockMs.compareAndSet(-1L, wallClockMs())
    }

    private fun finishSegment() {
        runCatching { currentWriter?.finish() }.onFailure { failure ->
            stopWithWarning("A recorded video segment could not be finalized: ${failure.message ?: failure::class.simpleName}.")
        }
        runCatching { currentIndex?.flush() }
        runCatching { currentIndex?.close() }
        currentWriter = null
        currentIndex = null
        currentSegment = null
        lastSegmentPtsUs = -1L
    }

    private fun stopWithWarning(message: String) {
        if (warning == null) warning = message
        accepting.set(false)
        stopRequested.set(true)
        freezeDuration()
    }

    /** Close is called on UI/discard paths; signal and defer file deletion without waiting for writer/decoder work. */
    override fun close() {
        stop()
        deleteRequested.set(true)
        if (finished.get()) scheduleCleanup()
    }

    private fun scheduleCleanup() {
        if (!cleanupStarted.compareAndSet(false, true)) return
        Thread({
            runCatching { worker.join() }
            synchronized(storyboardLock) { storageDirectory.deleteRecursively() }
        }, "recorded-video-cleanup").apply { isDaemon = true; start() }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MICROS_PER_MILLI = 1_000L
        const val MAX_DURATION_MS = 10 * 60 * 1_000L
        const val MAX_VIDEO_BYTES = 128L * 1024 * 1024
        const val MAX_QUEUE_BYTES = 8L * 1024 * 1024
        const val MAX_QUEUE_PACKETS = 180
        const val MAX_GAP_NANOS = 1_500_000_000L
        const val MAX_SIDE_WINDOW_MS = 5_000L
        const val MIN_SIDE_WINDOW_MS = 50L
        const val BEFORE_SAMPLE_OFFSET_MS = 450L
        const val EARLY_SAMPLE_MS = 180L
        const val MIDDLE_SAMPLE_MS = 900L
        const val LATE_SAMPLE_MS = 2_000L
        const val SETTLED_SAMPLE_MS = 4_000L
        const val MAX_STORYBOARD_RANGE_MS = 30_000L
        const val RANGE_FRAME_COUNT = 6
        const val MAX_STORYBOARD_FRAMES = 6
        const val MAX_DECODED_FRAMES_PER_REQUEST = 240
        const val MAX_WRITTEN_PACKETS = 40_000L
        const val SEGMENT_EDGE_TOLERANCE_MS = 120L
        const val DRAIN_TIMEOUT_MS = 10_000L
    }
}
