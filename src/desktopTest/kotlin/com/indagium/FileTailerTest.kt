package com.indagium

import com.indagium.utils.FileTailer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// macOS's WatchService is documented as coarser-grained than Linux's inotify-backed one (can fall
// back to polling every ~2s), so every await here is generous rather than tight — these tests
// assert correctness of what eventually arrives, not sub-second responsiveness.
private const val AWAIT_SECONDS = 8L

class FileTailerTest {
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun emitsOnlyNewlyAppendedCompleteLines() {
        val dir = createTempDirectory("openlog-tail").toFile()
        val file = File(dir, "out.log").apply { writeText("existing line before tailing starts\n") }
        val received = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        val tailer = FileTailer(file, onNewLines = { lines -> received.addAll(lines); latch.countDown() }, pollIntervalMs = 100)
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            file.appendText("new line one\nnew line two\n")
            assertTrue(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS), "expected a flush within ${AWAIT_SECONDS}s")
            waitUntil { received.size >= 2 }
            assertEquals(listOf("new line one", "new line two"), received.toList())
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun doesNotEmitAPartialLineUntilItsNewlineArrives() {
        val dir = createTempDirectory("openlog-tail").toFile()
        val file = File(dir, "out.log").apply { writeText("") }
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { lines -> received.addAll(lines) }, pollIntervalMs = 100)
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            file.appendText("no newline yet")
            Thread.sleep(500)
            assertTrue(received.isEmpty(), "a line with no trailing newline shouldn't be emitted yet")

