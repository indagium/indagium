package com.indagium.testing.authoring

import com.indagium.capture.RunningCaptureProcess
import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorTouchAction
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// Records what a person does on the device itself (or in the emulator window) by streaming `adb shell getevent -lt`, so recording
// does not depend on the Indagium mirror. The reader thread only parses lines; the worker thread owns every decision and is the
// only one that talks to the device again (keyboard state, field text). The recording session's lock is never held here.
//
// On-screen keyboard. While the keyboard is up, a gesture that starts inside its window is never recorded as a tap: it joins a
// typing burst. The burst ends when the keyboard hides, the user does something else, or BURST_IDLE_MS pass without a key; the field
// text is then read from the UI hierarchy and becomes one "Enter text" row (a password field becomes "Enter text: ••••"). No key
// position is passed on or kept anywhere.

/** What the recorder hands to the recording session. */
internal interface DeviceInputSink {
    /** One input the device produced, at wall-clock [atMs]. Delivered in order. */
    fun deviceInput(command: MirrorControlCommand, atMs: Long)

    /** The text typed on the on-screen keyboard between [startMs] and [endMs]. */
    fun keyboardText(reading: KeyboardTextReading, startMs: Long, endMs: Long)

    fun deviceInputWarning(message: String)

    /** After [DeviceInputHandle.finish]: everything that was going to be delivered has been. */
    fun deviceInputFinished()
}

/** What was read about a keyboard burst: the field text (null when it could not be read), the field's label and whether it is a password. */
internal data class KeyboardTextReading(val text: String?, val fieldLabel: String?, val password: Boolean)

/** A running recorder. [finish] ends observation without blocking and delivers what is pending; [close] stops it at once. */
internal interface DeviceInputHandle : Closeable {
    fun finish()
}

internal const val BURST_IDLE_MS = 2_000L
private const val BURST_SNAPSHOT_MS = 600L
private const val IME_FRESH_MS = 1_500L
private const val FALLBACK_KEYBOARD_PERCENT = 40
private const val PERCENT = 100
private const val MAX_GESTURE_EVENTS = 4_000
private const val MAX_QUEUED_EVENTS = 8_192
private const val MAX_GETEVENT_LINE_BYTES = 512
private const val TICK_MS = 200L
private const val ROTATION_POLL_MS = 2_000L
private const val QUARTER_TURNS_PER_ROTATION = 4
private const val SHUTDOWN_JOIN_MS = 1_000L

/**
 * The decisions of the recorder, single-threaded and free of processes and clocks of its own (so it is tested with a fake
 * environment). Events must come in order; [tick] must be called regularly. May block on [environment] reads.
 */
