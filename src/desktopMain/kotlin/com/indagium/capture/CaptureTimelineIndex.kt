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
class CaptureTimelineIndex(private val timeline: CaptureTimeline, private val logRowCount: Int) {
    data class Point(val ordinal: Int, val elapsedMs: Long, val videoMs: Long, val segmentFirstOrdinal: Int)

    enum class CapturePositionKind { MAPPED, BEFORE_FIRST, AFTER_LAST, GAP, EMPTY }

    data class Resolution(val kind: CapturePositionKind, val point: Point? = null)

    private data class Segment(
        val firstIndex: Int,
        val lastIndex: Int,
        val firstVideoMs: Long,
        val lastVideoMs: Long,
    )

    private val rows = timeline.rows
    private val segments: List<Segment>

    /** A repeated boundary means the later segment cannot be ordered against the earlier one. */
    private val ambiguousSegments: BooleanArray
    private val segmentsAscending: Boolean
    private val minVideoMs: Long
    private val maxVideoMs: Long

    init {
        val built = ArrayList<Segment>()
        var start = -1
        var previousOrdinal = -1
        var previousVideoMs = Long.MIN_VALUE

        fun finish(endInclusive: Int) {
            if (start < 0 || endInclusive < start) return
            built += Segment(
                firstIndex = start,
                lastIndex = endInclusive,
                firstVideoMs = rows[start].videoMs!!,
                lastVideoMs = rows[endInclusive].videoMs!!,
            )
            start = -1
        }

        rows.forEachIndexed { index, row ->
            val videoMs = row.videoMs
            val valid = row.ordinal in 1..logRowCount && videoMs != null
            val continues = valid && start >= 0 && row.ordinal == previousOrdinal + 1 && videoMs!! >= previousVideoMs
            if (!valid || (start >= 0 && !continues)) finish(index - 1)
            if (valid && start < 0) start = index
            if (valid) {
                previousOrdinal = row.ordinal
                previousVideoMs = videoMs!!
            } else {
                previousOrdinal = -1
                previousVideoMs = Long.MIN_VALUE
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

    /** Exact row mapping. No neighboring-row interpolation is performed. */
    fun pointForOrdinal(ordinal: Int, offsetMs: Long = 0L): Point? {
        var lo = 0
        var hi = rows.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val row = rows[mid]
            when {
                row.ordinal < ordinal -> lo = mid + 1
                row.ordinal > ordinal -> hi = mid - 1
                else -> return row.videoMs?.let {
                    val segment = segmentContainingRowIndex(mid) ?: return null
                    Point(row.ordinal, row.elapsedMs, it + offsetMs, rows[segment.firstIndex].ordinal)
                }
            }
        }
        return null
    }

    fun videoMsForOrdinal(ordinal: Int, offsetMs: Long = 0L): Long? = pointForOrdinal(ordinal, offsetMs)?.videoMs

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
            if (rows[mid].videoMs!! <= target) {
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
            val before = rows[floor].videoMs!!
            val after = rows[floor + 1].videoMs!!
            if (target - before <= after - target) floor else floor + 1
        }
        val row = rows[chosen]
        return Resolution(
            CapturePositionKind.MAPPED,
            Point(row.ordinal, row.elapsedMs, row.videoMs!! + offsetMs, rows[segment.firstIndex].ordinal),
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
}
