package com.indagium

import com.indagium.utils.HEAP_CONFIRM_MAX_INTERVAL_MS
import com.indagium.utils.HEAP_CONFIRM_MIN_INTERVAL_MS
import com.indagium.utils.HeapPressure
import com.indagium.utils.HeapPressureDecision
import com.indagium.utils.HeapPressureMonitor
import com.indagium.utils.HeapSnapshot
import com.indagium.utils.nextHeapPressure
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.management.ListenerNotFoundException
import javax.management.MBeanNotificationInfo
import javax.management.NotificationEmitter
import javax.management.NotificationFilter
import javax.management.NotificationListener
import kotlin.concurrent.thread
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
        val gcRequestTimes = mutableListOf<Long>()
        val changes = mutableListOf<Pair<HeapPressure, HeapSnapshot>>()
        var heapUsedNow = 0L
        val monitor = HeapPressureMonitor(
            maxBytes = { max },
            requestFullGc = {
                gcRequests++
                gcRequestTimes += now
            },
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

    // The live set sits at 0.82 (below CRITICAL) while every young GC reads 0.86: each confirming full
    // GC proves it is not critical, yet the old rule asked for another one every 60 s, forever.
    @Test
    fun aLiveSetJustBelowCriticalDoesNotGetAFullGcEveryMinute() {
        val f = Fixture()
        var seenRequests = 0
        repeat(360) { // one young GC every 10 s for an hour
            f.now += 10_000
            f.monitor.onGc(860, isFullGc = false)
            if (f.gcRequests > seenRequests) { // the requested confirming GC runs and reports the real live set
                seenRequests = f.gcRequests
                f.monitor.onGc(820, isFullGc = true)
            }
        }
        assertEquals(HeapPressure.WARNING, f.monitor.pressure, "never escalates: the confirmed live set is 0.82")
        assertTrue(f.gcRequests in 2..10, "expected a handful of backed-off confirmations in an hour, got ${f.gcRequests}")
        val gaps = f.gcRequestTimes.zipWithNext { a, b -> b - a }
        assertTrue(gaps.first() >= HEAP_CONFIRM_MIN_INTERVAL_MS, "first gap $gaps")
        assertEquals(gaps.sorted(), gaps, "the gap never shrinks while nothing changes: $gaps")
        assertTrue(gaps.last() >= 2 * HEAP_CONFIRM_MIN_INTERVAL_MS, "the backoff escalated: $gaps")
        assertTrue(gaps.all { it <= HEAP_CONFIRM_MAX_INTERVAL_MS + 10_000 }, "capped at 15 min: $gaps")
    }

    @Test
    fun youngReadingsAtOrBelowTheConfirmedBaselineNeverAskAgain() {
        val f = Fixture()
        f.monitor.onGc(900, false)
        assertEquals(1, f.gcRequests)
        f.monitor.onGc(840, true) // the confirming GC: live set 0.84
        f.now += 10 * HEAP_CONFIRM_MAX_INTERVAL_MS
        f.monitor.onGc(860, false) // 0.86 < 0.84 + 0.03: no new evidence
        f.monitor.onGc(855, false)
        assertEquals(1, f.gcRequests)
    }

    @Test
    fun aRisingLiveSetStillReachesCriticalOnceTheBackoffAllows() {
        val f = Fixture()
        f.monitor.onGc(900, false)
        f.monitor.onGc(820, true) // confirmed OK, baseline 0.82, backoff escalates after each request
        repeat(3) {
            f.now += HEAP_CONFIRM_MAX_INTERVAL_MS
            f.monitor.onGc(860, false)
            f.monitor.onGc(820, true)
        }
        // Now the live set genuinely grows: the next young reading is confirmed (backoff elapsed) ...
        f.now += HEAP_CONFIRM_MAX_INTERVAL_MS
        val before = f.gcRequests
        f.monitor.onGc(950, false)
        assertEquals(before + 1, f.gcRequests)
        // ... and the full GC shows it is real.
        f.monitor.onGc(910, true)
        assertEquals(HeapPressure.CRITICAL, f.monitor.pressure)
    }

    @Test
    fun aFullGcAtCriticalReachesItWithoutAnyBackoffWaiting() {
        val f = Fixture()
        f.monitor.onGc(900, false)
        f.monitor.onGc(820, true)
        f.monitor.onGc(950, true) // the JVM's own full GC: authoritative at once
        assertEquals(HeapPressure.CRITICAL, f.monitor.pressure)
    }

    @Test
    fun noConfirmationIsRequestedWhileAlreadyCritical() {
        val f = Fixture()
        f.monitor.onGc(900, false)
        f.monitor.onGc(900, true) // CRITICAL
        val requests = f.gcRequests
        f.now += 10 * HEAP_CONFIRM_MAX_INTERVAL_MS
        f.monitor.onGc(950, false)
        f.monitor.onGc(990, false)
        assertEquals(requests, f.gcRequests)
        assertEquals(HeapPressure.CRITICAL, f.monitor.pressure)
    }

    @Test
    fun aLevelChangeRestartsTheBackoff() {
        val f = Fixture()
        f.monitor.onGc(900, false) // request 1
        f.monitor.onGc(820, true)
        f.now += HEAP_CONFIRM_MIN_INTERVAL_MS
        f.monitor.onGc(860, false) // request 2: the gap is now 120 s
        f.monitor.onGc(820, true)
        f.monitor.onGc(300, true) // WARNING -> NORMAL: a level change, backoff restarts
        f.now += HEAP_CONFIRM_MIN_INTERVAL_MS
        f.monitor.onGc(900, false)
        assertEquals(3, f.gcRequests, "after a level change the first gap is the base interval again")
    }

    @Test
    fun readingsArePublishedThrottledToOnePerSecond() {
        val f = Fixture()
        val readings = mutableListOf<HeapSnapshot>()
        f.monitor.setOnReading { readings += it }
        f.monitor.onGc(100, false)
        f.now += 500
        f.monitor.onGc(110, false) // within 1 s of the last published reading: throttled
        f.now += 600
        f.monitor.onGc(120, false)
        assertEquals(listOf(100L, 120L), readings.map { it.usedAfterGcBytes })
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

    // Two collector beans can notify at once. The level decided first must be delivered first: a
    // slow thread that decided NORMAL->WARNING used to deliver after the thread that decided
    // WARNING->CRITICAL, leaving the consumer on WARNING while the monitor was CRITICAL.
    @Test
    fun levelChangesAreDeliveredInDecisionOrderEvenWhenAnEarlierCallbackIsSlow() {
        val f = Fixture()
        val delivered = CopyOnWriteArrayList<HeapPressure>()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        f.monitor.setOnChange { level, _ ->
            if (delivered.isEmpty()) {
                firstEntered.countDown()
                assertTrue(releaseFirst.await(10, TimeUnit.SECONDS))
            }
            delivered += level
        }
        val a = thread { f.monitor.onGc(750, isFullGc = false) } // NORMAL -> WARNING, callback parked
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val b = thread { f.monitor.onGc(900, isFullGc = true) } // WARNING -> CRITICAL, decided second
        b.join(5_000)
        assertEquals(HeapPressure.CRITICAL, f.monitor.pressure)

        releaseFirst.countDown()
        a.join(5_000)

        assertEquals(listOf(HeapPressure.WARNING, HeapPressure.CRITICAL), delivered.toList())
    }

    @Test
    fun readingsAndLevelChangesShareOneDeliveryOrder() {
        val f = Fixture()
        val events = CopyOnWriteArrayList<String>()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        f.monitor.setOnChange { level, _ ->
            events += "level:$level"
            firstEntered.countDown()
            assertTrue(releaseFirst.await(10, TimeUnit.SECONDS))
        }
        f.monitor.setOnReading { events += "reading:${it.usedAfterGcBytes}" }
        val a = thread { f.monitor.onGc(750, isFullGc = false) }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        f.now += 2_000
        val b = thread { f.monitor.onGc(760, isFullGc = false) } // same level, new reading
        b.join(5_000)
        releaseFirst.countDown()
        a.join(5_000)
        assertEquals(listOf("level:WARNING", "reading:750", "reading:760"), events.toList())
    }

    @Test
    fun aCallbackThatThrowsDoesNotStopLaterDeliveries() {
        val f = Fixture()
        val readings = mutableListOf<Long>()
        f.monitor.setOnChange { _, _ -> error("boom") }
        f.monitor.setOnReading { readings += it.usedAfterGcBytes }
        f.monitor.onGc(750, isFullGc = false)
        f.now += 2_000
        f.monitor.onGc(300, isFullGc = false)
        assertEquals(listOf(750L, 300L), readings)
    }

    // ---- listener registration ----

    private class FakeEmitter(private val failOnAdd: Boolean = false) : NotificationEmitter {
        val listeners = mutableListOf<NotificationListener>()

        override fun addNotificationListener(listener: NotificationListener, filter: NotificationFilter?, handback: Any?) {
            if (failOnAdd) error("bean refused the listener")
            listeners += listener
        }

        override fun removeNotificationListener(listener: NotificationListener) {
            if (!listeners.remove(listener)) throw ListenerNotFoundException()
        }

        override fun removeNotificationListener(listener: NotificationListener, filter: NotificationFilter?, handback: Any?) =
            removeNotificationListener(listener)

        override fun getNotificationInfo(): Array<MBeanNotificationInfo> = emptyArray()
    }

    // A bean that throws on add must not leave the listeners already added to earlier beans
    // untracked: stop() could not remove them and a later start() would double-process every GC.
    @Test
    fun aBeanThatRefusesTheListenerLeavesNoUntrackedListenerBehind() {
        val first = FakeEmitter()
        val broken = FakeEmitter(failOnAdd = true)
        val third = FakeEmitter()
        val monitor = HeapPressureMonitor(emitters = { listOf(first, broken, third) })

        monitor.start { _, _ -> }
        assertEquals(2, monitor.registeredListenerCount, "the healthy beans are still watched")
        assertEquals(1, first.listeners.size)
        assertEquals(1, third.listeners.size)

        monitor.stop()
        assertEquals(0, monitor.registeredListenerCount)
        assertTrue(first.listeners.isEmpty(), "stop() must remove the listener added before the failure")
        assertTrue(third.listeners.isEmpty())
    }

    @Test
    fun startAgainAfterAPartialFailureDoesNotDoubleRegister() {
        val first = FakeEmitter()
        val broken = FakeEmitter(failOnAdd = true)
        val monitor = HeapPressureMonitor(emitters = { listOf(first, broken) })

        monitor.start { _, _ -> }
        monitor.start { _, _ -> } // idempotent: replaces, never stacks
        assertEquals(1, first.listeners.size, "a second start must not add a second listener to the same bean")
        assertEquals(1, monitor.registeredListenerCount)

        monitor.stop()
        assertTrue(first.listeners.isEmpty())
    }
}
