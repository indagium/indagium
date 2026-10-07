package com.indagium.testing.authoring

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorFrame
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newExampleId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

data class RecordedTestStep(
    val action: String,
    val expected: String = "",
    val screenContext: String? = null,
    val screenshotJpeg: ByteArray? = null,
    /** The pre-action frame becomes an oracle only after an explicit review choice. */
    val useScreenshotAsExpected: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
)

data class TestStepRecordingSnapshot(
    val active: Boolean,
    val steps: List<RecordedTestStep> = emptyList(),
    val warnings: List<String> = emptyList(),
    val pendingSnapshots: Int = 0,
)

private const val MAX_RECORDING_SNAPSHOT_PIXELS = 4_000_000L
private const val MAX_RECORDING_SNAPSHOT_BYTES = 2 * 1024 * 1024
private const val MAX_RECORDING_SNAPSHOT_DIMENSION = 1_280

/** Converts accepted mirror inputs to an ordered, review-only step draft. It never starts a device or sends input. */
class TestStepRecordingSession internal constructor(
    val deviceSerial: String,
    subscription: Closeable? = null,
    private val snapshotEncoder: (MirrorFrame) -> ByteArray? = ::encodeRecordingFrame,
    private val drainTimeoutMs: Long = SNAPSHOT_DRAIN_TIMEOUT_MS,
) : Closeable {
    val id: String = UUID.randomUUID().toString()

    private data class TouchStart(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val downAt: Long,
        var moved: Boolean = false,
        var lastX: Int = x,
        var lastY: Int = y,
    )

    private val mutableSnapshot = MutableStateFlow(TestStepRecordingSnapshot(active = true))
    val snapshot: StateFlow<TestStepRecordingSnapshot> = mutableSnapshot
    private val touches = mutableMapOf<Long, TouchStart>()
    private val suppressedPointers = mutableSetOf<Long>()
    private var suppressingMultiTouch = false
    private val lock = Any()
    private var subscription: Closeable? = subscription
    private var applicationReserved = false
    private val queuedScreenshots = AtomicInteger()
    private val disposed = AtomicBoolean(false)
    private val screenshotWorker = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_PENDING_SCREENSHOTS),
        { task -> Thread(task, "test-step-recording-image").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    internal fun accept(command: MirrorControlCommand, frame: MirrorFrame? = null, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (!mutableSnapshot.value.active || mutableSnapshot.value.steps.size >= MAX_RECORDED_STEPS) {
            if (mutableSnapshot.value.active) warn("Recording stopped accepting input after $MAX_RECORDED_STEPS steps.")
            return@synchronized
        }
        when (command) {
            is MirrorControlCommand.Touch -> touch(command, frame, nowMs)
            is MirrorControlCommand.Key -> if (command.action == MirrorKeyAction.DOWN && command.repeat == 0) key(command.keycode, frame)
            is MirrorControlCommand.Text -> command.value.takeIf(String::isNotBlank)?.let { recordText(it, frame) }
            is MirrorControlCommand.Clipboard -> if (command.paste) command.value.takeIf(String::isNotBlank)?.let {
                recordText(it, frame, pasted = true)
            }
            is MirrorControlCommand.Back -> if (command.action == MirrorKeyAction.UP) {
                add("Press Back", "Android Back was sent through the device mirror.", frame)
            }
            MirrorControlCommand.ExpandNotifications, MirrorControlCommand.ExpandSettings,
            MirrorControlCommand.CollapsePanels, MirrorControlCommand.CollapseNotifications,
            MirrorControlCommand.RotateDevice, MirrorControlCommand.SetDisplayPowerOn -> warn("This device control is not converted to a test step yet.")
            is MirrorControlCommand.GetClipboard -> Unit
        }
    }

    /** Attach after constructing the session so a synchronous input callback can never observe an uninitialized owner. */
    fun attach(subscription: Closeable) {
        val reject = synchronized(lock) {
            if (!mutableSnapshot.value.active || this.subscription != null) {
                true
            } else {
                this.subscription = subscription
                false
            }
        }
        if (reject) subscription.close()
    }

    fun replaceSteps(steps: List<RecordedTestStep>): Boolean = synchronized(lock) {
        if (applicationReserved) return@synchronized false
        mutableSnapshot.value = mutableSnapshot.value.copy(steps = steps.take(MAX_RECORDED_STEPS))
        true
    }

    fun updateReviewedSteps(
        actionsAndExpected: List<Pair<String, String>>,
        expectedScreenshotIds: Set<String> = emptySet(),
        expectedRowIds: List<String>? = null,
    ): Boolean = synchronized(lock) {
        val old = mutableSnapshot.value.steps
        if (!validReviewUpdate(old, actionsAndExpected, expectedScreenshotIds, expectedRowIds)) return@synchronized false
        mutableSnapshot.value = mutableSnapshot.value.copy(steps = old.mapIndexed { index, row ->
            row.copy(
                action = actionsAndExpected[index].first,
                expected = actionsAndExpected[index].second,
                useScreenshotAsExpected = row.id in expectedScreenshotIds,
            )
        })
        true
    }

    private fun validReviewUpdate(
        old: List<RecordedTestStep>,
        actionsAndExpected: List<Pair<String, String>>,
        expectedScreenshotIds: Set<String>,
        expectedRowIds: List<String>?,
    ): Boolean = !mutableSnapshot.value.active && !applicationReserved && actionsAndExpected.size == old.size &&
        expectedScreenshotIds.all { id -> old.any { it.id == id } } &&
        (expectedRowIds == null || expectedRowIds == old.map { it.id })

    fun stop(): TestStepRecordingSnapshot {
        val detached = synchronized(lock) {
            var oldSubscription: Closeable? = null
            if (mutableSnapshot.value.active) {
                touches.clear()
                mutableSnapshot.value = mutableSnapshot.value.copy(active = false)
                oldSubscription = subscription
                subscription = null
                screenshotWorker.shutdown()
            }
            oldSubscription
        }
        detached?.close()
        return mutableSnapshot.value
    }

    suspend fun stopAndDrain(): TestStepRecordingSnapshot {
        stop()
        val drained = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { screenshotWorker.awaitTermination(drainTimeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        }
        if (!drained) {
            val discarded = screenshotWorker.shutdownNow().size
            synchronized(lock) {
                disposed.set(true)
                queuedScreenshots.updateAndGet { (it - discarded).coerceAtLeast(0) }
                mutableSnapshot.value = mutableSnapshot.value.copy(pendingSnapshots = 0)
                warn("Some screen snapshots could not finish before recording stopped; the draft remains usable without those images.")
            }
        }
        return mutableSnapshot.value
    }

    override fun close() {
        stop()
        disposed.set(true)
        val discarded = screenshotWorker.shutdownNow().size
        queuedScreenshots.updateAndGet { (it - discarded).coerceAtLeast(0) }
        synchronized(lock) { mutableSnapshot.value = mutableSnapshot.value.copy(pendingSnapshots = 0) }
    }

    fun toTestSteps(assetPathFor: (ByteArray) -> String? = { null }): List<TestStep> = toTestSteps(mutableSnapshot.value.steps, assetPathFor)

    internal fun freezeReviewedSnapshotForApply(): TestStepRecordingSnapshot? = synchronized(lock) {
        val current = mutableSnapshot.value
        if (current.active || current.pendingSnapshots > 0 || applicationReserved) null
        else current.also { applicationReserved = true }
    }

    internal fun releaseApplyReservation() = synchronized(lock) { applicationReserved = false }

    internal fun toTestSteps(rows: List<RecordedTestStep>, assetPathFor: (ByteArray) -> String? = { null }): List<TestStep> = rows.map { row ->
        val context = row.screenContext?.takeIf(String::isNotBlank)
        val assetPath = row.screenshotJpeg?.takeIf { row.useScreenshotAsExpected }?.let(assetPathFor)
        val examples = buildList {
            if (context != null) add(StepExample.ReferenceLog(newExampleId(), caption = "Input-time screen context (before this action)", text = context))
            if (assetPath != null && row.useScreenshotAsExpected) {
                add(StepExample.GoldenScreenshot(newExampleId(), caption = "Reviewed expected screenshot; captured at input time", assetPath = assetPath))
            }
        }
        TestStep(
            id = "",
            action = row.action,
            expected = row.expected,
            examples = examples,
        )
    }

    @Suppress("CyclomaticComplexMethod") // One lock-protected multi-pointer gesture state machine.
    private fun touch(event: MirrorControlCommand.Touch, frame: MirrorFrame?, nowMs: Long) {
        when (event.action) {
            MirrorTouchAction.DOWN -> {
                if (suppressingMultiTouch || touches.isNotEmpty()) {
                    suppressingMultiTouch = true
                    suppressedPointers += touches.keys
                    suppressedPointers += event.pointerId
                    touches.clear()
                    warn("Multi-touch gestures are not supported and were skipped.")
                } else {
                    touches[event.pointerId] = TouchStart(event.x, event.y, event.screenWidth, event.screenHeight, nowMs)
                }
            }
            MirrorTouchAction.MOVE -> touches[event.pointerId]?.let { start ->
                start.lastX = event.x
                start.lastY = event.y
                if (abs(event.x - start.x) + abs(event.y - start.y) > TOUCH_MOVE_THRESHOLD) start.moved = true
            }
            MirrorTouchAction.UP -> {
                if (suppressingMultiTouch) {
                    suppressedPointers.remove(event.pointerId)
                    if (suppressedPointers.isEmpty()) suppressingMultiTouch = false
                    return
                }
                touches.remove(event.pointerId)?.let { start ->
                    val context = "Device screen ${event.screenWidth}×${event.screenHeight}; coordinates normalized to screen size."
                    val duration = nowMs - start.downAt
                    val action = if (duration >= LONG_PRESS_MS && !start.moved) {
                        "Long press at ${point(event.x, event.y, event.screenWidth, event.screenHeight)}"
                    } else if (start.moved) {
                        "Swipe from ${point(start.x, start.y, start.width, start.height)} to ${point(event.x, event.y, event.screenWidth, event.screenHeight)}"
                    } else {
                        "Tap at ${point(event.x, event.y, event.screenWidth, event.screenHeight)}"
                    }
                    if (duration >= LONG_PRESS_MS && start.moved) warn("A long swipe/drag duration is not preserved; review the recorded swipe.")
                    add(action, context, frame)
                }
            }
            MirrorTouchAction.CANCEL -> {
                if (suppressingMultiTouch) {
                    suppressedPointers.remove(event.pointerId)
                    if (suppressedPointers.isEmpty()) suppressingMultiTouch = false
                } else if (touches.remove(event.pointerId) != null) warn("A cancelled touch gesture was skipped.")
            }
        }
    }

    private fun key(keycode: Int, frame: MirrorFrame?) {
        val label = when (keycode) {
            3 -> "Home"
            4 -> "Back"
            KEYCODE_TAB -> "Tab"
            KEYCODE_ENTER -> "Enter"
            KEYCODE_DELETE -> "Delete"
            KEYCODE_ESCAPE -> "Escape"
            KEYCODE_UP -> "Up"
            KEYCODE_DOWN -> "Down"
            KEYCODE_LEFT -> "Left"
            KEYCODE_RIGHT -> "Right"
            else -> "Android key $keycode"
        }
        if (label.startsWith("Android key")) warn("Key code $keycode has no friendly label; review this recorded action.")
        add("Press $label", "Android key input was sent through the device mirror.", frame)
    }

    private fun recordText(text: String, frame: MirrorFrame?, pasted: Boolean = false) {
        val bounded = text.take(MAX_RECORDED_TEXT)
        val suffix = if (text.length > MAX_RECORDED_TEXT) " (recorded only the first $MAX_RECORDED_TEXT of ${text.length} characters)" else ""
        if (suffix.isNotEmpty()) warn("Text input was truncated in the draft: ${text.length} characters received, first $MAX_RECORDED_TEXT retained.")
        val prefix = if (pasted) "Paste" else "Enter"
        val context = if (pasted) "Pasted text was sent through the device mirror." else "Text was entered through the device mirror."
        add("$prefix text: $bounded$suffix", context, frame)
    }

    private fun add(action: String, context: String, frame: MirrorFrame?) {
        val current = mutableSnapshot.value
        if (current.steps.size >= MAX_RECORDED_STEPS) {
            warn("Recording reached the $MAX_RECORDED_STEPS-step limit; stop and review the draft.")
            return
        }
        val index = current.steps.size
        val screenshotContext = if (frame == null) {
            "$context Screenshot frame unavailable; UI hierarchy isn't exposed by the mirror."
        } else {
            "$context UI hierarchy isn't exposed by the mirror; a bounded screen snapshot is captured when supported."
        }
        val recorded = RecordedTestStep(action, screenContext = screenshotContext)
        mutableSnapshot.value = current.copy(steps = current.steps + recorded)
        if (frame == null) {
            warn("No screen frame was available for a recorded input; its step keeps device/screen context only.")
        } else {
            scheduleSnapshot(recorded.id, frame)
        }
    }

    private fun scheduleSnapshot(recordedId: String, frame: MirrorFrame) {
        if (queuedScreenshots.incrementAndGet() > MAX_PENDING_SCREENSHOTS) {
            queuedScreenshots.decrementAndGet()
            warn("Screen snapshot queue is full; this gesture was recorded without an image.")
            return
        }
        synchronized(lock) { mutableSnapshot.value = mutableSnapshot.value.copy(pendingSnapshots = queuedScreenshots.get()) }
        try {
            screenshotWorker.execute {
                try {
                    val bytes = snapshotEncoder(frame)
                    synchronized(lock) {
                        if (disposed.get()) return@synchronized
                        val rows = mutableSnapshot.value.steps.toMutableList()
                        if (bytes == null) {
                            warn("A screen frame exceeded the image size limits and was omitted.")
                        } else {
                            val index = rows.indexOfFirst { it.id == recordedId }
                            if (index >= 0) rows[index] = rows[index].copy(screenshotJpeg = bytes)
                            mutableSnapshot.value = mutableSnapshot.value.copy(steps = rows, pendingSnapshots = (queuedScreenshots.get() - 1).coerceAtLeast(0))
                        }
                    }
                } finally {
                    queuedScreenshots.decrementAndGet()
                    synchronized(lock) {
                        mutableSnapshot.value = mutableSnapshot.value.copy(pendingSnapshots = queuedScreenshots.get())
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            queuedScreenshots.decrementAndGet()
            synchronized(lock) { mutableSnapshot.value = mutableSnapshot.value.copy(pendingSnapshots = queuedScreenshots.get()) }
            warn("Screen snapshot queue is full; this gesture was recorded without an image.")
        }
    }

    private fun warn(message: String) {
        val current = mutableSnapshot.value
        mutableSnapshot.value = current.copy(warnings = (current.warnings + message).distinct().takeLast(MAX_RECORDED_WARNINGS))
    }

    private fun point(x: Int, y: Int, width: Int, height: Int): String =
        if (width <= 0 || height <= 0) "(unknown screen coordinates)" else "(${(x * 100f / width).toInt()}%, ${(y * 100f / height).toInt()}%)"

    private companion object {
        const val MAX_RECORDED_STEPS = 100
        const val MAX_RECORDED_WARNINGS = 30
        const val MAX_RECORDED_TEXT = 200
        const val TOUCH_MOVE_THRESHOLD = 24
        const val LONG_PRESS_MS = 650L
        const val MAX_PENDING_SCREENSHOTS = 3
        const val SNAPSHOT_DRAIN_TIMEOUT_MS = 2_000L
        const val KEYCODE_TAB = 61
        const val KEYCODE_ENTER = 66
        const val KEYCODE_DELETE = 67
        const val KEYCODE_ESCAPE = 111
        const val KEYCODE_UP = 19
        const val KEYCODE_DOWN = 20
        const val KEYCODE_LEFT = 21
        const val KEYCODE_RIGHT = 22
    }
}

private fun encodeRecordingFrame(frame: MirrorFrame): ByteArray? {
    if (
        frame.width <= 0 || frame.height <= 0 || frame.width.toLong() * frame.height > MAX_RECORDING_SNAPSHOT_PIXELS ||
        frame.pixelsArgb.size != frame.width * frame.height
    ) return null
    return runCatching {
        val scale = minOf(1.0, MAX_RECORDING_SNAPSHOT_DIMENSION.toDouble() / max(frame.width, frame.height))
        val width = (frame.width * scale).toInt().coerceAtLeast(1)
        val height = (frame.height * scale).toInt().coerceAtLeast(1)
        val source = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
        source.setRGB(0, 0, frame.width, frame.height, frame.pixelsArgb, 0, frame.width)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.drawImage(source, 0, 0, width, height, null)
        graphics.dispose()
        ByteArrayOutputStream().use { output ->
            if (!ImageIO.write(image, "jpg", output)) return null
            output.toByteArray().takeIf { it.size <= MAX_RECORDING_SNAPSHOT_BYTES }
        }
    }.getOrNull()
}
