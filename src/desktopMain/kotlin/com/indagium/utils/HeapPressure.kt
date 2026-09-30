package com.indagium.utils

import com.indagium.debug.AppLogger
import com.sun.management.GarbageCollectionNotificationInfo
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import javax.management.Notification
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import javax.management.openmbean.CompositeData

/** How close the JVM heap is to running out, judged on occupancy after GC. */
internal enum class HeapPressure { NORMAL, WARNING, CRITICAL }

/** Heap occupancy observed after a GC, for display and for [HeapPressureMonitor.estimatedFreeBytes]. */
internal data class HeapSnapshot(val usedAfterGcBytes: Long, val maxBytes: Long) {
    /** `usedAfterGcBytes / maxBytes`, 0.0 when [maxBytes] is unknown (<= 0). */
    val occupancy: Double get() = if (maxBytes > 0) usedAfterGcBytes.toDouble() / maxBytes else 0.0
}

/** Occupancy (fraction of max heap, after GC) at which the level rises to [HeapPressure.WARNING]. */
internal const val HEAP_WARNING_OCCUPANCY = 0.70

/** Occupancy at which the level rises to [HeapPressure.CRITICAL] (once confirmed by a full GC). */
internal const val HEAP_CRITICAL_OCCUPANCY = 0.85

/**
 * A level is left only once occupancy is this far *below* the threshold that raised it
 * (WARNING -> NORMAL below 0.65, CRITICAL -> WARNING below 0.80), so the level does not flap around a threshold.
 */
internal const val HEAP_HYSTERESIS = 0.05

/** At most one confirming full GC is requested per this window. */
internal const val HEAP_CONFIRM_MIN_INTERVAL_MS = 60_000L

/**
 * Result of [nextHeapPressure]. [needsConfirmation] is true when the occupancy is in the CRITICAL
 * band but [level] was capped at WARNING because the reading did not come from a full GC; the
 * caller should get a full GC to run and feed its result back.
 */
internal data class HeapPressureDecision(val level: HeapPressure, val needsConfirmation: Boolean)

/**
 * Pure decision core (no JMX, no clock) for the next [HeapPressure] level.
 *
 * - Rising: WARNING at occupancy >= [HEAP_WARNING_OCCUPANCY]; CRITICAL at >= [HEAP_CRITICAL_OCCUPANCY]
 *   **only if [confirmedByFullGc]**. An unconfirmed reading in the CRITICAL band yields WARNING and
 *   `needsConfirmation = true`; it never escalates to CRITICAL by itself.
 * - Falling (hysteresis): a level already held is kept until occupancy drops [HEAP_HYSTERESIS] below
 *   its threshold. Holding or lowering a level never needs confirmation, because an after-young-GC
 *   reading only overestimates, so a low reading is trustworthy and a high one merely keeps the level.
 */
internal fun nextHeapPressure(
    current: HeapPressure,
    occupancy: Double,
    confirmedByFullGc: Boolean,
): HeapPressureDecision {
    val criticalHeld = current == HeapPressure.CRITICAL && occupancy >= HEAP_CRITICAL_OCCUPANCY - HEAP_HYSTERESIS
    if (criticalHeld) return HeapPressureDecision(HeapPressure.CRITICAL, needsConfirmation = false)
    if (occupancy >= HEAP_CRITICAL_OCCUPANCY) {
        return if (confirmedByFullGc) {
            HeapPressureDecision(HeapPressure.CRITICAL, needsConfirmation = false)
        } else {
            HeapPressureDecision(HeapPressure.WARNING, needsConfirmation = true)
        }
    }
    val warningHeld = current != HeapPressure.NORMAL && occupancy >= HEAP_WARNING_OCCUPANCY - HEAP_HYSTERESIS
    val level = if (occupancy >= HEAP_WARNING_OCCUPANCY || warningHeld) HeapPressure.WARNING else HeapPressure.NORMAL
    return HeapPressureDecision(level, needsConfirmation = false)
}

/**
 * Watches the JVM heap and reports [HeapPressure], so the app can warn the user and pause a live
 * capture's log view before an `OutOfMemoryError` (every open tab keeps all its rows on the heap).
 *
 * **Signal: occupancy after GC.** Raw `used` includes garbage not yet collected and would raise
 * constant false alarms. Instead every GC notification (`GarbageCollectionNotificationInfo`) carries
 * `memoryUsageAfterGc`; we sum `used` over the *heap* pools only (resolved by [MemoryType.HEAP], not
 * by name: pool names differ per collector, and the map also holds metaspace/code-cache pools).
 *
 * **CRITICAL is confirmed by a full GC.** After a *young* GC the old generation still holds
 * unreclaimed garbage, so that reading can only overestimate. A reading in the CRITICAL band from a
 * non-full GC is therefore capped at WARNING and triggers one [requestFullGc]; the full GC's own
 * notification then decides CRITICAL vs lower. That one stop-the-world pause is accepted even during
 * a live capture because the alternative is an OOM. Confirmations are rate limited to one per
 * [HEAP_CONFIRM_MIN_INTERVAL_MS]; while the limit blocks one, the level stays at WARNING (never
 * escalates unconfirmed).
 *
 * **Full GC detection.** JDK 21 reports `gcAction == "end of major GC"` for a full collection (G1:
 * `gcName = "G1 Old Generation"`, cause `System.gc()`; Parallel/Serial likewise via their old-gen
 * collector) and `"end of minor GC"` for young ones (`"G1 Young Generation"`). G1 also emits
 * `"G1 Concurrent GC"` pause notifications, which are not full collections. So only "major" counts.
 *
 * **Hysteresis** is in [nextHeapPressure]. [onChange] runs only when the level changes, on the JMX
 * notification thread and never while an internal lock is held (two beans may notify concurrently, so
 * callers must tolerate being called from different threads and should marshal to their own thread).
 *
 * The seams ([maxBytes], [requestFullGc], [nowMs], [currentHeapUsedBytes]) exist for tests.
 */
