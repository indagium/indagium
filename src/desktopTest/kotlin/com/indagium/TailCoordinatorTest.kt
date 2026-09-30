package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.LARGE_FILE_MODE_ROWS
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val AWAIT_SECONDS = 10L

// Phase 2a of the "capture as a streaming LogTab" plan: plumbing primitives the eventual capture
// wiring needs, each independently testable without any capture code. These drive AppState's
// public startTailing/stopTailing/drainAndStopTailing against a real file on disk, the same shape
// ConcurrentStateMutationTest already uses for tailing.
class TailCoordinatorTest {
    private fun newState(dir: File) = AppState(autosaveFile = File(dir, "state.cache"))

    private fun waitUntil(timeoutMs: Long = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS), condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }

    // TailCoordinator.kt:111 used to be `t.logData.maxOfOrNull { it.id }` — a full scan of the
    // whole (ever-growing) tab on every batch. Entry ids are strictly increasing by construction
    // (EntryIdMap.kt, Filter.kt:219), so the fix reads lastOrNull().id instead — behaviorally
    // equivalent for valid data (max and last always agree), so this is a correctness/regression
    // check on the resulting id, not a differentiator of which implementation ran.
    @Test
    fun appendTailedLinesUsesLastIdNotMaxForTheNextBatchesIds() {
        val dir = createTempDirectory("openlog-tail-nextid").toFile()
        val file = File(dir, "app.log").apply { writeText("") }
        val state = newState(dir)
        val tabId = checkNotNull(state.openFile(file))
        waitUntil { !state.isLoading }

        val seeded = (1..500).map { i -> LogEntry(i, "10:00:00.000", LogLevel.I, "Tag", "seed $i") }
        state.upTab(tabId) { it.copy(logData = seeded, rmap = emptyMap()) }

        state.startTailing(tabId)
        try {
            file.appendText("new line\n")
            waitUntil { state.tab(tabId)!!.logData.size == 501 }

            val newEntry = state.tab(tabId)!!.logData.last()
            assertEquals(501, newEntry.id, "the new entry must start right after the last entry's id (500), not a max scan")
        } finally {
            state.stopTailing(tabId)
        }
    }

    // largeFileMode is normally decided once at open time from file.length() and never
    // re-evaluated — a tail has no file-length signal, so appendTailedLines must flip it itself
    // once the row count crosses LARGE_FILE_MODE_ROWS.
    @Test
    fun tailingFlipsLargeFileModeAtTheRowCountThreshold() {
        val dir = createTempDirectory("openlog-tail-largefile").toFile()
        val file = File(dir, "app.log").apply { writeText("") }
        val state = newState(dir)
        val tabId = checkNotNull(state.openFile(file))
        waitUntil { !state.isLoading }

        val seeded = (1 until LARGE_FILE_MODE_ROWS).map { i -> LogEntry(i, "10:00:00.000", LogLevel.I, "Tag", "seed $i") }
        state.upTab(tabId) { it.copy(logData = seeded, rmap = emptyMap(), largeFileMode = false) }
        assertFalse(state.tab(tabId)!!.largeFileMode, "fixture precondition: not yet at the threshold")

        state.startTailing(tabId)
        try {
            // Two more lines cross LARGE_FILE_MODE_ROWS (seeded ends one short of it).
            file.appendText("line a\nline b\n")
            waitUntil { state.tab(tabId)!!.logData.size >= LARGE_FILE_MODE_ROWS }

            assertTrue(state.tab(tabId)!!.largeFileMode, "row count crossing LARGE_FILE_MODE_ROWS must flip largeFileMode on")
        } finally {
            state.stopTailing(tabId)
        }
    }

    // largeFileMode's flip is deliberately one-way — proves a batch that stays below the threshold
    // leaves it false, i.e. this isn't accidentally always-true.
    @Test
    fun tailingLeavesLargeFileModeOffBelowTheThreshold() {
        val dir = createTempDirectory("openlog-tail-notlargefile").toFile()
        val file = File(dir, "app.log").apply { writeText("") }
        val state = newState(dir)
        val tabId = checkNotNull(state.openFile(file))
        waitUntil { !state.isLoading }

        state.startTailing(tabId)
        try {
            file.appendText("just one small line\n")
            waitUntil { state.tab(tabId)!!.logData.isNotEmpty() }

            assertFalse(state.tab(tabId)!!.largeFileMode)
        } finally {
            state.stopTailing(tabId)
        }
    }

    // The bug this guards: stopTailing()'s plain Job.cancel() can leave up to one poll interval of
    // already-written bytes unread. Exercises the actual race by appending and draining WITHOUT
    // waiting for a poll tick first — if drainAndStopTailing degraded to stopTailing's cancel-only
    // behaviour, this would flake/fail because the lines would still be sitting unread on disk.
    @Test
    fun drainAndStopTailingCatchesUpLinesWrittenJustBeforeStoppingWithoutWaitingForAPollTick() {
        val dir = createTempDirectory("openlog-tail-drain").toFile()
        val file = File(dir, "app.log").apply { writeText("") }
        val state = newState(dir)
        val tabId = checkNotNull(state.openFile(file))
        waitUntil { !state.isLoading }

        state.startTailing(tabId)
        val lines = (1..25).map { "appended line $it" }
        file.appendText(lines.joinToString("\n", postfix = "\n"))
        // Deliberately no wait here — draining must not depend on the tailer's own poll tick
        // having already fired.
        state.drainAndStopTailing(tabId)

        assertEquals(lines.size, state.tab(tabId)!!.logData.size, "every appended line must be present immediately after drain, not just after the next poll")
        assertFalse(state.tab(tabId)!!.tailing, "drainAndStopTailing must still leave the tab in the stopped state")
    }

    @Test
    fun drainAndStopTailingOnATabThatIsNotTailingIsASafeNoOp() {
        val dir = createTempDirectory("openlog-tail-drain-noop").toFile()
        val file = File(dir, "app.log").apply { writeText("x\n") }
        val state = newState(dir)
        val tabId = checkNotNull(state.openFile(file))
        waitUntil { !state.isLoading }

        state.drainAndStopTailing(tabId)

        assertFalse(state.tab(tabId)!!.tailing)
    }

    // End-to-end version of FileTailerTest's own cancelAndJoin race test, through the real
    // AppState/TailCoordinator stack, exercising the actual lock (appState.stateLock) the reported
    // race depends on rather than a stand-in. Forces the tailer's in-flight append to genuinely
    // BLOCK trying to acquire stateLock (held here by a dedicated thread) — exactly the "read
    // bytes, advanced offset, about to call onNewLines, has NOT yet acquired stateLock" state the
    // race requires — then triggers drainAndStopTailing while a newer line is already sitting
    // unread on disk. If drainAndStopTailing used a bare Job.cancel() instead of cancelAndJoin(),
    // the catch-up read could win the race for stateLock once the holder releases it (Java monitors
    // are not FIFO-fair) and append "newer line" before the still-in-flight "older line" — breaking
    // both the file-write order and the strictly-increasing-id invariant. With cancelAndJoin(), the
    // drain thread cannot even begin reading until the tailer's own in-flight append has fully
    // landed, so this is deterministic under the fix.
    @Test
    fun drainAndStopTailingNeverReordersLogDataEvenWhenTheInFlightAppendIsBlockedOnStateLock() {
        val dir = createTempDirectory("openlog-tail-race-statelock").toFile()
        val file = File(dir, "app.log").apply { writeText("") }
        val state = newState(dir)
        val tabId = checkNotNull(state.openFile(file))
        waitUntil { !state.isLoading }

        state.startTailing(tabId)

        val holderAcquired = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holderThread = Thread {
            synchronized(state.stateLock) {
                holderAcquired.countDown()
                releaseHolder.await(AWAIT_SECONDS, TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true; start() }
        assertTrue(holderAcquired.await(AWAIT_SECONDS, TimeUnit.SECONDS), "fixture precondition: the holder thread must have acquired stateLock")

        file.appendText("older line\n")
        // Longer than one default poll interval (500ms): by the time this returns, the tailer must
        // have read "older line", advanced its own offset past it, and be blocked acquiring
        // stateLock behind the holder thread above.
        Thread.sleep(700)
        assertTrue(state.tab(tabId)!!.logData.isEmpty(), "fixture precondition: the tailer's append must still be blocked on stateLock, not yet landed")

        // Lands on disk while the older line's append is still blocked — the bytes a buggy
        // (bare-cancel) drain would race ahead and process first.
        file.appendText("newer line\n")

        val drainThread = Thread { state.drainAndStopTailing(tabId) }.apply { start() }
        // Give the drain thread a real chance to race ahead if it didn't actually wait.
        Thread.sleep(300)
        assertTrue(state.tab(tabId)!!.logData.isEmpty(), "the drain must not be able to land anything while the older line's append is still in flight")

        releaseHolder.countDown()
        drainThread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))
        holderThread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))

        val logData = state.tab(tabId)!!.logData
        val ids = logData.map { it.id }
        assertEquals(ids.sorted(), ids, "logData must stay sorted by strictly increasing id, never reordered")
        assertEquals(
            listOf("older line", "newer line"),
            logData.map { it.msg },
            "rows must land in the order they were written to the file, not the order threads happened to win the lock",
        )
        assertFalse(state.tab(tabId)!!.tailing)
    }
}
