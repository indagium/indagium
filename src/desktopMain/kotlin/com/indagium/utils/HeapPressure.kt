package com.indagium.utils

import com.indagium.debug.AppLogger
import com.sun.management.GarbageCollectionNotificationInfo
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.concurrent.atomic.AtomicLong
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

/** At most one confirming full GC is requested per this window (the initial backoff step). */
internal const val HEAP_CONFIRM_MIN_INTERVAL_MS = 60_000L

/** Cap of the exponential confirmation backoff (60 s, 2 min, 4 min, ... up to this). */
internal const val HEAP_CONFIRM_MAX_INTERVAL_MS = 15 * 60_000L

/**
 * A non-full-GC reading is only worth another confirming full GC when it exceeds the occupancy the
 * last confirmed (full-GC) reading showed by at least this much; a young-GC reading at or below the
 * confirmed baseline carries no new evidence and never overstates a live set that was already
 * measured below CRITICAL.
 */
internal const val HEAP_CONFIRM_BASELINE_MARGIN = 0.03

/** A full-GC reading this far from the previous full-GC reading restarts the backoff. */
internal const val HEAP_CONFIRM_RESET_DELTA = 0.05

/** At most one published after-GC reading per this window (see [HeapPressureMonitor.start]). */
internal const val HEAP_READING_PUBLISH_INTERVAL_MS = 1_000L

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
 * **Confirmations back off.** A live set just below CRITICAL makes every young GC read as "near
 * critical" while each confirming full GC shows it is not. The monitor therefore remembers the last
 * full-GC occupancy (the baseline): a young-GC reading is only confirmed again when it exceeds the
 * baseline by [HEAP_CONFIRM_BASELINE_MARGIN], and the gap between confirmations doubles after every
 * request (60 s, 2 min, 4 min ... capped at [HEAP_CONFIRM_MAX_INTERVAL_MS]). The backoff restarts on a
 * level change and when a full GC shows a big change ([HEAP_CONFIRM_RESET_DELTA]). A genuinely
 * rising live set is still caught: any full GC (including the JVM's own when the old generation
 * fills) is authoritative and applied at once, and a young-GC reading well above the baseline is
 * confirmed as soon as the current backoff gap (at most [HEAP_CONFIRM_MAX_INTERVAL_MS]) has elapsed.
 * No confirmation is ever requested while the level is already CRITICAL.
 *
 * **Full GC detection.** JDK 21 reports `gcAction == "end of major GC"` for a full collection (G1:
 * `gcName = "G1 Old Generation"`, cause `System.gc()`; Parallel/Serial likewise via their old-gen
 * collector) and `"end of minor GC"` for young ones (`"G1 Young Generation"`). G1 also emits
 * `"G1 Concurrent GC"` pause notifications, which are not full collections. So only "major" counts.
 *
 * **Hysteresis** is in [nextHeapPressure]. [onChange] runs only when the level changes (the optional `onReading` of [start] receives every
 * after-GC reading instead, throttled to one per [HEAP_READING_PUBLISH_INTERVAL_MS]), on a JMX
 * notification thread and never while an internal lock is held (two beans may notify concurrently, so
 * callers must tolerate being called from different threads and should marshal to their own thread).
 *
 * **Delivery is ordered.** Callbacks are queued in the order the decisions were made (under [lock])
 * and drained by one thread at a time, so a slower notification thread can never deliver an older
 * level after a newer one: the last callback a consumer sees always carries the monitor's real level.
 * A thread that finds a drain already running enqueues and returns; the draining thread delivers its
 * event too.
 *
 * The seams ([maxBytes], [requestFullGc], [nowMs], [currentHeapUsedBytes], [emitters]) exist for tests.
 */
