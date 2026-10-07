package com.indagium.testing.script

import com.indagium.capture.sanitizeAppImageRuntimeForChild
import com.indagium.capture.terminateProcessTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Runs one host command for the test-suite feature: custom scripts (TestScriptRunner) and anything else that needs
// a bounded, cancellable child process. Unlike capture's CaptureProcessRunner this one takes a working directory,
// stdin bytes and reports whether the output cap cut the output; like it, the child inherits a sanitized
// environment (AppImage runtime variables removed) onto which the explicit [HostCommandSpec.environment] is merged.

const val DEFAULT_HOST_COMMAND_TIMEOUT_MS = 30_000L
const val DEFAULT_HOST_COMMAND_OUTPUT_CAP_BYTES = 64 * 1024

/** The grace period a timed-out or cancelled command gets to exit on SIGTERM before it is killed. */
private const val TERMINATE_GRACE_MS = 500L
private const val OUTPUT_READER_JOIN_MS = 1_000L
private const val OUTPUT_INITIAL_CAPACITY_BYTES = 4 * 1024
private const val NANOS_PER_MILLI = 1_000_000L
private const val DENSE_SAMPLING_WINDOW_MS = 250L
private const val DENSE_SAMPLE_INTERVAL_MS = 5L
private const val SAMPLE_INTERVAL_MS = 50L

/** The warning attached to a result whose command left descendant processes running. */
const val BACKGROUND_PROCESS_WARNING = "This script left background processes running; scripts must not start background processes."

/**
 * One command to run. [command] is the full argv (no shell is added). [outputCapBytes] bounds stdout and stderr
 * EACH; [stdin] null closes the child's stdin immediately so a command that reads it sees end-of-file instead of
 * waiting forever.
 */
data class HostCommandSpec(
    val command: List<String>,
    val workingDir: File? = null,
    val environment: Map<String, String> = emptyMap(),
    val stdin: ByteArray? = null,
    val timeoutMs: Long = DEFAULT_HOST_COMMAND_TIMEOUT_MS,
    val outputCapBytes: Int = DEFAULT_HOST_COMMAND_OUTPUT_CAP_BYTES,
) {
    init {
        require(command.isNotEmpty()) { "Command cannot be empty" }
        require(timeoutMs > 0) { "Timeout must be positive" }
        require(outputCapBytes > 0) { "Output cap must be positive" }
    }
}

/**
 * [exitCode] is -1 when the process was killed because it timed out ([timedOut]). [truncated] is true when either
 * stream hit the output cap (the rest was read and discarded so the child never blocks on a full pipe).
 * [warnings] are human-readable notes about the run itself, e.g. [BACKGROUND_PROCESS_WARNING].
 */
data class HostCommandResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMs: Long,
    val warnings: List<String> = emptyList(),
) {
    fun stdoutText(): String = stdout.toString(Charsets.UTF_8)

    fun stderrText(): String = stderr.toString(Charsets.UTF_8)
}

interface HostCommandRunner {
    /**
     * Runs [spec] on Dispatchers.IO and returns when the process ended, timed out, or the caller was cancelled
     * (cancellation kills the process and its descendants before it propagates). A command that cannot be started
     * throws [IOException].
     */
    suspend fun run(spec: HostCommandSpec): HostCommandResult
}

class ProcessBuilderHostCommandRunner : HostCommandRunner {
    override suspend fun run(spec: HostCommandSpec): HostCommandResult = withContext(Dispatchers.IO) {
        val startedNanos = System.nanoTime()
        val builder = ProcessBuilder(spec.command)
        spec.workingDir?.let { builder.directory(it) }
        sanitizeAppImageRuntimeForChild(builder.environment())
        builder.environment().putAll(spec.environment)
        val process = builder.start()
        val stdout = BoundedSink(spec.outputCapBytes)
        val stderr = BoundedSink(spec.outputCapBytes)
        val readers = listOf(
            drain(process.inputStream, stdout, "host-command-stdout"),
            drain(process.errorStream, stderr, "host-command-stderr"),
        )
        val writer = feedStdin(process, spec.stdin)
        val descendants = DescendantTracker(process.toHandle())
        var finished = false
        var leftBehind = false
        try {
            finished = runInterruptible { waitSampling(process, descendants, spec.timeoutMs) }
        } finally {
            // Timeout or cancellation: the process and every descendant sampled while it ran are stopped. A script that
            // ended on its own is not touched, but descendants that outlive it are reported as a warning.
            if (process.isAlive) process.terminateProcessTree(Duration.ofMillis(TERMINATE_GRACE_MS), descendants.handles())
            leftBehind = descendants.anyAlive()
            joinWithin(readers + listOfNotNull(writer), OUTPUT_READER_JOIN_MS)
            closeQuietly(process)
        }
        HostCommandResult(
            exitCode = if (finished) process.exitValue() else TIMED_OUT_EXIT_CODE,
            stdout = stdout.toByteArray(),
            stderr = stderr.toByteArray(),
            timedOut = !finished,
            truncated = stdout.truncated || stderr.truncated,
            durationMs = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI,
            warnings = if (leftBehind) listOf(BACKGROUND_PROCESS_WARNING) else emptyList(),
        )
    }

