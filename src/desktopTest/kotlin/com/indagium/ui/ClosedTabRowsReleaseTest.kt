@file:Suppress("MagicNumber")

package com.indagium.ui

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.utils.AppendOnlyLogList
import com.indagium.utils.appendLogEntries
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Closing a tab whose rows live in an [AppendOnlyLogList] (a tailed or captured tab) explicitly
 * releases that store after a grace delay, then requests the heap trim, so a stale Compose snapshot
 * record of the old `tabs` list cannot keep the whole capture alive (see
 * [AppState.releaseClosedTabRowsLater]).
 */
class ClosedTabRowsReleaseTest {
    private fun entries(range: IntRange) = range.map { LogEntry(it, "10:00:00.000", LogLevel.I, "Tag", "msg $it") }

    private fun appendOnly(range: IntRange) = appendLogEntries(entries(1..3), entries(4..range.last)) as AppendOnlyLogList

    private fun newState(): AppState {
        val app = AppState(autosaveFile = Files.createTempFile("closed-rows-release-autosave", "").toFile(), autoExportNotes = false)
        app.closedTabRowsReleaseDelayMs = 150
        return app
    }

    // captureSessionId marks the tab as a capture so closing it asks for a trim whatever its size.
    private fun captureTab(id: String, rows: List<LogEntry>) = mkTab(id, "$id.log", rows).copy(captureSessionId = "session-$id")

    @Test
    fun closingATabReleasesItsRowsAfterTheGraceDelayThenRequestsTheTrim() {
        val app = newState()
        val rows = appendOnly(1..10)
        val events = mutableListOf<Pair<String, Boolean>>()
        val trimmed = CountDownLatch(1)
        app.heapTrimRequester = { reason ->
            synchronized(events) { events += reason to rows.isReleased }
            trimmed.countDown()
        }
        app.tabs = listOf(captureTab("t1", rows), mkTab("t2", "t2.log", entries(1..3)))

        app.closeTab("t1")

        assertFalse(rows.isReleased, "the rows must outlive the close by the grace delay")
        assertTrue(synchronized(events) { events.isEmpty() }, "no trim before the release: it would free nothing")
        assertTrue(trimmed.await(10, TimeUnit.SECONDS), "the release job must request the trim")
        assertTrue(rows.isReleased)
        // One trim only (the close-time request is suppressed), issued after the release.
        assertEquals(listOf("released closed capture rows" to true), synchronized(events) { events.toList() })
    }

    @Test
    fun aStoreStillUsedByARemainingTabIsNotReleased() {
        val app = newState()
        val shared = appendOnly(1..10)
        val trimReasons = mutableListOf<String>()
        val trimmed = CountDownLatch(1)
        app.heapTrimRequester = { reason ->
            synchronized(trimReasons) { trimReasons += reason }
            trimmed.countDown()
        }
        // t2 views the very same store (e.g. a duplicate sharing logData), so closing t1 must not drop it.
        app.tabs = listOf(captureTab("t1", shared), mkTab("t2", "t2.log", shared))

        app.closeTab("t1")

        assertTrue(trimmed.await(10, TimeUnit.SECONDS))
        assertFalse(shared.isReleased)
        assertEquals(1, shared[0].id, "a surviving tab keeps reading the rows")
        // Nothing was released, so the trim the close skipped is still issued, with its original reason.
        assertEquals(listOf("closed large or capture tab"), synchronized(trimReasons) { trimReasons.toList() })
    }

    @Test
    fun aPlainListTabClosesExactlyAsBefore() {
        val app = newState()
        val rows = entries(1..5)
        val trimReasons = mutableListOf<String>()
        app.heapTrimRequester = { reason -> trimReasons += reason }
        app.tabs = listOf(captureTab("t1", rows), mkTab("t2", "t2.log", entries(1..3)))

        app.closeTab("t1")

        // Synchronous, on the closing thread, with the original reason; nothing to release.
        assertEquals(listOf("closed large or capture tab"), trimReasons)
        assertEquals(entries(1..5), rows, "a plain list is never touched")
    }

    @Test
    fun aSmallNonCaptureTabIsReleasedButDoesNotRequestATrim() {
        val app = newState()
        val rows = appendOnly(1..10)
        val trimReasons = mutableListOf<String>()
        app.heapTrimRequester = { reason -> synchronized(trimReasons) { trimReasons += reason } }
        app.tabs = listOf(mkTab("t1", "t1.log", rows), mkTab("t2", "t2.log", entries(1..3)))

        app.closeTab("t1")

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!rows.isReleased && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(rows.isReleased)
        assertTrue(synchronized(trimReasons) { trimReasons.isEmpty() }, "close semantics for small tabs stay trim-free")
    }
}
