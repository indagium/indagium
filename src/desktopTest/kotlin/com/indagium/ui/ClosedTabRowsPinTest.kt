@file:Suppress("MagicNumber")

package com.indagium.ui

import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.utils.AppendOnlyLogList
import com.indagium.utils.ReleasedLogListException
import com.indagium.utils.appendLogEntries
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reader-pin registry ([AppState.pinRows]): a background job that captured a tab snapshot keeps
 * the closed tab's rows readable past the release delay, and the last unpin performs the release (and
 * its trim) that was deferred, so neither a half-written export nor a permanent leak can result.
 */
class ClosedTabRowsPinTest {
    private val delayMs = 150L

    private fun entries(range: IntRange) = range.map { LogEntry(it, "10:00:00.000", LogLevel.I, "Tag", "msg $it") }

    private fun appendOnly(count: Int) = appendLogEntries(entries(1..3), entries(4..count)) as AppendOnlyLogList

    private fun newState(autoExportNotes: Boolean = false, notesDir: java.io.File? = null): AppState {
        val autosave = Files.createTempFile("closed-rows-pin-autosave", "").toFile()
        val app = if (notesDir != null) {
            AppState(autosaveFile = autosave, autoExportNotes = autoExportNotes, notesDir = notesDir)
        } else {
            AppState(autosaveFile = autosave, autoExportNotes = autoExportNotes)
        }
        app.closedTabRowsReleaseDelayMs = delayMs
        return app
    }

    private fun captureTab(id: String, rows: List<LogEntry>) = mkTab(id, "$id.log", rows).copy(captureSessionId = "session-$id")

    /** Long enough that the delayed release job has certainly run (several multiples of [delayMs]). */
    private fun pastReleaseDelay() = Thread.sleep(delayMs * 6)

    private class TrimSink {
        val events = mutableListOf<Pair<String, Boolean>>()

        fun record(reason: String, rows: AppendOnlyLogList) = synchronized(events) { events += reason to rows.isReleased }

