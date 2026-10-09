package com.indagium.testing.authoring

import com.indagium.testing.device.DeviceStateReading
import com.indagium.testing.device.DeviceStateSource
import java.io.Closeable
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// Watches the device's surroundings while a recording runs: whether a USB device, a Bluetooth device, a charger or a headset is
// connected, Wi-Fi, airplane mode and mobile data, the screen and its rotation. Every [POLL_MS] it runs ONE bounded
// `adb shell sh -c '<script>'` whose text is a constant built from DeviceStateSource (no user or device text is ever put in it), cut
// into sections by marker lines. Each section is parsed by the same pure parsers the run-time DeviceState checks use. Changes between
// polls become EnvironmentEvents (see EnvironmentEvents.kt); the first poll is the baseline. The reads run on the watcher's own
// thread; the recording session's lock is only taken by the sink, never while adb runs.

/** Where the watcher's findings go. Called on the watcher's thread. */
internal interface EnvironmentSink {
    fun environmentEvents(events: List<EnvironmentEvent>)

    fun environmentWarning(message: String)
}

internal const val ENVIRONMENT_POLL_MS = 2_000L
internal const val ENVIRONMENT_MARKER = "@@INDAGIUM-ENV@@"
private const val SECTION_CAP_BYTES = 256 * 1024
private const val OUTPUT_LIMIT_BYTES = 10 * SECTION_CAP_BYTES
private const val POLL_TIMEOUT_SECONDS = 8L
private const val MAX_FAILURE_CHARS = 200

/**
 * The one script the watcher runs: for each source its marker line, then its command with stderr merged and the output cut at a cap.
 * It holds no quote of either kind (the single quotes around it are the command's, and a double quote would need escaping when the
 * host is Windows), so nothing in it needs interpreting.
 */
internal val ENVIRONMENT_PROBE_SCRIPT: String = DeviceStateSource.entries.joinToString("; ") { source ->
    "echo $ENVIRONMENT_MARKER${source.section}; ${source.command.joinToString(" ")} 2>&1 | head -c $SECTION_CAP_BYTES"
}

/** The adb arguments (after the serial) of one poll: the script as the single remote command string. It holds no single quote. */
internal val ENVIRONMENT_PROBE_COMMAND: List<String> = listOf("shell", "sh -c '$ENVIRONMENT_PROBE_SCRIPT'")

/** What one poll read: the [values] of the keys that could be read (list values in canonical form) and the [failedKeys] that could not. */
internal class EnvironmentSnapshot(val values: Map<String, String>, val failedKeys: List<String>)

/** Cuts the probe's output at its marker lines and parses each section; a missing, empty or not understood section fails its keys. */
internal fun parseEnvironmentOutput(stdout: String): EnvironmentSnapshot {
    val sections = HashMap<String, StringBuilder>()
    var current: StringBuilder? = null
    for (line in stdout.lineSequence()) {
        val text = line.trimEnd('\r')
        if (text.startsWith(ENVIRONMENT_MARKER)) {
            current = sections.getOrPut(text.removePrefix(ENVIRONMENT_MARKER)) { StringBuilder() }
        } else {
            current?.append(text)?.append('\n')
        }
    }
    val values = LinkedHashMap<String, String>()
    val failed = ArrayList<String>()
    for (source in DeviceStateSource.entries) {
        val readings = sections[source.section]?.toString()?.let(source::parse)
        for (key in source.keys) {
            val reading = readings?.get(key)
            if (reading is DeviceStateReading.Value) values[key] = normalizeStateValue(key, reading.text) else failed += key
        }
    }
    return EnvironmentSnapshot(values, failed)
}

/** Polls the device and reports changes to [sink]. [start] it once; [close] stops it at once without waiting. */
internal class RecordingEnvironmentWatcher(
    private val adb: RecordingAdb,
    private val sink: EnvironmentSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = ENVIRONMENT_POLL_MS,
) : Closeable {
    private val differ = EnvironmentDiffer()
    private val stopped = AtomicBoolean(false)
    private val stopSignal = CountDownLatch(1)
    private val started = AtomicBoolean(false)
    private val warned = HashSet<String>()
    private var warnedPollFailure = false

    @Volatile private var worker: Thread? = null

    fun start() {
        if (!started.compareAndSet(false, true) || stopped.get()) return
        worker = thread(name = "test-step-recording-environment", isDaemon = true) {
            do {
                pollOnce()
            } while (!stopped.get() && !awaitNextPoll())
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        stopSignal.countDown()
        worker?.interrupt()
    }

    private fun awaitNextPoll(): Boolean = try {
        stopSignal.await(pollMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        true
    }

    /** One read of the device, a diff and the delivery of what changed. Blocking (adb); never throws. */
    @Suppress("TooGenericExceptionCaught") // Whatever one poll does must not end the watching of the next.
    internal fun pollOnce() {
        if (stopped.get()) return
        try {
            val result = adb.run(ENVIRONMENT_PROBE_COMMAND, Duration.ofSeconds(POLL_TIMEOUT_SECONDS), OUTPUT_LIMIT_BYTES)
            val at = clock()
            if (stopped.get()) return
            if (result.timedOut || result.exitCode != 0) {
                warnPollFailed(if (result.timedOut) "adb timed out" else result.stderrText().trim().take(MAX_FAILURE_CHARS))
                return
            }
            val snapshot = parseEnvironmentOutput(result.stdoutText())
            warnUnreadable(snapshot.failedKeys)
            val events = differ.accept(snapshot.values, at)
            if (events.isNotEmpty() && !stopped.get()) sink.environmentEvents(events)
        } catch (failure: Exception) {
            if (!stopped.get()) warnPollFailed(failure.message ?: failure::class.simpleName.orEmpty())
        }
    }

    private fun warnPollFailed(reason: String) {
        if (warnedPollFailure) return
        warnedPollFailure = true
        val detail = if (reason.isBlank()) "" else " ($reason)"
        sink.environmentWarning("The device's state could not be read through adb$detail, so USB, Bluetooth, power and similar changes are not recorded.")
    }

    private fun warnUnreadable(keys: List<String>) {
        val fresh = keys.filter { warned.add(it) }
        if (fresh.isEmpty()) return
        sink.environmentWarning("These device states could not be read, so changes to them are not recorded: ${fresh.joinToString(", ")}.")
    }
}
