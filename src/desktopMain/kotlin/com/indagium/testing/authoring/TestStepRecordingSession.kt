package com.indagium.testing.authoring

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorFrame
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.debug.SWIPE_DURATION_MAX_MS
import com.indagium.debug.SWIPE_DURATION_MIN_MS
import com.indagium.debug.encodeBoundedDeviceScreen
import com.indagium.testing.model.StepCheck
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
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
    val optional: Boolean = false,
    val condition: String? = null,
    val screenContext: String? = null,
    val screenshotJpeg: ByteArray? = null,
    /** A recorded preview becomes an oracle only after an explicit review choice. */
    val useScreenshotAsExpected: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
    /** Legacy source flag: true when the image came from adb rather than a mirror frame. */
    val screenshotFromAdb: Boolean = false,
    /** How long the touch lasted. For a swipe this is the value replay uses (clamped to the lane swipe range). */
    val durationMs: Long? = null,
    /** Touch positions: the tap point, or a swipe's start, at most [MAX_GESTURE_SAMPLES] sampled moves and its end. */
    val gesturePath: List<RecordedPoint> = emptyList(),
    /** The screen read before / after this input, set only when the probe timing proves it (see [attachScreenStates]). */
    val before: RecordingScreenRef? = null,
    val after: RecordingScreenRef? = null,
    val tappedElement: TappedElement? = null,
    val kind: RecordedInputKind? = null,
    /** Wall-clock time the input completed (a touch's release); with [inputStartMs] the key the screen states are attached by. */
    val inputAtMs: Long = 0L,
    /** Wall-clock time the input began (a touch's press); equals [inputAtMs] for keys and text. */
    val inputStartMs: Long = 0L,
    /** Set on a row the AI rewrite made: the ids of the raw rows it stands for, in recorded order. Empty on a raw row. */
    val sourceInputIds: List<String> = emptyList(),
    /** Set with [sourceInputIds]: the raw inputs described for the AI that later runs the step (becomes a reference example). */
    val sourceHint: String? = null,
    /** Checks the rewrite proposed for this step; they become the applied step's checks. */
    val checks: List<StepCheck> = emptyList(),
    /** Why a rewrite step needs a person to supply or confirm missing screen evidence. */
    val reviewReason: String? = null,
    /** The screenshot selected as eligible before/after evidence, separate from the raw input preview above. */
    val beforeScreenshot: RecordingScreenshotEvidence? = null,
    val afterScreenshot: RecordingScreenshotEvidence? = null,
    /** Acquisition metadata for [screenshotJpeg], when it came from an adb screencap. */
    val screenshotSource: RecordingScreenshotSource? = null,
    val screenshotAcquiredAtMs: Long? = null,
    val screenshotAcquisitionFinishedAtMs: Long? = null,
    /** Mirror frame presentation timestamps are not mapped to wall clock, so raw mirror previews stay uncertain. */
    val screenshotTimingUncertain: Boolean = true,
    /** The screenshot is only verified for this moment when selected from eligible evidence. Raw previews use "input". */
    val screenshotVerifiedMoment: String? = null,
)

/**
 * [pendingSnapshots] counts queued screen images plus the screen-context probe while one is waiting or running. After an AI
 * rewrite [steps] are the rewritten rows and [rawSteps] the recorded ones they replaced (null before a rewrite); [rewriteNotes]
 * is what the AI said about its choices.
 */
data class TestStepRecordingSnapshot(
    val active: Boolean,
    val steps: List<RecordedTestStep> = emptyList(),
    val warnings: List<String> = emptyList(),
    val pendingSnapshots: Int = 0,
    val rawSteps: List<RecordedTestStep>? = null,
    val rewriteNotes: String = "",
    val videoTimeline: RecordedVideoTimelineSummary? = null,
)

private const val MAX_RECORDING_SNAPSHOT_PIXELS = 4_000_000L
private const val MAX_RECORDING_SNAPSHOT_BYTES = 2 * 1024 * 1024
private const val MAX_RECORDING_SNAPSHOT_DIMENSION = 1_280
private const val VIDEO_DRAIN_TIMEOUT_MS = 12_000L

/**
 * Converts accepted mirror inputs to an ordered, review-only step draft. It never starts a device or sends input.
 *
 * The GPU mirror paths (macOS Metal, Windows D3D, Linux VAAPI) carry no CPU pixels, so an input arrives without a
 * [MirrorFrame]. When [screencap] is set, that input's image is read through it instead (PNG bytes, throws on failure)
 * on the same background worker and under the same queue limit; it is never called on the caller's thread.
 *
 * When [screenProbe] is set, a separate single worker also reads the screen context (UI hierarchy, top activity, image)
 * once at the start and again [settleDelayMs] after the last input (a newer input replaces a probe that has not started),
 * so at most one probe runs and one waits. Probe results are matched to rows by time and never guessed, see
 * [attachScreenStates]. The recording [lock] is a leaf: adb work only ever runs on the two workers, never under it.
 */
