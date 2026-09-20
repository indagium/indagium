package com.indagium.capture

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class CaptureProcessSpec(
    val command: List<String>,
    val environment: Map<String, String> = emptyMap(),
    val redirectErrorStream: Boolean = false,
)

data class CaptureCommandResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val timedOut: Boolean = false,
) {
    fun stdoutText(): String = stdout.toString(Charsets.UTF_8)

    fun stderrText(): String = stderr.toString(Charsets.UTF_8)
}

interface RunningCaptureProcess : Closeable {
    val inputStream: InputStream
    val errorStream: InputStream
    val isAlive: Boolean

    fun waitFor(timeout: Duration): Boolean

    fun exitCode(): Int?

    fun terminate(grace: Duration = Duration.ofSeconds(2))
}

fun interface CaptureProcessRunner {
    fun start(spec: CaptureProcessSpec): RunningCaptureProcess

    fun run(
        spec: CaptureProcessSpec,
        timeout: Duration = Duration.ofSeconds(5),
        outputLimitBytes: Int = DEFAULT_CAPTURE_COMMAND_OUTPUT_LIMIT,
    ): CaptureCommandResult {
        require(outputLimitBytes > 0)
        val process = start(spec)
        val stdout = BoundedCaptureOutput(outputLimitBytes)
        val stderr = BoundedCaptureOutput(outputLimitBytes)
        val stdoutThread = thread(name = "capture-command-stdout", isDaemon = true) {
            process.inputStream.use { it.copyBoundedTo(stdout) }
        }
        val stderrThread = thread(name = "capture-command-stderr", isDaemon = true) {
            process.errorStream.use { it.copyBoundedTo(stderr) }
        }
        return try {
            val finished = process.waitFor(timeout)
            if (!finished) process.terminate()
            stdoutThread.join(PROCESS_OUTPUT_JOIN_TIMEOUT_MS)
            stderrThread.join(PROCESS_OUTPUT_JOIN_TIMEOUT_MS)
            CaptureCommandResult(
                exitCode = process.exitCode() ?: -1,
                stdout = stdout.toByteArray(),
                stderr = stderr.toByteArray(),
                timedOut = !finished,
            )
        } finally {
            process.close()
        }
    }
}

class ProcessBuilderCaptureRunner : CaptureProcessRunner {
    override fun start(spec: CaptureProcessSpec): RunningCaptureProcess {
        require(spec.command.isNotEmpty()) { "Capture command cannot be empty" }
        val builder = ProcessBuilder(spec.command)
            .redirectErrorStream(spec.redirectErrorStream)
        builder.environment().putAll(spec.environment)
        return JvmRunningCaptureProcess(builder.start())
    }
}

private class JvmRunningCaptureProcess(private val process: Process) : RunningCaptureProcess {
    override val inputStream: InputStream get() = process.inputStream
    override val errorStream: InputStream get() = process.errorStream
    override val isAlive: Boolean get() = process.isAlive

    override fun waitFor(timeout: Duration): Boolean =
        process.waitFor(timeout.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)

    override fun exitCode(): Int? = if (process.isAlive) null else process.exitValue()

    override fun terminate(grace: Duration) {
        val handle = process.toHandle()
        val descendants = handle.descendants().toList().asReversed()
        descendants.forEach { child -> runCatching { child.destroy() } }
        runCatching { process.destroy() }
        if (!waitFor(grace)) {
            descendants.forEach { child -> if (child.isAlive) runCatching { child.destroyForcibly() } }
            if (process.isAlive) runCatching { process.destroyForcibly() }
            waitFor(Duration.ofSeconds(1))
        }
    }

    override fun close() {
        if (process.isAlive) terminate()
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }
}

private class BoundedCaptureOutput(private val limit: Int) :
    ByteArrayOutputStream(minOf(limit, BOUNDED_OUTPUT_INITIAL_CAPACITY_BYTES)) {
    override fun write(value: Int) {
        if (count < limit) super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val accepted = minOf(length, limit - count)
        if (accepted > 0) super.write(bytes, offset, accepted)
    }
}

private fun InputStream.copyBoundedTo(destination: BoundedCaptureOutput) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) return
        destination.write(buffer, 0, count)
    }
}

const val DEFAULT_CAPTURE_COMMAND_OUTPUT_LIMIT: Int = 256 * 1024

private const val BOUNDED_OUTPUT_INITIAL_CAPACITY_BYTES = 8 * 1024
private const val PROCESS_OUTPUT_JOIN_TIMEOUT_MS = 1_000L
