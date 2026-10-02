package com.indagium

import com.indagium.utils.HeapTrimScheduler
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeapTrimTest {
    /** Captures scheduled tasks instead of running them, so the test drives time by hand. */
    private class ManualExecutor : ScheduledThreadPoolExecutor(1) {
        val queued = ArrayDeque<Runnable>()
        var lastDelayMs = -1L

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            queued.addLast(command)
            lastDelayMs = unit.toMillis(delay)
            return super.schedule({}, 1, TimeUnit.DAYS)
        }

        fun runNext() = queued.removeFirst().run()
    }

    private fun scheduler(executor: ManualExecutor, gcCalls: AtomicInteger) =
        HeapTrimScheduler(delayMs = 3_000, gc = { gcCalls.incrementAndGet() }, nativeRelief = { 0L }, executor = executor)

    @Test
    fun rapidRequestsCoalesceIntoOneGc() {
        val executor = ManualExecutor()
        val gcCalls = AtomicInteger()
        val trim = scheduler(executor, gcCalls)
        try {
            repeat(10) { trim.request("burst $it") }
            assertEquals(1, executor.queued.size)
            assertEquals(3_000L, executor.lastDelayMs)
            assertEquals(0, gcCalls.get())
            executor.runNext()
            assertEquals(1, gcCalls.get())
            assertTrue(executor.queued.isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun requestAfterGcSchedulesAnotherOne() {
        val executor = ManualExecutor()
        val gcCalls = AtomicInteger()
        val trim = scheduler(executor, gcCalls)
        try {
            trim.request("first")
            executor.runNext()
            trim.request("second")
            trim.request("second again")
            assertEquals(1, executor.queued.size)
            executor.runNext()
            assertEquals(2, gcCalls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun throwingGcNeitherPropagatesNorBlocksLaterRequests() {
        val executor = ManualExecutor()
        val calls = AtomicInteger()
        val trim = HeapTrimScheduler(
            delayMs = 1,
            gc = { calls.incrementAndGet(); error("boom") },
            nativeRelief = { 0L },
            executor = executor,
        )
        try {
            trim.request("a")
            executor.runNext()
            trim.request("b")
            executor.runNext()
            assertEquals(2, calls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun nativeReliefRunsAfterGcOnEveryTrim() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        val trim = HeapTrimScheduler(
            delayMs = 1,
            gc = { events += "gc" },
            nativeRelief = { events += "relief"; 4_096L },
            executor = executor,
        )
        try {
            trim.request("a")
            executor.runNext()
            trim.request("b")
            executor.runNext()
            assertEquals(listOf("gc", "relief", "gc", "relief"), events)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun throwingNativeReliefNeitherPropagatesNorBlocksLaterRequests() {
        val executor = ManualExecutor()
        val gcCalls = AtomicInteger()
        val reliefCalls = AtomicInteger()
        val trim = HeapTrimScheduler(
            delayMs = 1,
            gc = { gcCalls.incrementAndGet() },
            nativeRelief = { reliefCalls.incrementAndGet(); error("boom") },
            executor = executor,
        )
        try {
            trim.request("a")
            executor.runNext()
            trim.request("b")
            executor.runNext()
            assertEquals(2, gcCalls.get())
            assertEquals(2, reliefCalls.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
