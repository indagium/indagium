package com.indagium

import com.indagium.ui.followTailSampleDecision
import com.indagium.ui.isAtLastRow
import com.indagium.ui.newestRowScrollOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    // followTailSampleDecision: the user-intent sampler's own decision (see its doc in
    // LogViewer.kt), pinned against the exact five cases the two follow bugs turned on — a capture
    // starting with follow OFF by default, and opening the Unfiltered split turning both panels'
    // follow off. `null` means "the caller should leave followTail exactly as it is."

    // The very first emission after a LaunchedEffect (re)launches — a capture's first rows landing
    // before the first real follow-scroll, or the whole ItemList branch remounting when the
    // Unfiltered split opens — has nothing to compare against, so it must never flip anything.
    @Test
    fun initialSampleIsIgnored() {
        assertNull(followTailSampleDecision(previousSample = null, currentSample = 5 to 10, atLastRow = false))
        assertNull(followTailSampleDecision(previousSample = null, currentSample = 0 to 0, atLastRow = true))
    }

    @Test
    fun aGenuineBackwardMoveTurnsFollowOff() {
        // Earlier item index.
        assertEquals(false, followTailSampleDecision(previousSample = 10 to 0, currentSample = 9 to 0, atLastRow = false))
        // Same item, scrolled further up within it.
        assertEquals(false, followTailSampleDecision(previousSample = 10 to 50, currentSample = 10 to 20, atLastRow = false))
    }

    @Test
    fun reachingTheLastRowTurnsFollowOn() {
        assertEquals(true, followTailSampleDecision(previousSample = 3 to 0, currentSample = 20 to 0, atLastRow = true))
        // Even coming from what looks like a backward move — being at the bottom always wins.
        assertEquals(true, followTailSampleDecision(previousSample = 20 to 0, currentSample = 5 to 0, atLastRow = true))
    }

    @Test
    fun aForwardMoveThatDoesNotReachTheBottomLeavesFollowUnchanged() {
        assertNull(followTailSampleDecision(previousSample = 5 to 0, currentSample = 6 to 0, atLastRow = false))
        assertNull(followTailSampleDecision(previousSample = 5 to 10, currentSample = 5 to 40, atLastRow = false))
    }

    // A viewport resize or a remount that reports the same (index, offset) as the previous sample
    // is not evidence of a user scroll either way.
    @Test
    fun aResizeWithNoPositionChangeLeavesFollowUnchanged() {
        assertNull(followTailSampleDecision(previousSample = 12 to 30, currentSample = 12 to 30, atLastRow = false))
    }
}