internal class DeviceInputPipeline(
    private val sink: DeviceInputSink,
    private val environment: DeviceInputEnvironment,
    private val rotation: () -> Int,
    private val clock: () -> Long,
) {
    private class Buffered(val touch: MirrorControlCommand.Touch, val atMs: Long)

    private class Burst(val startMs: Long, var lastMs: Long) {
        var snapshot: FocusedField? = null
        var snapshotAtMs = 0L
        var passwordSeen = false
    }

    private class ImeCache(val state: ImeState?, val atMs: Long, val turns: Int)

    private val keyText = KeyTextAccumulator()
    private val gesture = ArrayList<Buffered>()
    private val pointersDown = HashSet<Long>()
    private var burst: Burst? = null
    private var ime: ImeCache? = null
    private var imeWarned = false

    fun onTouch(touch: MirrorControlCommand.Touch, atMs: Long) {
        when (touch.action) {
            MirrorTouchAction.DOWN -> {
                if (pointersDown.isEmpty()) {
                    flushKeyText()
                    gesture.clear()
                }
                pointersDown += touch.pointerId
                gesture += Buffered(touch, atMs)
            }
            MirrorTouchAction.MOVE -> if (pointersDown.isNotEmpty() && gesture.size < MAX_GESTURE_EVENTS) gesture += Buffered(touch, atMs)
            MirrorTouchAction.UP, MirrorTouchAction.CANCEL -> if (pointersDown.remove(touch.pointerId)) {
                gesture += Buffered(touch, atMs)
                if (pointersDown.isEmpty()) completeGesture()
            }
        }
    }

    fun onKey(key: DeviceKey, down: Boolean, repeat: Boolean, atMs: Long) {
        for (output in keyText.feed(key, down, repeat, atMs)) {
            when (output) {
                is KeyOutput.Text -> emitText(output.run)
                is KeyOutput.Control -> {
                    endBurst()
                    ime = null
                    sink.deviceInput(MirrorControlCommand.Key(MirrorKeyAction.DOWN, output.keycode), output.atMs)
                }
            }
        }
    }

    fun tick(nowMs: Long) {
        keyText.flushIfIdle(nowMs)?.let(::emitText)
        val current = burst ?: return
        val idle = nowMs - current.lastMs
        when {
            idle >= BURST_IDLE_MS -> endBurst()
            idle >= BURST_SNAPSHOT_MS -> {
                if (current.snapshotAtMs < current.lastMs) takeSnapshot(current)
                if (imeState()?.shown == false) endBurst()
            }
        }
    }

    /** Delivers what is pending: typed text and the open keyboard burst. A touch still in progress is dropped. */
    fun finish() {
        gesture.clear()
        pointersDown.clear()
        flushKeyText()
        endBurst()
    }

    private fun completeGesture() {
        val events = gesture.toList()
        gesture.clear()
        val downs = events.filter { it.touch.action == MirrorTouchAction.DOWN }
        if (downs.isEmpty()) return
        val state = imeState()
        if (state != null && state.shown && downs.all { insideKeyboard(it.touch, state) }) {
            val last = events.last().atMs
            burst = burst?.also { it.lastMs = last } ?: Burst(events.first().atMs, last)
            return
        }
        endBurst()
        ime = null
        events.forEach { sink.deviceInput(it.touch, it.atMs) }
    }

    private fun insideKeyboard(touch: MirrorControlCommand.Touch, state: ImeState): Boolean {
        val top = touch.screenHeight * (PERCENT - FALLBACK_KEYBOARD_PERCENT) / PERCENT
        val frame = state.frame ?: ScreenRect(0, top, touch.screenWidth, touch.screenHeight)
        return frame.contains(touch.x, touch.y)
    }

    /** The keyboard state, read again when the last read is older than [IME_FRESH_MS], was invalidated, or the rotation changed. */
    private fun imeState(): ImeState? {
        val cached = ime
        val turns = rotation()
        val now = clock()
        if (cached != null && now - cached.atMs < IME_FRESH_MS && cached.turns == turns) return cached.state
        val state = when (environment.imeShown()) {
            null -> {
                if (!imeWarned) {
                    imeWarned = true
                    sink.deviceInputWarning("The on-screen keyboard state could not be read; taps on the keyboard may be recorded as taps.")
                }
                null
            }
            false -> ImeState(shown = false, frame = null)
            true -> {
                val known = cached?.state?.takeIf { it.shown && cached.turns == turns }?.frame
                ImeState(shown = true, frame = known ?: environment.imeFrame())
            }
        }
        ime = ImeCache(state, now, turns)
        return state
    }

    private fun takeSnapshot(current: Burst) {
        current.snapshotAtMs = clock()
        val field = environment.focusedField() ?: return
        current.snapshot = field
        if (field.password) current.passwordSeen = true
    }

    private fun endBurst() {
        val current = burst ?: return
        burst = null
        // A snapshot taken after the last key is exact; otherwise read the field now (the screen may have moved on, see the class note).
        val field = current.snapshot?.takeIf { current.snapshotAtMs >= current.lastMs } ?: environment.focusedField()
        val password = current.passwordSeen || field?.password == true
        val reading = KeyboardTextReading(
            text = field?.text?.takeIf { !password },
            fieldLabel = (field ?: current.snapshot)?.label?.takeIf(String::isNotBlank),
            password = password,
        )
        sink.keyboardText(reading, current.startMs, current.lastMs)
    }

    private fun flushKeyText() {
        keyText.flush()?.let(::emitText)
    }

    private fun emitText(run: TypedRun) {
        endBurst()
        sink.deviceInput(MirrorControlCommand.Text(run.text), run.endMs)
    }
}

