package com.openlog

import com.openlog.ui.horizontalScrollDelta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxHorizontalScrollTest {
    @Test
    fun leftButtonScrollsNegative() {
        assertEquals(-20f, horizontalScrollDelta(button = 4, stepPx = 20f))
    }

    @Test
    fun rightButtonScrollsPositive() {
        assertEquals(20f, horizontalScrollDelta(button = 5, stepPx = 20f))
    }

    @Test
    fun ordinaryButtonsAreIgnored() {
        assertNull(horizontalScrollDelta(button = 1, stepPx = 20f))
        assertNull(horizontalScrollDelta(button = 2, stepPx = 20f))
        assertNull(horizontalScrollDelta(button = 3, stepPx = 20f))
    }

    @Test
    fun unrelatedExtraButtonsAreIgnored() {
        // X buttons 8/9 (mouse back/forward) surface as Java 6/7 — must stay unhandled so this
        // bridge never hijacks an unrelated mouse button.
        assertNull(horizontalScrollDelta(button = 6, stepPx = 20f))
        assertNull(horizontalScrollDelta(button = 7, stepPx = 20f))
    }

    @Test
    fun zeroStepYieldsZeroDelta() {
        // The mapping is button 4 -> -stepPx, so at stepPx = 0f the result is -0.0f, which
        // kotlin.test.assertEquals(0f, ...) rejects (Float.equals treats -0.0 != 0.0) — use the
        // primitive `==` operator instead, where -0.0f == 0.0f is true, matching "no scroll".
        assertTrue(horizontalScrollDelta(button = 4, stepPx = 0f) == 0f)
    }
}
