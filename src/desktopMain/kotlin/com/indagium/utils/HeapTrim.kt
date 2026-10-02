package com.indagium.utils

import com.indagium.capture.mirror.MacVideoToolboxMirrorNative
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
 * nothing references them.
 *
 * The `System.gc()` below is deliberately a real full GC (the launch flags in build.gradle.kts do
 * NOT set `-XX:+ExplicitGCInvokesConcurrent`). A concurrent cycle only reclaims fully-empty regions;
 * it does not evacuate old regions that still contain some garbage, and the mixed collections that
 * would do so only run while the app allocates, which an idle app does not. The result was ~1 GB
 * of dead heap staying committed after a large capture was closed. A full GC compacts it at the
 * cost of a short stop-the-world pause (~0.1-0.3 s with a few hundred MB live), which is acceptable
 * because callers only request a trim right after a user action (closing a big tab, Stop, export)
 * and never while a capture is recording (see AppState.requestHeapTrim). `-XX:G1PeriodicGCInterval`
 * stays as the cheap, concurrent idle backstop.
 *
 * The JVM heap is only half of it. Capture finalization and ZIP export run FFmpeg, whose transient
 * native buffers the macOS magazine allocator keeps dirty after `free` until system memory
 * pressure (hundreds of MB in a measured capture -> Stop -> export cycle, with live malloc flat).
 * So on macOS the trim also calls `malloc_zone_pressure_relief` right after the GC.
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
    /** Returns freed native memory to the OS after [gc]; yields the bytes released (0 = no-op). */
    private val nativeRelief: () -> Long = ::defaultNativeRelief,
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
            val released = nativeRelief()
            // macOS routinely reports a few KB here; a "released 0 MB" line on every trim is just noise.
            if (released >= BYTES_PER_MB) {
                log("heap trim ($reason): released ${released / BYTES_PER_MB} MB of native memory")
            }
        } catch (ignored: Throwable) {
            // Best effort: a failed hint must never disturb the caller or kill the executor thread.
        }
    }

    private fun log(message: String) {
        runCatching { AppLogger.debug("memory", message) }
    }

    private companion object {
        const val DEFAULT_DELAY_MS = 3_000L
        const val BYTES_PER_MB = 1024L * 1024L

        // macOS only: its magazine allocator is the one that hoards freed pages (measured); the
        // Linux and Windows mirror libraries have no equivalent hook and are deliberately not loaded
        // just for a trim.
        fun defaultNativeRelief(): Long =
            if (System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)) {
                MacVideoToolboxMirrorNative.releaseFreedMemory()
            } else {
                0L
            }

        fun defaultExecutor(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "indagium-heap-trim").apply { isDaemon = true }
        }
    }
}
