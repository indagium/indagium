package com.indagium.model

/** A log section within a structured JSON document. Rows are loaded for the selected section only. */
data class LogFileSection(
    val id: String, val title: String, val group: String, val recordCount: Int,
    /** Null means decoded (possibly empty); otherwise the record count is unknown. */
    val unavailableReason: String? = null,
    /** Decoded rows are a lower bound; zero does not establish that traffic is absent. */
    val decodeNotice: String? = null,
    val decodeSource: String? = null,
)

/** Independent row/filter state while switching sections in the same document tab. */
data class LogSectionViewState(
    val filter: Filter = Filter(),
    val selected: Set<Int> = emptySet(),
    val expanded: Set<String> = emptySet(),
    val manualBlocks: List<ManualCollapseBlock> = emptyList(),
)