            file.appendText(" - now complete\n")
            waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS)) { received.isNotEmpty() }
            assertEquals(listOf("no newline yet - now complete"), received.toList())
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun deliversEveryAppendedLineInOrderWithoutDuplicationAcrossMultipleFlushes() {
        val dir = createTempDirectory("openlog-tail").toFile()
        val file = File(dir, "out.log").apply { writeText("") }
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { lines -> received.addAll(lines) }, pollIntervalMs = 100)
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            file.appendText("one\n")
            waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS)) { received.size >= 1 }
            file.appendText("two\n")
            waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS)) { received.size >= 2 }
            file.appendText("three\n")
            waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS)) { received.size >= 3 }

            assertEquals(listOf("one", "two", "three"), received.toList())
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun resumesFromEndOfFileAfterTruncationInsteadOfReplayingFromZero() {
        val dir = createTempDirectory("openlog-tail").toFile()
        val file = File(dir, "out.log").apply { writeText("first session line\n") }
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { lines -> received.addAll(lines) }, pollIntervalMs = 100)
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            file.appendText("more\n")
            waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS)) { received.isNotEmpty() }
            received.clear()

            // Simulate external log rotation: the file shrinks (a new session starts writing).
            file.writeText("second session start\n")
            waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS)) { received.isNotEmpty() }
            assertEquals(listOf("second session start"), received.toList())
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    // P-04: readNewLines() caps a single read at a few MB instead of allocating one buffer sized
    // to the whole unread delta. A burst bigger than that cap must still be drained completely,
    // in order, without loss or duplication — start()'s inner drain loop must keep re-reading
    // within one poll tick rather than only picking up one chunk per tick.
    @Test
    fun deliversEveryLineOfABurstLargerThanTheReadChunkCap() {
        val dir = createTempDirectory("openlog-tail-burst").toFile()
        val file = File(dir, "out.log").apply { writeText("") }
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { lines -> received.addAll(lines) }, pollIntervalMs = 100)
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            // ~200 bytes/line * 40_000 lines ≈ 8MB, comfortably past the (currently 4MB) chunk cap.
            val lineCount = 40_000
            val expected = (1..lineCount).map { "line $it ${"x".repeat(150)}" }
            file.appendText(expected.joinToString("\n", postfix = "\n"))

            waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS)) { received.size >= lineCount }
            assertEquals(expected, received.toList())
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    // The rare edge case the chunking fallback exists for: a single line longer than the read
    // chunk cap must still complete once its newline arrives, not stall forever because the chunk
    // boundary landed mid-line.
    @Test
    fun deliversASingleLineLongerThanTheReadChunkCap() {
        val dir = createTempDirectory("openlog-tail-longline").toFile()
        val file = File(dir, "out.log").apply { writeText("") }
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { lines -> received.addAll(lines) }, pollIntervalMs = 100)
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            // ~6MB single line, past the (currently 4MB) chunk cap.
            val longLine = "x".repeat(6 * 1024 * 1024)
            file.appendText("$longLine\n")

            waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS)) { received.isNotEmpty() }
            assertEquals(listOf(longLine), received.toList())
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    // Empirical check for the plan's flagged risk, not an assumption: prints real observed
    // append-to-flush latency on this dev machine. A generous ceiling catches a genuinely broken
    // watch registration without asserting a specific sub-second number that may not hold on
    // every OS/filesystem.
    @Test
    fun detectsAnAppendWithinAReasonableLatencyOnThisMachine() {
        val dir = createTempDirectory("openlog-tail").toFile()
        val file = File(dir, "out.log").apply { writeText("") }
        val latch = CountDownLatch(1)
        val tailer = FileTailer(file, onNewLines = { latch.countDown() }, pollIntervalMs = 200)
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            val startNanos = System.nanoTime()
            file.appendText("ping\n")
            assertTrue(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS), "append was not detected within ${AWAIT_SECONDS}s")
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            println("[FileTailerTest] observed append-to-flush latency on this machine: ${elapsedMs}ms")
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    // Phase 2a: FileTailer(startOffset) — a live-capture tab (Phase 2b) opens its backing file
    // empty and must read from byte 0 rather than the v1 default of "only new growth", because the
    // recorder may already have written lines before the tailer could start(). See FileTailer's
    // own class doc for the full rationale.
    @Test
    fun startOffsetZeroReplaysExistingContent() {
        val dir = createTempDirectory("openlog-tail-replay").toFile()
        val file = File(dir, "out.log").apply { writeText("first\nsecond\nthird\n") }
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { lines -> received.addAll(lines) }, pollIntervalMs = 100, startOffset = 0L)
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS)) { received.size >= 3 }
            assertEquals(listOf("first", "second", "third"), received.toList())
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    // Regression guard: the default (no startOffset passed) must still be the v1 behaviour —
    // pre-existing content is never replayed, only growth after start() is emitted.
    @Test
    fun defaultStartOffsetStillSkipsExistingContent() {
        val dir = createTempDirectory("openlog-tail-noreplay").toFile()
        val file = File(dir, "out.log").apply { writeText("pre-existing\n") }
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { lines -> received.addAll(lines) }, pollIntervalMs = 100)
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            file.appendText("grown after start\n")
            waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS)) { received.isNotEmpty() }
            assertEquals(listOf("grown after start"), received.toList(), "pre-existing content must not be replayed by default")
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    // Reproduces the concurrency bug flagged in review of ui/TailCoordinator.drainAndStopTailing:
    // a bare Job.cancel() returns as soon as cancellation is REQUESTED, not once the coroutine has
    // actually stopped running. FileTailer's own poll loop advances its offset BEFORE calling
    // onNewLines (see readToEndOfFile's doc) — so a caller that does `job.cancel()` immediately
    // followed by `readToEndOfFile()` can read from the already-advanced offset and process NEWER
    // bytes while an OLDER batch's onNewLines call is still genuinely in flight (e.g. blocked
    // acquiring appState.stateLock in production), reordering the two relative to each other and
    // breaking the strictly-increasing-id invariant downstream code relies on (utils/EntryIdMap.kt,
    // utils/Filter.kt:219). drainAndStopTailing's fix is `runBlocking { job.cancelAndJoin() }`
    // instead of a bare cancel() — this test proves that specific pattern (not just "eventually
    // consistent" timing) actually blocks the reader until the in-flight batch has fully returned,
    // by making onNewLines take real, observable time via a latch the test controls, and recording
    // call order. Run this same test against a bare `job.cancel()` in place of cancelAndJoin() and
    // it fails: the mid-test callOrder assertion below would already contain "batch2-line1" while
    // batch1's callback is still blocked.
    @Test
    fun cancelAndJoinBeforeReadToEndOfFileNeverReordersAnInFlightBatchBehindTheCatchUpRead() {
        val dir = createTempDirectory("openlog-tail-race").toFile()
        val file = File(dir, "out.log").apply { writeText("") }

        val callOrder = CopyOnWriteArrayList<String>()
        val firstBatchEntered = CountDownLatch(1)
        val releaseFirstBatch = CountDownLatch(1)

        val tailer = FileTailer(
            file,
            onNewLines = { lines ->
                if (lines.first() == "batch1-line1") {
                    // Proves this call is genuinely IN FLIGHT (not merely scheduled) when the
                    // "drain" thread below acts — mirrors appendTailedLines blocking on
                    // appState.stateLock in production.
                    firstBatchEntered.countDown()
                    releaseFirstBatch.await(AWAIT_SECONDS, TimeUnit.SECONDS)
                }
                callOrder.add(lines.first())
            },
            pollIntervalMs = 50,
        )
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            file.appendText("batch1-line1\n")
            assertTrue(firstBatchEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS), "batch1's onNewLines must have started")

            // Lands while batch1's onNewLines is still blocked inside the callback — exactly the
            // bytes readToEndOfFile() would race to read if cancelAndJoin() didn't actually wait.
            file.appendText("batch2-line1\n")

            // Runs on another thread, exactly like TailCoordinator.drainAndStopTailing does.
            val drainThread = Thread {
                runBlocking { job.cancelAndJoin() }
                val remaining = tailer.readToEndOfFile()
                if (remaining.isNotEmpty()) callOrder.add(remaining.first())
            }.apply { start() }

            // Give the drain thread a real chance to race ahead if cancelAndJoin() didn't wait.
            Thread.sleep(300)
            assertEquals(emptyList<String>(), callOrder.toList(), "the drain must not proceed while batch1's onNewLines is still in flight")

            releaseFirstBatch.countDown()
            drainThread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))

            assertEquals(
                listOf("batch1-line1", "batch2-line1"),
                callOrder.toList(),
                "batch1 must be fully processed before the catch-up read's batch2 is handled",
            )
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    private fun waitUntil(timeoutMs: Long = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS), condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }
}