    /**
     * Waits up to [timeoutMs] for [process], sampling its descendants on the way (a descendant that is re-parented
     * when its parent exits can no longer be found afterwards). The first moments are sampled densely because
     * most scripts are short. Returns whether the process exited in time.
     */
    private fun waitSampling(process: Process, descendants: DescendantTracker, timeoutMs: Long): Boolean {
        val startedNanos = System.nanoTime()
        val deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            descendants.sample()
            val now = System.nanoTime()
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - now)
            if (remainingMs <= 0) return process.waitFor(0, TimeUnit.MILLISECONDS)
            val dense = now - startedNanos < TimeUnit.MILLISECONDS.toNanos(DENSE_SAMPLING_WINDOW_MS)
            val intervalMs = if (dense) DENSE_SAMPLE_INTERVAL_MS else SAMPLE_INTERVAL_MS
            if (process.waitFor(minOf(intervalMs, remainingMs), TimeUnit.MILLISECONDS)) return true
        }
    }

    /** Joins [threads] against ONE shared deadline, so output held open by a leftover process costs [totalMs] once. */
    private fun joinWithin(threads: List<Thread>, totalMs: Long) {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalMs)
        threads.forEach { thread ->
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            if (remainingMs > 0) thread.join(remainingMs)
        }
    }

    /** Reads [input] on its own daemon thread until end of stream, keeping only what [sink] accepts. */
    private fun drain(input: InputStream, sink: BoundedSink, name: String): Thread = thread(name = name, isDaemon = true) {
        runCatching {
            input.use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    sink.accept(buffer, count)
                }
            }
        }
    }

    /** Writes [bytes] and closes stdin on a separate thread: a child that never reads it must not block the caller. */
    private fun feedStdin(process: Process, bytes: ByteArray?): Thread? {
        if (bytes == null) {
            runCatching { process.outputStream.close() }
            return null
        }
        return thread(name = "host-command-stdin", isDaemon = true) {
            runCatching { process.outputStream.use { it.write(bytes) } }
        }
    }

    private fun closeQuietly(process: Process) {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }

    private companion object {
        const val TIMED_OUT_EXIT_CODE = -1
    }
}

/** Collects up to [limit] bytes and remembers whether anything was dropped. Written by one reader thread, read after it joined. */
private class BoundedSink(private val limit: Int) {
    private val bytes = ByteArrayOutputStream(minOf(limit, OUTPUT_INITIAL_CAPACITY_BYTES))

    @Volatile
    var truncated: Boolean = false
        private set

    @Synchronized
    fun accept(buffer: ByteArray, count: Int) {
        val room = limit - bytes.size()
        if (count > room) truncated = true
        if (room > 0) bytes.write(buffer, 0, minOf(count, room))
    }

    @Synchronized
    fun toByteArray(): ByteArray = bytes.toByteArray()
}

/** Remembers every descendant seen while the command ran, so survivors can be found after their parent exited. */
private class DescendantTracker(private val root: ProcessHandle) {
    private val seen = LinkedHashMap<Long, ProcessHandle>()

    fun sample() {
        runCatching { root.descendants().forEach { seen.putIfAbsent(it.pid(), it) } }
    }

    fun handles(): List<ProcessHandle> = seen.values.toList()

    fun anyAlive(): Boolean = seen.values.any { it.isAlive }
}
