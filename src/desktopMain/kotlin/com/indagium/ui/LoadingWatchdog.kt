package com.indagium.ui

import com.indagium.debug.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * Diagnostics-only timing for the global "Loading file…" cover ([AppState.isLoading]). A loading
 * episode that outlives [warnAfterMs] leaves one multi-line warning (state of every active load plus
 * a full thread dump); [followUpsMs] add short progress lines, and the episode's end logs its total
 * duration. Nothing here changes loading behaviour.
 */
data class LoadingWatchdogConfig(
    val warnAfterMs: Long = DEFAULT_WARN_AFTER_MS,
    /** Elapsed marks after [warnAfterMs] that log a short follow-up line. */
    val followUpsMs: List<Long> = listOf(DEFAULT_SECOND_DUMP_MS, DEFAULT_LAST_FOLLOW_UP_MS),
    /** The follow-up mark that repeats the thread dump (a second sample shows whether stacks moved). */
    val secondDumpAtMs: Long = DEFAULT_SECOND_DUMP_MS,
)

private const val DEFAULT_WARN_AFTER_MS = 10_000L
private const val DEFAULT_SECOND_DUMP_MS = 30_000L
private const val DEFAULT_LAST_FOLLOW_UP_MS = 60_000L

/** One [AppState] active load as the watchdog reports it; deliberately free of paths and log content. */
internal data class LoadingLoadInfo(
    val tabId: String,
    val title: String?,
    val kind: String,
    val source: String,
    val jobState: String,
    val countsAsLoading: Boolean,
)

internal data class LoadingSnapshot(
    val pendingLoads: Int,
    val status: String?,
    val restoreLoadsQueued: Int,
    val loads: List<LoadingLoadInfo>,
)

/**
 * Starts a timer on the first pending load ([episodeStarted]) and stops it when the last one ends
 * ([episodeEnded]). Both are non-blocking, so they are safe to call under [AppState.stateLock] from
 * any thread including the UI thread; the timer coroutine takes its [snapshot] (which locks briefly)
 * and builds the thread dump only after it holds no lock of its own.
 */
internal class LoadingWatchdog(
    private val scope: CoroutineScope,
    private val config: LoadingWatchdogConfig,
    private val snapshot: () -> LoadingSnapshot,
    private val warn: (String) -> Unit = ::logMultiLineWarning,
    private val info: (String) -> Unit = { AppLogger.info(LOG_COMPONENT, it) },
    private val threadDump: () -> List<String> = ::captureThreadDumpLines,
    private val clockMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    private class Episode(val startedAtMs: Long) {
        @Volatile var job: Job? = null
    }

    @Volatile private var episode: Episode? = null
    private val transition = Any()

    fun episodeStarted() {
        val started = Episode(clockMs())
        synchronized(transition) {
            if (episode != null) return
            episode = started
        }
        started.job = scope.launch { watch(started) }
    }

    fun episodeEnded() {
        val ended = synchronized(transition) { episode.also { episode = null } } ?: return
        ended.job?.cancel()
        val elapsed = clockMs() - ended.startedAtMs
        if (elapsed > config.warnAfterMs) info("Global loading finished after ${seconds(elapsed)}")
    }

    private suspend fun watch(started: Episode) {
        delay(config.warnAfterMs)
        if (!scope.isActive || episode !== started) return
        val dump = threadDump()
        val snap = snapshot()
        warn(initialReport(clockMs() - started.startedAtMs, snap, dump))
        var previousMark = config.warnAfterMs
        for (mark in config.followUpsMs.filter { it > config.warnAfterMs }.sorted()) {
            delay(mark - previousMark)
            previousMark = mark
            if (!scope.isActive || episode !== started) return
            val dumpAgain = if (mark == config.secondDumpAtMs) threadDump() else null
            val current = snapshot()
            warn(followUpReport(clockMs() - started.startedAtMs, current, dumpAgain))
        }
    }

    private fun initialReport(elapsedMs: Long, snap: LoadingSnapshot, dump: List<String>): String = buildString {
        append("Global loading still active after ${seconds(elapsedMs)}: ").append(summary(snap)).append('\n')
        if (snap.loads.isEmpty()) append("  (no active load entries)\n")
        snap.loads.forEach { load ->
            append("  load tab=${load.tabId} title=${load.title ?: "(none)"} kind=${load.kind} source=${load.source}")
            append(" job=${load.jobState} countsAsLoading=${load.countsAsLoading}\n")
        }
        dump.forEach { append(it).append('\n') }
    }.trimEnd()

    private fun followUpReport(elapsedMs: Long, snap: LoadingSnapshot, dump: List<String>?): String = buildString {
        append("Global loading STILL active after ${seconds(elapsedMs)}: ").append(summary(snap))
        dump?.forEach { append('\n').append(it) }
    }

    private fun summary(snap: LoadingSnapshot): String =
        "pendingLoads=${snap.pendingLoads} status=${snap.status ?: "(none)"} " +
            "restoreQueued=${snap.restoreLoadsQueued} activeLoads=${snap.loads.size}"

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1fs", ms / MS_PER_SECOND)

    companion object {
        const val LOG_COMPONENT = "loading"
        const val THREAD_DUMP_MARKER = "[thread-dump]"
        private const val NANOS_PER_MS = 1_000_000L
        private const val MS_PER_SECOND = 1000.0
    }
}

