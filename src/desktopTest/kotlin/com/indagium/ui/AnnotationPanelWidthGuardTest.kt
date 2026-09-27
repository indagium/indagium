package com.indagium.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * annotationPanelEffectiveMaxWidth is the window-relative guard that stops the right sidebar
 * (raised to ANNOTATION_PANEL_MAX_WIDTH = 1400f so a capture's device mirror can grow — see
 * AppState.kt) from squeezing LogViewer's weight(1f) share to nothing on a narrower window. See
 * FileView.kt/CompareView.kt for how the result is used: `min(annotationPanelWidth, this)` for the
 * rendered width, never rewriting the stored value itself.
 */
class AnnotationPanelWidthGuardTest {
    @Test
    fun narrowWindowClampsWellBelowTheStoredMaxWithFilterPanelHidden() {
        // 900dp row, no filter panel: reserve one sidebar divider (10) + log view floor (320).
        val effectiveMax = annotationPanelEffectiveMaxWidth(
            availableRowWidth = 900f,
            filterVisible = false,
            filterPanelWidth = 0f,
        )
        assertEquals(900f - PANEL_DIVIDER_WIDTH - LOG_VIEW_MIN_WIDTH, effectiveMax)
        assertTrue(effectiveMax < ANNOTATION_PANEL_MAX_WIDTH, "a narrow window must not offer the full stored ceiling")
    }

    @Test
    fun narrowWindowAlsoReservesTheFilterPanelAndItsOwnDividerWhenVisible() {
        // 1200dp leaves the raw reservation comfortably above ANNOTATION_PANEL_MIN_WIDTH, so this
        // actually exercises the two-divider arithmetic rather than just hitting the floor.
        val effectiveMax = annotationPanelEffectiveMaxWidth(
            availableRowWidth = 1200f,
            filterVisible = true,
            filterPanelWidth = 220f,
        )
        // Two dividers now: one after the filter panel, one before the sidebar.
        assertEquals(1200f - 220f - PANEL_DIVIDER_WIDTH - PANEL_DIVIDER_WIDTH - LOG_VIEW_MIN_WIDTH, effectiveMax)
    }

    @Test
    fun wideWindowReturnsTheFullStoredCeiling() {
        // Plenty of room: 3000dp comfortably clears filter panel + dividers + log view floor even
        // at the full ANNOTATION_PANEL_MAX_WIDTH, so the guard should not clip it further.
        val effectiveMax = annotationPanelEffectiveMaxWidth(
            availableRowWidth = 3000f,
            filterVisible = true,
            filterPanelWidth = 220f,
        )
        assertEquals(ANNOTATION_PANEL_MAX_WIDTH, effectiveMax)
    }

    @Test
    fun extremelyNarrowWindowFloorsAtAnnotationPanelMinWidthRatherThanGoingLower() {
        // Not enough room even for the log view floor — the sidebar still keeps its own minimum
        // rather than shrinking further (the log view is what gives, per the plan).
        val effectiveMax = annotationPanelEffectiveMaxWidth(
            availableRowWidth = 200f,
            filterVisible = true,
            filterPanelWidth = 220f,
        )
        assertEquals(ANNOTATION_PANEL_MIN_WIDTH, effectiveMax)
    }

    @Test
    fun zeroAvailableRowWidthAlsoFloorsAtTheMinimum() {
        // availableRowWidth is 0 before the first layout pass reports a real measurement (see
        // FileView's rowWidthPx starting at 0) — must not throw or go negative.
        val effectiveMax = annotationPanelEffectiveMaxWidth(
            availableRowWidth = 0f,
            filterVisible = false,
            filterPanelWidth = 0f,
        )
        assertEquals(ANNOTATION_PANEL_MIN_WIDTH, effectiveMax)
    }
}
