package com.indagium.capture

import com.indagium.capture.mirror.MirrorPathStats
import com.indagium.debug.AppLogger
import com.indagium.utils.heapPressureDiagnosticSummary
import java.lang.Thread.State
import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * How long a capture that records video may run without a single video packet before the UI says
 * so (`captureNoVideoWarning`) and the one-shot host diagnostic ([reportNoVideoDiagnostic]) writes
 * its evidence to the debug log. The device normally delivers its first packet within a second or two.
 */
internal const val CAPTURE_NO_VIDEO_AFTER_MS = 8_000L

/** Every debug-log line of the one-shot no-video diagnostic starts with this, for grepping. */
internal const val NO_VIDEO_DIAGNOSTIC_PREFIX = "No-video diagnostic"

/**
 * Decides when the one-shot "no video" diagnostic fires: [poll] returns true **exactly once**, the
 * first time a capture that records video ([recordVideo]) is at least [thresholdMs] old on
 * [elapsedMs] (the capture clock) and [firstVideoPacketArrived] is still false. Pure logic with
 * injectable inputs; every input must be readable without taking a lock, because the point of the
 * diagnostic is to still run when the recorder's or the session's own locks are stuck.
 */
internal class NoVideoDiagnosticTrigger(
    private val elapsedMs: () -> Long,
    private val recordVideo: Boolean,
    private val firstVideoPacketArrived: () -> Boolean,
    private val thresholdMs: Long = CAPTURE_NO_VIDEO_AFTER_MS,
) {
    private val fired = AtomicBoolean(false)

    /** True once there is nothing left to watch for: video is off, video arrived, or it already fired. */
    val isFinished: Boolean get() = !recordVideo || fired.get() || firstVideoPacketArrived()

    fun poll(): Boolean {
        if (isFinished) return false
        if (elapsedMs() < thresholdMs) return false
        return fired.compareAndSet(false, true)
    }
}

/**
 * Writes the one-shot no-video evidence to the debug log via [emit] (never per packet, never log
 * content): the host-path counters, the heap watchdog's state, and a bounded thread dump. Each
 * [emit] call is one log entry, kept well under `AppLogger`'s 2000-character per-message cap so
 * nothing is truncated. Never throws. Call it on its own daemon thread: the thread dump needs a
 * safepoint and the heap summary takes the monitor's lock (bounded here by [HEAP_SUMMARY_TIMEOUT_MS]).
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList")
internal fun reportNoVideoDiagnostic(
    deviceSerial: String,
    sessionId: String,
    elapsedMs: Long,
    stats: MirrorPathStats,
    heapSummary: () -> String = ::heapPressureDiagnosticSummary,
    threadDump: () -> List<String> = { ThreadDumpReport.capture() },
    emit: (String) -> Unit = { AppLogger.warn("capture", it) },
) {
    fun safely(step: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            runCatching { emit("$NO_VIDEO_DIAGNOSTIC_PREFIX: $step failed: ${t::class.simpleName}: ${t.message}") }
        }
    }
    safely("counters") {
        emit(
            "$NO_VIDEO_DIAGNOSTIC_PREFIX: no video packet reached the muxer ${elapsedMs}ms after capture start " +
                "(session=$sessionId device=$deviceSerial) | ${stats.describe()}",
        )
    }
    safely("heap state") {
        val summary = runBounded(HEAP_SUMMARY_TIMEOUT_MS, heapSummary)
        emit(
            "$NO_VIDEO_DIAGNOSTIC_PREFIX: heap watchdog | " +
                (summary ?: "state unavailable (monitor lock busy for more than ${HEAP_SUMMARY_TIMEOUT_MS}ms)"),
        )
    }
    safely("thread dump") {
        val entries = threadDump()
        entries.forEachIndexed { index, entry ->
            emit("$NO_VIDEO_DIAGNOSTIC_PREFIX threads ${index + 1}/${entries.size}: $entry")
        }
    }
    safely("end marker") { emit("$NO_VIDEO_DIAGNOSTIC_PREFIX: end of report") }
}

private const val HEAP_SUMMARY_TIMEOUT_MS = 1_500L

/** Runs [block] on a throwaway daemon thread and waits at most [timeoutMs]; null on timeout or failure. */
@Suppress("TooGenericExceptionCaught")
private fun runBounded(timeoutMs: Long, block: () -> String): String? {
    val result = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val worker = thread(name = "capture-no-video-diagnostic-probe", isDaemon = true) {
        try {
            result.set(block())
        } catch (_: Throwable) {
            // Reported as "unavailable" by the caller.
        }
    }
    worker.join(timeoutMs)
    return result.get()
}

