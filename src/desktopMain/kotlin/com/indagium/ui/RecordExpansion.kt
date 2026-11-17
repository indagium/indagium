package com.indagium.ui

import com.indagium.model.LogTab

/**
 * Which structured records of one tab are open. Not persisted: the state is [defaultExpanded] plus the rows that differ from it, so
 * "Expand all" and "Collapse all" are two constants rather than a set the size of the log, and a row is open when its default and its
 * override disagree ([overrides] holds the ids a row's own click flipped).
 */
internal data class RecordExpansion(val defaultExpanded: Boolean = false, val overrides: Set<Int> = emptySet()) {
    fun isExpanded(entryId: Int): Boolean = defaultExpanded != (entryId in overrides)

    fun toggled(entryId: Int): RecordExpansion =
        copy(overrides = if (entryId in overrides) overrides - entryId else overrides + entryId)

    /** True while no record can still be opened. */
    val allExpanded: Boolean get() = defaultExpanded && overrides.isEmpty()

    /** True while some record may be open. */
    val anyExpanded: Boolean get() = defaultExpanded || overrides.isNotEmpty()

    val isInitial: Boolean get() = this == RecordExpansion()

    companion object {
        val ALL_EXPANDED = RecordExpansion(defaultExpanded = true)
        val ALL_COLLAPSED = RecordExpansion()
    }
}

/** How many leading rows [tabHasExpandableRecords] looks at: enough to tell a record format from plain text, never a scan of the log. */
private const val EXPANDABLE_PROBE_ROWS = 64

/**
 * Whether [tab]'s rows can open into a record body: a profile-presented tab (metadata JSON, JSON Lines, regex ...) whose first rows
 * have one, or a DLT tab whose first rows carry a multi-line payload. Looks at a few leading rows only, so it is cheap per recomposition.
 */
internal fun tabHasExpandableRecords(tab: LogTab): Boolean {
    val rows = tab.logData
    val limit = minOf(rows.size, EXPANDABLE_PROBE_ROWS)
    for (index in 0 until limit) {
        if (expandableRecordSource(rows[index], tab.logFormat) != null) return true
    }
    return false
}
