package com.indagium.capture

import com.indagium.model.LogEntry
import com.indagium.model.NO_DATE
import com.indagium.utils.TS_UNKNOWN
import com.indagium.utils.elapsedMillisOfDay
import com.indagium.utils.logDaySlot
import com.indagium.utils.parseMillisOfDay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.BitSet
import java.util.Locale

/** Persistent layout version used by newly-created captures. Missing in old session.json means v1. */
enum class CaptureStorageVersion(val value: Int) {
    LEGACY_DENSE(1),
    BLOCKS_V2(2),
    ;

    companion object {
        fun from(value: Int?): CaptureStorageVersion = when (value) {
            null -> LEGACY_DENSE
            else -> entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unsupported retained capture storage version $value")
        }
    }
}

enum class CaptureSyncReliability { RELIABLE, UNCERTAIN }

/** Clock fields are a compact, persistent context for resolving rows even when an export omits
 *  the row that supplied the anchor. `anchorDayOfYear == null` is a clock with only time-of-day. */
data class CaptureClockReference(
    val anchorTimeOfDayMs: Long,
    val anchorDayOfYear: Int?,
    val anchorYear: Int,
) {
    init {
        require(anchorTimeOfDayMs in 0 until MILLIS_PER_DAY) { "Invalid capture clock time-of-day" }
        require(anchorDayOfYear == null || anchorDayOfYear in 0..MAX_DAY_OF_YEAR) { "Invalid capture clock day" }
        require(anchorYear in MIN_CAPTURE_CLOCK_YEAR..MAX_CAPTURE_CLOCK_YEAR) { "Invalid capture clock year" }
    }
}

/** User-created anchor for one segment. It survives export cuts even if [rowOrdinal] is omitted. */
data class CaptureSegmentManualAnchor(
    val segmentId: Int,
    val rowOrdinal: Int,
    val clockReference: CaptureClockReference,
    val videoMs: Long,
    val captureElapsedMs: Long? = null,
) {
    init {
        // A child archive can omit the row that supplied a manual anchor. When that reference lies
        // before the exported clip, its rebased video coordinate is negative; it remains valid
        // affine context because resolved rows are still constrained to playable positions.
        require(segmentId >= 0 && rowOrdinal >= 1)
        require(captureElapsedMs == null || captureElapsedMs >= 0L)
    }
}

/** A half-open range of parser row ordinals. The reference row may be outside an exported child cut. */
data class CaptureSyncSegment(
    val id: Int,
    val firstRow: Int,
    val lastRowExclusive: Int,
    val anchorLogTimeMs: Long?,
    val anchorCaptureElapsedMs: Long?,
    val reliability: CaptureSyncReliability,
    val clockReference: CaptureClockReference? = null,
    val manualOffsetMs: Long = 0L,
    val manualAnchor: CaptureSegmentManualAnchor? = null,
) {
    init {
        require(id >= 0) { "Invalid capture sync segment id" }
        require(firstRow >= 1 && lastRowExclusive > firstRow && lastRowExclusive < Int.MAX_VALUE) { "Invalid capture sync segment bounds" }
        require(anchorLogTimeMs == null || anchorLogTimeMs >= 0L) { "Invalid capture sync log anchor" }
        require(anchorCaptureElapsedMs == null || anchorCaptureElapsedMs >= 0L) { "Invalid capture sync elapsed anchor" }
        require(manualOffsetMs in -MAX_MANUAL_OFFSET_MS..MAX_MANUAL_OFFSET_MS) { "Invalid capture manual offset" }
        require((anchorLogTimeMs == null) == (clockReference == null)) { "Capture clock anchor is incomplete" }
        require((anchorLogTimeMs == null) == (anchorCaptureElapsedMs == null)) { "Capture sync anchor is incomplete" }
        if (reliability == CaptureSyncReliability.RELIABLE) {
            require(anchorLogTimeMs != null && anchorCaptureElapsedMs != null) { "Reliable capture sync segment has no anchor" }
        }
        require(manualAnchor == null || manualAnchor.segmentId == id) { "Manual sync anchor belongs to another segment" }
    }

    fun contains(rowOrdinal: Int): Boolean = rowOrdinal >= firstRow && rowOrdinal < lastRowExclusive
}

/** Compact retained and portable timing manifest. `checksum` covers the canonical payload only. */
data class CaptureSyncMetadata(
    val logBytes: Long,
    val rowCount: Int,
    val timelineBaseElapsedMs: Long,
    val timelineDurationMs: Long,
    val segments: List<CaptureSyncSegment>,
    val uncertainBoundary: Boolean = false,
) {
    init {
        require(logBytes >= 0L) { "Invalid capture sync log bound" }
        require(rowCount in 0 until Int.MAX_VALUE) { "Invalid capture sync row bound" }
        require(timelineBaseElapsedMs >= 0L && timelineDurationMs >= 0L) { "Invalid capture sync time bounds" }
        runCatching { Math.addExact(timelineBaseElapsedMs, timelineDurationMs) }
            .getOrElse { throw IllegalArgumentException("Capture sync time bounds overflow", it) }
        var previousEnd = 1
        var previousId = -1
        segments.forEach { segment ->
            require(segment.lastRowExclusive > segment.firstRow) { "Capture sync segment is empty" }
            require(segment.lastRowExclusive <= rowCount + 1) { "Capture sync segment exceeds row bounds" }
            require(segment.firstRow == previousEnd) { "Capture sync segments do not cover rows contiguously" }
            require(segment.id > previousId) { "Capture sync segment IDs are duplicated or out of order" }
            previousEnd = segment.lastRowExclusive
            previousId = segment.id
        }
        require((rowCount == 0 && segments.isEmpty()) || previousEnd == rowCount + 1) {
            "Capture sync segments do not cover the frozen row bounds"
        }
    }

    /**
     * The segment holding [rowOrdinal]. Binary search: the init above guarantees [segments] are
     * sorted and contiguous, and callers resolve every row of a log through here.
     */
    fun segmentContaining(rowOrdinal: Int): CaptureSyncSegment? {
        var lo = 0
        var hi = segments.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val segment = segments[mid]
            when {
                rowOrdinal < segment.firstRow -> hi = mid - 1
                rowOrdinal >= segment.lastRowExclusive -> lo = mid + 1
                else -> return segment
            }
        }
        return null
    }

    fun toJson(): String {
        val payload = payloadJson()
        val digest = sha256(payload.toString().toByteArray(Charsets.UTF_8))
        return buildJsonObject {
            put("payload", payload)
            put("sha256", digest)
        }.toString()
    }

    private fun payloadJson() = buildJsonObject {
        put("version", SYNC_METADATA_VERSION)
        put("logBytes", logBytes)
        put("rowCount", rowCount)
        put("timelineBaseElapsedMs", timelineBaseElapsedMs)
        put("timelineDurationMs", timelineDurationMs)
        put("uncertainBoundary", uncertainBoundary)
        put("segments", buildJsonArray { segments.forEach { add(it.toJson()) } })
    }

    companion object {
        fun parse(raw: String): CaptureSyncMetadata {
            val root = Json.parseToJsonElement(raw).jsonObject
            val payload = root.objectValue("payload")
            val actual = root.stringValue("sha256")
            require(sha256(payload.toString().toByteArray(Charsets.UTF_8)) == actual) {
                "Capture sync metadata checksum mismatch"
            }
            require(payload.intValue("version") == SYNC_METADATA_VERSION) { "Unsupported capture sync metadata version" }
            return CaptureSyncMetadata(
                logBytes = payload.longValue("logBytes"),
                rowCount = payload.intValue("rowCount"),
                timelineBaseElapsedMs = payload.longValue("timelineBaseElapsedMs"),
                timelineDurationMs = payload.longValue("timelineDurationMs"),
                segments = payload.arrayValue("segments").map { it.jsonObject.toSegment() },
                uncertainBoundary = payload.booleanValue("uncertainBoundary") ?: false,
            )
        }
    }
}