/**
 * Formats a JVM thread dump into log-entry-sized strings for [reportNoVideoDiagnostic]. Content, in
 * priority order (lower-priority parts are dropped first when [maxTotalChars] is reached, and the
 * omission is stated): a summary with any deadlocked threads; every BLOCKED thread, and every
 * WAITING thread that waits on a lock, with lock name and owner; full stacks (capped at
 * [maxFrames] frames) of the BLOCKED threads, the lock owners, and threads whose name matches
 * [focus]. Frames are written as `class.method(File:line)` with no class-loader/module prefix and
 * every `/` in names replaced, so `AppLogger.safeText`'s path redaction cannot rewrite them.
 */
internal object ThreadDumpReport {
    val DEFAULT_FOCUS = Regex(
        "capture|embedded|scrcpy|mirror|video|decode|mux|audio|tail|heap|notification|AWT-EventQueue|DefaultDispatcher|JMX|RMI",
        RegexOption.IGNORE_CASE,
    )
    const val DEFAULT_MAX_TOTAL_CHARS = 200_000
    const val DEFAULT_MAX_ENTRY_CHARS = 1_800
    const val DEFAULT_MAX_FRAMES = 40
    private const val SEPARATOR = " || "
    private const val NAME_LIMIT = 80
    private const val HOLD_LIMIT = 8

