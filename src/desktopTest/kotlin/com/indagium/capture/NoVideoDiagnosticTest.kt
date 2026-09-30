@file:Suppress("MagicNumber")

package com.indagium.capture

import com.indagium.capture.mirror.MirrorPathStats
import com.indagium.capture.mirror.StreamPathCounters
import java.lang.management.ManagementFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoVideoDiagnosticTest {
    @Test
    fun triggerFiresExactlyOnceOnceTheThresholdPassesWithoutAVideoPacket() {
        val clock = AtomicLong(0)
        val trigger = NoVideoDiagnosticTrigger(clock::get, recordVideo = true, firstVideoPacketArrived = { false })
        assertFalse(trigger.poll())
        clock.set(CAPTURE_NO_VIDEO_AFTER_MS - 1)
        assertFalse(trigger.poll(), "just under the threshold")
        clock.set(CAPTURE_NO_VIDEO_AFTER_MS)
        assertTrue(trigger.poll(), "fires at the threshold")
        assertFalse(trigger.poll(), "never a second time")
        clock.set(CAPTURE_NO_VIDEO_AFTER_MS * 10)
        assertFalse(trigger.poll())
        assertTrue(trigger.isFinished)
    }

    @Test
    fun triggerNeverFiresWhenVideoRecordingIsOff() {
        val clock = AtomicLong(CAPTURE_NO_VIDEO_AFTER_MS * 5)
        val trigger = NoVideoDiagnosticTrigger(clock::get, recordVideo = false, firstVideoPacketArrived = { false })
        assertTrue(trigger.isFinished)
        assertFalse(trigger.poll())
    }

    @Test
    fun triggerNeverFiresOnceTheFirstPacketHasArrived() {
        val clock = AtomicLong(0)
        val arrived = AtomicBoolean(false)
        val trigger = NoVideoDiagnosticTrigger(clock::get, recordVideo = true, firstVideoPacketArrived = arrived::get)
        arrived.set(true) // arrived before the threshold...
        clock.set(CAPTURE_NO_VIDEO_AFTER_MS * 3) // ...and the clock runs well past it
        assertFalse(trigger.poll())
        assertTrue(trigger.isFinished)
    }

    @Test
    fun triggerHonoursACustomThreshold() {
        val clock = AtomicLong(0)
        val trigger = NoVideoDiagnosticTrigger(clock::get, true, { false }, thresholdMs = 100)
        clock.set(99)
        assertFalse(trigger.poll())
        clock.set(100)
        assertTrue(trigger.poll())
    }

    @Test
    fun dumpIncludesBlockedThreadWithItsLockAndOwner() {
        val monitor = Any()
        val ownerHolding = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val owner = Thread({
            synchronized(monitor) {
                ownerHolding.countDown()
                releaseOwner.await(20, TimeUnit.SECONDS)
            }
        }, "diag-test-owner").apply { isDaemon = true }
        val blocked = Thread({ synchronized(monitor) { /* acquired after the owner releases */ } }, "diag-test-blocked")
            .apply { isDaemon = true }
        try {
            owner.start()
            assertTrue(ownerHolding.await(5, TimeUnit.SECONDS))
            blocked.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (blocked.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(Thread.State.BLOCKED, blocked.state)

            val entries = ThreadDumpReport.capture()
            val text = entries.joinToString("\n")

            val waitLine = Regex("WAIT diag-test-blocked\\((\\d+)\\) BLOCKED on \\S+ owner=diag-test-owner\\((\\d+)\\)")
                .find(text)
            assertTrue(waitLine != null, "BLOCKED thread with lock name and owner expected in:\n$text")
            assertEquals(blocked.threadId().toString(), waitLine.groupValues[1])
            assertEquals(owner.threadId().toString(), waitLine.groupValues[2])
            assertTrue(text.contains("T${blocked.threadId()} diag-test-blocked(${blocked.threadId()}) BLOCKED on"), "full stack header")
            assertTrue(text.contains("T${owner.threadId()} diag-test-owner("), "the owner's stack is included even though its name matches nothing")
            assertTrue(text.contains("holdsMonitors=["), "the owner's held monitor is listed")
            entries.forEach { assertTrue(it.length <= ThreadDumpReport.DEFAULT_MAX_ENTRY_CHARS, "entry too long: ${it.length}") }
        } finally {
            releaseOwner.countDown()
            owner.join(5_000)
            blocked.join(5_000)
        }
    }

    @Test
    fun dumpIncludesFocusThreadsFullStackAndNoSlashesSurvive() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val focus = Thread({
            started.countDown()
            release.await(20, TimeUnit.SECONDS)
        }, "capture-diag/test worker").apply { isDaemon = true }
        try {
            focus.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val text = ThreadDumpReport.capture().joinToString("\n")
            // The '/' in the thread name is replaced so AppLogger's path redaction cannot eat the line.
            assertTrue(text.contains("capture-diag_test worker(${focus.threadId()})"), text.take(2_000))
            assertFalse(text.contains('/'), "no slash may survive into a log entry")
        } finally {
            release.countDown()
            focus.join(5_000)
        }
    }

    @Test
    fun dumpIsBoundedAndSaysWhatItOmitted() {
        val threads = (1..40).map { index ->
            Thread({ Thread.sleep(20_000) }, "capture-bulk-$index").apply { isDaemon = true; start() }
        }
        try {
            val infos = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true).filterNotNull()
            val limit = 6_000
            val entries = ThreadDumpReport.format(infos, maxTotalChars = limit, maxEntryChars = 900)
            assertTrue(entries.sumOf { it.length } <= limit, "total ${entries.sumOf { it.length }} must stay within $limit")
            assertTrue(entries.all { it.length <= 900 })
            assertTrue(entries.joinToString("\n").contains("TRUNCATED"), "omissions must be stated")

            val unbounded = ThreadDumpReport.format(infos)
            assertTrue(unbounded.sumOf { it.length } <= ThreadDumpReport.DEFAULT_MAX_TOTAL_CHARS)
            assertFalse(unbounded.joinToString("\n").contains("TRUNCATED"))
        } finally {
            threads.forEach { it.interrupt() }
        }
    }

    @Test
    fun stackFramesAreCappedAtFortyPerThread() {
        val depth = 80
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun recurse(level: Int) {
            if (level == 0) {
                reached.countDown()
                release.await(20, TimeUnit.SECONDS)
            } else {
                recurse(level - 1)
            }
        }
        val deep = Thread({ recurse(depth) }, "capture-deep").apply { isDaemon = true }
        try {
            deep.start()
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            val text = ThreadDumpReport.capture().joinToString("\n")
            assertTrue(text.contains("T${deep.threadId()} frames 1-"), text.take(2_000))
            assertTrue(Regex("T${deep.threadId()} \\(\\d+ deeper frames omitted\\)").containsMatchIn(text))
        } finally {
            release.countDown()
            deep.join(5_000)
        }
    }

    @Test
    fun reportWritesCountersHeapAndThreadEntriesOnceAndSurvivesAFailingStep() {
        val stats = MirrorPathStats({ 1_234L }, log = {})
        stats.video.bytes(10)
        val emitted = CopyOnWriteArrayList<String>()
        reportNoVideoDiagnostic(
            deviceSerial = "emulator-5554",
            sessionId = "s1",
            elapsedMs = 8_250,
            stats = stats,
            heapSummary = { error("monitor exploded") },
            threadDump = { listOf("a", "b") },
            emit = { emitted += it },
        )
        assertTrue(emitted[0].startsWith("$NO_VIDEO_DIAGNOSTIC_PREFIX: no video packet"), emitted[0])
        assertTrue(emitted[0].contains("8250ms") && emitted[0].contains("emulator-5554") && emitted[0].contains("bytes=10"), emitted[0])
        assertTrue(emitted.any { it.contains("heap watchdog") && it.contains("unavailable") }, "a failing heap probe is reported, not thrown")
        assertTrue(emitted.contains("$NO_VIDEO_DIAGNOSTIC_PREFIX threads 1/2: a"))
        assertTrue(emitted.contains("$NO_VIDEO_DIAGNOSTIC_PREFIX threads 2/2: b"))
        assertTrue(emitted.last().endsWith("end of report"))
    }

    @Test
    fun firstBytesConfigAndKeyFrameAreLoggedOnceEach() {
        val logged = CopyOnWriteArrayList<String>()
        val clock = AtomicLong(700)
        val stats = MirrorPathStats(clock::get, log = { logged += it })
        stats.video.bytes(4)
        stats.video.bytes(4)
        clock.set(900)
        stats.video.event(com.indagium.capture.mirror.ScrcpyStreamEvent.Packet(0, config = true, keyFrame = false, data = ByteArray(1)))
        stats.video.event(com.indagium.capture.mirror.ScrcpyStreamEvent.Packet(0, config = true, keyFrame = false, data = ByteArray(1)))
        clock.set(1_100)
        stats.video.event(com.indagium.capture.mirror.ScrcpyStreamEvent.Packet(1, config = false, keyFrame = true, data = ByteArray(1)))
        assertEquals(
            listOf(
                "Video path: first bytes arrived 700 ms after capture start",
                "Video path: first config packet arrived 900 ms after capture start",
                "Video path: first key frame arrived 1100 ms after capture start",
            ),
            logged,
        )
        assertEquals(StreamPathCounters.NEVER, stats.audio.firstBytesAtMs.get(), "audio is counted separately")
    }
}
