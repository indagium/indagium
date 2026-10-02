package com.indagium.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * filterPanelEffectiveMaxWidth is the window-relative guard for the left Filters panel (stored
 * ceiling FILTER_PANEL_MAX_WIDTH = 1400f, see AppState.kt). It reserves only the right sidebar's
 * MINIMUM width, and annotationPanelEffectiveMaxWidth then clamps the sidebar from the filter
 * panel's RENDERED width — the asymmetry that keeps the two guards from depending on each other.
 */
class FilterPanelWidthGuardTest {
    @Test
    fun wideWindowLetsThePanelReachTheStoredCeiling() {
        val effectiveMax = filterPanelEffectiveMaxWidth(availableRowWidth = 4000f, rightSidebarVisible = true)
        assertEquals(FILTER_PANEL_MAX_WIDTH, effectiveMax)
        assertTrue(FILTER_PANEL_MAX_WIDTH > 420f, "the panel must be resizable well past its old 420dp cap")
    }

    @Test
    fun withoutASidebarOnlyTheLogViewAndOneDividerAreReserved() {
        val effectiveMax = filterPanelEffectiveMaxWidth(availableRowWidth = 1000f, rightSidebarVisible = false)
        assertEquals(1000f - PANEL_DIVIDER_WIDTH - LOG_VIEW_MIN_WIDTH, effectiveMax)
    }

    @Test
    fun withTheSidebarVisibleItKeepsItsMinimumWidthAndDivider() {
        val effectiveMax = filterPanelEffectiveMaxWidth(availableRowWidth = 1400f, rightSidebarVisible = true)
        assertEquals(
            1400f - PANEL_DIVIDER_WIDTH - (ANNOTATION_PANEL_MIN_WIDTH + PANEL_DIVIDER_WIDTH) - LOG_VIEW_MIN_WIDTH,
            effectiveMax,
        )
        // Rendering the panel at its maximum still leaves the sidebar at least its minimum.
        val sidebarMax = annotationPanelEffectiveMaxWidth(
            availableRowWidth = 1400f,
            filterVisible = true,
            filterPanelWidth = effectiveMax,
        )
        assertEquals(ANNOTATION_PANEL_MIN_WIDTH, sidebarMax)
    }

    @Test
    fun tinyWindowKeepsThePanelsOwnMinimum() {
        assertEquals(FILTER_PANEL_MIN_WIDTH, filterPanelEffectiveMaxWidth(availableRowWidth = 300f, rightSidebarVisible = true))
        assertEquals(FILTER_PANEL_MIN_WIDTH, filterPanelEffectiveMaxWidth(availableRowWidth = 0f, rightSidebarVisible = false))
    }

    @Test
    fun theSidebarGuardGivenTheRenderedFilterWidthStillLeavesTheLogViewItsMinimum() {
        for (rowWidth in listOf(1000f, 1280f, 1600f, 2400f)) {
            val filterRendered = minOf(900f, filterPanelEffectiveMaxWidth(rowWidth, rightSidebarVisible = true))
            val sidebarMax = annotationPanelEffectiveMaxWidth(
                availableRowWidth = rowWidth,
                filterVisible = true,
                filterPanelWidth = filterRendered,
            )
            val logView = rowWidth - filterRendered - PANEL_DIVIDER_WIDTH - sidebarMax - PANEL_DIVIDER_WIDTH
            assertTrue(logView >= LOG_VIEW_MIN_WIDTH, "row $rowWidth leaves the log view only $logView")
            assertTrue(sidebarMax >= ANNOTATION_PANEL_MIN_WIDTH)
        }
    }
}