/** One sparse block covers a contiguous raw-log byte range and its parsed row ordinal range. */
data class CaptureBlock(
    val byteStart: Long,
    val byteEnd: Long,
    val firstRow: Int,
    val lastRowExclusive: Int,
    val firstElapsedMs: Long,
    val lastElapsedMs: Long,
    val clockSegmentId: Int?,
    val sha256: String,
    val recovered: Boolean = false,
) {
    init {
        require(byteStart >= 0L && byteEnd > byteStart) { "Invalid capture block byte bounds" }
        require(firstRow >= 1 && lastRowExclusive >= firstRow && lastRowExclusive < Int.MAX_VALUE) { "Invalid capture block row bounds" }
        require(firstElapsedMs >= 0L && lastElapsedMs >= firstElapsedMs) { "Invalid capture block elapsed bounds" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid capture block checksum" }
    }

    fun overlapsRows(startInclusive: Int, endExclusive: Int): Boolean =
        firstRow < endExclusive && startInclusive < lastRowExclusive

    fun overlapsElapsed(startInclusive: Long, endInclusive: Long): Boolean =
        firstElapsedMs <= endInclusive && startInclusive <= lastElapsedMs

    internal fun toJson() = buildJsonObject {
        put("v", BLOCK_INDEX_VERSION)
        put("byteStart", byteStart)
        put("byteEnd", byteEnd)
        put("firstRow", firstRow)
        put("lastRowExclusive", lastRowExclusive)
        put("firstElapsedMs", firstElapsedMs)
        put("lastElapsedMs", lastElapsedMs)
        if (clockSegmentId == null) put("clockSegmentId", JsonNull) else put("clockSegmentId", clockSegmentId)
        put("sha256", sha256)
        put("recovered", recovered)
    }
}

/** Small append-only sparse index. Only candidate blocks need raw-byte hash verification. */
data class CaptureBlockIndex(
    val blocks: List<CaptureBlock>,
    val indexedLogBytes: Long = blocks.lastOrNull()?.byteEnd ?: 0L,
    val indexedRows: Int = blocks.lastOrNull()?.lastRowExclusive?.minus(1) ?: 0,
) {
    val serializedBytes: Long get() = serialize().size.toLong()

    fun serialize(): ByteArray = blocks.joinToString(separator = "", postfix = "") {
        it.toJson().toString() + "\n"
    }.toByteArray(Charsets.UTF_8)

    fun candidateBlocksForRows(startInclusive: Int, endExclusive: Int): List<CaptureBlock> {
        if (startInclusive < 1 || endExclusive <= startInclusive) return emptyList()
        return blocks.filter {
            it.overlapsRows(startInclusive, endExclusive) ||
                (it.firstRow == it.lastRowExclusive && it.firstRow in startInclusive..endExclusive)
        }
    }

    fun candidateBlocksForElapsed(startInclusive: Long, endInclusive: Long): List<CaptureBlock> {
        if (startInclusive < 0L || endInclusive < startInclusive) return emptyList()
        return blocks.filter { it.overlapsElapsed(startInclusive, endInclusive) }
    }

    companion object {
        /** Reads the index and verifies raw bytes for blocks overlapping [verifyByteRange]. */
        fun read(
            indexFile: File,
            logFile: File? = null,
            maxIndexBytes: Long = Long.MAX_VALUE,
            maxLogBytes: Long = logFile?.length() ?: Long.MAX_VALUE,
            verifyByteRange: LongRange? = null,
        ): CaptureBlockIndex {
            if (!indexFile.isFile) return CaptureBlockIndex(emptyList())
            val prefixLength = if (maxIndexBytes == Long.MAX_VALUE) indexFile.length() else maxIndexBytes
            require(indexFile.length() >= prefixLength) { "Capture block index is shorter than the frozen prefix" }
            require(logFile == null || maxLogBytes == Long.MAX_VALUE || logFile.length() >= maxLogBytes) {
                "Raw capture log is shorter than the frozen prefix"
            }
            if (prefixLength == 0L) {
                require(maxLogBytes == 0L || maxLogBytes == Long.MAX_VALUE) { "Capture block index does not cover the frozen log" }
                return CaptureBlockIndex(emptyList())
            }
            require(prefixLength <= MAX_BLOCK_INDEX_BYTES && prefixLength <= Int.MAX_VALUE) {
                "Capture block index exceeds the size limit"
            }
            val prefix = ByteArray(prefixLength.toInt())
            RandomAccessFile(indexFile, "r").use { input ->
                input.readFully(prefix)
            }
            require(prefix.last() == '\n'.code.toByte()) { "Capture block index prefix ends inside a record" }
            val blocks = ArrayList<CaptureBlock>()
            val content = prefix.toString(Charsets.UTF_8)
            require(content.endsWith('\n')) { "Capture block index prefix ends inside a record" }
            var expectedByte = 0L
            var expectedRow = 1
            content.removeSuffix("\n").split('\n').forEach { line ->
                require(line.length <= MAX_BLOCK_INDEX_LINE_CHARS) { "Capture block index record exceeds the size limit" }
                require(line.isNotBlank()) { "Capture block index contains an empty record" }
                val value = Json.parseToJsonElement(line).jsonObject
                require(value.intValue("v") == BLOCK_INDEX_VERSION) { "Unsupported capture block index version" }
                val block = value.toBlock()
                require(block.byteEnd <= maxLogBytes) { "Capture block extends past the frozen log boundary" }
                require(block.byteStart == expectedByte) { "Capture block index has an unindexed byte range" }
                require(block.firstRow == expectedRow) { "Capture block index has an unindexed row range" }
                val verify = verifyByteRange == null ||
                    (verifyByteRange.first <= verifyByteRange.last &&
                        block.byteStart <= verifyByteRange.last && verifyByteRange.first < block.byteEnd)
                if (verify) {
                    val source = requireNotNull(logFile) { "Capture block verification needs the raw log" }
                    require(sha256Range(source, block.byteStart, block.byteEnd) == block.sha256) {
                        "Capture block checksum mismatch"
                    }
                }
                expectedByte = block.byteEnd
                if (block.lastRowExclusive > block.firstRow) expectedRow = block.lastRowExclusive
                blocks += block
            }
            require(maxLogBytes == Long.MAX_VALUE || expectedByte == maxLogBytes) {
                "Capture block index does not cover the frozen log"
            }
            return CaptureBlockIndex(blocks, expectedByte, expectedRow - 1)
        }

        fun write(file: File, blocks: List<CaptureBlock>) {
            file.parentFile?.mkdirs()
            file.writeBytes(CaptureBlockIndex(blocks).serialize())
        }

        /** Verifies only one candidate block, leaving unrelated source bytes untouched. */
        fun verifyBlock(logFile: File, block: CaptureBlock) {
            require(sha256Range(logFile, block.byteStart, block.byteEnd) == block.sha256) {
                "Capture block checksum mismatch"
            }
        }
    }
}

/** Appends blocks as capture rows arrive. The current partial block is closed at any threshold. */
class CaptureBlockWriter(
    private val indexFile: File,
    private val maxRows: Int = MAX_BLOCK_ROWS,
    private val maxBytes: Long = MAX_BLOCK_BYTES,
    private val maxDurationMs: Long = MAX_BLOCK_DURATION_MS,
    private val openStream: (File) -> FileOutputStream = { FileOutputStream(it, true) },
    initialBlocks: List<CaptureBlock> = emptyList(),
    private val recoveredBlocks: Boolean = false,
) : Closeable {
    private val fileOutput = indexFile.apply { parentFile?.mkdirs() }.let(openStream)
    private val output = BufferedOutputStream(fileOutput, 64 * 1024)
    private val digest = MessageDigest.getInstance("SHA-256")
    private val completed = ArrayList<CaptureBlock>(initialBlocks)
    private var byteStart = -1L
    private var byteEnd = -1L
    private var firstRow = -1
    private var lastRowExclusive = -1
    private var firstElapsedMs = -1L
    private var lastElapsedMs = -1L
    private var firstObservedElapsedMs = -1L
    private var lastObservedElapsedMs = -1L
    private var clockSegmentId: Int? = null
    private var nextRowOrdinal = initialBlocks.lastOrNull()?.lastRowExclusive ?: 1
    private var closed = false

    init {
        require(initialBlocks.zipWithNext().all { (left, right) ->
            left.byteEnd == right.byteStart && left.lastRowExclusive == right.firstRow
        }) { "Initial capture blocks are not contiguous" }
    }

    @Synchronized
    fun append(
        byteOffset: Long,
        rawLine: ByteArray,
        rowOrdinal: Int?,
        elapsedMs: Long,
        segmentId: Int?,
        resolvedCaptureElapsedMs: Long? = null,
    ) {
        check(!closed) { "Capture block writer is closed" }
        require(byteOffset >= 0L && elapsedMs >= 0L) { "Invalid capture block append bounds" }
        require(rowOrdinal == null || rowOrdinal in 1 until Int.MAX_VALUE) { "Invalid capture row ordinal" }
        if (byteStart >= 0L) {
            val currentBytes = byteEnd - byteStart
            val exceedsByteLimit = rawLine.size.toLong() > maxBytes - currentBytes
            val exceedsDuration = firstObservedElapsedMs >= 0L && elapsedMs >= firstObservedElapsedMs &&
                elapsedMs - firstObservedElapsedMs >= maxDurationMs
            if (exceedsByteLimit || exceedsDuration) closeCurrentBlock()
        }
        if (byteStart < 0L) {
            require(byteOffset == (completed.lastOrNull()?.byteEnd ?: 0L)) { "Capture log bytes are not contiguous" }
            byteStart = byteOffset
            firstRow = nextRowOrdinal
            lastRowExclusive = nextRowOrdinal
            firstElapsedMs = elapsedMs
            lastElapsedMs = elapsedMs
            firstObservedElapsedMs = elapsedMs
            lastObservedElapsedMs = elapsedMs
            clockSegmentId = segmentId
        }
        if (byteEnd >= 0L) require(byteOffset == byteEnd) { "Capture log bytes are not contiguous" }
        digest.update(rawLine)
        byteEnd = Math.addExact(byteOffset, rawLine.size.toLong())
        val resolvedElapsed = resolvedCaptureElapsedMs?.coerceAtLeast(0L)
        firstElapsedMs = minOf(firstElapsedMs, resolvedElapsed ?: elapsedMs)
        lastElapsedMs = maxOf(lastElapsedMs, elapsedMs, resolvedElapsed ?: elapsedMs)
        firstObservedElapsedMs = minOf(firstObservedElapsedMs, elapsedMs)
        lastObservedElapsedMs = maxOf(lastObservedElapsedMs, elapsedMs)
        if (rowOrdinal != null) {
            require(rowOrdinal == nextRowOrdinal) {
                "Capture block row ordinals are not contiguous"
            }
            lastRowExclusive = rowOrdinal + 1
            nextRowOrdinal++
            if (clockSegmentId != segmentId) clockSegmentId = null
        }
        if (byteEnd - byteStart >= maxBytes || lastObservedElapsedMs - firstObservedElapsedMs >= maxDurationMs ||
            (rowOrdinal != null && lastRowExclusive - firstRow >= maxRows)
        ) closeCurrentBlock()
    }

    @Synchronized
    fun closeCurrentBlock() {
        check(!closed) { "Capture block writer is closed" }
        if (byteStart >= 0L && byteEnd > byteStart) {
            val block = CaptureBlock(
                byteStart = byteStart,
                byteEnd = byteEnd,
                firstRow = firstRow,
                lastRowExclusive = lastRowExclusive,
                firstElapsedMs = firstElapsedMs,
                lastElapsedMs = lastElapsedMs,
                clockSegmentId = clockSegmentId,
                sha256 = digest.digest().toHex(),
                recovered = recoveredBlocks,
            )
            output.write(block.toJson().toString().toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            completed += block
        }
        resetCurrent()
    }

    /** Flushes a point-in-time block boundary but leaves the writer ready for later capture rows. */
    @Synchronized
    fun freeze(): CaptureBlockIndex {
        closeCurrentBlock()
        output.flush()
        fileOutput.fd.sync()
        return CaptureBlockIndex(completed.toList())
    }

    @Synchronized
    fun sync() {
        output.flush()
        fileOutput.fd.sync()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closeCurrentBlock()
        output.flush()
        fileOutput.fd.sync()
        output.close()
        closed = true
    }

    private fun resetCurrent() {
        byteStart = -1L
        byteEnd = -1L
        firstRow = -1
        lastRowExclusive = -1
        firstElapsedMs = -1L
        lastElapsedMs = -1L
        firstObservedElapsedMs = -1L
        lastObservedElapsedMs = -1L
        clockSegmentId = null
        digest.reset()
    }
}

data class CaptureTimingPoint(
    val rowOrdinal: Int,
    val captureElapsedMs: Long?,
    val videoMs: Long?,
    val segmentId: Int?,
    val reliable: Boolean,
)

/** The shared resolver for v5 segments, legacy dense timelines, and v3's single-anchor mapping. */
class CaptureTimingResolver private constructor(
    private val entries: List<LogEntry>,
    private val timeline: CaptureTimeline?,
    private val sparse: CaptureSyncMetadata?,
    private val singleAnchor: CaptureSyncAnchor?,
) {
    /**
     * One primitive column per field instead of a [CaptureTimingPoint] per row (an object plus a
     * reference slot each, retained for the life of the tab): a 5M-row log costs ~80 MB here and
     * cost several hundred before. [pointForOrdinal] materializes a point on demand.
     *
     * [keys] is the playhead key for v5 clock segments (see [playheadKeyForOrdinal]) and the plain
     * video position for the legacy and single-anchor mappings; [NO_PLAYHEAD_KEY] marks none. The
     * row's [CaptureTimingPoint.videoMs] is that key when it is not negative: for a v5 row the only
     * difference between the two is that the key keeps the negative positions.
     */
    private class TimingColumns(size: Int) {
        val keys = LongArray(size) { NO_PLAYHEAD_KEY }
        val elapsed = LongArray(size)
        val present = BitSet(size)
        val hasElapsed = BitSet(size)
        val reliable = BitSet(size)

        fun set(index: Int, point: CaptureTimingPoint) {
            present.set(index)
            point.captureElapsedMs?.let {
                elapsed[index] = it
                hasElapsed.set(index)
            }
            if (point.reliable) reliable.set(index)
        }
    }

    private val columns: TimingColumns by lazy(LazyThreadSafetyMode.PUBLICATION) { buildColumns() }

    fun pointForOrdinal(rowOrdinal: Int): CaptureTimingPoint? {
        if (rowOrdinal !in 1..entries.size) return null
        val index = rowOrdinal - 1
        val resolved = columns
        if (!resolved.present[index]) return null
        return CaptureTimingPoint(
            rowOrdinal = rowOrdinal,
            captureElapsedMs = resolved.elapsed[index].takeIf { resolved.hasElapsed[index] },
            videoMs = videoMsAt(resolved, index),
            segmentId = sparse?.segmentContaining(rowOrdinal)?.id,
            reliable = resolved.reliable[index],
        )
    }

    /**
     * The video position the playhead search orders [rowOrdinal] by, or null when the row cannot be
     * searched by playhead at all. For v5 clock segments this is the row's UNCLAMPED position: a row
     * logged shortly before the video started has a negative one. [pointForOrdinal] reports no video
     * position for it (nothing can seek there), but the playhead sitting at the first frame still
     * is "after" that row, so Follow needs it. Rows past the video's end and rows with no usable
     * clock have no key. Every other mapping returns the same position as [pointForOrdinal].
     */
    fun playheadKeyForOrdinal(rowOrdinal: Int): Long? {
        if (rowOrdinal !in 1..entries.size) return null
        return columns.keys[rowOrdinal - 1].takeIf { it != NO_PLAYHEAD_KEY }
    }

    fun elapsedMsForOrdinal(rowOrdinal: Int): Long? {
        if (rowOrdinal !in 1..entries.size) return null
        val resolved = columns
        val index = rowOrdinal - 1
        return resolved.elapsed[index].takeIf { resolved.present[index] && resolved.hasElapsed[index] }
    }

    fun videoMsForOrdinal(rowOrdinal: Int): Long? {
        if (rowOrdinal !in 1..entries.size) return null
        val resolved = columns
        val index = rowOrdinal - 1
        return if (resolved.present[index]) videoMsAt(resolved, index) else null
    }

    fun segmentIdForOrdinal(rowOrdinal: Int): Int? = sparse?.segmentContaining(rowOrdinal)?.id

    /** Computed rows are a transient compatibility view; callers must not persist them. */
    fun allPoints(): List<CaptureTimingPoint> = (1..entries.size).mapNotNull(::pointForOrdinal)

    private fun videoMsAt(resolved: TimingColumns, index: Int): Long? =
        resolved.keys[index].takeIf { it != NO_PLAYHEAD_KEY && it >= 0L }

    private fun buildColumns(): TimingColumns {
        if (sparse != null) return sparseColumns(sparse)
        if (timeline != null) return legacyTimelineColumns(timeline)
        if (singleAnchor != null) return singleAnchorColumns(singleAnchor)
        return TimingColumns(entries.size)
    }

    private fun sparseColumns(metadata: CaptureSyncMetadata): TimingColumns {
        val result = TimingColumns(entries.size)
        for (index in entries.indices) {
            val timing = sparseTiming(entries[index], index + 1, metadata, timeline)
            timing.point?.let { result.set(index, it) }
            timing.playheadKeyMs?.let { result.keys[index] = it }
        }
        return result
    }

    private fun legacyTimelineColumns(currentTimeline: CaptureTimeline): TimingColumns {
        val result = TimingColumns(entries.size)
        val videoDuration = currentTimeline.videoDurationMs
        currentTimeline.rows.forEach { row ->
            if (row.ordinal !in 1..entries.size) return@forEach
            val index = row.ordinal - 1
            // Legacy exported row positions already include the descriptor's manual offset.
            val video = row.videoMs?.takeIf { it >= 0L && (videoDuration == null || it <= videoDuration) }
            result.set(index, CaptureTimingPoint(row.ordinal, row.elapsedMs, video, null, reliable = true))
            result.keys[index] = video ?: NO_PLAYHEAD_KEY
        }
        return result
    }

    private fun singleAnchorColumns(anchor: CaptureSyncAnchor): TimingColumns {
        val result = TimingColumns(entries.size)
        val anchorEntry = entries.getOrNull(anchor.row - 1) ?: return result
        val anchorClock = captureClockReference(anchorEntry, LEGACY_ANCHOR_CONTEXT_EPOCH_MS) ?: return result
        entries.forEachIndexed { index, entry ->
            val delta = clockDeltaMs(anchorClock, entry) ?: return@forEachIndexed
            val video = runCatching { Math.addExact(anchor.videoMs, delta) }.getOrNull()?.takeIf { it >= 0L }
            result.set(index, CaptureTimingPoint(index + 1, delta, video, null, reliable = true))
            result.keys[index] = video ?: NO_PLAYHEAD_KEY
        }
        return result
    }

    companion object {
        fun sparsePoint(
            entry: LogEntry,
            rowOrdinal: Int,
            metadata: CaptureSyncMetadata,
            timeline: CaptureTimeline? = null,
        ): CaptureTimingPoint? = sparseCaptureTimingPoint(entry, rowOrdinal, metadata, timeline)

        fun sparse(
            entries: List<LogEntry>,
            metadata: CaptureSyncMetadata,
            timeline: CaptureTimeline,
            manualAnchors: List<CaptureSegmentManualAnchor> = emptyList(),
        ): CaptureTimingResolver {
            val overrides = manualAnchors.associateBy(CaptureSegmentManualAnchor::segmentId)
            require(overrides.size == manualAnchors.size) { "Duplicate manual capture segment anchor" }
            require(overrides.keys.all { id -> metadata.segments.any { it.id == id } }) {
                "Manual capture anchor references an unknown segment"
            }
            val withOverrides = if (overrides.isEmpty()) metadata else metadata.copy(
                segments = metadata.segments.map { segment ->
                    overrides[segment.id]?.let { segment.copy(manualAnchor = it) } ?: segment
                },
            )
            return CaptureTimingResolver(entries, timeline, withOverrides, null)
        }

        fun legacyRows(entries: List<LogEntry>, timeline: CaptureTimeline): CaptureTimingResolver =
            CaptureTimingResolver(entries, timeline, null, null)

        fun singleAnchor(entries: List<LogEntry>, anchor: CaptureSyncAnchor): CaptureTimingResolver =
            CaptureTimingResolver(entries, null, null, anchor)

        fun forTimeline(
            entries: List<LogEntry>,
            timeline: CaptureTimeline,
            manualAnchors: List<CaptureSegmentManualAnchor> = emptyList(),
        ): CaptureTimingResolver =
            if (timeline.sparseTiming || timeline.syncSegments.isNotEmpty()) {
                val metadata = CaptureSyncMetadata(
                    logBytes = 0L,
                    rowCount = entries.size,
                    timelineBaseElapsedMs = timeline.timelineBaseElapsedMs,
                    timelineDurationMs = timeline.timelineDurationMs,
                    segments = timeline.syncSegments,
                )
                sparse(entries, metadata, timeline, manualAnchors)
            } else {
                legacyRows(entries, timeline)
            }
    }
}

/** Resolves one exact sparse row without materializing other rows. This is also used by marker and
 *  export scans after blocks narrow the candidate byte ranges. */
fun sparseCaptureTimingPoint(
    entry: LogEntry,
    rowOrdinal: Int,
    metadata: CaptureSyncMetadata,
    timeline: CaptureTimeline? = null,
): CaptureTimingPoint? = sparseTiming(entry, rowOrdinal, metadata, timeline).point

/** [point] as [sparseCaptureTimingPoint] reports it, plus the position the playhead search orders the row by. */
private class SparseTiming(val point: CaptureTimingPoint?, val playheadKeyMs: Long?)

private val NO_SPARSE_TIMING = SparseTiming(null, null)

private const val NO_PLAYHEAD_KEY = Long.MIN_VALUE

private fun sparseTiming(
    entry: LogEntry,
    rowOrdinal: Int,
    metadata: CaptureSyncMetadata,
    timeline: CaptureTimeline?,
): SparseTiming {
    val segment = metadata.segmentContaining(rowOrdinal) ?: return NO_SPARSE_TIMING
    val origin = timeline?.videoOriginElapsedMs
    val base = timeline?.timelineBaseElapsedMs ?: metadata.timelineBaseElapsedMs
    val automaticDelta = automaticClockDelta(segment, entry)
    val automaticElapsed = automaticDelta?.let { delta -> segment.anchorCaptureElapsedMs?.let { safeAdd(it, delta) } }
    val manualDelta = segment.manualAnchor?.let { manual -> clockDeltaMs(manual.clockReference, entry) }
    val manualElapsed = manualCaptureElapsed(segment.manualAnchor, manualDelta, origin)
    val absoluteElapsed = manualElapsed ?: automaticElapsed
    val localElapsed = absoluteElapsed?.let { safeSubtract(it, base) }
        ?.takeIf { it in 0L..metadata.timelineDurationMs }
    val rawVideoMs = rawSparseVideoPosition(segment, manualDelta, absoluteElapsed, origin)
    val videoDurationMs = timeline?.videoDurationMs
    val videoMs = rawVideoMs?.takeIf { it >= 0L && (videoDurationMs == null || it <= videoDurationMs) }
    // Unlike videoMs this keeps rows logged before the video started (negative), so the first
    // frame still has a log row at or before it; rows past the end of the video stay out.
    val playheadKeyMs = rawVideoMs?.takeIf { videoDurationMs == null || it <= videoDurationMs }
    val point = if (absoluteElapsed == null && videoMs == null) {
        null
    } else {
        CaptureTimingPoint(rowOrdinal, localElapsed, videoMs, segment.id, reliable = automaticDelta != null || manualDelta != null)
    }
    return SparseTiming(point, playheadKeyMs)
}

private fun automaticClockDelta(segment: CaptureSyncSegment, entry: LogEntry): Long? =
    if (segment.reliability == CaptureSyncReliability.RELIABLE) {
        segment.clockReference?.let { clockDeltaMs(it, entry) }
    } else {
        null
    }

private fun manualCaptureElapsed(
    manual: CaptureSegmentManualAnchor?,
    deltaMs: Long?,
    videoOriginElapsedMs: Long?,
): Long? {
    if (manual == null || deltaMs == null) return null
    val anchorElapsed = videoOriginElapsedMs?.let { safeAdd(it, manual.videoMs) } ?: manual.captureElapsedMs
    return anchorElapsed?.let { safeAdd(it, deltaMs) }
}

private fun rawSparseVideoPosition(
    segment: CaptureSyncSegment,
    manualDeltaMs: Long?,
    absoluteElapsedMs: Long?,
    videoOriginElapsedMs: Long?,
): Long? = if (manualDeltaMs != null && segment.manualAnchor != null) {
    safeAdd(segment.manualAnchor.videoMs, manualDeltaMs)
} else if (absoluteElapsedMs != null && videoOriginElapsedMs != null) {
    safeAdd(safeSubtract(absoluteElapsedMs, videoOriginElapsedMs) ?: return null, segment.manualOffsetMs)
} else {
    null
}

private fun safeAdd(left: Long, right: Long): Long? = runCatching { Math.addExact(left, right) }.getOrNull()

private fun safeSubtract(left: Long, right: Long): Long? = runCatching { Math.subtractExact(left, right) }.getOrNull()

/** Lightweight live estimator. It retains at most 256 startup observations plus a five-row
 *  discontinuity window, then persists only one anchor per segment. */
class CaptureSyncRecorder(
    private val startedEpochMs: Long,
    private val startupSampleLimit: Int = MAX_STARTUP_SAMPLES,
    private val startupWindowMs: Long = 2_000L,
) {
    private data class Sample(val row: Int, val elapsed: Long, val reference: CaptureClockReference, val offset: Long)

    private val segments = ArrayList<CaptureSyncSegment>()
    private val startupSamples = ArrayList<Sample>(startupSampleLimit)
    private val discontinuitySamples = ArrayDeque<Sample>(MAX_DISCONTINUITY_SAMPLE_CONTEXT)
    private var nextSegmentId = 0
    private var currentSegmentFirstRow = 1
    private var currentAnchor: Sample? = null
    private var currentReliability = CaptureSyncReliability.UNCERTAIN
    private var splitAtStableAnchor = false
    private var lastPersistedElapsedMs = Long.MIN_VALUE
    private var lastObservedClockOffset: Long? = null
    private var clockJumpCandidateCount = 0

    init {
        require(startedEpochMs >= 0L)
        require(startupSampleLimit in 1..MAX_STARTUP_SAMPLES)
    }

    @Synchronized
    fun startNewSegment(firstRow: Int, captureElapsedMs: Long) {
        require(firstRow >= 1 && captureElapsedMs >= 0L)
        finishCurrent(firstRow)
        currentSegmentFirstRow = firstRow
        currentAnchor = null
        currentReliability = CaptureSyncReliability.UNCERTAIN
        splitAtStableAnchor = false
        startupSamples.clear()
        discontinuitySamples.clear()
        lastObservedClockOffset = null
        clockJumpCandidateCount = 0
        nextSegmentId++
    }

    /** Adds a timestamped row. Historical buffered/synthetic rows can be persisted while passing
     *  `eligibleForAnchor=false`; they never influence clock estimation or jump detection. */
    @Synchronized
    fun observe(
        rowOrdinal: Int,
        text: String,
        captureElapsedMs: Long,
        eligibleForAnchor: Boolean,
    ): Int {
        val approximateEpoch = runCatching { Math.addExact(startedEpochMs, captureElapsedMs) }.getOrDefault(startedEpochMs)
        return observeReference(rowOrdinal, parseCaptureClockReference(text, approximateEpoch), captureElapsedMs, eligibleForAnchor)
    }

    /** In-memory legacy-import adapter for parsed rows whose original raw text is no longer held. */
    @Synchronized
    fun observeParsedEntry(
        rowOrdinal: Int,
        entry: LogEntry,
        captureElapsedMs: Long,
        eligibleForAnchor: Boolean,
    ): Int {
        val approximateEpoch = runCatching { Math.addExact(startedEpochMs, captureElapsedMs) }.getOrDefault(startedEpochMs)
        return observeReference(rowOrdinal, captureClockReference(entry, approximateEpoch), captureElapsedMs, eligibleForAnchor)
    }

    @Synchronized
    private fun observeReference(
        rowOrdinal: Int,
        reference: CaptureClockReference?,
        captureElapsedMs: Long,
        eligibleForAnchor: Boolean,
    ): Int {
        require(rowOrdinal >= 1 && captureElapsedMs >= 0L)
        if (reference == null) {
            updateSegmentBounds(rowOrdinal)
            return currentId()
        }
        val anchor = currentAnchor
        val offset = if (anchor == null) {
            0L
        } else {
            val delta = clockDeltaMs(anchor.reference, reference) ?: return currentId()
            captureElapsedMs - (anchor.elapsed + delta)
        }

        if (eligibleForAnchor && anchor != null && currentReliability == CaptureSyncReliability.RELIABLE) {
            val discontinuity = trackDiscontinuity(rowOrdinal, captureElapsedMs, reference, offset)
            if (discontinuity != null) {
                startNewSegment(discontinuity.row, discontinuity.elapsed)
                splitAtStableAnchor = true
                addStartupSample(rowOrdinal, captureElapsedMs, reference)
                return currentId()
            }
        }

        if (anchor == null) {
            if (eligibleForAnchor) {
                val first = startupSamples.firstOrNull()
                if (first != null && captureElapsedMs - first.elapsed >= startupWindowMs) {
                    maybeEstablishAnchor(captureElapsedMs, force = true)
                }
                if (currentAnchor == null) {
                    addStartupSample(rowOrdinal, captureElapsedMs, reference)
                    maybeEstablishAnchor(captureElapsedMs)
                }
            }
            updateSegmentBounds(rowOrdinal)
            return currentId()
        }
        if (eligibleForAnchor && currentReliability == CaptureSyncReliability.UNCERTAIN) {
            addStartupSample(rowOrdinal, captureElapsedMs, reference)
            maybeEstablishAnchor(captureElapsedMs)
        }
        lastObservedClockOffset = offset
        updateSegmentBounds(rowOrdinal)
        return currentId()
    }

    @Synchronized
    fun segmentIdForRow(rowOrdinal: Int): Int? {
        if (rowOrdinal < currentSegmentFirstRow) return segments.lastOrNull()?.takeIf { it.contains(rowOrdinal) }?.id
        return currentId()
    }

    /** Absolute capture elapsed time implied by the current reliable clock anchor, if available. */
    @Synchronized
    fun resolvedCaptureElapsedMs(text: String, captureElapsedMs: Long): Long? {
        val anchor = currentAnchor?.takeIf { currentReliability == CaptureSyncReliability.RELIABLE } ?: return null
        val reference = parseCaptureClockReference(text, startedEpochMs + captureElapsedMs) ?: return null
        val delta = clockDeltaMs(anchor.reference, reference) ?: return null
        return runCatching { Math.addExact(anchor.elapsed, delta) }.getOrNull()
    }

    @Synchronized
    fun snapshot(
        logBytes: Long,
        rowCount: Int,
        elapsedMs: Long,
        availability: List<CaptureAvailabilityInterval> = emptyList(),
        finalize: Boolean = false,
    ): CaptureSyncMetadata {
        if (currentAnchor == null && startupSamples.isNotEmpty()) {
            maybeEstablishAnchor(elapsedMs, force = finalize)
        }
        finishCurrent(rowCount + 1)
        val availabilityBase = 0L
        return CaptureSyncMetadata(
            logBytes = logBytes,
            rowCount = rowCount,
            timelineBaseElapsedMs = availabilityBase,
            timelineDurationMs = maxOf(elapsedMs, availability.maxOfOrNull { it.endElapsedMs ?: elapsedMs } ?: 0L),
            segments = segments.toList(),
        )
    }

    @Synchronized
    fun needsPersist(elapsedMs: Long): Boolean =
        lastPersistedElapsedMs == Long.MIN_VALUE || elapsedMs - lastPersistedElapsedMs >= SYNC_METADATA_PERSIST_INTERVAL_MS

    @Synchronized
    fun markPersisted(elapsedMs: Long) { lastPersistedElapsedMs = elapsedMs }

    private fun addStartupSample(row: Int, elapsed: Long, reference: CaptureClockReference) {
        if (startupSamples.size >= startupSampleLimit) return
        val base = startupSamples.firstOrNull()
        val offset = if (base == null) {
            0L
        } else {
            val delta = clockDeltaMs(base.reference, reference) ?: return
            (elapsed - base.elapsed) - delta
        }
        startupSamples += Sample(row, elapsed, reference, offset)
    }

    private fun maybeEstablishAnchor(nowElapsed: Long, force: Boolean = false) {
        if (startupSamples.isEmpty()) return
        val first = startupSamples.first()
        if (!force && startupSamples.size < startupSampleLimit && nowElapsed - first.elapsed < startupWindowMs) return
        val offsets = startupSamples.map(Sample::offset).sorted()
        val percentileIndex = ((offsets.size - LAST_SAMPLE_PERCENTILE_OFFSET) * STARTUP_OFFSET_LOWER_PERCENTILE)
            .toInt()
            .coerceIn(0, offsets.lastIndex)
        val chosenOffset = offsets[percentileIndex]
        val chosen = startupSamples.minByOrNull { kotlin.math.abs(it.offset - chosenOffset) } ?: first
        val adjusted = chosen.copy(elapsed = chosen.elapsed - chosen.offset + chosenOffset, offset = chosenOffset)
        val sampleEndRow = startupSamples.last().row + 1
        if (splitAtStableAnchor && chosen.row > currentSegmentFirstRow) {
            finishCurrent(chosen.row)
            currentSegmentFirstRow = chosen.row
            nextSegmentId++
        }
        currentAnchor = adjusted
        currentReliability = CaptureSyncReliability.RELIABLE
        val established = CaptureSyncSegment(
            id = currentId(),
            firstRow = currentSegmentFirstRow,
            lastRowExclusive = sampleEndRow.coerceAtLeast(currentSegmentFirstRow + 1),
            anchorLogTimeMs = clockReferenceEpochMs(adjusted.reference),
            anchorCaptureElapsedMs = adjusted.elapsed,
            reliability = currentReliability,
            clockReference = adjusted.reference,
        )
        val previousIndex = segments.indexOfLast { it.id == currentId() }
        if (previousIndex >= 0) segments[previousIndex] = established else segments += established
        splitAtStableAnchor = false
        startupSamples.clear()
    }

    private fun trackDiscontinuity(
        row: Int,
        elapsed: Long,
        reference: CaptureClockReference,
        offset: Long,
    ): Sample? {
        val shifted = kotlin.math.abs(offset) >= CLOCK_DISCONTINUITY_THRESHOLD_MS
        if (!shifted) {
            discontinuitySamples.clear()
            clockJumpCandidateCount = 0
            return null
        }
        val existing = discontinuitySamples.firstOrNull()
        if (existing != null && kotlin.math.abs(saturatingSubtract(offset, existing.offset)) >= CLOCK_JUMP_CONSISTENCY_TOLERANCE_MS) {
            discontinuitySamples.clear()
            clockJumpCandidateCount = 0
        }
        clockJumpCandidateCount++
        if (discontinuitySamples.isEmpty()) {
            discontinuitySamples.addLast(Sample(row, elapsed, reference, offset))
        } else {
            if (discontinuitySamples.size == MAX_DISCONTINUITY_SAMPLE_CONTEXT) discontinuitySamples.removeLast()
            discontinuitySamples.addLast(Sample(row, elapsed, reference, offset))
        }
        val first = discontinuitySamples.first()
        return first.takeIf {
            clockJumpCandidateCount >= CLOCK_JUMP_CONFIRMATION_OBSERVATIONS && elapsed - it.elapsed >= CLOCK_DISCONTINUITY_SUSTAIN_MS
        }
    }

    private fun finishCurrent(endExclusive: Int) {
        if (endExclusive <= currentSegmentFirstRow) return
        val anchor = currentAnchor
        val previousIndex = segments.indexOfLast { it.id == currentId() }
        val segment = CaptureSyncSegment(
            id = currentId(),
            firstRow = currentSegmentFirstRow,
            lastRowExclusive = endExclusive,
            anchorLogTimeMs = anchor?.let { clockReferenceEpochMs(it.reference) },
            anchorCaptureElapsedMs = anchor?.elapsed,
            reliability = currentReliability,
            clockReference = anchor?.reference,
        )
        if (previousIndex >= 0) segments[previousIndex] = segment else segments += segment
    }

    private fun updateSegmentBounds(rowOrdinal: Int) {
        val index = segments.indexOfLast { it.id == currentId() }
        if (index >= 0) segments[index] = segments[index].copy(lastRowExclusive = maxOf(segments[index].lastRowExclusive, rowOrdinal + 1))
    }

    private fun currentId(): Int = nextSegmentId
}

/** Resolves a timestamp against its stored clock anchor without needing the anchor row in this log cut. */
fun clockDeltaMs(reference: CaptureClockReference, entry: LogEntry): Long? {
    val tod = parseMillisOfDay(entry.ts).takeUnless { it == TS_UNKNOWN } ?: return null
    val rowDay = entry.dayOfYear.takeUnless { it == NO_DATE }?.toInt()
    return clockDeltaMs(reference, CaptureClockReference(tod, rowDay, reference.anchorYear))
}

fun clockDeltaMs(reference: CaptureClockReference, other: CaptureClockReference): Long? {
    val tod = other.anchorTimeOfDayMs
    val rowDay = other.anchorDayOfYear
    val anchorDay = reference.anchorDayOfYear
    if (rowDay == null || anchorDay == null) return elapsedMillisOfDay(reference.anchorTimeOfDayMs, tod)
    val anchorDate = daySlotToDate(reference.anchorYear, anchorDay) ?: return null
    val rowDates = listOf(-1, 0, 1).mapNotNull { yearDelta ->
        daySlotToDate(other.anchorYear + yearDelta, rowDay)
    }
    val rowDate = rowDates.minByOrNull { kotlin.math.abs(it.toEpochDay() - anchorDate.toEpochDay()) } ?: return null
    val anchorClock = anchorDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + reference.anchorTimeOfDayMs
    val rowClock = rowDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + tod
    return runCatching { Math.subtractExact(rowClock, anchorClock) }.getOrNull()
}

fun parseCaptureClockReference(text: String, approximateEpochMs: Long): CaptureClockReference? {
    val timestamp = CAPTURE_THREADTIME_PREFIX.find(text)?.value ?: return null
    val daySlot = logDaySlot(timestamp.substring(0, 2).toIntOrNull() ?: return null, timestamp.substring(3, 5).toIntOrNull() ?: return null)
    val timeOfDay = parseMillisOfDay(timestamp.substring(6)).takeUnless { it == TS_UNKNOWN } ?: return null
    val year = referenceYear(approximateEpochMs, daySlot.toInt(), timeOfDay)
        ?: Instant.ofEpochMilli(approximateEpochMs).atZone(ZoneOffset.UTC).year
    return CaptureClockReference(timeOfDay, daySlot.toInt(), year)
}

/** Builds a compact manual-link anchor from a parsed log row. The year only resolves ambiguous
 *  year-boundary wraps; day-of-year and time-of-day come from the selected row itself. */
fun captureClockReference(entry: LogEntry, approximateEpochMs: Long): CaptureClockReference? {
    val timeOfDay = parseMillisOfDay(entry.ts).takeUnless { it == TS_UNKNOWN } ?: return null
    val day = entry.dayOfYear.takeUnless { it == NO_DATE }?.toInt()
    val year = day?.let { referenceYear(approximateEpochMs, it, timeOfDay) }
        ?: Instant.ofEpochMilli(approximateEpochMs).atZone(ZoneOffset.UTC).year
    return CaptureClockReference(timeOfDay, day, year)
}

/** Device log dates use local clock fields while capture elapsed is host-based. Pick the closest
 *  valid calendar year near the approximate host epoch so Jan 1 device rows near Dec 31 UTC do
 *  not inherit the wrong year. */
private fun referenceYear(approximateEpochMs: Long, daySlot: Int, timeOfDayMs: Long): Int? {
    val approximateYear = Instant.ofEpochMilli(approximateEpochMs).atZone(ZoneOffset.UTC).year
    val approximate = Instant.ofEpochMilli(approximateEpochMs)
    return (approximateYear - YEAR_SEARCH_RADIUS..approximateYear + YEAR_SEARCH_RADIUS).mapNotNull { year ->
        val date = daySlotToDate(year, daySlot) ?: return@mapNotNull null
        val epoch = runCatching {
            Math.addExact(date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), timeOfDayMs)
        }.getOrNull() ?: return@mapNotNull null
        year to kotlin.math.abs(epoch - approximate.toEpochMilli())
    }.minByOrNull { it.second }?.first
}

private fun daySlotToDate(year: Int, daySlot: Int): LocalDate? = runCatching {
    require(daySlot in 0..MAX_DAY_OF_YEAR)
    val leap = LocalDate.of(year, 1, 1).isLeapYear
    require(leap || daySlot != NON_LEAP_FEBRUARY_29_SLOT) { "February 29 is invalid in this year" }
    val dayOfYear = if (leap || daySlot <= NON_LEAP_FEBRUARY_29_SLOT) {
        daySlot
    } else {
        daySlot - POST_FEBRUARY_29_SLOT_DAY_ADJUSTMENT
    }
    LocalDate.of(year, 1, 1).plusDays(dayOfYear.toLong())
}.getOrNull()

internal fun clockReferenceEpochMs(reference: CaptureClockReference): Long {
    val day = reference.anchorDayOfYear?.let { daySlotToDate(reference.anchorYear, it) }
        ?: LocalDate.of(reference.anchorYear, 1, 1)
    return day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + reference.anchorTimeOfDayMs
}

private fun JsonObject.toSegment(): CaptureSyncSegment {
    val reference = this["clockReference"]?.takeUnless { it is JsonNull }?.jsonObject?.let { value ->
        CaptureClockReference(
            anchorTimeOfDayMs = value.longValue("anchorTimeOfDayMs"),
            anchorDayOfYear = value.intValueOrNull("anchorDayOfYear"),
            anchorYear = value.intValue("anchorYear"),
        )
    }
    return CaptureSyncSegment(
        id = intValue("id"),
        firstRow = intValue("firstRow"),
        lastRowExclusive = intValue("lastRowExclusive"),
        anchorLogTimeMs = longValueOrNull("anchorLogTimeMs"),
        anchorCaptureElapsedMs = longValueOrNull("anchorCaptureElapsedMs"),
        reliability = CaptureSyncReliability.valueOf(stringValue("reliability")),
        clockReference = reference,
        manualOffsetMs = longValueOrNull("manualOffsetMs") ?: 0L,
        manualAnchor = this["manualAnchor"]?.takeUnless { it is JsonNull }?.jsonObject?.toManualAnchor(),
    )
}

private fun CaptureSyncSegment.toJson() = buildJsonObject {
    put("id", id)
    put("firstRow", firstRow)
    put("lastRowExclusive", lastRowExclusive)
    if (anchorLogTimeMs == null) put("anchorLogTimeMs", JsonNull) else put("anchorLogTimeMs", anchorLogTimeMs)
    if (anchorCaptureElapsedMs == null) put("anchorCaptureElapsedMs", JsonNull) else put("anchorCaptureElapsedMs", anchorCaptureElapsedMs)
    put("reliability", reliability.name)
    if (clockReference == null) put("clockReference", JsonNull) else put("clockReference", buildJsonObject {
        put("anchorTimeOfDayMs", clockReference.anchorTimeOfDayMs)
        if (clockReference.anchorDayOfYear == null) put("anchorDayOfYear", JsonNull) else put("anchorDayOfYear", clockReference.anchorDayOfYear)
        put("anchorYear", clockReference.anchorYear)
    })
    put("manualOffsetMs", manualOffsetMs)
    if (manualAnchor == null) put("manualAnchor", JsonNull) else put("manualAnchor", manualAnchor.toJson())
}

internal fun JsonObject.toManualAnchor() = CaptureSegmentManualAnchor(
    segmentId = intValue("segmentId"),
    rowOrdinal = intValue("rowOrdinal"),
    clockReference = getValue("clockReference").jsonObject.let { value ->
        CaptureClockReference(
            anchorTimeOfDayMs = value.longValue("anchorTimeOfDayMs"),
            anchorDayOfYear = value.intValueOrNull("anchorDayOfYear"),
            anchorYear = value.intValue("anchorYear"),
        )
    },
    videoMs = longValue("videoMs"),
    captureElapsedMs = longValueOrNull("captureElapsedMs"),
)

internal fun CaptureSegmentManualAnchor.toJson() = buildJsonObject {
    put("segmentId", segmentId)
    put("rowOrdinal", rowOrdinal)
    put("clockReference", buildJsonObject {
        put("anchorTimeOfDayMs", clockReference.anchorTimeOfDayMs)
        if (clockReference.anchorDayOfYear == null) put("anchorDayOfYear", JsonNull)
        else put("anchorDayOfYear", clockReference.anchorDayOfYear)
        put("anchorYear", clockReference.anchorYear)
    })
    put("videoMs", videoMs)
    if (captureElapsedMs == null) put("captureElapsedMs", JsonNull) else put("captureElapsedMs", captureElapsedMs)
}

fun encodeCaptureSegmentManualAnchors(anchors: List<CaptureSegmentManualAnchor>): String =
    buildJsonArray { anchors.forEach { add(it.toJson()) } }.toString()

fun decodeCaptureSegmentManualAnchors(raw: String): List<CaptureSegmentManualAnchor> =
    Json.parseToJsonElement(raw).jsonArray.map { it.jsonObject.toManualAnchor() }

private fun JsonObject.toBlock() = CaptureBlock(
    byteStart = longValue("byteStart"),
    byteEnd = longValue("byteEnd"),
    firstRow = intValue("firstRow"),
    lastRowExclusive = intValue("lastRowExclusive"),
    firstElapsedMs = longValue("firstElapsedMs"),
    lastElapsedMs = longValue("lastElapsedMs"),
    clockSegmentId = intValueOrNull("clockSegmentId"),
    sha256 = stringValue("sha256"),
    recovered = booleanValue("recovered") ?: false,
)

private fun JsonObject.longValue(name: String): Long = getValue(name).jsonPrimitive.longOrNull
    ?: error("Capture metadata is missing a valid $name")

private fun JsonObject.longValueOrNull(name: String): Long? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull

private fun JsonObject.intValue(name: String): Int = getValue(name).jsonPrimitive.intOrNull
    ?: error("Capture metadata is missing a valid $name")

private fun JsonObject.intValueOrNull(name: String): Int? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull

private fun JsonObject.stringValue(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.booleanValue(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.objectValue(name: String): JsonObject = getValue(name).jsonObject

private fun JsonObject.arrayValue(name: String): JsonArray = getValue(name).jsonArray

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun sha256Range(file: File, start: Long, end: Long): String {
    require(start >= 0 && end >= start && end <= file.length()) { "Capture block is outside the raw log" }
    val digest = MessageDigest.getInstance("SHA-256")
    RandomAccessFile(file, "r").use { input ->
        input.seek(start)
        var remaining = end - start
        val buffer = ByteArray(BLOCK_HASH_BUFFER_BYTES)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("Raw capture log ended inside a block")
            digest.update(buffer, 0, read)
            remaining -= read
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }

private fun saturatingSubtract(left: Long, right: Long): Long =
    runCatching { Math.subtractExact(left, right) }.getOrElse { if (left < right) Long.MIN_VALUE else Long.MAX_VALUE }

internal fun captureTimingsDiffer(leftMs: Long, rightMs: Long): Boolean {
    val difference = runCatching { Math.subtractExact(leftMs, rightMs) }.getOrNull() ?: return true
    return difference > LEGACY_SYNC_TIMING_TOLERANCE_MS || difference < -LEGACY_SYNC_TIMING_TOLERANCE_MS
}

private const val BLOCK_INDEX_VERSION = 1
private const val SYNC_METADATA_VERSION = 1
private const val MAX_BLOCK_INDEX_LINE_CHARS = 16 * 1024
private const val MAX_BLOCK_INDEX_BYTES = 256L * 1024L * 1024L
private const val MAX_BLOCK_ROWS = 1024
private const val MAX_BLOCK_BYTES = 1024L * 1024L
private const val MAX_BLOCK_DURATION_MS = 1_000L
internal const val LEGACY_SYNC_TIMING_TOLERANCE_MS = 1_000L
private const val MIN_CAPTURE_CLOCK_YEAR = 1970
private const val MAX_CAPTURE_CLOCK_YEAR = 9999
private const val MAX_DAY_OF_YEAR = 365
private const val YEAR_SEARCH_RADIUS = 1
private const val LEGACY_ANCHOR_CONTEXT_EPOCH_MS = 0L
private const val MAX_STARTUP_SAMPLES = 256
private const val MAX_DISCONTINUITY_SAMPLE_CONTEXT = 2
private const val CLOCK_JUMP_CONFIRMATION_OBSERVATIONS = 5
private const val SYNC_METADATA_PERSIST_INTERVAL_MS = 2_000L
private const val CLOCK_DISCONTINUITY_THRESHOLD_MS = 1_000L
private const val CLOCK_DISCONTINUITY_SUSTAIN_MS = 1_000L
private const val CLOCK_JUMP_CONSISTENCY_TOLERANCE_MS = 250L
private const val MAX_MANUAL_OFFSET_MS = 7L * 24L * 60L * 60L * 1_000L
private const val MILLIS_PER_DAY = 86_400_000L
private const val LAST_SAMPLE_PERCENTILE_OFFSET = 1
private const val STARTUP_OFFSET_LOWER_PERCENTILE = 0.02
private const val NON_LEAP_FEBRUARY_29_SLOT = 59
private const val POST_FEBRUARY_29_SLOT_DAY_ADJUSTMENT = 1
private const val BLOCK_HASH_BUFFER_BYTES = 128 * 1024
private val CAPTURE_THREADTIME_PREFIX = Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}")