        fun snapshot() = synchronized(events) { events.toList() }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), what)
    }

    private fun readAll(rows: List<LogEntry>) = rows.map { it.id }

    @Test
    fun aPinnedStoreOutlivesTheReleaseDelayAndIsReleasedByTheLastUnpinWithExactlyOneTrim() {
        val app = newState()
        val rows = appendOnly(10)
        val sink = TrimSink()
        app.heapTrimRequester = { reason -> sink.record(reason, rows) }
        val capture = captureTab("t1", rows)
        app.tabs = listOf(capture, mkTab("t2", "t2.log", entries(1..3)))
        // The snapshot an export/Save job captured before the user closed the tab.
        val pin = app.pinRows(capture)

        app.closeTab("t1")
        pastReleaseDelay()

        assertFalse(rows.isReleased, "a pinned store must survive the release delay")
        assertEquals((1..10).toList(), readAll(rows), "the job reads every row")
        assertTrue(sink.snapshot().isEmpty(), "no trim while the release is deferred: it would free nothing")

        pin.close()

        assertTrue(rows.isReleased, "the last unpin releases the store right away")
        assertEquals(listOf("released closed capture rows" to true), sink.snapshot(), "one trim, after the release")
        assertFailsWith<ReleasedLogListException> { rows[0] }
    }

    @Test
    fun aMergeStylePinHeldAcrossTheDelayDoesNotLeakTheStoreForever() {
        // Bug 2: the old merge pin skipped the release permanently; unpinning must now release it.
        val app = newState()
        val rows = appendOnly(10)
        val capture = captureTab("t1", rows)
        app.tabs = listOf(capture, mkTab("t2", "t2.log", entries(1..3)))
        val pin = app.pinRows(capture)
        app.closeTab("t1")
        pastReleaseDelay()
        assertFalse(rows.isReleased)

        pin.close()

        assertTrue(rows.isReleased)
    }

    @Test
    fun theStoreIsReleasedOnlyAfterTheLastOfSeveralPinsCloses() {
        val app = newState()
        val rows = appendOnly(10)
        val sink = TrimSink()
        app.heapTrimRequester = { reason -> sink.record(reason, rows) }
        val capture = captureTab("t1", rows)
        app.tabs = listOf(capture, mkTab("t2", "t2.log", entries(1..3)))
        val first = app.pinRows(capture)
        val second = app.pinRows(capture)
        app.closeTab("t1")
        pastReleaseDelay()

        first.close()
        first.close() // idempotent: must not consume the second pin's count
        assertFalse(rows.isReleased, "one reader is still running")
        assertTrue(sink.snapshot().isEmpty())

        second.close()
        assertTrue(rows.isReleased)
        assertEquals(1, sink.snapshot().size)
        second.close()
        assertEquals(1, sink.snapshot().size, "a repeated close must not trim or release again")
    }

    @Test
    fun aPinClosedBeforeTheDelayLeavesTheNormalDelayedReleaseInCharge() {
        val app = newState()
        val rows = appendOnly(10)
        val sink = TrimSink()
        app.heapTrimRequester = { reason -> sink.record(reason, rows) }
        val capture = captureTab("t1", rows)
        app.tabs = listOf(capture, mkTab("t2", "t2.log", entries(1..3)))
        app.pinRows(capture).close()

        app.closeTab("t1")

        waitUntil("released by the delayed job") { rows.isReleased }
        waitUntil("trim requested") { sink.snapshot().isNotEmpty() }
        assertEquals(listOf("released closed capture rows" to true), sink.snapshot())
    }

    @Test
    fun aStoreAdoptedByATabWhileDeferredIsNotReleasedByTheUnpin() {
        val app = newState()
        val shared = appendOnly(10)
        val sink = TrimSink()
        app.heapTrimRequester = { reason -> sink.record(reason, shared) }
        val capture = captureTab("t1", shared)
        app.tabs = listOf(capture, mkTab("t2", "t2.log", entries(1..3)))
        val pin = app.pinRows(capture)
        app.closeTab("t1")
        pastReleaseDelay()
        // A tab now reads the same store (a rows-sharing duplicate): it must never lose its rows.
        app.tabs = app.tabs + mkTab("t3", "t3.log", shared)

        pin.close()

        assertFalse(shared.isReleased)
        assertEquals(1, shared[0].id)
        // Nothing released, so the trim the close skipped is still issued with its original reason.
        assertEquals(listOf("closed large or capture tab" to false), sink.snapshot())
    }

    @Test
    fun aStoreUsedByARemainingTabIsNeverPendingSoUnpinningItDoesNothing() {
        val app = newState()
        val shared = appendOnly(10)
        val sink = TrimSink()
        app.heapTrimRequester = { reason -> sink.record(reason, shared) }
        val capture = captureTab("t1", shared)
        app.tabs = listOf(capture, mkTab("t2", "t2.log", shared))
        val pin = app.pinRows(capture)
        app.closeTab("t1")
        waitUntil("the skipped release still trims") { sink.snapshot().isNotEmpty() }
        val trimsAfterDelay = sink.snapshot()

        pin.close()

        assertFalse(shared.isReleased)
        assertEquals(trimsAfterDelay, sink.snapshot(), "unpin must not add a trim for a store that was never pending")
    }

    @Test
    fun aPlainListTabGetsANoOpPin() {
        val app = newState()
        val tab = captureTab("t1", entries(1..5))
        app.tabs = listOf(tab)
        val pin = app.pinRows(tab)
        pin.close()
        pin.close()
        assertEquals(entries(1..5), tab.logData)
    }

    @Test
    fun withPinnedRowsUnpinsWhenTheBlockThrows() {
        val app = newState()
        val rows = appendOnly(10)
        val capture = captureTab("t1", rows)
        app.tabs = listOf(capture, mkTab("t2", "t2.log", entries(1..3)))

        assertFailsWith<IllegalStateException> {
            app.withPinnedRows(capture) {
                app.closeTab("t1")
                pastReleaseDelay()
                assertFalse(rows.isReleased, "pinned for the whole block")
                error("boom")
            }
        }

        assertTrue(rows.isReleased, "the throwing block still unpinned, which released the deferred store")
    }

    @Test
    fun aNoteAutoExportReleasesItsPinSoTheClosedCaptureStillGetsReleased() {
        val notesDir = Files.createTempDirectory("closed-rows-pin-notes").toFile()
        val app = newState(autoExportNotes = true, notesDir = notesDir)
        val rows = appendOnly(10)
        app.tabs = listOf(captureTab("t1", rows), mkTab("t2", "t2.log", entries(1..3)))

        app.upAnn("t1") { t ->
            t.copy(annotations = t.annotations.copy(blocks = t.annotations.blocks + AnnBlock.Note("n1", "hello")))
        }
        waitUntil("the auto-export wrote the note") { notesDir.walkTopDown().any { it.isFile && it.extension == "md" } }
        app.closeTab("t1")

        waitUntil("no pin leaked: the closed capture is released") { rows.isReleased }
    }
}
