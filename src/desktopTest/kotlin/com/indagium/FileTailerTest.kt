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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// macOS's WatchService is documented as coarser-grained than Linux's inotify-backed one (can fall
// back to polling every ~2s), so every await here is generous rather than tight — these tests
// assert correctness of what eventually arrives, not sub-second responsiveness.
private const val AWAIT_SECONDS = 8L
private const val PER_BYTE_PAUSE_MS = 120L

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

    // resume() is what makes a pause lossless: start() would re-derive the offset (replaying a
    // startOffset = 0 tailer from the top), resume() must continue from where the tailer stopped.
    @Test
    fun resumeContinuesFromTheSavedOffsetInsteadOfReplayingOrSkipping() {
        val dir = createTempDirectory("openlog-tail-resume").toFile()
        val file = File(dir, "out.log").apply { writeText("one\ntwo\n") }
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { received.addAll(it) }, pollIntervalMs = 50, startOffset = 0L)
        val scope = newScope()
        try {
            val first = tailer.start(scope)
            waitUntil { received.size >= 2 }
            runBlocking { first.cancelAndJoin() }
            val offsetAtPause = tailer.currentOffset

            // Written while no poll loop runs: must not be read until resume, and read exactly once.
            file.appendText("three\nfour\n")
            Thread.sleep(200)
            assertEquals(listOf("one", "two"), received.toList())
            assertEquals(offsetAtPause, tailer.currentOffset)

            val second = tailer.resume(scope)
            waitUntil { received.size >= 4 }
            Thread.sleep(200)
            assertEquals(listOf("one", "two", "three", "four"), received.toList())
            second.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun readToEndOfFileEmitsTheTrailingPartialLineOnlyWhenAskedTo() {
        val dir = createTempDirectory("openlog-tail").toFile()
        val file = File(dir, "out.log").apply { writeText("first\nsecond\nno newline at the end") }

        val ordinary = FileTailer(file, onNewLines = {}, startOffset = 0L)
        ordinary.start(newScope()).cancel()
        assertEquals(listOf("first", "second"), ordinary.readToEndOfFile())
        // Nothing more arrives, and the default never emits the unterminated tail.
        assertEquals(emptyList(), ordinary.readToEndOfFile())

        val optIn = FileTailer(file, onNewLines = {}, startOffset = 0L)
        optIn.start(newScope()).cancel()
        assertEquals(
            listOf("first", "second", "no newline at the end"),
            optIn.readToEndOfFile(includeTrailingPartialLine = true),
        )
        assertEquals(file.length(), optIn.currentOffset)
        // The partial line is emitted once, not again on a second drain.
        assertEquals(emptyList(), optIn.readToEndOfFile(includeTrailingPartialLine = true))
    }

    @Test
    fun trailingPartialLineOptInIgnoresAFileThatEndsWithANewlineOrACarriageReturn() {
        val dir = createTempDirectory("openlog-tail").toFile()
        val complete = File(dir, "complete.log").apply { writeText("a\nb\n") }
        val tailer = FileTailer(complete, onNewLines = {}, startOffset = 0L)
        assertEquals(listOf("a", "b"), tailer.readToEndOfFile(includeTrailingPartialLine = true))

        val carriageReturn = File(dir, "cr.log").apply { writeText("a\n\r") }
        val crTailer = FileTailer(carriageReturn, onNewLines = {}, startOffset = 0L)
        assertEquals(listOf("a"), crTailer.readToEndOfFile(includeTrailingPartialLine = true))
    }

    /** A tailer that has not started a poll loop: [FileTailer.readToEndOfFile] reads from [startOffset] on the caller's thread. */
    private fun syncTailer(file: File, startOffset: Long? = 0L) = FileTailer(file, onNewLines = {}, startOffset = startOffset)

    @Test
    fun aCarriageReturnOnlyFileIsReadAsLinesAndItsLastLineWaitsForTheByteAfterItsCarriageReturn() {
        val dir = createTempDirectory("openlog-tail-cr").toFile()
        val file = File(dir, "cr.log").apply { writeText("one\rtwo\rthree\r") }
        val tailer = syncTailer(file)

        // "three\r" could still become "three\r\n": it is not a line yet.
        assertEquals(listOf("one", "two"), tailer.readToEndOfFile())
        assertEquals(emptyList(), tailer.readToEndOfFile())

        file.appendText("four\r")
        assertEquals(listOf("three"), tailer.readToEndOfFile(), "the next byte settles the held carriage return")

        // The writer finished: the drain's opt-in ends the held line at the end of the file.
        assertEquals(listOf("four"), tailer.readToEndOfFile(includeTrailingPartialLine = true))
        assertEquals(file.length(), tailer.currentOffset)
    }

    @Test
    fun aCarriageReturnLineFeedCutBetweenTwoReadsIsOneLineEnd() {
        val dir = createTempDirectory("openlog-tail-crlf-cut").toFile()
        val file = File(dir, "crlf.log").apply { writeText("a\r\nb\r") }
        val tailer = syncTailer(file)

        assertEquals(listOf("a"), tailer.readToEndOfFile())

        file.appendText("\nc\r\n")
        assertEquals(listOf("b", "c"), tailer.readToEndOfFile(), "the line feed after a held carriage return ends the same line")
    }

    @Test
    fun aLoneCarriageReturnEndedByTheDrainDoesNotTurnTheLineFeedThatFollowsItIntoAnEmptyLine() {
        val dir = createTempDirectory("openlog-tail-drain-cr").toFile()
        val file = File(dir, "drain.log").apply { writeText("a\rb\r") }
        val tailer = syncTailer(file)

        assertEquals(listOf("a", "b"), tailer.readToEndOfFile(includeTrailingPartialLine = true))

        file.appendText("\nc\n")
        assertEquals(listOf("c"), tailer.readToEndOfFile(), "the \\n completes the \\r\\n already delivered as one line")
    }

    @Test
    fun aTailStartedRightAfterACarriageReturnSkipsTheLineFeedThatCompletesIt() {
        val dir = createTempDirectory("openlog-tail-start-cr").toFile()
        val file = File(dir, "start.log").apply { writeText("first\r") }
        val tailer = syncTailer(file, startOffset = null)
        tailer.start(newScope()).cancel()

        file.appendText("\nsecond\n")
        assertEquals(listOf("second"), tailer.readToEndOfFile())

        // Not after a carriage return: a line feed that is the next byte is an empty line.
        val plain = File(dir, "plain.log").apply { writeText("first\n") }
        val plainTailer = syncTailer(plain, startOffset = null)
        plainTailer.start(newScope()).cancel()
        plain.appendText("\nsecond\n")
        assertEquals(listOf("", "second"), plainTailer.readToEndOfFile())
    }

    @Test
    fun mixedLineBreaksSplitLikeAFreshOpenOfTheFile() {
        val dir = createTempDirectory("openlog-tail-mixed").toFile()
        val text = "a\nb\r\nc\rd\n\ne\r\rf\r\n"
        val file = File(dir, "mixed.log").apply { writeText(text) }

        val tailed = syncTailer(file).readToEndOfFile()

        assertEquals(text.reader().buffered().readLines(), tailed)
        assertEquals(listOf("a", "b", "c", "d", "", "e", "", "f"), tailed)
    }

    @Test
    fun carriageReturnsArriveOneByteAtATimeAsThePollLoopSeesThem() {
        val dir = createTempDirectory("openlog-tail-bytes").toFile()
        val file = File(dir, "bytes.log").apply { writeText("") }
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { lines -> received.addAll(lines) }, pollIntervalMs = 50)
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            // The file ends up as "a\rb\r\nc\n\rd": CR only, a CRLF written in two steps, an LF alone, an empty CR line, an open last line.
            val steps = listOf("a", "\r", "b", "\r", "\n", "c", "\n", "\r", "d")
            for (step in steps) {
                file.appendText(step)
                Thread.sleep(PER_BYTE_PAUSE_MS)
            }
            val expected = listOf("a", "b", "c", "")
            waitUntil { received.size >= expected.size }
            Thread.sleep(PER_BYTE_PAUSE_MS)
            assertEquals(expected, received.toList(), "a CRLF cut between two polls is one line end, not two")
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun aCarriageReturnTerminatedLineLongerThanTheReadChunkCapStillCompletes() {
        val dir = createTempDirectory("openlog-tail-cr-long").toFile()
        val file = File(dir, "long.log").apply { writeText("") }
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { lines -> received.addAll(lines) }, pollIntervalMs = 100)
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            val longLine = "x".repeat(6 * 1024 * 1024)
            file.appendText("$longLine\rnext\r")

            waitUntil(timeoutMs = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS)) { received.isNotEmpty() }
            assertEquals(listOf(longLine), received.toList(), "the line after the long one is still held, its \\r may be half of a CRLF")
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun drainToEndOfFileDeliversALargeBacklogInBoundedBatches() {
        val dir = createTempDirectory("openlog-tail").toFile()
        val file = File(dir, "out.log")
        val line = "x".repeat(1023)
        // ~9 MiB of complete lines plus a partial tail: more than two of FileTailer's 4 MiB chunks.
        file.bufferedWriter().use { out -> repeat(9 * 1024) { out.write(line); out.write("\n") } }
        file.appendText("tail")
        val batches = mutableListOf<Int>()
        val tailer = FileTailer(file, onNewLines = {}, startOffset = 0L)
        tailer.drainToEndOfFile(includeTrailingPartialLine = true) { batches += it.size }

        assertEquals(9 * 1024 + 1, batches.sum())
        assertTrue(batches.size >= 3, "expected several bounded batches, got $batches")
        assertTrue(batches.dropLast(1).all { it <= 4 * 1024 }, "no batch may exceed the 4 MiB chunk cap: $batches")
        assertEquals(1, batches.last(), "the trailing partial line arrives as its own final batch")
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

    // A consumer that keeps state across lines resets it in onRestart, so the callback must come
    // exactly once per shrink and before the first line of the rewritten file.
    @Test
    fun onRestartFiresOnceWhenTheFileIsRewrittenAndBeforeTheNewLines() {
        val dir = createTempDirectory("openlog-tail-restart").toFile()
        val file = File(dir, "out.log").apply { writeText("old one\nold two\n") }
        val events = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(
            file,
            onNewLines = { lines -> events.add("lines:" + lines.joinToString(",")) },
            pollIntervalMs = 50,
            onRestart = { events.add("restart") },
        )
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            file.appendText("more old\n")
            waitUntil { events.isNotEmpty() }
            file.writeText("new a\nnew b\n")
            waitUntil { events.any { it == "lines:new a,new b" } }
            Thread.sleep(300)
            assertEquals(listOf("lines:more old", "restart", "lines:new a,new b"), events.toList())
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    // Truncating to nothing leaves no line to deliver, so the offset has to move back on its own or
    // every following poll would announce the same shrink again.
    @Test
    fun onRestartFiresOnlyOnceForATruncationToAnEmptyFile() {
        val dir = createTempDirectory("openlog-tail-restart-empty").toFile()
        val file = File(dir, "out.log").apply { writeText("old one\nold two\n") }
        val restarts = AtomicInteger()
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { received.addAll(it) }, pollIntervalMs = 50, onRestart = { restarts.incrementAndGet() })
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            file.writeText("")
            waitUntil { restarts.get() >= 1 }
            Thread.sleep(400)
            assertEquals(1, restarts.get())
            file.appendText("fresh\n")
            waitUntil { received.isNotEmpty() }
            assertEquals(listOf("fresh"), received.toList())
            assertEquals(1, restarts.get())
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    // The final catch-up read of a stopping tail goes through the same restart announcement.
    @Test
    fun drainToEndOfFileAnnouncesARestartBeforeTheRewrittenLines() {
        val dir = createTempDirectory("openlog-tail-restart-drain").toFile()
        val file = File(dir, "out.log").apply { writeText("old one\nold two\n") }
        val events = mutableListOf<String>()
        val tailer = FileTailer(file, onNewLines = {}, pollIntervalMs = 50, startOffset = 0L, onRestart = { events.add("restart") })
        tailer.drainToEndOfFile { events.add("lines:" + it.joinToString(",")) }
        file.writeText("new\n")
        tailer.drainToEndOfFile { events.add("lines:" + it.joinToString(",")) }
        assertEquals(listOf("lines:old one,old two", "restart", "lines:new"), events)
    }

    // A tail started at an unfinished last line's first byte (offset 0 for a one-line file) holds an offset far below the end of the
    // file. A rewrite shorter than the file was but longer than that offset is still a rotation, not a continuation.
    @Test
    fun aRewriteShorterThanBeforeButPastTheOffsetStillAnnouncesARestart() {
        val dir = createTempDirectory("openlog-tail-restart-partial").toFile()
        val file = File(dir, "out.log").apply { writeText("the one unfinished line of the old file") }
        val events = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(
            file,
            onNewLines = { lines -> events.add("lines:" + lines.joinToString(",")) },
            pollIntervalMs = 50,
            startOffset = 0L,
            onRestart = { events.add("restart") },
        )
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            file.writeText("new a\nnew b\n")
            waitUntil { events.any { it.startsWith("lines:") } }
            Thread.sleep(300)
            assertEquals(listOf("restart", "lines:new a,new b"), events.toList())
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    // The same blind spot without a started-at-the-line tail: a partial line held back leaves the offset below the file's end.
    @Test
    fun aRewriteShorterThanBeforeButPastAHeldBackPartialLinesOffsetAnnouncesARestart() {
        val dir = createTempDirectory("openlog-tail-restart-held").toFile()
        val file = File(dir, "out.log").apply { writeText("one\ntwo\nthree is not finished") }
        val events = mutableListOf<String>()
        val tailer = FileTailer(
            file,
            onNewLines = {},
            pollIntervalMs = 60_000,
            startOffset = 0L,
            onRestart = { events.add("restart") },
        )
        val scope = newScope()
        val job = tailer.start(scope)
        try {
            tailer.drainToEndOfFile { events.add("lines:" + it.joinToString(",")) }
            assertEquals(8L, tailer.currentOffset, "the partial line is held back")
            // Longer than the offset (8), shorter than the old file (29).
            file.writeText("aaa\nbbbb\ncc\n")
            tailer.drainToEndOfFile { events.add("lines:" + it.joinToString(",")) }
            assertEquals(listOf("lines:one,two", "restart", "lines:aaa,bbbb,cc"), events)
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    // A pause must not forget how long the file was: a rewrite while paused is announced on resume.
    @Test
    fun aRewriteWhilePausedIsAnnouncedAfterResume() {
        val dir = createTempDirectory("openlog-tail-restart-paused").toFile()
        val file = File(dir, "out.log").apply { writeText("an unfinished line that is long") }
        val restarts = AtomicInteger()
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { received.addAll(it) }, pollIntervalMs = 50, startOffset = 0L, onRestart = { restarts.incrementAndGet() })
        val scope = newScope()
        try {
            runBlocking { tailer.start(scope).cancelAndJoin() }
            file.writeText("x\ny\n")
            val resumed = tailer.resume(scope)
            waitUntil { received.size >= 2 }
            assertEquals(1, restarts.get())
            assertEquals(listOf("x", "y"), received.toList())
            resumed.cancel()
        } finally {
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
