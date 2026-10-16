package com.indagium.capture

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class FakeCaptureRunner : CaptureProcessRunner {
    private val handles = ArrayDeque<RunningCaptureProcess>()
    val specs = mutableListOf<CaptureProcessSpec>()
    val runSpecs = mutableListOf<CaptureProcessSpec>()
    @Volatile var adbState: String = "device"
    @Volatile var shellResponsive: Boolean = true
    @Volatile var bootId: String = "test-boot-id"
    @Volatile var bootUptimeMs: Long = 10_000_000L
    @Volatile var bootCompletedValue: String = "1"
    @Volatile var bufferListing: String = "main: ring buffer is 256Kb\nsystem: ring buffer is 256Kb\ncrash: ring buffer is 256Kb\n"
    @Volatile var globalLogTagValue: String = "\n"
    @Volatile var getpropListing: String = "[log.tag.FOO]: [D]\n[ro.build.type]: [userdebug]\n"
    @Volatile var bufferedLogcatOutput: String = ""
    @Volatile var failRestoreCommands: Boolean = false
    @Volatile var runHandler: ((CaptureProcessSpec) -> CaptureCommandResult?)? = null

    fun enqueue(handle: RunningCaptureProcess) {
        handles.addLast(handle)
    }

    override fun start(spec: CaptureProcessSpec): RunningCaptureProcess {
        specs += spec
        return checkNotNull(if (handles.isEmpty()) null else handles.removeFirst()) {
            "No fake process queued for ${spec.command}"
        }
    }

    override fun run(spec: CaptureProcessSpec, timeout: Duration, outputLimitBytes: Int): CaptureCommandResult {
        runSpecs += spec
        runHandler?.invoke(spec)?.let { return it }
        val args = spec.command
        val (stdout, exitCode) = when {
            args.takeLast(4) == listOf("shell", "date", "-u", "+%m-%dT%H:%M:%S") ->
                Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("MM-dd'T'HH:mm:ss")) to 0
            args.takeLast(3) == listOf("shell", "cat", "/proc/sys/kernel/random/boot_id") -> bootId to 0
            args.takeLast(3) == listOf("shell", "cat", "/proc/uptime") -> "${bootUptimeMs / 1000.0} 0.00\n" to 0
            args.takeLast(3) == listOf("shell", "getprop", "sys.boot_completed") -> bootCompletedValue to 0
            args.lastOrNull() == "get-state" -> adbState to if (adbState == "device") 0 else 1
            args.takeLast(3) == listOf("shell", "echo", "capture-ready") ->
                (if (shellResponsive) "capture-ready\n" else "") to if (shellResponsive) 0 else 1
            args.takeLast(4) == listOf("logcat", "-b", "all", "-g") -> bufferListing to 0
            args.takeLast(3) == listOf("shell", "getprop", "log.tag") -> globalLogTagValue to 0
            args.takeLast(2) == listOf("shell", "getprop") -> getpropListing to 0
            args.contains("logcat") && args.contains("-d") -> bufferedLogcatOutput to 0
            args.contains("-G") -> {
                if (!failRestoreCommands) applyFakeBufferResize(args)
                "" to if (failRestoreCommands) 1 else 0
            }
            args.any { it.contains("setprop") } -> {
                if (!failRestoreCommands) applyFakeSetprop(args.joinToString(" "))
                "" to if (failRestoreCommands) 1 else 0
            }
            else -> return super.run(spec, timeout, outputLimitBytes)
        }
        return CaptureCommandResult(exitCode, stdout.toByteArray(), if (exitCode == 0) byteArrayOf() else "permission denied".toByteArray())
    }

    /** Updates fake logd ring sizes exactly as a successful `logcat -G` would. */
    fun setFakeBufferSizes(sizes: Map<String, Long>) {
        val current = parseLogcatBufferSizes(bufferListing).associate { it.buffer to requireNotNull(it.sizeBytes) }.toMutableMap()
        current.putAll(sizes)
        bufferListing = current.entries.joinToString("\n", postfix = "\n") { (buffer, bytes) ->
            "$buffer: ring buffer is ${formatFakeBufferSize(bytes)}"
        }
    }

    private fun applyFakeBufferResize(arguments: List<String>) {
        val sizeBytes = arguments.lastOrNull()?.toLongOrNull() ?: return
        val bufferIndex = arguments.indexOf("-b")
        if (bufferIndex < 0 || bufferIndex + 1 >= arguments.size) return
        val selectedBuffer = arguments[bufferIndex + 1]
        val current = parseLogcatBufferSizes(bufferListing).associate { it.buffer to requireNotNull(it.sizeBytes) }.toMutableMap()
        val targets = if (selectedBuffer == "all") current.keys.toList() else listOf(selectedBuffer)
        targets.forEach { current[it] = sizeBytes }
        bufferListing = current.entries.joinToString("\n", postfix = "\n") { (buffer, bytes) ->
            "$buffer: ring buffer is ${formatFakeBufferSize(bytes)}"
        }
    }

    private fun applyFakeSetprop(command: String) {
        val properties = getpropListing.lineSequence().mapNotNull { line ->
            Regex("""^\[([^\]]+)\]: \[(.*)]$""").matchEntire(line.trim())?.let { it.groupValues[1] to it.groupValues[2] }
        }.toMap().toMutableMap()
        Regex("setprop '([^']+)' '([^']*)'").findAll(command).forEach { match ->
            val property = match.groupValues[1]
            val value = match.groupValues[2]
            when {
                property == "log.tag" -> globalLogTagValue = if (value.isEmpty()) "\n" else "$value\n"
                property.startsWith("log.tag.") && value.isEmpty() -> properties.remove(property)
                property.startsWith("log.tag.") -> properties[property] = value
            }
        }
        getpropListing = properties.entries.joinToString("\n", postfix = if (properties.isEmpty()) "" else "\n") { (property, value) ->
            "[$property]: [$value]"
        }
    }

    private fun formatFakeBufferSize(bytes: Long): String = when {
        bytes % (1024L * 1024L) == 0L -> "${bytes / (1024L * 1024L)}Mb"
        bytes % 1024L == 0L -> "${bytes / 1024L}Kb"
        else -> bytes.toString()
    }
}

