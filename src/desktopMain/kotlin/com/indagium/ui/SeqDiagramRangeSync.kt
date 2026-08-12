package com.indagium.ui

import com.indagium.diagram.DiagramRange
import com.indagium.model.LogTab
import com.indagium.utils.TS_UNKNOWN
import com.indagium.utils.parseMillisOfDay

/** Pure range/selection synchronization helpers used by the diagram inspector. */
internal data class DiagramRangeSyncResult(
    val range: DiagramRange?,
    val selectedIds: List<Int> = emptyList(),
    val error: String? = null,
)

internal fun selectionRangeForRows(tab: LogTab, selected: Set<Int>): DiagramRange.Ids? {
    if (selected.isEmpty()) return null
    val known = selected.filter { id -> tab.logData.any { it.id == id } }
    if (known.isEmpty()) return null
    return DiagramRange.Ids(known.min(), known.max(), known.toSet())
}

internal fun timeRangeForRows(tab: LogTab, selected: Set<Int>): DiagramRange.Time? {
    if (selected.isEmpty()) return null
    val byId = effectiveTimestamps(tab).associateBy { it.first }
    val times = selected.mapNotNull { byId[it]?.second }.sortedBy { it.first }
    if (times.isEmpty()) return null
    return DiagramRange.Time(times.first().second, times.last().second)
}

internal fun rowsForTimeRange(tab: LogTab, fromText: String, toText: String): DiagramRangeSyncResult {
    val from = parseMillisOfDay(fromText)
    val to = parseMillisOfDay(toText)
    if (from == TS_UNKNOWN || to == TS_UNKNOWN) {
        return DiagramRangeSyncResult(null, error = "Use HH:MM:SS or HH:MM:SS.mmm for both bounds.")
    }
    val low = minOf(from, to)
    val high = maxOf(from, to)
    val times = effectiveTimestamps(tab)
    val matching = times.filter { it.second.first in low..high }
    if (matching.isNotEmpty()) {
        return DiagramRangeSyncResult(
            range = DiagramRange.Time(fromText, toText),
            selectedIds = matching.map { it.first },
        )
    }
    val parsed = times.filter { it.second.first != TS_UNKNOWN }
    if (parsed.isEmpty()) return DiagramRangeSyncResult(null, error = "No parseable timestamps exist in this log.")
    val nearestLow = parsed.minBy { kotlin.math.abs(it.second.first - low) }
    val nearestHigh = parsed.minBy { kotlin.math.abs(it.second.first - high) }
    val loId = minOf(nearestLow.first, nearestHigh.first)
    val hiId = maxOf(nearestLow.first, nearestHigh.first)
    val ids = tab.logData.filter { it.id in loId..hiId }.map { it.id }
    return DiagramRangeSyncResult(DiagramRange.Time(fromText, toText), ids)
}

private fun effectiveTimestamps(tab: LogTab): List<Pair<Int, Pair<Long, String>>> {
    var carried: Pair<Long, String>? = null
    return tab.logData.mapNotNull { entry ->
        val parsed = parseMillisOfDay(entry.ts)
        if (parsed != TS_UNKNOWN) carried = parsed to entry.ts
        carried?.let { entry.id to it }
    }
}
