package com.indagium.ui

import com.indagium.model.Filter
import com.indagium.model.LogLevel

/**
 * The left Filters panel's sections that the filter bar's residual "+ …" chip
 * ([filterBarResidualSummary]) can summarize and therefore must be able to reveal. Declared in the
 * order the panel lays them out, so "the first relevant section" is the lowest ordinal.
 */
enum class FilterPanelSection { HIGHLIGHTERS, LOG_LEVEL }

/**
 * Which panel sections a click on the residual chip must expand so the user sees what the chip
 * summarizes. Mirrors [filterBarResidualSummary]'s own conditions: enabled highlighters, and a
 * non-default level selection (a non-empty, non-full set). The summary's excl-kw / pid/tid parts
 * have no dedicated section in the panel, so they contribute nothing here.
 */
internal fun residualChipSectionsToReveal(filter: Filter): Set<FilterPanelSection> {
    val sections = linkedSetOf<FilterPanelSection>()
    if (filter.highlighters.any { it.on }) sections += FilterPanelSection.HIGHLIGHTERS
    if (filter.levels.isNotEmpty() && filter.levels != LogLevel.entries.toSet()) {
        sections += FilterPanelSection.LOG_LEVEL
    }
    return sections
}

/**
 * Expands [sections] in the panel UI state — for highlighters the exact end state of clicking the
 * "N active" pill on the Highlighters header (section and its list both open) — and asks the
 * panel to scroll the first of them into view. No-op for an empty set.
 */
internal fun FilterPanelUiState.revealSections(sections: Set<FilterPanelSection>) {
    if (sections.isEmpty()) return
    if (FilterPanelSection.HIGHLIGHTERS in sections) {
        highlightersExpanded = true
        hlListExpanded = true
    }
    if (FilterPanelSection.LOG_LEVEL in sections) lvlExpanded = true
    revealSection = sections.minBy { it.ordinal }
}
