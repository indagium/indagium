package com.indagium.capture

/**
 * Search index over a portable capture's row mapping.
 *
 * Null video positions split the mapping into independent segments. Queries deliberately never
 * bridge those segments: a playhead inside an interruption returns [CapturePositionKind.GAP]
 * instead of inventing a clock-based relationship between the rows on either side. If the first
 * video position after an interruption repeats the previous segment's end, the later segment is
 * also treated as ambiguous for playhead queries; its exact rows remain available by ordinal.
 */
class CaptureTimelineIndex(
    private val timeline: CaptureTimeline,
    private val logRowCount: Int,
    private val timingResolver: CaptureTimingResolver? = null,
) {
    data class Point(val ordinal: Int, val elapsedMs: Long, val videoMs: Long, val segmentFirstOrdinal: Int)

    enum class CapturePositionKind { MAPPED, BEFORE_FIRST, AFTER_LAST, GAP, EMPTY }

    data class Resolution(val kind: CapturePositionKind, val point: Point? = null)

    private data class Segment(
        val firstIndex: Int,
        val lastIndex: Int,
        val firstVideoMs: Long,
        val lastVideoMs: Long,
    )

    private val rows: List<CaptureMappingRow>

    /**
     * Each row's own video position as the playhead search sees it (null = not searchable). For a
     * v5 clock segment this is the unclamped position, so rows logged just before the video started
     * (negative) still precede the first frame; see [CaptureTimingResolver.playheadKeyForOrdinal].
     */
    private val rowKeys: LongArray

    /** [rowKeys] made non-decreasing inside each segment; the binary searches run over this. */
    private val searchKeys: LongArray
    private val segments: List<Segment>

    /** A repeated boundary means the later segment cannot be ordered against the earlier one. */
    private val ambiguousSegments: BooleanArray
    private val segmentsAscending: Boolean
    private val minVideoMs: Long
    private val maxVideoMs: Long

    init {
        val builtRows = ArrayList<CaptureMappingRow>()
        val builtKeys = ArrayList<Long>()
        if (timingResolver != null) {
            for (ordinal in 1..logRowCount) {
                val key = timingResolver.playheadKeyForOrdinal(ordinal)
                // A hole inside a v5 clock segment (a row with no timestamp, one outside the video)
                // is not an interruption: the clock keeps running across it. Dropping the row keeps
                // it from splitting the segment into pieces whose in-between playhead positions
                // would read as unmapped.
                if (key == null && timingResolver.segmentIdForOrdinal(ordinal) != null) continue
                val point = timingResolver.pointForOrdinal(ordinal)
                builtRows += CaptureMappingRow(ordinal, point?.captureElapsedMs ?: 0L, point?.videoMs)
                builtKeys += key ?: NOT_SEARCHABLE
            }
        } else {
            timeline.rows.forEach { row ->
                builtRows += row
                builtKeys += row.videoMs ?: NOT_SEARCHABLE
            }
        }
        rows = builtRows
        rowKeys = builtKeys.toLongArray()
        searchKeys = rowKeys.copyOf()

        val built = ArrayList<Segment>()
        var start = -1
        var previousOrdinal = -1
        var previousKey = Long.MIN_VALUE
        var previousClockSegmentId: Int? = null

        fun finish(endInclusive: Int) {
            if (start < 0 || endInclusive < start) return
            built += Segment(
                firstIndex = start,
                lastIndex = endInclusive,
                firstVideoMs = searchKeys[start],
                lastVideoMs = searchKeys[endInclusive],
            )
            start = -1
        }

        rows.forEachIndexed { index, row ->
            val key = rowKeys[index]
            val clockSegmentId = timingResolver?.segmentIdForOrdinal(row.ordinal)
            val valid = row.ordinal in 1..logRowCount && key != NOT_SEARCHABLE
            // Inside one v5 clock segment, rows are logged by several processes and buffers, so a
            // row stamped a few milliseconds before its predecessor is routine and says nothing
            // about the recording: the segment goes on and the search order is repaired below. For
            // legacy arrival-time rows a backwards step is the only sign of an interruption.
            val continues = valid && start >= 0 && clockSegmentId == previousClockSegmentId &&
                (clockSegmentId != null || (row.ordinal == previousOrdinal + 1 && key >= previousKey))
            if (!valid || (start >= 0 && !continues)) finish(index - 1)
            if (valid && start < 0) start = index
            if (valid) {
                if (continues && clockSegmentId != null) searchKeys[index] = maxOf(key, searchKeys[index - 1])
                previousOrdinal = row.ordinal
                previousKey = key
                previousClockSegmentId = clockSegmentId
            } else {
                previousOrdinal = -1
                previousKey = Long.MIN_VALUE
                previousClockSegmentId = null
            }
        }
        finish(rows.lastIndex)
        segments = built
        ambiguousSegments = BooleanArray(segments.size) { index ->
            index > 0 && segments[index - 1].lastVideoMs >= segments[index].firstVideoMs
        }
        segmentsAscending = segments.zipWithNext().all { (a, b) ->
            a.firstVideoMs <= b.firstVideoMs && a.lastVideoMs <= b.firstVideoMs
        }
        minVideoMs = segments.minOfOrNull(Segment::firstVideoMs) ?: 0L
        maxVideoMs = segments.maxOfOrNull(Segment::lastVideoMs) ?: 0L
    }

    /** True when the playhead search can land on [ordinal]'s row (it has a video position in some segment). */
    fun isPlayheadSearchable(ordinal: Int): Boolean {
        val index = rowIndexForOrdinal(ordinal)
        return index >= 0 && rowKeys[index] != NOT_SEARCHABLE
    }

    private fun rowIndexForOrdinal(ordinal: Int): Int {
        var lo = 0
        var hi = rows.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val rowOrdinal = rows[mid].ordinal
            when {
                rowOrdinal < ordinal -> lo = mid + 1
                rowOrdinal > ordinal -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** Exact row mapping. No neighboring-row interpolation is performed. */
    fun pointForOrdinal(ordinal: Int, offsetMs: Long = 0L): Point? {
        val index = rowIndexForOrdinal(ordinal).takeIf { it >= 0 } ?: return null
        val row = rows[index]
        return row.videoMs?.let {
            val segment = segmentContainingRowIndex(index) ?: return null
            Point(row.ordinal, row.elapsedMs, it + offsetMs, rows[segment.firstIndex].ordinal)
        }
    }

    fun videoMsForOrdinal(ordinal: Int, offsetMs: Long = 0L): Long? = pointForOrdinal(ordinal, offsetMs)?.videoMs

    /** Sparse uncertain rows still belong to a segment even when they have no automatic mapping. */
    fun segmentIdForOrdinal(ordinal: Int): Int? = timingResolver?.segmentIdForOrdinal(ordinal)
        ?: timeline.syncSegments.firstOrNull { it.contains(ordinal) }?.id

    /** Closest captured row in the segment containing [videoMs], with earlier rows winning ties. */
    fun nearest(videoMs: Long, offsetMs: Long = 0L): Resolution = resolve(videoMs, offsetMs, nearest = true)

    /** Last captured row at or before [videoMs], constrained to its continuous mapped segment. */
    fun floor(videoMs: Long, offsetMs: Long = 0L): Resolution = resolve(videoMs, offsetMs, nearest = false)

    private fun resolve(videoMs: Long, offsetMs: Long, nearest: Boolean): Resolution {
        if (segments.isEmpty()) return Resolution(CapturePositionKind.EMPTY)
        val target = videoMs - offsetMs
        if (target < minVideoMs) return Resolution(CapturePositionKind.BEFORE_FIRST)
        if (target > maxVideoMs) return Resolution(CapturePositionKind.AFTER_LAST)
        val segment = segmentContaining(target) ?: return Resolution(CapturePositionKind.GAP)

        var lo = segment.firstIndex
        var hi = segment.lastIndex
        var floor = segment.firstIndex - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (searchKeys[mid] <= target) {
                floor = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (floor < segment.firstIndex) return Resolution(CapturePositionKind.GAP)
        val chosen = if (!nearest || floor == segment.lastIndex) {
            floor
        } else {
            val before = searchKeys[floor]
            val after = searchKeys[floor + 1]
            if (target - before <= after - target) floor else floor + 1
        }
        val row = rows[chosen]
        return Resolution(
            CapturePositionKind.MAPPED,
            Point(row.ordinal, row.elapsedMs, rowKeys[chosen] + offsetMs, rows[segment.firstIndex].ordinal),
        )
    }

    private fun segmentContainingRowIndex(rowIndex: Int): Segment? {
        var lo = 0
        var hi = segments.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val segment = segments[mid]
            when {
                rowIndex < segment.firstIndex -> hi = mid - 1
                rowIndex > segment.lastIndex -> lo = mid + 1
                else -> return segment
            }
        }
        return null
    }

    private fun segmentContaining(target: Long): Segment? {
        if (!segmentsAscending) {
            return segments.withIndex().firstOrNull { (index, segment) ->
                !ambiguousSegments[index] && target in segment.firstVideoMs..segment.lastVideoMs
            }?.value
        }

        // Segments can touch when a recording interruption happens at the same video PTS as
        // the first row after it. Pick the earlier segment for that exact boundary, matching
        // nearest()'s earlier-row tie rule. A search by last PTS finds the first segment that
        // could contain the target; searching by first PTS would choose the later one.
        var lo = 0
        var hi = segments.lastIndex
        var candidate = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (segments[mid].lastVideoMs < target) {
                candidate = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val firstContaining = (candidate + 1).takeIf { it <= segments.lastIndex } ?: return null
        if (ambiguousSegments[firstContaining]) return null
        return segments[firstContaining].takeIf { target >= it.firstVideoMs }
    }

    private companion object {
        const val NOT_SEARCHABLE = Long.MIN_VALUE
    }
}
