package com.indagium.testing.authoring

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File

// The recorded video of an applied recording, as stored beside the case (see testing/store/RecordingEvidenceFiles.kt for the layout): the
// finished MKV segments of RecordedVideoTimeline and its packet index, `timeline.jsonl`, copied as they are. The index holds one JSON line per
// written packet, which is everything a timeline needs to map a session time to a frame; the figures that are not per packet (when the
// session started, how long it ran, gaps, caps) travel in recording.json. Reading never touches the files it reads.

/** Segment files are named `timeline-<n>.mkv`; [n] is the `segment` number of the index lines. */
private const val SEGMENT_FILE_PREFIX = "timeline-"
private const val SEGMENT_FILE_SUFFIX = ".mkv"

/** The index is read at most this far: 40 000 packets of about 250 bytes each, with room to spare. */
private const val MAX_INDEX_BYTES = 32L * 1024L * 1024L
private const val MAX_INDEX_LINES = 60_000

/** One packet of a stored segment: where it sits in the segment ([segmentPtsUs]) and in the session ([sessionElapsedMs]). */
internal data class StoredPacketMapping(
    val segmentPtsUs: Long,
    val sessionElapsedMs: Long,
    val receivedAtMs: Long,
    val receivedAtNanos: Long,
    val sourcePtsUs: Long,
    val keyFrame: Boolean,
)

internal data class StoredVideoSegment(
    val fileName: String,
    val connectionEpoch: Long,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val mappings: List<StoredPacketMapping>,
)

/** A finished timeline read back from stored files: [directory] holds the segment files; the rest are the figures of the live one. */
internal data class StoredVideoTimeline(
    val directory: File,
    val sessionStartedAtMs: Long,
    val stoppedDurationMs: Long,
    val stoppedWallClockMs: Long,
    val gapCount: Int,
    val capReached: Boolean,
    val warning: String?,
    val packetCount: Long,
    val segments: List<StoredVideoSegment>,
)

/** The files and figures of a finished live timeline that a stored copy needs (see [RecordedVideoTimeline.exportForStorage]). */
internal class VideoStorageExport(
    val segmentFiles: List<File>,
    val indexFile: File?,
    val sessionStartedAtMs: Long,
    val stoppedDurationMs: Long,
    val stoppedWallClockMs: Long,
    val gapCount: Int,
    val capReached: Boolean,
    val warning: String?,
    val packetCount: Long,
) {
    /** The bytes the segments and the index take on disk. */
    val bytes: Long get() = segmentFiles.sumOf { it.length() } + (indexFile?.length() ?: 0L)
}

/**
 * The segments the index in [directory] describes, in segment order. A segment whose file is missing is left out; a line that is not a
 * packet record is skipped. Empty when the index is missing, too big or has no packets.
 */
internal fun readStoredVideoSegments(directory: File): List<StoredVideoSegment> {
    val index = File(directory, RecordedVideoTimeline.INDEX_FILE_NAME)
    if (!index.isFile || index.length() > MAX_INDEX_BYTES) return emptyList()
    val byNumber = sortedMapOf<Int, SegmentBuilder>()
    runCatching {
        index.useLines { lines ->
            lines.take(MAX_INDEX_LINES).forEach { line ->
                parseIndexLine(line)?.let { (number, record) -> byNumber.getOrPut(number, ::SegmentBuilder).add(record) }
            }
        }
    }
    return byNumber.mapNotNull { (number, builder) ->
        val name = "$SEGMENT_FILE_PREFIX$number$SEGMENT_FILE_SUFFIX"
        if (File(directory, name).isFile) builder.build(name) else null
    }
}

/** The segment file name for a stored segment number. */
internal fun storedSegmentFileName(number: Int): String = "$SEGMENT_FILE_PREFIX$number$SEGMENT_FILE_SUFFIX"

/** True for a name a stored segment file may have (so a copy never takes an arbitrary file along). */
internal fun isStoredSegmentFileName(name: String): Boolean =
    name.startsWith(SEGMENT_FILE_PREFIX) && name.endsWith(SEGMENT_FILE_SUFFIX) &&
        name.removePrefix(SEGMENT_FILE_PREFIX).removeSuffix(SEGMENT_FILE_SUFFIX).toIntOrNull() != null

private class IndexRecord(val epoch: Long, val segmentStartElapsedMs: Long, val mapping: StoredPacketMapping)

private class SegmentBuilder {
    private val mappings = ArrayList<StoredPacketMapping>()
    private var epoch = 0L
    private var start = 0L

    fun add(record: IndexRecord) {
        if (mappings.isEmpty()) {
            epoch = record.epoch
            start = record.segmentStartElapsedMs
        }
        mappings += record.mapping
    }

    fun build(fileName: String) = StoredVideoSegment(
        fileName = fileName,
        connectionEpoch = epoch,
        startElapsedMs = start,
        endElapsedMs = maxOf(start, mappings.maxOf { it.sessionElapsedMs }),
        mappings = mappings.toList(),
    )
}

private fun parseIndexLine(line: String): Pair<Int, IndexRecord>? {
    if (line.isBlank()) return null
    val o: JsonObject = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
    val number = o.long("segment")?.toInt()?.takeIf { it > 0 } ?: return null
    val mapping = StoredPacketMapping(
        segmentPtsUs = o.long("segmentPtsUs") ?: return null,
        sessionElapsedMs = o.long("sessionElapsedMs") ?: return null,
        receivedAtMs = o.long("receivedAtMs") ?: 0L,
        receivedAtNanos = o.long("receivedAtNanos") ?: 0L,
        sourcePtsUs = o.long("sourcePtsUs") ?: 0L,
        keyFrame = (o["keyFrame"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
    return number to IndexRecord(o.long("connectionEpoch") ?: 0L, o.long("segmentStartElapsedMs") ?: 0L, mapping)
}

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
