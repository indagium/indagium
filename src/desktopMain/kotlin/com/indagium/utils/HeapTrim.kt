package com.indagium.utils

import com.indagium.debug.AppLogger
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asks the JVM to give freed heap back to the OS after a large allocation has just been released
 * (a big capture tab closed, a capture finalized or exported).
 *
 * G1 only uncommits heap at the end of a concurrent cycle or a full GC, and an idle app never
 * starts one, so without this a closed 1M-row capture keeps gigabytes committed forever even though
 * nothing references them. The launch flags `-XX:+ExplicitGCInvokesConcurrent` (makes the
 * `System.gc()` below a concurrent cycle, so the UI never stalls on a stop-the-world full GC) and
 * `-XX:G1PeriodicGCInterval` (the idle backstop) are set together with this in build.gradle.kts.
 *
 * Requests are coalesced: the first one schedules a single GC a few seconds out (so the caller's
 * references have dropped and a burst of closes costs one cycle), later ones while it is pending
 * are absorbed.
 */
internal object HeapTrim {
    private val default = HeapTrimScheduler()

    /** Safe from any thread, never throws. [reason] is only used for the diagnostic log. */
    fun request(reason: String) = default.request(reason)
}

internal class HeapTrimScheduler(
    private val delayMs: Long = DEFAULT_DELAY_MS,
    private val gc: () -> Unit = System::gc,
    private val executor: ScheduledExecutorService = defaultExecutor(),
) {
    private val pending = AtomicBoolean(false)

    @Volatile
    private var pendingReason: String = ""

    @Suppress("TooGenericExceptionCaught")
    fun request(reason: String) {
        try {
            if (!pending.compareAndSet(false, true)) return
            pendingReason = reason
            try {
                executor.schedule(::runTrim, delayMs, TimeUnit.MILLISECONDS)
            } catch (rejected: RejectedExecutionException) {
                pending.set(false)
                log("heap trim rejected: ${rejected.message}")
            }
        } catch (ignored: Throwable) {
            pending.set(false)
        }
    }

    // Cleared before the GC so a request arriving while the cycle runs schedules a fresh one: its
    // references may only have been dropped after this cycle's marking started.
    @Suppress("TooGenericExceptionCaught")
    private fun runTrim() {
        val reason = pendingReason
        pending.set(false)
        try {
            log("heap trim ($reason)")
            gc()
        } catch (ignored: Throwable) {
            // Best effort: a failed hint must never disturb the caller or kill the executor thread.
        }
    }

    private fun log(message: String) {
        runCatching { AppLogger.debug("memory", message) }
    }

    private companion object {
        const val DEFAULT_DELAY_MS = 3_000L

        fun defaultExecutor(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "indagium-heap-trim").apply { isDaemon = true }
        }
    }
}
