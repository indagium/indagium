package com.indagium.ui

import com.indagium.utils.HeapPressure
import com.indagium.utils.HeapPressureMonitor
import com.indagium.utils.HeapSnapshot
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeapPressureStateTest {
    private val gb = 1024L * 1024L * 1024L

    private fun newApp() =
        AppState(autosaveFile = Files.createTempFile("heap-pressure-state", "").toFile(), autoExportNotes = false)

    @Test
    fun aFreshAppStateIsNormalAndHasNotStartedMonitoring() {
        val app = newApp()
        try {
            assertEquals(HeapPressure.NORMAL, app.heapPressure)
            assertNull(app.heapSnapshot)
            assertEquals(0, app.heapPressureMonitor.registeredListenerCount)
        } finally {
            app.close()
        }
    }

    @Test
    fun monitorChangesUpdateStateThroughTheInjectedMonitor() {
        val app = newApp()
        try {
            val monitor = HeapPressureMonitor(maxBytes = { 10 * gb }, requestFullGc = {}, currentHeapUsedBytes = { 0L })
            app.heapPressureMonitor = monitor
            // Drive the monitor's own callback seam (no JMX): the same wiring start() installs.
            monitor.setOnChange(app::onHeapPressureChanged)

            monitor.onGc(usedAfterGcBytes = 8 * gb, isFullGc = false)
            assertEquals(HeapPressure.WARNING, app.heapPressure)
            assertEquals(HeapSnapshot(8 * gb, 10 * gb), app.heapSnapshot)

            monitor.onGc(usedAfterGcBytes = 9 * gb, isFullGc = true)
            assertEquals(HeapPressure.CRITICAL, app.heapPressure)
            assertEquals(9 * gb, app.heapSnapshot?.usedAfterGcBytes)

            monitor.onGc(usedAfterGcBytes = 1 * gb, isFullGc = true)
            assertEquals(HeapPressure.NORMAL, app.heapPressure)
            assertEquals(9 * gb, app.heapFreeBytesEstimate())
        } finally {
            app.close()
        }
    }

    // The banner text reads heapSnapshot: it used to be written only when the LEVEL changed, so at
    // NORMAL it stayed null and after a change it froze at the reading that caused it.
    @Test
    fun everyPublishedGcReadingReachesTheBannerSnapshotWithoutALevelChange() {
        val app = newApp()
        try {
            var now = 1_000_000L
            val monitor = HeapPressureMonitor(maxBytes = { 10 * gb }, requestFullGc = {}, nowMs = { now }, currentHeapUsedBytes = { 0L })
            app.heapPressureMonitor = monitor
            monitor.setOnChange(app::onHeapPressureChanged)
            monitor.setOnReading(app::onHeapReading)

            monitor.onGc(usedAfterGcBytes = 3 * gb, isFullGc = false) // NORMAL: no level change
            assertEquals(HeapPressure.NORMAL, app.heapPressure)
            assertEquals(HeapSnapshot(3 * gb, 10 * gb), app.heapSnapshot)

            monitor.onGc(usedAfterGcBytes = 8 * gb, isFullGc = false) // -> WARNING, throttled reading is the same instant
            now += 2_000
            monitor.onGc(usedAfterGcBytes = 7 * gb, isFullGc = false) // still WARNING: only the reading callback fires
            assertEquals(HeapPressure.WARNING, app.heapPressure)
            assertEquals(7 * gb, app.heapSnapshot?.usedAfterGcBytes, "the snapshot tracks the heap, not the level change")
            assertEquals(HeapSnapshot(7 * gb, 10 * gb), app.currentHeapSnapshot())
        } finally {
            app.close()
        }
    }

    @Test
    fun startHeapPressureMonitoringIsIdempotent() {
        val app = newApp()
        try {
            val monitor = HeapPressureMonitor(maxBytes = { 10 * gb }, requestFullGc = {}, currentHeapUsedBytes = { 0L })
            app.heapPressureMonitor = monitor
            app.startHeapPressureMonitoring()
            val afterFirst = monitor.registeredListenerCount
            assertTrue(afterFirst > 0, "start registers JMX listeners")
            app.startHeapPressureMonitoring()
            assertEquals(afterFirst, monitor.registeredListenerCount, "second start adds nothing")
            // start() on the real monitor installed the AppState callback; a level change reaches the state.
            monitor.onGc(usedAfterGcBytes = 8 * gb, isFullGc = false)
            assertEquals(HeapPressure.WARNING, app.heapPressure)
            app.close()
            assertEquals(0, monitor.registeredListenerCount, "close stops the monitor")
        } finally {
            app.close()
        }
    }

    // Two collector beans notify concurrently: the thread that decided WARNING is slow to deliver, the
    // one that decided CRITICAL is not. AppState must still end at the monitor's real level (a stale
    // WARNING would let resumeCaptureLogView proceed at real CRITICAL).
    @Test
    fun interleavedNotificationsLeaveAppStateAtTheMonitorsRealLevel() {
        val app = newApp()
        try {
            val monitor = HeapPressureMonitor(maxBytes = { 10 * gb }, requestFullGc = {}, currentHeapUsedBytes = { 0L })
            app.heapPressureMonitor = monitor
            val warningEntered = CountDownLatch(1)
            val releaseWarning = CountDownLatch(1)
            monitor.setOnChange { level, snapshot ->
                if (level == HeapPressure.WARNING) {
                    warningEntered.countDown()
                    assertTrue(releaseWarning.await(10, TimeUnit.SECONDS))
                }
                app.onHeapPressureChanged(level, snapshot)
            }

            val a = thread { monitor.onGc(usedAfterGcBytes = 8 * gb, isFullGc = false) } // NORMAL -> WARNING
            assertTrue(warningEntered.await(5, TimeUnit.SECONDS))
            val b = thread { monitor.onGc(usedAfterGcBytes = 9 * gb, isFullGc = true) } // WARNING -> CRITICAL
            b.join(5_000)
            releaseWarning.countDown()
            a.join(5_000)

            assertEquals(HeapPressure.CRITICAL, monitor.pressure)
            assertEquals(HeapPressure.CRITICAL, app.heapPressure)
            assertEquals(9 * gb, app.heapSnapshot?.usedAfterGcBytes)
        } finally {
            app.close()
        }
    }
}
