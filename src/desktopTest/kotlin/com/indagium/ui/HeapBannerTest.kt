package com.indagium.ui

import com.indagium.utils.HeapPressure
import com.indagium.utils.HeapSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeapBannerTest {
    private val gb = 1024L * 1024L * 1024L
    private val mb = 1024L * 1024L

    @Test
    fun usageLabelUsesOneDecimalGbAndDropsTrailingZero() {
        assertEquals("9.1 of 12 GB", heapUsageLabel(HeapSnapshot((9.1 * gb).toLong(), 12 * gb)))
        assertEquals("11.2 of 12 GB", heapUsageLabel(HeapSnapshot((11.2 * gb).toLong(), 12 * gb)))
        assertEquals("2 of 4 GB", heapUsageLabel(HeapSnapshot(2 * gb, 4 * gb)))
    }

    @Test
    fun usageLabelUsesWholeMbBelowOneGb() {
        assertEquals("600 of 768 MB", heapUsageLabel(HeapSnapshot(600 * mb, 768 * mb)))
        assertEquals("512 MB of 12 GB", heapUsageLabel(HeapSnapshot(512 * mb, 12 * gb)))
    }

    @Test
    fun bannerTextPerLevel() {
        val snap = HeapSnapshot((9.1 * gb).toLong(), 12 * gb)
        assertNull(heapBannerText(HeapPressure.NORMAL, snap))
        assertEquals(
            "Memory is running low — Indagium uses 9.1 of 12 GB. Close tabs you don't need.",
            heapBannerText(HeapPressure.WARNING, snap),
        )
        val critical = assertNotNull(heapBannerText(HeapPressure.CRITICAL, HeapSnapshot((11.2 * gb).toLong(), 12 * gb)))
        assertTrue(critical.startsWith("Memory is almost full — Indagium uses 11.2 of 12 GB."))
        assertTrue("paused" !in critical, "no pause claim until a capture tab is actually paused (W3)")
        assertNotNull(heapBannerText(HeapPressure.WARNING, null))
    }

    @Test
    fun visibilityAndDismissalRules() {
        assertFalse(heapBannerVisible(HeapPressure.NORMAL, null))
        assertTrue(heapBannerVisible(HeapPressure.WARNING, null))
        assertFalse(heapBannerVisible(HeapPressure.WARNING, HeapPressure.WARNING))
        assertTrue(heapBannerVisible(HeapPressure.CRITICAL, HeapPressure.WARNING), "rising level shows again")
        assertFalse(heapBannerVisible(HeapPressure.CRITICAL, HeapPressure.CRITICAL))
    }

    @Test
    fun dismissalResetsAtNormalAndFollowsTheLevelDown() {
        assertNull(nextDismissedHeapLevel(HeapPressure.NORMAL, HeapPressure.CRITICAL))
        assertEquals(HeapPressure.WARNING, nextDismissedHeapLevel(HeapPressure.WARNING, HeapPressure.CRITICAL))
        assertEquals(HeapPressure.WARNING, nextDismissedHeapLevel(HeapPressure.CRITICAL, HeapPressure.WARNING))
        assertNull(nextDismissedHeapLevel(HeapPressure.WARNING, null))
        // CRITICAL dismissed, falls to WARNING (still hidden), then rises again: shown.
        val afterFall = nextDismissedHeapLevel(HeapPressure.WARNING, HeapPressure.CRITICAL)
        assertFalse(heapBannerVisible(HeapPressure.WARNING, afterFall))
        assertTrue(heapBannerVisible(HeapPressure.CRITICAL, afterFall))
    }
}