internal class StreamingFakeProcess(
    /** Written to stdout from [terminate], like adb's last buffered bytes arriving while it exits. */
    private val emitOnTerminate: String? = null,
) : RunningCaptureProcess {
    private val alive = AtomicBoolean(true)
    private val exited = CountDownLatch(1)
    private val pipe = PipedInputStream(64 * 1024)
    private val producer = PipedOutputStream(pipe)

    override val inputStream: InputStream = pipe
    override val errorStream: InputStream = ByteArrayInputStream(byteArrayOf())
    override val isAlive: Boolean get() = alive.get()

    fun emit(text: String) {
        producer.write(text.toByteArray(Charsets.UTF_8))
        producer.flush()
    }

    fun finish() {
        producer.close()
        alive.set(false)
        exited.countDown()
    }

    override fun waitFor(timeout: Duration): Boolean = exited.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

    override fun exitCode(): Int? = if (alive.get()) null else 0

    override fun terminate(grace: Duration) {
        emitOnTerminate?.let(::emit)
        finish()
    }

    override fun close() {
        if (alive.get()) finish()
        pipe.close()
    }
}

internal class CompletedFakeProcess(
    stdout: ByteArray = byteArrayOf(),
    stderr: ByteArray = byteArrayOf(),
    private val code: Int = 0,
) : RunningCaptureProcess {
    constructor(stdout: String, stderr: String = "", code: Int = 0) :
        this(stdout.toByteArray(), stderr.toByteArray(), code)

    override val inputStream: InputStream = ByteArrayInputStream(stdout)
    override val errorStream: InputStream = ByteArrayInputStream(stderr)
    override val isAlive: Boolean = false

    override fun waitFor(timeout: Duration): Boolean = true

    override fun exitCode(): Int = code

    override fun terminate(grace: Duration) = Unit

    override fun close() = Unit
}

internal fun awaitCapture(timeoutMs: Long = 4_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for capture state" }
        Thread.sleep(10)
    }
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
