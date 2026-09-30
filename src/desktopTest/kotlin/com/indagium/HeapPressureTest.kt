package com.indagium

import com.indagium.utils.HeapPressure
import com.indagium.utils.HeapPressureDecision
import com.indagium.utils.HeapPressureMonitor
import com.indagium.utils.HeapSnapshot
import com.indagium.utils.nextHeapPressure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeapPressureTest {
    private fun next(current: HeapPressure, occupancy: Double, full: Boolean = false) =
        nextHeapPressure(current, occupancy, full)

    @Test
    fun normalRisesToWarningAtThreshold() {
        assertEquals(HeapPressureDecision(HeapPressure.NORMAL, false), next(HeapPressure.NORMAL, 0.69))
        assertEquals(HeapPressureDecision(HeapPressure.WARNING, false), next(HeapPressure.NORMAL, 0.70))
    }

    @Test
    fun warningHoldsInHysteresisBandAndDropsBelowIt() {
        assertEquals(HeapPressure.WARNING, next(HeapPressure.WARNING, 0.66).level)
        assertEquals(HeapPressure.NORMAL, next(HeapPressure.WARNING, 0.64).level)
        // A level that was never raised is not held by the band.
        assertEquals(HeapPressure.NORMAL, next(HeapPressure.NORMAL, 0.66).level)
    }

    @Test
    fun unconfirmedCriticalBandYieldsWarningAndAsksForConfirmation() {
        assertEquals(HeapPressureDecision(HeapPressure.WARNING, true), next(HeapPressure.NORMAL, 0.90))
        assertEquals(HeapPressureDecision(HeapPressure.WARNING, true), next(HeapPressure.WARNING, 0.85))
    }

    @Test
    fun confirmedCriticalBandYieldsCritical() {
        assertEquals(HeapPressureDecision(HeapPressure.CRITICAL, false), next(HeapPressure.WARNING, 0.85, full = true))
        assertEquals(HeapPressureDecision(HeapPressure.CRITICAL, false), next(HeapPressure.NORMAL, 0.95, full = true))
        assertEquals(HeapPressure.WARNING, next(HeapPressure.WARNING, 0.84, full = true).level)
    }

    @Test
    fun criticalHoldsAboveLowerBoundWithoutConfirmationAndDropsBelow() {
        assertEquals(HeapPressureDecision(HeapPressure.CRITICAL, false), next(HeapPressure.CRITICAL, 0.81))
        assertEquals(HeapPressureDecision(HeapPressure.WARNING, false), next(HeapPressure.CRITICAL, 0.79))
        assertEquals(HeapPressure.NORMAL, next(HeapPressure.CRITICAL, 0.60).level)
    }

    // --- Monitor logic without JMX ---

    private class Fixture(val max: Long = 1_000L) {
        var now = 1_000_000L
        var gcRequests = 0
        val changes = mutableListOf<Pair<HeapPressure, HeapSnapshot>>()
        var heapUsedNow = 0L
        val monitor = HeapPressureMonitor(
            maxBytes = { max },
            requestFullGc = { gcRequests++ },
            nowMs = { now },
            currentHeapUsedBytes = { heapUsedNow },
        )

        init {
            monitor.setOnChange { level, snap -> changes += level to snap }
        }
    }

    @Test
    fun youngGcAtCriticalRequestsOneConfirmationAndStaysWarning() {
        val f = Fixture()
        f.monitor.onGc(900, isFullGc = false)
        assertEquals(1, f.gcRequests)
        assertEquals(HeapPressure.WARNING, f.monitor.pressure)
        assertEquals(listOf(HeapPressure.WARNING), f.changes.map { it.first })
    }

    @Test
    fun confirmationIsRateLimitedToOncePerWindow() {
        val f = Fixture()
        f.monitor.onGc(900, false)
        f.now += 59_999
        f.monitor.onGc(900, false)
        f.monitor.onGc(950, false)
        assertEquals(1, f.gcRequests)
        assertEquals(HeapPressure.WARNING, f.monitor.pressure) // never escalates unconfirmed
        f.now += 1
        f.monitor.onGc(900, false)
        assertEquals(2, f.gcRequests)
    }

    @Test
    fun criticalOnlyAfterFullGcEventAndFiresOnChangeOncePerLevel() {
        val f = Fixture()
        f.monitor.onGc(900, false) // NORMAL -> WARNING, asks for confirmation
        f.monitor.onGc(910, false) // still WARNING: no change callback
        f.monitor.onGc(880, true) // confirmed -> CRITICAL
        f.monitor.onGc(870, false) // holds CRITICAL, no new confirmation needed
        f.monitor.onGc(860, true) // still CRITICAL
        assertEquals(listOf(HeapPressure.WARNING, HeapPressure.CRITICAL), f.changes.map { it.first })
        assertEquals(HeapPressure.CRITICAL, f.monitor.pressure)
        assertEquals(1, f.gcRequests)
        f.monitor.onGc(700, false) // below 0.80 -> WARNING
        f.monitor.onGc(300, false) // below 0.65 -> NORMAL
        assertEquals(
            listOf(HeapPressure.WARNING, HeapPressure.CRITICAL, HeapPressure.WARNING, HeapPressure.NORMAL),
            f.changes.map { it.first },
        )
        assertEquals(300L, f.changes.last().second.usedAfterGcBytes)
    }

    @Test
    fun fullGcBelowCriticalDeclinesEscalation() {
        val f = Fixture()
        f.monitor.onGc(900, false)
        f.monitor.onGc(700, true) // confirming GC shows garbage was the cause
        assertEquals(HeapPressure.WARNING, f.monitor.pressure)
        assertEquals(1, f.changes.size)
    }

    @Test
    fun estimatedFreeBytesUsesLastAfterGcReadingThenFallsBack() {
        val f = Fixture(max = 1_000L)
        f.heapUsedNow = 400
        assertNull(f.monitor.latestSnapshot)
        assertEquals(600L, f.monitor.estimatedFreeBytes())
        f.monitor.onGc(250, false)
        assertEquals(750L, f.monitor.estimatedFreeBytes())
        assertEquals(0.25, f.monitor.latestSnapshot!!.occupancy, 1e-9)
        f.monitor.onGc(5_000, true) // used above max must not go negative
        assertEquals(0L, f.monitor.estimatedFreeBytes())
    }

    @Test
    fun startAndStopOnRealJvmDoNotThrow() {
        val monitor = HeapPressureMonitor()
        monitor.start { _, _ -> }
        monitor.start { _, _ -> } // idempotent
        monitor.stop()
        monitor.stop()
        assertTrue(monitor.estimatedFreeBytes() >= 0)
    }
}
