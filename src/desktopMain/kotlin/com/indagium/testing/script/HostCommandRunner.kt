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
 */
data class HostCommandResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMs: Long,
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
        var finished = false
        try {
            finished = runInterruptible { process.waitFor(spec.timeoutMs, TimeUnit.MILLISECONDS) }
        } finally {
            // Timeout, cancellation or normal exit: nothing of this process may outlive the call.
            if (process.isAlive) process.terminateProcessTree(Duration.ofMillis(TERMINATE_GRACE_MS))
            readers.forEach { it.join(OUTPUT_READER_JOIN_MS) }
            writer?.join(OUTPUT_READER_JOIN_MS)
            closeQuietly(process)
        }
        HostCommandResult(
            exitCode = if (finished) process.exitValue() else TIMED_OUT_EXIT_CODE,
            stdout = stdout.toByteArray(),
            stderr = stderr.toByteArray(),
            timedOut = !finished,
            truncated = stdout.truncated || stderr.truncated,
            durationMs = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI,
        )
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