/**
 * Calls [onLine] for each line of [input], reading at most [maxLineBytes] of any one line (the rest of a longer line is skipped).
 * Stops when [onLine] returns false or the stream ends; a final line without a newline counts.
 */
internal fun forEachBoundedLine(input: InputStream, maxLineBytes: Int, onLine: (String) -> Boolean) {
    val line = java.io.ByteArrayOutputStream()
    var skipping = false
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        for (index in 0 until count) {
            val value = buffer[index]
            if (value == '\n'.code.toByte()) {
                val wasSkipping = skipping
                val text = line.toString(Charsets.UTF_8)
                line.reset()
                skipping = false
                if (!wasSkipping && !onLine(text)) return
            } else if (!skipping) {
                if (line.size() < maxLineBytes) line.write(value.toInt()) else skipping = true
            }
        }
    }
    if (line.size() > 0 && !skipping) onLine(line.toString(Charsets.UTF_8))
}

/** Maps getevent's kernel timestamps (device uptime) onto the host wall clock; never runs backwards. */
internal class KernelClock(private val now: () -> Long) {
    private var offsetMs: Long? = null
    private var last = Long.MIN_VALUE

    /** The wall-clock time of an event at [kernelMicros]. The offset is the smallest seen, i.e. the one with the least transport delay. */
    fun wallMs(kernelMicros: Long): Long {
        val kernelMs = kernelMicros / MICROS_PER_MILLI
        val candidate = now() - kernelMs
        val offset = offsetMs?.let { minOf(it, candidate) } ?: candidate
        offsetMs = offset
        last = maxOf(last, kernelMs + offset)
        return last
    }

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
    }
}

/**
 * Streams the device's input events and feeds them to a [DeviceInputSink]. Threads: a reader (parses lines, never blocks on the
 * device), a worker (the [DeviceInputPipeline], may wait on adb reads) and a rotation poller. Construct, then [start].
 */
