package com.indagium

import com.indagium.model.LogViewSectionId
import com.indagium.ui.filterKeyboardTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilterKeyboardTargetsSectionsTest {
    private fun targets(visibleSections: Set<LogViewSectionId>?, includeModeControls: Boolean = true) = filterKeyboardTargets(
        levelCount = 3,
        sequenceIds = listOf("s1"),
        manualCollapseIds = listOf("m1"),
        savedFilterIds = listOf("f1"),
        includeModeControls = includeModeControls,
        visibleSections = visibleSections,
    )

    @Test
    fun nullVisibleSectionsIsTheLegacyList() {
        val legacy = filterKeyboardTargets(
            levelCount = 3,
            sequenceIds = listOf("s1"),
            manualCollapseIds = listOf("m1"),
            savedFilterIds = listOf("f1"),
        )
        assertEquals(legacy, targets(null))
        assertEquals(legacy, targets(LogViewSectionId.entries.toSet()))
    }

    @Test
    fun onlyTargetsOfVisibleSectionsRemainInPanelOrder() {
        val ids = targets(setOf(LogViewSectionId.LOG_LEVEL, LogViewSectionId.SAVED_FILTERS)).map { it.id }
        assertEquals(
            listOf(
                "filter-section-levels", "level-0", "level-1", "level-2",
                "filter-section-saved", "saved:f1", "save-current-filter", "clear-filters", "export-filters", "import-filters",
            ),
            ids,
        )
    }

    @Test
    fun eachSectionOwnsItsTargets() {
        assertEquals(
            listOf("filter-mode-tags", "filter-mode-regex", "filter-tag-input"),
            targets(setOf(LogViewSectionId.TAGS)).map { it.id },
        )
        assertEquals(listOf("filter-message-input"), targets(setOf(LogViewSectionId.MESSAGE_RULES)).map { it.id })
        assertEquals(listOf("filter-highlighter-input"), targets(setOf(LogViewSectionId.HIGHLIGHTERS)).map { it.id })
        assertEquals(listOf("filter-section-log-composition"), targets(setOf(LogViewSectionId.LOG_COMPOSITION)).map { it.id })
        assertEquals(
            listOf("filter-section-sequences", "sequence:s1", "manual:m1", "new-sequence"),
            targets(setOf(LogViewSectionId.SEQUENCES)).map { it.id },
        )
    }

    @Test
    fun sectionsWithoutTargetsAndNonFilterTilesContributeNothing() {
        assertTrue(targets(emptySet()).isEmpty())
        assertTrue(targets(setOf(LogViewSectionId.ISSUES, LogViewSectionId.LOG, LogViewSectionId.NOTES)).isEmpty())
    }

    @Test
    fun hiddenModeControlsStayHiddenEvenWhenTheirSectionsAreVisible() {
        val ids = targets(setOf(LogViewSectionId.TAGS, LogViewSectionId.MESSAGE_RULES), includeModeControls = false).map { it.id }
        assertTrue(ids.isEmpty())
    }
}
