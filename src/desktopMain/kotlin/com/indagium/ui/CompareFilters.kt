package com.indagium.ui

import com.indagium.model.CompareFilterTarget
import com.indagium.model.Filter
import com.indagium.utils.TS_UNKNOWN

// The pure rules of the compare view's "Filters apply to A | B | Both" switch and of its sync scroll. UI-free so they can be pinned
// without composing anything; CompareView.kt and AppState.effectiveSearchFilter both read them, so what a pane shows and what its Find
// bar searches can never disagree.

/** A filter that filters nothing and keeps only [f]'s highlighters: what the pane a filter does NOT apply to still paints. */
internal fun highlightOnly(f: Filter): Filter = Filter(highlighters = f.highlighters)

/**
 * The filters the compare view DISPLAYS: (left pane, right pane), given the stored filters of the left tab ([left], A) and the right tab
 * ([right], B).
 * - [CompareFilterTarget.A]: A shows its own filter; B shows everything, painted with A's highlighters.
 * - [CompareFilterTarget.B]: B shows its own filter; A shows everything, painted with B's highlighters (the panel edits B's filter).
 * - [CompareFilterTarget.BOTH]: both panes show A's filter (the panel edits A's filter).
 */
internal fun compareDisplayedFilters(target: CompareFilterTarget, left: Filter, right: Filter): Pair<Filter, Filter> = when (target) {
    CompareFilterTarget.A -> left to highlightOnly(left)
    CompareFilterTarget.B -> highlightOnly(right) to right
    CompareFilterTarget.BOTH -> left to left
}

/** The tab the filter panel edits for [target]: B's for [CompareFilterTarget.B], A's otherwise (BOTH mirrors A's filter onto B). */
internal fun compareFilterEditTabId(target: CompareFilterTarget, leftId: String, rightId: String): String =
    if (target == CompareFilterTarget.B) rightId else leftId

/** True when the log pane of [side] (A = left, B = right) is the one the filter panel is editing, for the A / B badges. */
internal fun compareSideIsEdited(target: CompareFilterTarget, sideIsRight: Boolean): Boolean = when (target) {
    CompareFilterTarget.A -> !sideIsRight
    CompareFilterTarget.B -> sideIsRight
    CompareFilterTarget.BOTH -> true
}

// How far from the probe a binary-search step looks for a row that has a timestamp before it gives up on that step.
private const val TIME_PROBE_CAP = 256

/**
 * The index in a list of [count] displayed rows whose time is nearest [target] (millis of day), or null when no row has a time.
 * [timeAt] returns a row's time, or [TS_UNKNOWN] for a row without one (brief/RAW rows, blank timestamps). Rows are assumed to run
 * forwards in time (the order of a log file), so a log that crosses midnight gets an approximate answer; the search is a binary search
 * that steps over runs of unknown rows (at most [TIME_PROBE_CAP] of them per step), so it stays cheap on multi-million-row logs.
 * A tie goes to the earlier row.
 */
internal fun nearestEntryIndexByTime(count: Int, timeAt: (Int) -> Long, target: Long): Int? {
    if (count <= 0 || target == TS_UNKNOWN) return null
    var lo = 0
    var hi = count
    // First index whose (known) time is >= target.
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        val probe = probeKnown(mid, lo, hi, timeAt)
        if (probe < 0) {
            hi = mid
        } else if (timeAt(probe) >= target) {
            hi = probe
        } else {
            lo = probe + 1
        }
    }
    val after = nextKnown(lo, count, timeAt)
    val before = previousKnown(lo - 1, timeAt)
    return when {
        after < 0 && before < 0 -> null
        after < 0 -> before
        before < 0 -> after
        target - timeAt(before) <= timeAt(after) - target -> before
        else -> after
    }
}

// An index in [from, to) holding a known time, searched outwards from [mid] (left side first) within the probe cap; -1 when none.
private fun probeKnown(mid: Int, from: Int, to: Int, timeAt: (Int) -> Long): Int {
    var i = mid
    var steps = 0
    while (i >= from && steps < TIME_PROBE_CAP) {
        if (timeAt(i) != TS_UNKNOWN) return i
        i--
        steps++
    }
    i = mid + 1
    steps = 0
    while (i < to && steps < TIME_PROBE_CAP) {
        if (timeAt(i) != TS_UNKNOWN) return i
        i++
        steps++
    }
    return -1
}

private fun nextKnown(from: Int, count: Int, timeAt: (Int) -> Long): Int {
    var i = from
    var steps = 0
    while (i < count && steps < TIME_PROBE_CAP) {
        if (timeAt(i) != TS_UNKNOWN) return i
        i++
        steps++
    }
    return -1
}

private fun previousKnown(from: Int, timeAt: (Int) -> Long): Int {
    var i = from
    var steps = 0
    while (i >= 0 && steps < TIME_PROBE_CAP) {
        if (timeAt(i) != TS_UNKNOWN) return i
        i--
        steps++
    }
    return -1
}