    fun capture(maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS): List<String> {
        val bean = ManagementFactory.getThreadMXBean()
        val infos = bean.dumpAllThreads(true, true).filterNotNull()
        val deadlocked = bean.findDeadlockedThreads()?.toSet().orEmpty()
        return format(infos, deadlocked, maxTotalChars = maxTotalChars)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun format(
        infos: List<ThreadInfo>,
        deadlockedIds: Set<Long> = emptySet(),
        focus: Regex = DEFAULT_FOCUS,
        maxFrames: Int = DEFAULT_MAX_FRAMES,
        maxEntryChars: Int = DEFAULT_MAX_ENTRY_CHARS,
        maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS,
    ): List<String> {
        val byId = infos.associateBy { it.threadId }
        val atoms = ArrayList<String>()
        var used = 0
        var omitted = 0
        val reserve = TRUNCATION_RESERVE

        fun add(atom: String): Boolean {
            if (used + atom.length + SEPARATOR.length > maxTotalChars - reserve) return false
            atoms += atom
            used += atom.length + SEPARATOR.length
            return true
        }

        val deadlockNames = infos.filter { it.threadId in deadlockedIds }.joinToString(",") { label(it) }
        add("SUMMARY threads=${infos.size} deadlocked=[$deadlockNames]")

        val waiting = infos.filter { isLockWaiter(it) }
            .sortedWith(compareBy({ it.threadState != State.BLOCKED }, { it.lockOwnerId < 0 }, { it.threadName }))
        for (info in waiting) {
            val owner = if (info.lockOwnerId < 0) "none" else byId[info.lockOwnerId]?.let(::label)
                ?: "${clean(info.lockOwnerName ?: "?")}(${info.lockOwnerId})"
            if (!add("WAIT ${label(info)} ${info.threadState} on ${clean(info.lockName ?: "?")} owner=$owner")) omitted++
        }

        // Full stacks: blocked threads, lock owners, deadlocked threads, then name matches.
        val selected = LinkedHashMap<Long, ThreadInfo>()
        infos.filter { it.threadState == State.BLOCKED }.forEach { selected[it.threadId] = it }
        infos.filter { it.threadId in deadlockedIds }.forEach { selected.putIfAbsent(it.threadId, it) }
        waiting.forEach { waiter -> byId[waiter.lockOwnerId]?.let { selected.putIfAbsent(it.threadId, it) } }
        infos.filter { focus.containsMatchIn(it.threadName) }.forEach { selected.putIfAbsent(it.threadId, it) }

        for (info in selected.values) {
            val stackAtoms = stackAtoms(info, maxFrames, maxEntryChars)
            val size = stackAtoms.sumOf { it.length + SEPARATOR.length }
            if (used + size > maxTotalChars - reserve) {
                omitted++
                continue
            }
            stackAtoms.forEach { add(it) }
        }
        if (omitted > 0) atoms += "TRUNCATED $omitted item(s) omitted to stay within $maxTotalChars chars"
        return pack(atoms, maxEntryChars)
    }

    private fun isLockWaiter(info: ThreadInfo): Boolean = info.threadState == State.BLOCKED ||
        ((info.threadState == State.WAITING || info.threadState == State.TIMED_WAITING) && info.lockName != null)

    private fun stackAtoms(info: ThreadInfo, maxFrames: Int, maxEntryChars: Int): List<String> {
        val monitors = info.lockedMonitors.take(HOLD_LIMIT).joinToString(",") {
            "${clean(it.className)}@${Integer.toHexString(it.identityHashCode)}"
        }
        val synchronizers = info.lockedSynchronizers.take(HOLD_LIMIT).joinToString(",") {
            "${clean(it.className)}@${Integer.toHexString(it.identityHashCode)}"
        }
        val head = buildString {
            append("T").append(info.threadId).append(' ').append(label(info)).append(' ').append(info.threadState)
            info.lockName?.let { append(" on ").append(clean(it)) }
            if (info.lockOwnerId >= 0) append(" owner=").append(clean(info.lockOwnerName ?: "?")).append('(').append(info.lockOwnerId).append(')')
            if (monitors.isNotEmpty()) append(" holdsMonitors=[").append(monitors).append(']')
            if (synchronizers.isNotEmpty()) append(" holdsSynchronizers=[").append(synchronizers).append(']')
        }
        val stack = info.stackTrace
        val frames = stack.take(maxFrames).map(::frame)
        val atoms = ArrayList<String>()
        atoms += head
        var group = StringBuilder()
        var groupStart = 1
        var index = 0
        for (text in frames) {
            index++
            if (group.isNotEmpty() && group.length + text.length + 3 > maxEntryChars - FRAME_PREFIX_RESERVE) {
                atoms += "T${info.threadId} frames $groupStart-${index - 1}: $group"
                group = StringBuilder()
                groupStart = index
            }
            if (group.isNotEmpty()) group.append(" < ")
            group.append(text)
        }
        if (group.isNotEmpty()) atoms += "T${info.threadId} frames $groupStart-$index: $group"
        if (stack.size > maxFrames) atoms += "T${info.threadId} (${stack.size - maxFrames} deeper frames omitted)"
        return atoms
    }

    private fun frame(element: StackTraceElement): String {
        val location = when {
            element.isNativeMethod -> "Native"
            element.fileName == null -> "?"
            element.lineNumber >= 0 -> "${element.fileName}:${element.lineNumber}"
            else -> element.fileName
        }
        return clean("${element.className}.${element.methodName}($location)")
    }

    private fun label(info: ThreadInfo): String = "${clean(info.threadName).take(NAME_LIMIT)}(${info.threadId})"

    /** Collapses whitespace and removes `/` and `\` so the log line survives `AppLogger.safeText` intact. */
    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").replace('/', '_').replace('\\', '_')

    private fun pack(atoms: List<String>, maxEntryChars: Int): List<String> {
        val entries = ArrayList<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) entries += current.toString()
            current.setLength(0)
        }
        for (atom in atoms) {
            for (piece in atom.chunked(maxEntryChars)) {
                if (current.isNotEmpty() && current.length + SEPARATOR.length + piece.length > maxEntryChars) flush()
                if (current.isNotEmpty()) current.append(SEPARATOR)
                current.append(piece)
            }
        }
        flush()
        return entries
    }

    private const val TRUNCATION_RESERVE = 200
    private const val FRAME_PREFIX_RESERVE = 40
}