internal class HeapPressureMonitor(
    private val maxBytes: () -> Long = { Runtime.getRuntime().maxMemory() },
    private val requestFullGc: () -> Unit = ::requestFullGcOnDaemonThread,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val currentHeapUsedBytes: () -> Long = { ManagementFactory.getMemoryMXBean().heapMemoryUsage.used },
) {
    private val lock = Any()
    private var level = HeapPressure.NORMAL
    private var snapshot: HeapSnapshot? = null
    private var lastConfirmRequestMs: Long? = null
    private var onChange: ((HeapPressure, HeapSnapshot) -> Unit)? = null
    private val registered = mutableListOf<Pair<NotificationEmitter, NotificationListener>>()

    /** Current level. */
    val pressure: HeapPressure get() = synchronized(lock) { level }

    /** Last after-GC reading, or null until the first GC has been observed. */
    val latestSnapshot: HeapSnapshot? get() = synchronized(lock) { snapshot }

    /**
     * Registers a GC notification listener on every collector bean that can emit them. Idempotent
     * (a second call replaces the callback and re-registers). Never throws.
     */
    @Suppress("TooGenericExceptionCaught")
    fun start(onChange: (HeapPressure, HeapSnapshot) -> Unit) {
        stop()
        synchronized(lock) { this.onChange = onChange }
        try {
            val heapPools = ManagementFactory.getMemoryPoolMXBeans()
                .filter { it.type == MemoryType.HEAP }
                .map { it.name }
                .toSet()
            val listener = NotificationListener { notification, _ -> handleNotification(notification, heapPools) }
            val added = ManagementFactory.getGarbageCollectorMXBeans()
                .filterIsInstance<NotificationEmitter>()
                .map { emitter ->
                    emitter.addNotificationListener(listener, null, null)
                    emitter to listener
                }
            synchronized(lock) { registered += added }
        } catch (t: Throwable) {
            log("heap pressure monitor failed to start: $t")
        }
    }

    /** Test seam: sets the change callback without touching JMX. */
    internal fun setOnChange(callback: ((HeapPressure, HeapSnapshot) -> Unit)?) {
        synchronized(lock) { onChange = callback }
    }

    /** Removes the listeners added by [start]. Safe to call repeatedly or without [start]. */
    fun stop() {
        val toRemove = synchronized(lock) {
            onChange = null
            registered.toList().also { registered.clear() }
        }
        toRemove.forEach { (emitter, listener) -> runCatching { emitter.removeNotificationListener(listener) } }
    }

    /**
     * `max - used after the last GC`, or `max - current heap used` when no GC has been observed yet.
     * Never negative.
     */
    fun estimatedFreeBytes(): Long {
        val max = maxBytes()
        val used = synchronized(lock) { snapshot?.usedAfterGcBytes } ?: currentHeapUsedBytes()
        return (max - used).coerceAtLeast(0L)
    }

    /**
     * Entry point the JMX listener delegates to; internal so tests can drive it without a real GC.
     * [usedAfterGcBytes] is the summed heap-pool usage after the collection; [isFullGc] marks a
     * major/full collection.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun onGc(usedAfterGcBytes: Long, isFullGc: Boolean) {
        try {
            var changeTo: Pair<HeapPressure, HeapSnapshot>? = null
            var callback: ((HeapPressure, HeapSnapshot) -> Unit)? = null
            var confirm = false
            synchronized(lock) {
                val snap = HeapSnapshot(usedAfterGcBytes.coerceAtLeast(0L), maxBytes())
                snapshot = snap
                val decision = nextHeapPressure(level, snap.occupancy, isFullGc)
                if (decision.needsConfirmation) {
                    val now = nowMs()
                    val last = lastConfirmRequestMs
                    if (last == null || now - last >= HEAP_CONFIRM_MIN_INTERVAL_MS) {
                        lastConfirmRequestMs = now
                        confirm = true
                    }
                }
                if (decision.level != level) {
                    level = decision.level
                    changeTo = decision.level to snap
                    callback = onChange
                }
            }
            changeTo?.let { (newLevel, snap) ->
                log("heap pressure -> $newLevel (occupancy ${"%.2f".format(snap.occupancy)}, fullGc=$isFullGc)")
                callback?.invoke(newLevel, snap)
            }
            if (confirm) {
                log("heap near critical after non-full GC; requesting confirming full GC")
                requestFullGc()
            }
        } catch (t: Throwable) {
            log("heap pressure handling failed: $t")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handleNotification(notification: Notification, heapPools: Set<String>) {
        try {
            if (notification.type != GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) return
            val info = GarbageCollectionNotificationInfo.from(notification.userData as CompositeData)
            val used = info.gcInfo.memoryUsageAfterGc
                .filterKeys { it in heapPools }
                .values
                .sumOf { it.used }
            onGc(used, isFullGc = info.gcAction.contains("major", ignoreCase = true))
        } catch (t: Throwable) {
            log("heap pressure notification ignored: $t")
        }
    }

    private fun log(message: String) {
        runCatching { AppLogger.debug("memory", message) }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L

        /** Runs the GC off the caller's thread: the caller is the GC-notification thread and must not block. */
        fun requestFullGcOnDaemonThread() {
            Thread({ System.gc() }, "indagium-heap-pressure-gc").apply { isDaemon = true }.start()
        }
    }
}