/** AppLogger collapses newlines in a message, so a multi-line report is written one line per entry. */
private fun logMultiLineWarning(report: String) {
    report.lineSequence().filter { it.isNotBlank() }.forEach { AppLogger.warn(LoadingWatchdog.LOG_COMPONENT, it.trim()) }
}

private const val MAX_DUMP_FRAMES = 30
private const val MAX_DUMP_LINE_CHARS = 1_800

/**
 * Every live thread (name, state, blocking lock and its owner, held monitors/synchronizers, stack),
 * one log line per thread, each tagged [LoadingWatchdog.THREAD_DUMP_MARKER]. Held monitors are what
 * reveal an owner such as `AppState.stateLock`. Falls back to plain stack traces if the management
 * bean is unavailable. Stack frames are class/method/line only, never log content.
 */
internal fun captureThreadDumpLines(): List<String> {
    val marker = LoadingWatchdog.THREAD_DUMP_MARKER
    val infos = runCatching {
        ManagementFactory.getThreadMXBean().dumpAllThreads(true, true).toList()
    }.getOrNull()
    if (infos == null) {
        return Thread.getAllStackTraces().map { (thread, frames) ->
            val stack = frames.take(MAX_DUMP_FRAMES).joinToString(" <- ", transform = ::frameText)
            "$marker \"${safeName(thread.name)}\" state=${thread.state} at $stack".take(MAX_DUMP_LINE_CHARS)
        }.let { listOf("$marker ${it.size} threads (no ThreadMXBean)") + it }
    }
    return listOf("$marker ${infos.size} threads") + infos.map { info ->
        buildString {
            append(marker).append(" \"").append(safeName(info.threadName)).append("\" state=").append(info.threadState)
            info.lockName?.let { append(" waitingOn=").append(it) }
            info.lockOwnerName?.let { append(" ownedBy=\"").append(safeName(it)).append('"') }
            val held = info.lockedMonitors.map { "${it.className}@${Integer.toHexString(it.identityHashCode)}" } +
                info.lockedSynchronizers.map { "${it.className}@${Integer.toHexString(it.identityHashCode)}" }
            if (held.isNotEmpty()) append(" holds=").append(held.joinToString(","))
            append(" at ")
            append(info.stackTrace.take(MAX_DUMP_FRAMES).joinToString(" <- ", transform = ::frameText))
        }.take(MAX_DUMP_LINE_CHARS)
    }
}

/**
 * `Class.method(File.kt:12)` without StackTraceElement's `module/` prefix: the unnamed app module
 * renders as `app//`, and AppLogger's path redaction would swallow everything after that `//`.
 */
private fun frameText(frame: StackTraceElement): String =
    "${frame.className}.${frame.methodName}(${if (frame.isNativeMethod) "Native" else "${frame.fileName}:${frame.lineNumber}"})"

/** Thread names such as "ForkJoinPool.commonPool-worker-1 / x" must not look like paths to AppLogger. */
private fun safeName(name: String): String = name.replace('/', '_')
