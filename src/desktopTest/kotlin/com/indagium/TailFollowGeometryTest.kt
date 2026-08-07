package com.indagium

import com.indagium.ui.isAtLastRow
import com.indagium.ui.newestRowScrollOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The only headless-testable part of tail-follow: the surrounding Compose scroll machinery can't
// be exercised without a real composition, so the two decisions it makes are extracted as pure
// functions (beside centerAnchorIndex, which is factored out for the same reason) and pinned here.
class TailFollowGeometryTest {
    @Test
    fun lastRowVisibleCountsAsBeingAtTheNewestLine() {
        assertTrue(isAtLastRow(lastVisibleIndex = 42, lastRowIndex = 42))
    }

    // The trailing "tail-space" spacer sits one index past the last real row, so a viewport
    // showing it has necessarily already scrolled past the newest line. Treating that as "not yet
    // at the bottom" would make a slight overshoot un-follow the panel.
    @Test
    fun scrolledOntoTheTrailingSpacerStillCountsAsAtTheNewestLine() {
        assertTrue(isAtLastRow(lastVisibleIndex = 43, lastRowIndex = 42))
    }

    @Test
    fun scrolledUpAwayFromTheLastRowIsNotAtTheNewestLine() {
        assertFalse(isAtLastRow(lastVisibleIndex = 41, lastRowIndex = 42))
    }

    // No layout yet (or a genuinely empty list) — there is nothing to disagree with, and
    // defaulting to true is what lets the follow effect scroll on its first composition rather
    // than waiting for a "not following" reading it would never get.
    @Test
    fun anUnmeasuredViewportDefaultsToBeingAtTheNewestLine() {
        assertTrue(isAtLastRow(lastVisibleIndex = null, lastRowIndex = 42))
        assertTrue(isAtLastRow(lastVisibleIndex = null, lastRowIndex = 0))
    }

    // A negative offset of exactly one viewport height pulls the spacer's top up to the viewport's
    // bottom edge, which puts the last real row's bottom edge there too.
    @Test
    fun newestRowOffsetIsOneNegativeViewportHeight() {
        assertEquals(-800, newestRowScrollOffset(800))
        assertEquals(-1, newestRowScrollOffset(1))
    }

    // Layout not measured yet: scrollToItem(spacerIndex, 0) is a harmless no-op, whereas a
    // meaningless positive offset would scroll somewhere arbitrary.
    @Test
    fun anUnmeasuredViewportProducesNoScrollOffset() {
        assertEquals(0, newestRowScrollOffset(0))
        assertEquals(0, newestRowScrollOffset(-120))
    }
}