internal class DeviceInputRecorder(
    private val plan: DeviceInputPreflight.Ready,
    private val startStream: () -> RunningCaptureProcess,
    private val environment: DeviceInputEnvironment,
    private val sink: DeviceInputSink,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviceInputHandle {
    private sealed interface Event {
        class Touch(val command: MirrorControlCommand.Touch, val atMs: Long) : Event

        class Key(val key: DeviceKey, val down: Boolean, val repeat: Boolean, val atMs: Long) : Event

        data object Finish : Event

        data object Stop : Event
    }

    @Volatile private var rotationTurns = plan.rotation.mod(QUARTER_TURNS_PER_ROTATION)
    private val queue = LinkedBlockingQueue<Event>(MAX_QUEUED_EVENTS)
    private val closed = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val stopSignal = CountDownLatch(1)
    private val dropped = AtomicInteger()
    private val kernelClock = KernelClock(clock)
    private val keyPaths = plan.devices.keyDevices.map { it.path }.toSet()
    private val touchParser = plan.geometry?.let { geometry ->
        GeteventTouchParser(plan.devices.touchScreens, geometry) { rotationTurns }
    }
    private val touchPaths = if (touchParser == null) emptySet() else plan.devices.touchScreens.map { it.path }.toSet()
    private val pipeline = DeviceInputPipeline(sink, environment, { rotationTurns }, clock)
    private var process: RunningCaptureProcess? = null
    private var worker: Thread? = null

    /** The display rotation (quarter turns) a probe or poll just learned; later touches are converted with it. */
    fun updateRotation(turns: Int) {
        rotationTurns = turns.mod(QUARTER_TURNS_PER_ROTATION)
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        plan.warnings.forEach(sink::deviceInputWarning)
        val running = try {
            startStream()
        } catch (failure: IOException) {
            sink.deviceInputWarning("Device input could not be observed: ${failure.message ?: failure::class.simpleName}.")
            closed.set(true)
            return
        }
        process = running
        worker = thread(name = "test-step-recording-input-worker", isDaemon = true, block = ::workerLoop)
        thread(name = "test-step-recording-input-reader", isDaemon = true) { readLoop(running) }
        thread(name = "test-step-recording-input-rotation", isDaemon = true, block = ::rotationLoop)
    }

    override fun finish() {
        if (!started.get() || closed.get()) {
            // Nothing is running, so nothing is pending.
            sink.deviceInputFinished()
            return
        }
        if (!finishing.compareAndSet(false, true)) return
        stopSignal.countDown()
        if (!queue.offer(Event.Finish)) {
            queue.clear()
            queue.offer(Event.Finish)
        }
        stopProcessAsync()
    }

    override fun close() {
        if (closed.getAndSet(true)) return
        stopSignal.countDown()
        queue.clear()
        queue.offer(Event.Stop)
        stopProcessAsync()
    }

    private fun stopProcessAsync() {
        val running = process ?: return
        thread(name = "test-step-recording-input-stop", isDaemon = true) {
            runCatching { running.close() }
            worker?.takeIf { closed.get() }?.join(SHUTDOWN_JOIN_MS)
        }
    }

    private fun readLoop(running: RunningCaptureProcess) {
        try {
            forEachBoundedLine(running.inputStream, MAX_GETEVENT_LINE_BYTES) { line ->
                if (closed.get() || finishing.get()) {
                    false
                } else {
                    handleLine(line)
                    true
                }
            }
        } catch (_: IOException) {
            // The stream closes when the recorder stops or the device goes away; the check below tells which.
        }
        if (!closed.get() && !finishing.get()) {
            sink.deviceInputWarning("Observing the device's input stopped because adb getevent ended; later input on the device is not recorded.")
        }
    }

    private fun handleLine(line: String) {
        val event = parseGeteventLine(line) ?: return
        when {
            event.path in touchPaths -> touchParser?.accept(event)?.forEach { timed ->
                enqueue(Event.Touch(timed.command, kernelClock.wallMs(timed.kernelMicros)))
            }
            event.path in keyPaths && event.type == "EV_KEY" -> {
                val key = deviceKeyFor(event.code) ?: return
                val repeat = event.value == "REPEAT" || event.value == "00000002"
                val down = repeat || event.value == "DOWN" || event.value == "00000001"
                val up = event.value == "UP" || event.value == "00000000"
                if (down || up) enqueue(Event.Key(key, down, repeat, kernelClock.wallMs(event.kernelMicros)))
            }
        }
    }

    private fun enqueue(event: Event) {
        if (!queue.offer(event) && dropped.getAndIncrement() == 0) {
            sink.deviceInputWarning("Device input arrived faster than it could be processed; some of it was not recorded.")
        }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever one event does must not end the recording of the next.
    private fun workerLoop() {
        var failed = false
        while (true) {
            val event = try {
                queue.poll(TICK_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            }
            try {
                when (event) {
                    is Event.Touch -> pipeline.onTouch(event.command, event.atMs)
                    is Event.Key -> pipeline.onKey(event.key, event.down, event.repeat, event.atMs)
                    Event.Finish -> {
                        pipeline.finish()
                        sink.deviceInputFinished()
                        return
                    }
                    Event.Stop -> return
                    null -> Unit
                }
                if (!closed.get()) pipeline.tick(clock())
            } catch (failure: Exception) {
                if (!failed) {
                    failed = true
                    sink.deviceInputWarning("Processing device input failed: ${failure.message ?: failure::class.simpleName}.")
                }
            }
        }
    }

    private fun rotationLoop() {
        while (!stopSignal.await(ROTATION_POLL_MS, TimeUnit.MILLISECONDS)) {
            environment.rotation()?.let(::updateRotation)
        }
    }
}
