package com.indagium.capture

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class FakeCaptureRunner : CaptureProcessRunner {
    private val handles = ArrayDeque<RunningCaptureProcess>()
    val specs = mutableListOf<CaptureProcessSpec>()

    fun enqueue(handle: RunningCaptureProcess) {
        handles.addLast(handle)
    }

    override fun start(spec: CaptureProcessSpec): RunningCaptureProcess {
        specs += spec
        return checkNotNull(if (handles.isEmpty()) null else handles.removeFirst()) {
            "No fake process queued for ${spec.command}"
        }
    }
}

internal class StreamingFakeProcess : RunningCaptureProcess {
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