class TestStepRecordingSession internal constructor(
    val deviceSerial: String,
    subscription: Closeable? = null,
    private val snapshotEncoder: (MirrorFrame) -> ByteArray? = ::encodeRecordingFrame,
    private val drainTimeoutMs: Long = SNAPSHOT_DRAIN_TIMEOUT_MS,
    private val screencap: (() -> ByteArray)? = null,
    private val screenProbe: (() -> RecordingScreenState?)? = null,
    private val screenProbeWithScreenshot: (((RecordingScreenshotEvidence) -> Unit) -> RecordingScreenState?)? = null,
    private val settleDelayMs: Long = PROBE_SETTLE_MS,
    private val probeDrainTimeoutMs: Long = PROBE_DRAIN_TIMEOUT_MS,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    internal val videoTimeline: RecordedVideoTimeline? = null,
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
        /** Sampled MOVE points, thinned by doubling [stride] whenever they exceed [MAX_GESTURE_SAMPLES]. */
        val samples: MutableList<RecordedPoint> = mutableListOf(),
        var moveCount: Int = 0,
        var stride: Int = 1,
    )

    private val mutableSnapshot = MutableStateFlow(TestStepRecordingSnapshot(active = true))
    val snapshot: StateFlow<TestStepRecordingSnapshot> = mutableSnapshot
    private val touches = mutableMapOf<Long, TouchStart>()
    private val suppressedPointers = mutableSetOf<Long>()
    private var suppressingMultiTouch = false
    private val lock = Any()
    private var subscription: Closeable? = subscription
    private var videoSubscription: Closeable? = null
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

    // Probe state. probeLock guards probeRunning/probeWanted and is only ever taken after (never before) `lock`.
    private val probeLock = Any()
    private var probeRunning = false
    private var probeWanted = false

    @Volatile
    private var probeSettleUntilNanos = 0L
    private val probeStopped = CountDownLatch(1)
    private val probeDisposed = AtomicBoolean(false)
    private val screenStates = LinkedHashMap<Int, RecordingScreenState>()
    private val probeScreenshots = ArrayList<RecordingScreenshotEvidence>()
    private var nextStateSeq = 1
    private var stateImageBytes = 0L
    private val probeWorker = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { task -> Thread(task, "test-step-recording-probe").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    init {
        if (videoTimeline != null) mutableSnapshot.value = mutableSnapshot.value.copy(videoTimeline = videoTimeline.summary())
        if (screenProbe != null || screenProbeWithScreenshot != null) requestProbe(delayMs = 0L)
    }

    /** Connects the existing mirror stream's packet tap; observer delivery only enqueues bytes. */
    fun attachVideoSubscription(subscription: Closeable) {
        val reject = synchronized(lock) {
            if (!mutableSnapshot.value.active || videoSubscription != null) {
                true
            } else { videoSubscription = subscription; false }
        }
        if (reject) subscription.close()
    }

    internal fun accept(command: MirrorControlCommand, frame: MirrorFrame? = null, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (!mutableSnapshot.value.active || mutableSnapshot.value.steps.size >= MAX_RECORDED_STEPS) {
            if (mutableSnapshot.value.active) warn("Recording stopped accepting input after $MAX_RECORDED_STEPS steps.")
            return@synchronized
        }
        when (command) {
            is MirrorControlCommand.Touch -> touch(command, frame, nowMs)
            is MirrorControlCommand.Key -> if (command.action == MirrorKeyAction.DOWN && command.repeat == 0) key(command.keycode, frame, nowMs)
            is MirrorControlCommand.Text -> command.value.takeIf(String::isNotBlank)?.let { recordText(it, frame, nowMs) }
            is MirrorControlCommand.Clipboard -> if (command.paste) command.value.takeIf(String::isNotBlank)?.let {
                recordText(it, frame, nowMs, pasted = true)
            }
            is MirrorControlCommand.Back -> if (command.action == MirrorKeyAction.UP) {
                add("Press Back", "Android Back was sent through the device mirror.", frame, RecordedInputKind.BACK, nowMs)
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

    /** Why an AI rewrite cannot start now, in words the reviewer can act on; null when it can. */
    internal fun rewriteBlockedReason(): String? = synchronized(lock) {
        val current = mutableSnapshot.value
        when {
            current.active -> "Stop the recording first."
            applicationReserved -> "This recording is being applied."
            current.pendingSnapshots > 0 -> "Waiting for ${current.pendingSnapshots} screen snapshot(s) to finish."
            (current.rawSteps ?: current.steps).isEmpty() -> "No input was recorded."
            else -> null
        }
    }

    /** The recorded rows an AI rewrite starts from: always the raw ones, also after an earlier rewrite. */
    internal fun rewriteRows(): List<RecordedTestStep> = synchronized(lock) { mutableSnapshot.value.let { it.rawSteps ?: it.steps } }

    /**
     * Swaps the recorded rows for [rewritten], keeping them as [TestStepRecordingSnapshot.rawSteps]. False (nothing changes)
     * when the recording is active or being applied, or when its recorded rows are no longer the ones with ids [rawIds].
     */
    internal fun applyRewrite(rawIds: List<String>, rewritten: List<RecordedTestStep>, notes: String): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        val raw = current.rawSteps ?: current.steps
        if (current.active || applicationReserved || rewritten.isEmpty() || raw.map { it.id } != rawIds) return@synchronized false
        mutableSnapshot.value = current.copy(steps = rewritten.take(MAX_RECORDED_STEPS), rawSteps = raw, rewriteNotes = notes)
        true
    }

    /** Puts the recorded rows back exactly as they were before the rewrite (same ids). False when there is nothing to restore. */
    fun restoreRaw(): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        val raw = current.rawSteps
        if (raw == null || current.active || applicationReserved) return@synchronized false
        mutableSnapshot.value = current.copy(steps = raw, rawSteps = null, rewriteNotes = "")
        true
    }

    fun updateReviewedSteps(
        actionsAndExpected: List<Pair<String, String>>,
        expectedScreenshotIds: Set<String> = emptySet(),
        expectedRowIds: List<String>? = null,
        optionalOverrides: Map<String, Pair<Boolean, String?>> = emptyMap(),
    ): Boolean = synchronized(lock) {
        val old = mutableSnapshot.value.steps
        if (!validReviewUpdate(old, actionsAndExpected, expectedScreenshotIds, expectedRowIds, optionalOverrides)) return@synchronized false
        mutableSnapshot.value = mutableSnapshot.value.copy(steps = old.mapIndexed { index, row ->
            val optional = optionalOverrides[row.id]?.first ?: row.optional
            val condition = if (optionalOverrides.containsKey(row.id)) optionalOverrides[row.id]?.second?.trim()?.takeIf(String::isNotEmpty) else row.condition
            row.copy(
                action = actionsAndExpected[index].first,
                expected = actionsAndExpected[index].second,
                optional = optional,
                condition = condition,
                reviewReason = row.reviewReason.takeIf { actionsAndExpected[index].second.isBlank() },
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
        optionalOverrides: Map<String, Pair<Boolean, String?>>,
    ): Boolean = !mutableSnapshot.value.active && !applicationReserved && actionsAndExpected.size == old.size &&
        expectedScreenshotIds.all { id -> old.any { it.id == id } } &&
        optionalOverrides.keys.all { id -> old.any { it.id == id } } &&
        old.mapIndexed { index, row -> row to actionsAndExpected[index].second }.all { (row, expected) ->
            val (optional, condition) = optionalOverrides[row.id] ?: (row.optional to row.condition)
            recordingRowIssues(row, expected, optional, condition, requireExpected = false).isEmpty()
        } &&
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
                // A probe still waiting for its settle delay runs now (the last screen is worth reading); no new one is requested.
                probeStopped.countDown()
                probeWorker.shutdown()
            }
            val oldVideoSubscription = videoSubscription
            videoSubscription = null
            oldSubscription to oldVideoSubscription
        }
        detached.first?.close()
        detached.second?.close()
        videoTimeline?.stop()
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
                mutableSnapshot.value = mutableSnapshot.value.copy(pendingSnapshots = probePending())
                warn("Some screen snapshots could not finish before recording stopped; the draft remains usable without those images.")
            }
        }
        val probeDrained = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { probeWorker.awaitTermination(probeDrainTimeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        }
        if (!probeDrained) {
            probeWorker.shutdownNow()
            synchronized(lock) {
                probeDisposed.set(true)
                resetProbeFlags()
                refreshPending()
                warn("The last screen context could not be read before recording stopped; those steps keep fewer screen details.")
            }
        }
        val videoDrained = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            videoTimeline?.awaitFinished(VIDEO_DRAIN_TIMEOUT_MS) ?: true
        }
        synchronized(lock) {
            val summary = videoTimeline?.summary()
            mutableSnapshot.value = mutableSnapshot.value.copy(videoTimeline = summary)
            summary?.warning?.let { message ->
                val warnings = mutableSnapshot.value.warnings
                if (message !in warnings) mutableSnapshot.value = mutableSnapshot.value.copy(warnings = (warnings + message).takeLast(MAX_RECORDED_WARNINGS))
            }
            if (!videoDrained) warn("The recorded video timeline could not finish draining before timeout; its available coverage may be shorter.")
        }
        return mutableSnapshot.value
    }

    override fun close() {
        stop()
        videoTimeline?.close()
        disposed.set(true)
        probeDisposed.set(true)
        val discarded = screenshotWorker.shutdownNow().size
        probeWorker.shutdownNow()
        queuedScreenshots.updateAndGet { (it - discarded).coerceAtLeast(0) }
        synchronized(lock) {
            resetProbeFlags()
            mutableSnapshot.value = mutableSnapshot.value.copy(pendingSnapshots = 0)
        }
    }

    fun toTestSteps(assetPathFor: (ByteArray) -> String? = { null }): List<TestStep> = toTestSteps(mutableSnapshot.value.steps, assetPathFor)

    internal fun freezeReviewedSnapshotForApply(): TestStepRecordingSnapshot? = synchronized(lock) {
        val current = mutableSnapshot.value
        if (current.active || current.pendingSnapshots > 0 || applicationReserved) null
        else current.also { applicationReserved = true }
    }

    internal fun releaseApplyReservation() = synchronized(lock) { applicationReserved = false }

    internal fun toTestSteps(rows: List<RecordedTestStep>, assetPathFor: (ByteArray) -> String? = { null }): List<TestStep> = rows.map { row ->
        val rewritten = row.sourceInputIds.isNotEmpty()
        val context = row.screenContext?.takeIf(String::isNotBlank)
        val assetPath = row.screenshotJpeg?.takeIf { row.useScreenshotAsExpected }?.let(assetPathFor)
        val screenshotSource = row.screenshotSource?.name?.lowercase()?.replace('_', ' ')
        val examples = buildList {
            when {
                rewritten -> row.sourceHint?.takeIf(String::isNotBlank)?.let {
                    add(StepExample.ReferenceLog(newExampleId(), caption = REWRITE_HINT_CAPTION, text = it))
                }
                context != null ->
                    add(StepExample.ReferenceLog(newExampleId(), caption = "Recorded input context; see source timing", text = context))
            }
            if (assetPath != null && row.useScreenshotAsExpected) {
                val caption = when {
                    rewritten -> "Reviewed expected screenshot; verified after evidence from ${screenshotSource ?: "screen probe"}"
                    row.screenshotVerifiedMoment != null ->
                        "Reviewed expected screenshot; verified ${row.screenshotVerifiedMoment} evidence from ${screenshotSource ?: "screen probe"}"
                    row.screenshotTimingUncertain -> "Reviewed expected screenshot; uncertain raw input preview from ${screenshotSource ?: "mirror"}"
                    else -> "Reviewed expected screenshot; raw input preview from ${screenshotSource ?: "adb"}"
                }
                add(StepExample.GoldenScreenshot(newExampleId(), caption = caption, assetPath = assetPath))
            }
        }
        TestStep(
            id = "",
            action = row.action,
            expected = row.expected,
            checks = row.checks,
            examples = examples,
            optional = row.optional,
            condition = row.condition,
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
                sampleMove(start, RecordedPoint(event.x, event.y, event.screenWidth, event.screenHeight))
            }
            MirrorTouchAction.UP -> {
                if (suppressingMultiTouch) {
                    suppressedPointers.remove(event.pointerId)
                    if (suppressedPointers.isEmpty()) suppressingMultiTouch = false
                    return
                }
                touches.remove(event.pointerId)?.let { start -> finishGesture(start, event, frame, nowMs) }
            }
            MirrorTouchAction.CANCEL -> {
                if (suppressingMultiTouch) {
                    suppressedPointers.remove(event.pointerId)
                    if (suppressedPointers.isEmpty()) suppressingMultiTouch = false
                } else if (touches.remove(event.pointerId) != null) warn("A cancelled touch gesture was skipped.")
            }
        }
    }

    /** Keeps at most [MAX_GESTURE_SAMPLES] evenly spread MOVE points: when full, every second one goes and the sampling stride doubles. */
    private fun sampleMove(start: TouchStart, point: RecordedPoint) {
        start.moveCount++
        if (start.moveCount % start.stride != 0) return
        start.samples += point
        if (start.samples.size > MAX_GESTURE_SAMPLES) {
            var index = start.samples.size - 1
            while (index >= 1) {
                start.samples.removeAt(index)
                index -= 2
            }
            start.stride *= 2
        }
    }

    private fun finishGesture(start: TouchStart, event: MirrorControlCommand.Touch, frame: MirrorFrame?, nowMs: Long) {
        val context = "Device screen ${event.screenWidth}×${event.screenHeight}; coordinates normalized to screen size."
        val duration = nowMs - start.downAt
        val end = RecordedPoint(event.x, event.y, event.screenWidth, event.screenHeight)
        val endText = point(event.x, event.y, event.screenWidth, event.screenHeight)
        when {
            start.moved -> {
                val replayMs = duration.coerceIn(SWIPE_DURATION_MIN_MS.toLong(), SWIPE_DURATION_MAX_MS.toLong())
                if (duration > SWIPE_DURATION_MAX_MS) {
                    warn("Step ${mutableSnapshot.value.steps.size + 1}: recorded drag took $duration ms; replay uses $SWIPE_DURATION_MAX_MS ms.")
                }
                val from = point(start.x, start.y, start.width, start.height)
                val path = listOf(RecordedPoint(start.x, start.y, start.width, start.height)) + start.samples + end
                add("Swipe from $from to $endText over $replayMs ms", context, frame, RecordedInputKind.SWIPE, nowMs, replayMs, path, start.downAt)
            }
            duration >= LONG_PRESS_MS ->
                add("Long press at $endText", context, frame, RecordedInputKind.LONG_PRESS, nowMs, duration, listOf(end), start.downAt)
            else -> add("Tap at $endText", context, frame, RecordedInputKind.TAP, nowMs, duration, listOf(end), start.downAt)
        }
    }

    private fun key(keycode: Int, frame: MirrorFrame?, nowMs: Long) {
        val label = when (keycode) {
            3 -> "Home"
            KEYCODE_BACK -> "Back"
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
        val kind = if (keycode == KEYCODE_BACK) RecordedInputKind.BACK else RecordedInputKind.KEY
        add("Press $label", "Android key input was sent through the device mirror.", frame, kind, nowMs)
    }

    private fun recordText(text: String, frame: MirrorFrame?, nowMs: Long, pasted: Boolean = false) {
        val bounded = text.take(MAX_RECORDED_TEXT)
        val suffix = if (text.length > MAX_RECORDED_TEXT) " (recorded only the first $MAX_RECORDED_TEXT of ${text.length} characters)" else ""
        if (suffix.isNotEmpty()) warn("Text input was truncated in the draft: ${text.length} characters received, first $MAX_RECORDED_TEXT retained.")
        val prefix = if (pasted) "Paste" else "Enter"
        val context = if (pasted) "Pasted text was sent through the device mirror." else "Text was entered through the device mirror."
        add("$prefix text: $bounded$suffix", context, frame, RecordedInputKind.TEXT, nowMs)
    }

    @Suppress("LongParameterList") // One row's raw input facts, all set together.
    private fun add(
        action: String,
        context: String,
        frame: MirrorFrame?,
        kind: RecordedInputKind,
        nowMs: Long,
        durationMs: Long? = null,
        path: List<RecordedPoint> = emptyList(),
        startMs: Long = nowMs,
    ) {
        val current = mutableSnapshot.value
        if (current.steps.size >= MAX_RECORDED_STEPS) {
            warn("Recording reached the $MAX_RECORDED_STEPS-step limit; stop and review the draft.")
            return
        }
        val index = current.steps.size
        val screenshotContext = when {
            frame != null -> "$context UI hierarchy isn't exposed by the mirror; the raw mirror preview has uncertain acquisition timing."
            screencap != null ->
                "$context UI hierarchy isn't exposed by the mirror; a raw input preview is read through adb after the action, " +
                    "with its acquisition interval recorded."
            else -> "$context Screenshot frame unavailable; UI hierarchy isn't exposed by the mirror."
        }
        val recorded = RecordedTestStep(
            action,
            screenContext = screenshotContext,
            durationMs = durationMs,
            gesturePath = path,
            kind = kind,
            inputAtMs = nowMs,
            inputStartMs = startMs,
        )
        publishRows(current.steps + recorded)
        requestProbe(settleDelayMs)
        when {
            frame != null -> scheduleSnapshot(recorded.id, fromAdb = false) { snapshotEncoder(frame) }
            screencap != null -> scheduleSnapshot(recorded.id, fromAdb = true) { screencap.invoke() }
            else -> warn(NO_FRAME_WARNING)
        }
    }

    /** Queues [produce] on the image worker; null from it means no image (an adb failure or an over-limit frame). */
    private fun scheduleSnapshot(recordedId: String, fromAdb: Boolean, produce: () -> ByteArray?) {
        if (queuedScreenshots.incrementAndGet() > MAX_PENDING_SCREENSHOTS) {
            queuedScreenshots.decrementAndGet()
            warn("Screen snapshot queue is full; this gesture was recorded without an image.")
            return
        }
        refreshPending()
        try {
            screenshotWorker.execute {
                try {
                    val acquiredAt = if (fromAdb) wallClockMs() else null
                    val capturedBytes = if (fromAdb) runCatching(produce).getOrNull() else produce()
                    val acquisitionFinishedAt = if (fromAdb) wallClockMs() else null
                    val bytes = if (fromAdb) capturedBytes?.let(::encodeRecordingScreencap) else capturedBytes
                    synchronized(lock) {
                        if (disposed.get()) return@synchronized
                        val rows = mutableSnapshot.value.steps.toMutableList()
                        if (bytes == null) {
                            warn(if (fromAdb) NO_FRAME_WARNING else "A screen frame exceeded the image size limits and was omitted.")
                        } else {
                            val index = rows.indexOfFirst { it.id == recordedId }
                            if (index >= 0) {
                                rows[index] = rows[index].copy(
                                    screenshotJpeg = bytes,
                                    screenshotFromAdb = fromAdb,
                                    screenshotSource = if (fromAdb) RecordingScreenshotSource.ADB_INPUT else RecordingScreenshotSource.MIRROR_INPUT,
                                    screenshotAcquiredAtMs = acquiredAt,
                                    screenshotAcquisitionFinishedAtMs = acquisitionFinishedAt,
                                    screenshotTimingUncertain = !fromAdb,
                                )
                                publishRows(rows)
                            }
                        }
                    }
                } finally {
                    queuedScreenshots.decrementAndGet()
                    refreshPending()
                }
            }
        } catch (_: RejectedExecutionException) {
            queuedScreenshots.decrementAndGet()
            refreshPending()
            warn("Screen snapshot queue is full; this gesture was recorded without an image.")
        }
    }

    // ── Screen-context probe ─────────────────────────────────────────

    /**
     * Asks for a probe [delayMs] after the latest call. A probe that is still waiting is replaced (its delay restarts); one
     * that is already reading is left alone and followed by exactly one more. Does no adb work itself.
     */
    private fun requestProbe(delayMs: Long) {
        if ((screenProbe == null && screenProbeWithScreenshot == null) || probeDisposed.get() || probeStopped.count == 0L) return
        var launch = false
        synchronized(probeLock) {
            probeSettleUntilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs)
            probeWanted = true
            if (!probeRunning) {
                probeRunning = true
                launch = true
            }
        }
        if (launch) {
            try {
                probeWorker.execute(::probeLoop)
            } catch (_: RejectedExecutionException) {
                resetProbeFlags()
            }
        }
        refreshPending()
    }

    private fun probeLoop() {
        var running = true
        while (running) {
            if (!awaitSettle()) {
                resetProbeFlags()
                break
            }
            synchronized(probeLock) { probeWanted = false }
            val separateScreenshotReader = screenProbeWithScreenshot
            val storesScreenshotSeparately = separateScreenshotReader != null
            val state = if (separateScreenshotReader != null) {
                runCatching { separateScreenshotReader.invoke(::deliverProbeScreenshot) }.getOrNull()
            } else {
                runCatching { screenProbe?.invoke() }.getOrNull()
            }
            deliverProbe(state, storesScreenshotSeparately)
            running = synchronized(probeLock) {
                if (!probeWanted || probeDisposed.get()) probeRunning = false
                probeRunning
            }
        }
        refreshPending()
    }

    /** Waits out the settle delay (a newer input extends it); stopping ends the wait early. False when interrupted or disposed. */
    private fun awaitSettle(): Boolean = try {
        while (probeStopped.count > 0L && !probeDisposed.get()) {
            val remaining = probeSettleUntilNanos - System.nanoTime()
            if (remaining <= 0L) break
            probeStopped.await(remaining, TimeUnit.NANOSECONDS)
        }
        !probeDisposed.get()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun resetProbeFlags() = synchronized(probeLock) {
        probeRunning = false
        probeWanted = false
    }

    private fun probePending(): Int = synchronized(probeLock) { if (probeRunning) 1 else 0 }

    private fun pendingCount(): Int = queuedScreenshots.get() + probePending()

    private fun refreshPending() = synchronized(lock) {
        mutableSnapshot.value = mutableSnapshot.value.copy(pendingSnapshots = pendingCount())
    }

    private fun deliverProbeScreenshot(evidence: RecordingScreenshotEvidence) = synchronized(lock) {
        if (probeDisposed.get()) return@synchronized
        when {
            probeScreenshots.size >= MAX_RECORDED_STEPS + 1 -> warn("Screen context for later inputs was skipped: the probe limit was reached.")
            stateImageBytes + evidence.jpeg.size > MAX_STATE_IMAGE_BYTES ->
                warn("Screen images for later inputs were skipped: the recording image budget is used up.")
            else -> {
                probeScreenshots += evidence
                stateImageBytes += evidence.jpeg.size
                publishRows(mutableSnapshot.value.steps)
            }
        }
    }

    private fun deliverProbe(state: RecordingScreenState?, screenshotStoredSeparately: Boolean = false) = synchronized(lock) {
        if (probeDisposed.get()) return@synchronized
        if (state == null || state.nodes.isEmpty()) warn(PROBE_FAILED_WARNING)
        val image = state?.screenshotJpeg.takeUnless { screenshotStoredSeparately }
        val hasScreenContext = state != null && (
            !state.packageName.isNullOrBlank() || !state.activity.isNullOrBlank() || state.nodes.isNotEmpty()
        )
        when {
            state == null -> Unit
            !hasScreenContext && image == null -> Unit
            screenStates.size >= MAX_RECORDED_STEPS + 1 -> warn("Screen context for later inputs was skipped: the probe limit was reached.")
            else -> {
                val withinBudget = image == null || stateImageBytes + image.size <= MAX_STATE_IMAGE_BYTES
                if (!withinBudget) warn("Screen images for later inputs were skipped: the recording image budget is used up.")
                val stored = state.copy(seq = nextStateSeq++, screenshotJpeg = image.takeIf { withinBudget })
                stateImageBytes += stored.screenshotJpeg?.size ?: 0
                screenStates[stored.seq] = stored
                publishRows(mutableSnapshot.value.steps)
            }
        }
    }

    /** A stored probe state, for the review panel and later tools; null for an unknown [seq]. */
    fun screenState(seq: Int): RecordingScreenState? = synchronized(lock) { screenStates[seq] }

    /** Publishes [rows] with their before/after states, tapped element and password masking recomputed from the stored states. */
    private fun publishRows(rows: List<RecordedTestStep>) {
        val (withContext, masked) = withScreenContext(rows)
        mutableSnapshot.value = mutableSnapshot.value.copy(steps = withContext)
        if (masked.isNotEmpty()) {
            warn("${if (masked.size == 1) "Step" else "Steps"} ${stepRanges(masked)}: text typed into a password field was not recorded.")
        }
    }

    private fun withScreenContext(rows: List<RecordedTestStep>): Pair<List<RecordedTestStep>, List<Int>> {
        val recorded = rows.indices.filter { rows[it].kind != null }
        val inputWindows = recorded.map { InputWindow(rows[it].inputStartMs, rows[it].inputAtMs) }
        val windows = screenStates.values
            .filter { !it.packageName.isNullOrBlank() || !it.activity.isNullOrBlank() || it.nodes.isNotEmpty() }
            .map { ProbeWindow(it.seq, it.startedAt, it.finishedAt) }
        val attached = attachScreenStates(inputWindows, windows)

        val probeEvidence = buildList {
            addAll(probeScreenshots)
            screenStates.values.forEach { state ->
                val bytes = state.screenshotJpeg ?: return@forEach
                add(
                    RecordingScreenshotEvidence(
                        jpeg = bytes,
                        source = RecordingScreenshotSource.SCREEN_PROBE,
                        acquiredAtMs = state.screenshotStartedAt ?: state.startedAt,
                        acquisitionFinishedAtMs = state.screenshotFinishedAt ?: state.finishedAt,
                        timingUncertain = false,
                    ),
                )
            }
        }
        val adbEvidence = buildList {
            rows.forEach { row ->
                if (row.screenshotJpeg != null && row.screenshotSource == RecordingScreenshotSource.ADB_INPUT &&
                    row.screenshotAcquiredAtMs != null && row.screenshotAcquisitionFinishedAtMs != null
                ) {
                    add(
                        RecordingScreenshotEvidence(
                            jpeg = row.screenshotJpeg,
                            source = RecordingScreenshotSource.ADB_INPUT,
                            acquiredAtMs = row.screenshotAcquiredAtMs,
                            acquisitionFinishedAtMs = row.screenshotAcquisitionFinishedAtMs,
                            timingUncertain = false,
                        ),
                    )
                }
            }
        }

        fun screenshotAttachments(evidence: List<RecordingScreenshotEvidence>): List<RowAttachment> =
            attachEvidenceWindows(
                inputWindows,
                evidence.mapIndexedNotNull { index, item ->
                    val start = item.acquiredAtMs ?: return@mapIndexedNotNull null
                    val finish = item.acquisitionFinishedAtMs ?: return@mapIndexedNotNull null
                    EvidenceWindow(index, start, finish)
                },
            )
        val probeScreens = screenshotAttachments(probeEvidence)
        val adbScreens = screenshotAttachments(adbEvidence)
        val result = rows.toMutableList()
        val passwordEvidence = BooleanArray(rows.size)
        recorded.forEachIndexed { slot, index ->
            val before = attached[slot].beforeSeq?.let(screenStates::get)
            val after = attached[slot].afterSeq?.let(screenStates::get)
            val beforeScreenshot = probeScreens[slot].beforeSeq?.let(probeEvidence::getOrNull)
                ?: adbScreens[slot].beforeSeq?.let(adbEvidence::getOrNull)
            val afterScreenshot = probeScreens[slot].afterSeq?.let(probeEvidence::getOrNull)
                ?: adbScreens[slot].afterSeq?.let(adbEvidence::getOrNull)
            result[index] = rows[index].copy(
                before = before?.ref(),
                after = after?.ref(),
                tappedElement = tappedElementFor(rows[index], before),
                beforeScreenshot = beforeScreenshot,
                afterScreenshot = afterScreenshot,
            )
            passwordEvidence[index] = before?.passwordFieldLikelyEdited() == true || after?.passwordFieldLikelyEdited() == true
        }
        return result to maskPasswordRuns(result, passwordEvidence)
    }

    /**
     * Hides the text of every typing run (consecutive typed/pasted rows, with Delete presses between) in which any row shows a
     * password field being edited: all of a run goes to one field, but the screen is only read now and then. Returns the
     * step numbers newly hidden.
     */
    private fun maskPasswordRuns(rows: MutableList<RecordedTestStep>, evidence: BooleanArray): List<Int> {
        val newlyMasked = mutableListOf<Int>()
        var index = 0
        while (index < rows.size) {
            if (!isTypingRow(rows[index])) {
                index++
                continue
            }
            val end = (index until rows.size).firstOrNull { !isTypingRow(rows[it]) } ?: rows.size
            if ((index until end).any { evidence[it] }) {
                for (row in index until end) {
                    val masked = maskedTextAction(rows[row]) ?: continue
                    rows[row] = rows[row].copy(action = masked)
                    newlyMasked += row + 1
                }
            }
            index = end
        }
        return newlyMasked
    }

    private fun isTypingRow(row: RecordedTestStep) = row.kind == RecordedInputKind.TEXT || (row.kind == RecordedInputKind.KEY && row.action == DELETE_ACTION)

    private fun tappedElementFor(row: RecordedTestStep, before: RecordingScreenState?): TappedElement? {
        val tapped = row.kind == RecordedInputKind.TAP || row.kind == RecordedInputKind.LONG_PRESS
        val point = row.gesturePath.lastOrNull()
        return if (tapped && before != null && point != null) resolveTappedElement(before, point) else null
    }

    /** The hidden action for an unedited typed/pasted row that is not hidden yet; null for anything else. */
    private fun maskedTextAction(row: RecordedTestStep): String? {
        if (row.kind != RecordedInputKind.TEXT) return null
        val prefix = listOf(ENTER_TEXT_PREFIX, PASTE_TEXT_PREFIX).firstOrNull { row.action.startsWith(it) } ?: return null
        return (prefix + MASKED_TEXT).takeIf { it != row.action }
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
        const val NO_FRAME_WARNING = "No screen frame was available for a recorded input; its step keeps device/screen context only."
        const val SNAPSHOT_DRAIN_TIMEOUT_MS = 2_000L
        const val PROBE_SETTLE_MS = 800L
        const val PROBE_DRAIN_TIMEOUT_MS = 8_000L
        const val MAX_STATE_IMAGE_BYTES = 48L * 1024 * 1024
        const val MAX_GESTURE_SAMPLES = 8
        const val ENTER_TEXT_PREFIX = "Enter text: "
        const val PASTE_TEXT_PREFIX = "Paste text: "
        const val MASKED_TEXT = "••••"
        const val DELETE_ACTION = "Press Delete"
        const val REWRITE_HINT_CAPTION = "Recorded input (hint; prefer what is on screen)"
        const val PROBE_FAILED_WARNING = "The screen context (UI hierarchy) could not be read through adb; steps keep coordinates and images only."
        const val KEYCODE_BACK = 4
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

/** Ascending step numbers as ranges: 3, 4, 5, 7 becomes "3-5, 7". */
private fun stepRanges(numbers: List<Int>): String {
    val parts = mutableListOf<String>()
    var start = 0
    while (start < numbers.size) {
        var end = start
        while (end + 1 < numbers.size && numbers[end + 1] == numbers[end] + 1) end++
        parts += if (end == start) "${numbers[start]}" else "${numbers[start]}-${numbers[end]}"
        start = end + 1
    }
    return parts.joinToString(", ")
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

/** An adb screencap PNG as the bounded JPEG a recorded step keeps; null when it cannot be decoded or stays over the limits. */
internal fun encodeRecordingScreencap(png: ByteArray): ByteArray? = runCatching {
    encodeBoundedDeviceScreen(png, MAX_RECORDING_SNAPSHOT_DIMENSION, MAX_RECORDING_SNAPSHOT_BYTES).bytes
}.getOrNull()
