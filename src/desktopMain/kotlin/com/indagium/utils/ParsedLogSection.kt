package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.LogFileSection

/** Parser-owned rows, discarded after the selected section has been published. */
data class ParsedLogSection(
    val id: String, val title: String, val group: String, val entries: List<LogEntry>, val unavailableReason: String? = null,
    /** Decoded rows are a lower bound; zero does not establish that traffic is absent. */
    val decodeNotice: String? = null,
    val decodeSource: String? = null,
) {
    fun description(): LogFileSection = LogFileSection(id, title, group, entries.size, unavailableReason, decodeNotice, decodeSource)
}

internal fun ParsedLog.selectSection(sectionId: String?): ParsedLog {
    val section = sections.firstOrNull { it.id == sectionId }
        ?: sections.firstOrNull { section -> section.entries.any { it.profileFields["identity"] != "JSON.Metadata" } }
        ?: sections.firstOrNull { it.entries.isNotEmpty() }
        ?: sections.firstOrNull()
        ?: return this
    return copy(entries = section.entries, selectedSectionId = section.id)
}