internal class HeapPressureMonitor(
    private val maxBytes: () -> Long = { Runtime.getRuntime().maxMemory() },
    private val requestFullGc: () -> Unit = ::requestFullGcOnDaemonThread,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val currentHeapUsedBytes: () -> Long = { ManagementFactory.getMemoryMXBean().heapMemoryUsage.used },
    private val emitters: () -> List<NotificationEmitter> = {
        ManagementFactory.getGarbageCollectorMXBeans().filterIsInstance<NotificationEmitter>()
    },
) {
    private val lock = Any()
    private var level = HeapPressure.NORMAL
    private var snapshot: HeapSnapshot? = null
    private var lastConfirmRequestMs: Long? = null

    // How long to wait after the last confirmation request before issuing another (the backoff
    // step that applied when it was issued), and the step the next request will apply.
    private var lastConfirmGapMs = HEAP_CONFIRM_MIN_INTERVAL_MS
    private var nextConfirmGapMs = HEAP_CONFIRM_MIN_INTERVAL_MS

    // Occupancy shown by the last full GC (null until one has been observed).
    private var fullGcBaselineOccupancy: Double? = null
    private var lastReadingPublishMs: Long? = null
    private var onChange: ((HeapPressure, HeapSnapshot) -> Unit)? = null
    private var onReading: ((HeapSnapshot) -> Unit)? = null
    private val registered = mutableListOf<Pair<NotificationEmitter, NotificationListener>>()

    // Callback deliveries in decision order, drained by one thread at a time (see class doc). Guarded by [lock].
    private val deliveries = ArrayDeque<() -> Unit>()
    private var delivering = false

    // Lock-free evidence for diagnostics (see [diagnosticSummary]): were GC notifications arriving at all?
    private val gcNotificationCount = AtomicLong(0)
    private val lastGcNotificationNanos = AtomicLong(0)

    /** Current level. */
    val pressure: HeapPressure get() = synchronized(lock) { level }

    /** Last after-GC reading, or null until the first GC has been observed. */
    val latestSnapshot: HeapSnapshot? get() = synchronized(lock) { snapshot }

    /**
     * Registers a GC notification listener on every collector bean that can emit them. Idempotent
     * (a second call replaces the callback and re-registers). Never throws.
     */
    @Suppress("TooGenericExceptionCaught")
    fun start(onReading: ((HeapSnapshot) -> Unit)? = null, onChange: (HeapPressure, HeapSnapshot) -> Unit) {
        stop()
        synchronized(lock) {
            this.onChange = onChange
            this.onReading = onReading
        }
        try {
            val heapPools = ManagementFactory.getMemoryPoolMXBeans()
                .filter { it.type == MemoryType.HEAP }
                .map { it.name }
                .toSet()
            val listener = NotificationListener { notification, _ -> handleNotification(notification, heapPools) }
            // Each listener is recorded the moment it is added, so `registered` always matches what is
            // really attached (stop() can remove it) even when a later bean throws; one bad bean does
            // not stop the others from being watched.
            emitters().forEach { emitter ->
                try {
                    emitter.addNotificationListener(listener, null, null)
                    synchronized(lock) { registered += emitter to listener }
                } catch (t: Throwable) {
                    log("heap pressure monitor could not listen to a collector bean: $t")
                }
            }
            activeMonitor = this
        } catch (t: Throwable) {
            log("heap pressure monitor failed to start: $t")
        }
    }

    /** Number of JMX listeners currently registered (0 before [start] / after [stop]); for tests. */
    internal val registeredListenerCount: Int get() = synchronized(lock) { registered.size }

    /** Test seam: sets the change callback without touching JMX. */
    internal fun setOnChange(callback: ((HeapPressure, HeapSnapshot) -> Unit)?) {
        synchronized(lock) { onChange = callback }
    }

    /** Test seam: sets the throttled per-reading callback without touching JMX. */
    internal fun setOnReading(callback: ((HeapSnapshot) -> Unit)?) {
        synchronized(lock) { onReading = callback }
    }

    /** Removes the listeners added by [start]. Safe to call repeatedly or without [start]. */
    fun stop() {
        if (activeMonitor === this) activeMonitor = null
        val toRemove = synchronized(lock) {
            onChange = null
            onReading = null
            deliveries.clear()
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
            var confirm = false
            synchronized(lock) {
                val snap = HeapSnapshot(usedAfterGcBytes.coerceAtLeast(0L), maxBytes())
                snapshot = snap
                val now = nowMs()
                val decision = nextHeapPressure(level, snap.occupancy, isFullGc)
                if (isFullGc) noteFullGcReading(snap.occupancy)
                if (decision.needsConfirmation && level != HeapPressure.CRITICAL && shouldConfirm(snap.occupancy, now)) {
                    lastConfirmRequestMs = now
                    lastConfirmGapMs = nextConfirmGapMs
                    nextConfirmGapMs = (nextConfirmGapMs * 2).coerceAtMost(HEAP_CONFIRM_MAX_INTERVAL_MS)
                    confirm = true
                }
                if (decision.level != level) {
                    level = decision.level
                    resetConfirmBackoff()
                    val newLevel = decision.level
                    val callback = onChange
                    deliveries.addLast {
                        log("heap pressure -> $newLevel (occupancy ${"%.2f".format(snap.occupancy)}, fullGc=$isFullGc)")
                        callback?.invoke(newLevel, snap)
                    }
                }
                val lastPublished = lastReadingPublishMs
                val readingCallback = onReading
                if (readingCallback != null && (lastPublished == null || now - lastPublished >= HEAP_READING_PUBLISH_INTERVAL_MS)) {
                    lastReadingPublishMs = now
                    deliveries.addLast { readingCallback(snap) }
                }
            }
            drainDeliveries()
            if (confirm) {
                log("heap near critical after non-full GC; requesting confirming full GC")
                requestFullGc()
            }
        } catch (t: Throwable) {
            log("heap pressure handling failed: $t")
        }
    }

    // Delivers queued callbacks in order, outside [lock]. Only one thread drains at a time: a thread that
    // finds a drain running leaves its event queued for that drainer (which re-checks the queue under
    // [lock] before giving up the role, so no event is stranded).
    @Suppress("TooGenericExceptionCaught")
    private fun drainDeliveries() {
        synchronized(lock) {
            if (delivering) return
            delivering = true
        }
        while (true) {
            val next = synchronized(lock) {
                deliveries.removeFirstOrNull().also { if (it == null) delivering = false }
            } ?: return
            try {
                next()
            } catch (t: Throwable) {
                log("heap pressure callback failed: $t")
            }
        }
    }

    // Records a full-GC reading as the new baseline; a big jump from the previous one means the
    // old backoff no longer describes the situation. Caller holds [lock].
    private fun noteFullGcReading(occupancy: Double) {
        val previous = fullGcBaselineOccupancy
        if (previous == null || kotlin.math.abs(occupancy - previous) >= HEAP_CONFIRM_RESET_DELTA) resetConfirmBackoff()
        fullGcBaselineOccupancy = occupancy
    }

    // Caller holds [lock].
    private fun resetConfirmBackoff() {
        nextConfirmGapMs = HEAP_CONFIRM_MIN_INTERVAL_MS
        lastConfirmGapMs = HEAP_CONFIRM_MIN_INTERVAL_MS
    }

    // Whether a young-GC reading in the CRITICAL band should be confirmed by a full GC now. Caller
    // holds [lock].
    private fun shouldConfirm(occupancy: Double, now: Long): Boolean {
        val last = lastConfirmRequestMs ?: return true
        val baseline = fullGcBaselineOccupancy
        val elapsed = now - last
        return when {
            baseline == null -> elapsed >= lastConfirmGapMs
            occupancy < baseline + HEAP_CONFIRM_BASELINE_MARGIN -> false
            else -> elapsed >= lastConfirmGapMs
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handleNotification(notification: Notification, heapPools: Set<String>) {
        try {
            if (notification.type != GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) return
            gcNotificationCount.incrementAndGet()
            lastGcNotificationNanos.set(System.nanoTime())
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

    /**
     * One line of watchdog state for the "no video" capture diagnostic: pressure, last after-GC
     * reading, registered listener count and how many GC notifications were received (and how long
     * ago). Takes this monitor's lock, so callers that must not hang run it on a throwaway thread.
     */
    internal fun diagnosticSummary(): String {
        val (pressureLevel, snap, listeners) = synchronized(lock) { Triple(level, snapshot, registered.size) }
        val lastNotification = lastGcNotificationNanos.get()
        val ago = if (lastNotification == 0L) "never" else "${(System.nanoTime() - lastNotification) / NANOS_PER_MS}ms ago"
        val afterGc = snap?.let {
            "${it.usedAfterGcBytes / BYTES_PER_MB}MB/${it.maxBytes / BYTES_PER_MB}MB occupancy=${"%.2f".format(it.occupancy)}"
        } ?: "none"
        return "pressure=$pressureLevel lastAfterGcUsed=$afterGc registeredListeners=$listeners " +
            "gcNotifications=${gcNotificationCount.get()} lastGcNotification=$ago"
    }

    private fun log(message: String) {
        runCatching { AppLogger.debug("memory", message) }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val BYTES_PER_MB = 1024L * 1024L

        /** Runs the GC off the caller's thread: the caller is the GC-notification thread and must not block. */
        fun requestFullGcOnDaemonThread() {
            Thread({ System.gc() }, "indagium-heap-pressure-gc").apply { isDaemon = true }.start()
        }
    }
}

/** The monitor most recently [HeapPressureMonitor.start]ed (AppState's); for diagnostics only. */
@Volatile
private var activeMonitor: HeapPressureMonitor? = null

/**
 * Watchdog state for the diagnostics log: the started monitor's [HeapPressureMonitor.diagnosticSummary]
 * plus the plain JVM heap numbers, or a note that no monitor is running. May block briefly if the
 * monitor's lock is contended; call it off any thread that must stay responsive.
 */
internal fun heapPressureDiagnosticSummary(): String {
    val runtime = Runtime.getRuntime()
    val mb = BYTES_PER_MB_TOP_LEVEL
    val heap = "heapUsed=${(runtime.totalMemory() - runtime.freeMemory()) / mb}MB " +
        "heapCommitted=${runtime.totalMemory() / mb}MB heapMax=${runtime.maxMemory() / mb}MB"
    val monitor = activeMonitor
    return (monitor?.diagnosticSummary() ?: "no heap pressure monitor started") + " " + heap
}

private const val BYTES_PER_MB_TOP_LEVEL = 1_048_576L
