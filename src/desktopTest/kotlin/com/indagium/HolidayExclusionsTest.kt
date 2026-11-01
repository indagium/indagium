// Pixel rectangles under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.ui.geometry.Rect
import com.indagium.ui.holiday.*
import com.indagium.ui.holiday.HolidayExclusions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class HolidayExclusionsTest {
    private val a = Rect(0f, 0f, 100f, 50f)
    private val b = Rect(200f, 0f, 300f, 80f)

    @Test
    fun noPathUntilAnAreaIsReported() {
        assertNull(HolidayExclusions().path())
    }

    @Test
    fun theUnionCoversEveryReportedArea() {
        val ex = HolidayExclusions()
        ex.set("a", a)
        ex.set("b", b)
        val bounds = assertNotNull(ex.path()).getBounds()
        assertEquals(Rect(0f, 0f, 300f, 80f), bounds)
    }

    @Test
    fun anUnchangedAreaWritesNothingAndKeepsThePath() {
        val ex = HolidayExclusions()
        ex.set("a", a)
        val version = ex.version
        val path = ex.path()
        ex.set("a", a.copy())
        assertEquals(version, ex.version)
        assertSame(path, ex.path())
    }

    @Test
    fun removingOrEmptyingAnAreaClearsItAndAnEmptyRectCountsAsRemoval() {
        val ex = HolidayExclusions()
        ex.set("a", a)
        ex.set("b", b)
        ex.remove("a")
        assertEquals(b, assertNotNull(ex.path()).getBounds())
        val before = ex.version
        ex.set("b", Rect(10f, 10f, 10f, 10f))
        assertNull(ex.path())
        assertEquals(before + 1, ex.version)
        ex.remove("missing")
        assertEquals(before + 1, ex.version)
    }
}
