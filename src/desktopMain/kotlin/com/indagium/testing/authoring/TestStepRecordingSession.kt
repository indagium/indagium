package com.indagium.testing.authoring

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorFrame
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.debug.SWIPE_DURATION_MAX_MS
import com.indagium.debug.SWIPE_DURATION_MIN_MS
import com.indagium.debug.encodeBoundedDeviceScreen
import com.indagium.model.AiUsageStats
import com.indagium.model.sumAiUsage
import com.indagium.testing.device.DEVICE_STATE_BATTERY_STATUS
import com.indagium.testing.device.DEVICE_STATE_POWER_SOURCE
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.NormalizedGestureBounds
import com.indagium.testing.model.NormalizedGesturePoint
import com.indagium.testing.model.RecordedGestureHint
import com.indagium.testing.model.RecordedGestureTarget
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepIntent
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.moveById
import com.indagium.testing.model.newExampleId
import com.indagium.testing.model.withKind
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
    /** Exact node under a touch, including an unlabeled icon. */
    val targetUnderTouch: RecordedUiTarget? = null,
    /** Enclosing clickable nodes, nearest first; duplicate system tile IDs are distinguished by their bounds. */
    val clickableTargets: List<RecordedUiTarget> = emptyList(),
    val targetScreenWidth: Int? = null,
    val targetScreenHeight: Int? = null,
    val targetOrientationQuarterTurns: Int? = null,
    val touchWithinTarget: NormalizedGesturePoint? = null,
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
    /**
     * True on a row the reviewer typed in during review (no recorded input behind it, [kind] and [sourceInputIds] empty).
     * An AI rewrite passes such a row through verbatim and in place; see [TestStepRecordingSession].
     */
    val authorAdded: Boolean = false,
    /**
     * Who performs the step it becomes: an AI agent (every recorded input), or, on an author-added row, a tester or a script. A script
     * row needs [script]; a manual row may carry one as its automation alternative, and [checks] that detect it done.
     */
    val stepKind: StepKind = StepKind.AGENT,
    val script: ScriptCall? = null,
    val manualCompletion: ManualCompletion = ManualCompletion.TESTER_OR_CHECKS,
    /**
     * What changed on the device while recording (see [RecordingEnvironmentWatcher]). On a recorded input: the changes that followed it
     * within a few seconds, evidence of what it did ("Wi-Fi turned off"). On a [RecordedInputKind.EXTERNAL] row: the event it stands for.
     */
    val stateChanges: List<EnvironmentEvent> = emptyList(),
    /** The model's proposed playback semantics, kept distinct from the reviewer's explicit review. */
    val intent: StepIntent = StepIntent.PERFORM_ACTION,
    val precondition: String? = null,
    /** True only after a person edits/reviews this row; model support alone does not lock it in a partial rewrite. */
    val humanReviewed: Boolean = false,
    /** True when the rewrite had adequate evidence for the row; kept separate from explicit human review. */
    val modelSupported: Boolean = false,
    /** Explicitly reviewed after image selected as an expected screenshot; raw preview images remain separate. */
    val expectedScreenshotJpeg: ByteArray? = null,
    val expectedScreenshotSource: String? = null,
    val expectedScreenshotTimestampMs: Long? = null,
    val expectedScreenshotAlignment: String? = null,
)

/**
 * [pendingSnapshots] counts queued screen images plus the screen-context probe while one is waiting or running. After an AI
 * rewrite [steps] are the rewritten rows and [rawSteps] the recorded ones they replaced (null before a rewrite); [rewriteNotes]
 * is what the AI said about its choices. [rawSteps] is history, not the review order: reordering never rewrites it, the session
 * derives the reviewed raw order from [steps] (see [TestStepRecordingSession]).
 */
data class TestStepRecordingSnapshot(
    val active: Boolean,
    val steps: List<RecordedTestStep> = emptyList(),
    val warnings: List<String> = emptyList(),
    val pendingSnapshots: Int = 0,
    val rawSteps: List<RecordedTestStep>? = null,
    val rewriteNotes: String = "",
    val videoTimeline: RecordedVideoTimelineSummary? = null,
    /** Source input ids deliberately removed from review. Original rows and evidence remain in [rawSteps]. */
    val excludedSourceInputIds: Set<String> = emptySet(),
    /** True only while [steps] contains AI-proposed rows; rawSteps can also hold history after a user removal. */
    val rewriteApplied: Boolean = false,
    /** Latest rewrite attempt and session total; null before any rewrite has started. */
    val rewriteUsage: AiUsageStats? = null,
    val rewriteUsageTotal: AiUsageStats? = null,
    val rewriteUsagePending: AiUsageStats? = null,
    val rewriteAttemptCount: Int = 0,
    val rewriteInProgress: Boolean = false,
    /** Set on a recording reopened from stored evidence (no device, no live input); null on a recording made just now. */
    val reopened: ReopenedRecordingInfo? = null,
)

/** What the review panel and the MCP tools say about the stored recording a reopened session came from. */
data class ReopenedRecordingInfo(
    val recordingId: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** A password was typed while recording: the video was not stored, and screenshots from the sensitive windows were left out. */
    val passwordSensitive: Boolean,
    val videoStored: Boolean,
    /** How many library steps applying this recording replaces (the steps it produced). */
    val producedStepCount: Int,
    /** Why the video or screenshots are missing, and anything else worth telling the person, in words. */
    val notes: List<String> = emptyList(),
)

/** The rows an AI rewrite starts from and the rewritten [accepted] rows of an earlier rewrite that may be kept as they are. */
internal class RewriteSource(val rows: List<RecordedTestStep>, val accepted: List<RecordedTestStep>)

/** Where an accepted input came from: a command the Indagium mirror sent, or an event the device itself produced (adb getevent). */
internal enum class InputSource { MIRROR, DEVICE }

private const val MAX_RECORDING_SNAPSHOT_PIXELS = 4_000_000L
private const val MAX_RECORDING_SNAPSHOT_BYTES = 2 * 1024 * 1024
private const val MAX_RECORDING_SNAPSHOT_DIMENSION = 1_280
private const val VIDEO_DRAIN_TIMEOUT_MS = 12_000L
private const val REVIEW_HASH_MULTIPLIER = 31
private const val MAX_GESTURE_TARGET_TEXT_CHARS = 120
private const val MAX_GESTURE_TARGET_RESOURCE_ID_CHARS = 200
private const val MAX_GESTURE_TARGET_CLASS_CHARS = 80

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
 *
 * Review order. Once stopped, the reviewer can [moveReviewedRow], [insertReviewedRow] (an author-added row, no recorded input
 * behind it) and [removeReviewedRows]. A moved row keeps all its evidence (it is the same object); only its place changes. The
 * order of [TestStepRecordingSnapshot.steps] is the one source of truth, and [reviewedRaw] derives everything else from it:
 *  - the raw review list (recorded rows, plus author rows) in reviewed order: each visible row in turn, a rewritten row standing
 *    for its [RecordedTestStep.sourceInputIds] in recorded order, and removed rows anchored after the row that preceded them;
 *  - an AI rewrite numbers exactly that list (minus removed rows), so the numbers follow the review order, and an author row
 *    reaches the AI as a fixed "keep as is" input that the answer must return unchanged and in place (RecordingRewrite.kt);
 *  - Undo rewrite and Restore removed inputs put raw rows back in that same order, author rows included. Removing an author
 *    row deletes it (it has no recorded input to restore), so it never counts as an excluded input.
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

    /** The last mirror or device input that can be a duplicate of the other source's, for [isDuplicateOfOtherSource]. */
    private data class LastInput(val command: MirrorControlCommand, val source: InputSource, val atMs: Long)

    private val mutableSnapshot = MutableStateFlow(TestStepRecordingSnapshot(active = true))
    val snapshot: StateFlow<TestStepRecordingSnapshot> = mutableSnapshot
    private val touches = mutableMapOf<Long, TouchStart>()
    private val suppressedPointers = mutableSetOf<Long>()
    private var suppressingMultiTouch = false
    private val lock = Any()
    private var subscription: Closeable? = subscription
    private var videoSubscription: Closeable? = null
    private var deviceInput: DeviceInputHandle? = null
    private var environment: Closeable? = null
    private var environmentEventCount = 0
    private var tailPending = false
    private val tailDone = CountDownLatch(1)
    private var inputSource = InputSource.MIRROR

    /** Set once by [loadStopped] when this session was reopened from stored evidence; null for a recording made just now. */
    @Volatile
    internal var reopened: ReopenedRecording? = null
        private set

    /** How long [stopAndDrain] waits for the device input recorder's last rows; tests shorten it. */
    internal var tailDrainTimeoutMs: Long = TAIL_DRAIN_TIMEOUT_MS
    private var lastInput: LastInput? = null
    private var applicationReserved = false
    private var completedRewriteUsage: AiUsageStats? = null
    private var pendingRewriteUsage: AiUsageStats? = null
    private var rewriteUsageAppliedThroughAttempt: Int = 0
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

    internal fun accept(
        command: MirrorControlCommand,
        frame: MirrorFrame? = null,
        nowMs: Long = System.currentTimeMillis(),
        source: InputSource = InputSource.MIRROR,
    ) = synchronized(lock) {
        if (mutableSnapshot.value.active) acceptLocked(command, frame, nowMs, source)
    }

    private fun acceptLocked(command: MirrorControlCommand, frame: MirrorFrame?, nowMs: Long, source: InputSource) {
        val current = mutableSnapshot.value
        if (current.steps.size >= MAX_RECORDED_STEPS) {
            if (current.active) warn("Recording stopped accepting input after $MAX_RECORDED_STEPS steps.")
            return
        }
        if (isDuplicateOfOtherSource(command, nowMs, source)) return
        inputSource = source
        when (command) {
            is MirrorControlCommand.Touch -> touch(command, frame, nowMs)
            is MirrorControlCommand.Key -> if (command.action == MirrorKeyAction.DOWN && command.repeat == 0) key(command.keycode, frame, nowMs)
            is MirrorControlCommand.Text -> command.value.takeIf(String::isNotBlank)?.let { recordText(it, frame, nowMs) }
            is MirrorControlCommand.Clipboard -> if (command.paste) command.value.takeIf(String::isNotBlank)?.let {
                recordText(it, frame, nowMs, pasted = true)
            }
            is MirrorControlCommand.Back -> if (command.action == MirrorKeyAction.UP) {
                add("Press Back", "Android Back was ${channel()}.", frame, RecordedInputKind.BACK, nowMs)
            }
            MirrorControlCommand.ExpandNotifications, MirrorControlCommand.ExpandSettings, MirrorControlCommand.CollapsePanels,
            MirrorControlCommand.CollapseNotifications, MirrorControlCommand.RotateDevice, MirrorControlCommand.SetDisplayPowerOn ->
                acceptSystemControl(command, frame, nowMs)
            is MirrorControlCommand.GetClipboard -> Unit
        }
    }

    /** The mirror's panel, rotation and screen-on commands, as rows. */
    private fun acceptSystemControl(command: MirrorControlCommand, frame: MirrorFrame?, nowMs: Long) {
        when (command) {
            MirrorControlCommand.ExpandNotifications -> addSystem("Open the notification shade", RecordedInputKind.NOTIFICATIONS, frame, nowMs)
            MirrorControlCommand.ExpandSettings -> addSystem("Open quick settings", RecordedInputKind.QUICK_SETTINGS, frame, nowMs)
            MirrorControlCommand.CollapsePanels, MirrorControlCommand.CollapseNotifications ->
                addSystem("Close the notification panels", RecordedInputKind.CLOSE_PANELS, frame, nowMs)
            MirrorControlCommand.RotateDevice -> addSystem("Rotate the device", RecordedInputKind.ROTATE, frame, nowMs)
            MirrorControlCommand.SetDisplayPowerOn -> addSystem("Turn the screen on", RecordedInputKind.SCREEN_ON, frame, nowMs)
            else -> Unit
        }
    }

    /**
     * True when [command] repeats, within [DUPLICATE_WINDOW_MS], the same input the other source just delivered (a safety net: the
     * mirror's injected input does not show up in getevent, but nothing guarantees every setup behaves so). Moves are not compared.
     */
    private fun isDuplicateOfOtherSource(command: MirrorControlCommand, nowMs: Long, source: InputSource): Boolean {
        if (command is MirrorControlCommand.Touch && command.action == MirrorTouchAction.MOVE) return false
        val previous = lastInput
        val duplicate = previous != null && previous.source != source && nowMs - previous.atMs in 0..DUPLICATE_WINDOW_MS &&
            sameInput(previous.command, command)
        if (!duplicate) lastInput = LastInput(command, source, nowMs)
        return duplicate
    }

    private fun sameInput(a: MirrorControlCommand, b: MirrorControlCommand): Boolean = when {
        a is MirrorControlCommand.Touch && b is MirrorControlCommand.Touch ->
            a.action == b.action && a.screenWidth == b.screenWidth && a.screenHeight == b.screenHeight &&
                abs(a.x - b.x) <= DUPLICATE_TOUCH_TOLERANCE_PX && abs(a.y - b.y) <= DUPLICATE_TOUCH_TOLERANCE_PX
        else -> a == b
    }

    /** The words for how the current input reached the recorder, used in a row's context. */
    private fun channel(): String =
        if (inputSource == InputSource.DEVICE) "observed on the device (adb getevent)" else "sent through the device mirror"

    /**
     * Connects the device input recorder. [stop] asks it to [DeviceInputHandle.finish] (the session then counts as pending until it
     * reports back through [deviceInputSink]); [close] closes it at once.
     */
    internal fun attachDeviceInput(handle: DeviceInputHandle) {
        val reject = synchronized(lock) {
            if (!mutableSnapshot.value.active || deviceInput != null) {
                true
            } else { deviceInput = handle; false }
        }
        if (reject) handle.close()
    }

    /** Adds a warning to the recording; for the setup code that learns something the session cannot (a mirror that did not start). */
    internal fun addWarning(message: String) = synchronized(lock) { warn(message) }

    /** Connects the environment watcher. [stop] and [close] close it; it only ever reports while the recording is active. */
    internal fun attachEnvironmentWatcher(watcher: Closeable) {
        val reject = synchronized(lock) {
            if (!mutableSnapshot.value.active || environment != null) {
                true
            } else { environment = watcher; false }
        }
        if (reject) watcher.close()
    }

    /** What the environment watcher delivers to: changes of the device's surroundings become evidence on an input or a row of their own. */
    internal val environmentSink: EnvironmentSink = object : EnvironmentSink {
        override fun environmentEvents(events: List<EnvironmentEvent>) = synchronized(lock) {
            if (mutableSnapshot.value.active) environmentEventsLocked(events)
        }

        override fun environmentWarning(message: String) = synchronized(lock) {
            if (mutableSnapshot.value.active) warn(message)
        }
    }

    /**
     * A change that follows a recorded input by at most [ATTRIBUTION_WINDOW_MS] is that input's evidence ([RecordedTestStep.stateChanges]);
     * the change happened somewhere between two polls, so an input counts when the change could have been within the window after it.
     * Any other change is an external row. A battery status that changes together with the power source is not told twice.
     */
    private fun environmentEventsLocked(events: List<EnvironmentEvent>) {
        var rows = mutableSnapshot.value.steps
        val keys = events.mapTo(HashSet()) { it.key }
        for (event in events) {
            if (event.key == DEVICE_STATE_BATTERY_STATUS && DEVICE_STATE_POWER_SOURCE in keys) continue
            if (++environmentEventCount > MAX_ENVIRONMENT_EVENTS) {
                warn("More than $MAX_ENVIRONMENT_EVENTS changes of the device's surroundings were seen; later ones are not recorded.")
                break
            }
            val target = rows.indices.filter { followsInput(rows[it], event) }.maxByOrNull { rows[it].inputAtMs }
            rows = when {
                target != null -> rows.toMutableList().also { it[target] = it[target].withStateChange(event) }
                event.key in EVIDENCE_ONLY_STATE_KEYS -> rows
                rows.size >= MAX_RECORDED_STEPS -> rows.also { warn("Recording reached the $MAX_RECORDED_STEPS-step limit; stop and review the draft.") }
                else -> rows + externalEventRow(event)
            }
        }
        publishRows(rows)
    }

    private fun followsInput(row: RecordedTestStep, event: EnvironmentEvent): Boolean =
        row.kind != null && row.kind != RecordedInputKind.EXTERNAL && !row.authorAdded && row.inputAtMs <= event.atMs &&
            row.inputAtMs + ATTRIBUTION_WINDOW_MS >= event.sinceMs

    private fun RecordedTestStep.withStateChange(event: EnvironmentEvent) =
        copy(stateChanges = (stateChanges + event).takeLast(MAX_STATE_CHANGES_PER_ROW))

    /** What the device input recorder delivers to: rows are added in order, also in the short tail after [stop]. */
    internal val deviceInputSink: DeviceInputSink = object : DeviceInputSink {
        override fun deviceInput(command: MirrorControlCommand, atMs: Long) = synchronized(lock) {
            if (mutableSnapshot.value.active || tailPending) acceptLocked(command, null, atMs, InputSource.DEVICE)
        }

        override fun keyboardText(reading: KeyboardTextReading, startMs: Long, endMs: Long) = synchronized(lock) {
            if (mutableSnapshot.value.active || tailPending) keyboardTextLocked(reading, startMs, endMs)
        }

        override fun deviceInputWarning(message: String) = synchronized(lock) { warn(message) }

        override fun deviceInputFinished() = synchronized(lock) {
            tailPending = false
            tailDone.countDown()
            refreshPending()
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
        val current = mutableSnapshot.value
        if (applicationReserved || current.active || current.rewriteInProgress || steps.map { it.id } != current.steps.map { it.id }) return@synchronized false
        val oldById = current.steps.associateBy { it.id }
        val reviewed = steps.take(MAX_RECORDED_STEPS).map { proposed ->
            val old = oldById.getValue(proposed.id)
            val changed = reviewedContent(old) != reviewedContent(proposed)
            if (!changed) old else proposed.copy(
                humanReviewed = true,
                reviewReason = if (old.action != proposed.action || old.expected != proposed.expected || old.optional != proposed.optional) {
                    reviewReasonAfterEdit(old.reviewReason, proposed.action, proposed.expected, proposed.optional)
                } else {
                    old.reviewReason
                },
            )
        }
        mutableSnapshot.value = current.copy(
            steps = reviewed,
            rawSteps = current.rawSteps?.let { syncRawReviewRows(it, current.steps, reviewed) },
        )
        true
    }

    private fun reviewedContent(row: RecordedTestStep): List<Any?> = listOf(
        row.action, row.expected, row.optional, row.condition, row.useScreenshotAsExpected, row.checks,
        row.stepKind, row.script, row.manualCompletion, row.intent, row.precondition,
    )

    /**
     * Removes reviewed rows without discarding their recorded source inputs or evidence. An author-added row has no recorded input,
     * so removing it deletes it outright (it never becomes an excluded input).
     */
    fun removeReviewedRows(rowIds: Set<String>): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        if (!reviewMutationAvailable(current) || rowIds.isEmpty()) return@synchronized false
        val visibleIds = current.steps.mapTo(HashSet()) { it.id }
        if (!visibleIds.containsAll(rowIds)) return@synchronized false
        val removed = current.steps.filter { it.id in rowIds }
        val authorIds = removed.filter { it.authorAdded }.mapTo(HashSet()) { it.id }
        val sourceIds = removed.filterNot { it.authorAdded }.flatMap { row -> row.sourceInputIds.ifEmpty { listOf(row.id) } }.toSet()
        val raw = (current.rawSteps ?: current.steps).filterNot { it.id in authorIds }
        mutableSnapshot.value = current.copy(
            steps = current.steps.filterNot { it.id in rowIds },
            rawSteps = raw,
            excludedSourceInputIds = current.excludedSourceInputIds + sourceIds,
        )
        true
    }

    /**
     * Moves the reviewed row [rowId] to [toIndex] (clamped to the list, like every other `move*`). False when the row is unknown or
     * review changes are not allowed now; moving a row onto its own place succeeds and changes nothing.
     */
    fun moveReviewedRow(rowId: String, toIndex: Int): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        if (!reviewMutationAvailable(current) || current.steps.none { it.id == rowId }) return@synchronized false
        mutableSnapshot.value = current.copy(steps = current.steps.moveById(rowId, toIndex) { it.id })
        true
    }

    /**
     * Inserts an author-added copy of [row] (its action, expected text, optional flag, condition, id, and the kind, script, completion
     * mode and checks of a manual or script step; nothing else) before the
     * row now at [index] (0..size; size appends). False when review changes are not allowed, the list is full, [index] is out of
     * range, the id is already used, or the optional/condition pair is invalid. The action may be blank: the reviewer fills it in
     * before applying, and an AI rewrite is refused until every author row has one.
     */
    fun insertReviewedRow(index: Int, row: RecordedTestStep): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        if (!reviewMutationAvailable(current) || current.steps.size >= MAX_RECORDED_STEPS || index !in 0..current.steps.size) {
            return@synchronized false
        }
        val used = current.steps.mapTo(HashSet()) { it.id } + current.rawSteps.orEmpty().map { it.id }
        val inserted = RecordedTestStep(
            action = row.action,
            expected = row.expected,
            optional = row.optional,
            condition = row.condition,
            id = row.id,
            authorAdded = true,
            checks = row.checks,
            stepKind = row.stepKind,
            script = row.script,
            manualCompletion = row.manualCompletion,
        )
        if (inserted.id in used || RecordingRowIssue.CONDITION in recordingRowIssues(inserted, requireExpected = false)) return@synchronized false
        mutableSnapshot.value = current.copy(steps = current.steps.toMutableList().apply { add(index, inserted) })
        true
    }

    /** Why an AI rewrite cannot start now, in words the reviewer can act on; null when it can. */
    internal fun rewriteBlockedReason(): String? = synchronized(lock) {
        val current = mutableSnapshot.value
        val rows = rewriteRowsOf(current)
        when {
            current.active -> "Stop the recording first."
            applicationReserved -> "This recording is being applied."
            current.pendingSnapshots > 0 -> "Waiting for ${current.pendingSnapshots} screen snapshot(s) to finish."
            rows.none { !it.authorAdded } ->
                "No recorded inputs remain. Restore removed inputs or keep at least one step before rewriting."
            rows.any { it.authorAdded && it.action.isBlank() } ->
                "An added step has no action. Fill it in or remove it before rewriting."
            rows.any { it.authorAdded && max(it.action.length, it.expected.length) > MAX_FIXED_STEP_CHARS } ->
                "An added step is longer than $MAX_FIXED_STEP_CHARS characters. Shorten it before rewriting."
            else -> null
        }
    }

    /**
     * The rows an AI rewrite starts from, in review order: raw inputs and author-added rows minus explicit user exclusions. An
     * author-added row is a fixed input the rewrite must keep as is.
     */
    internal fun rewriteRows(): List<RecordedTestStep> = synchronized(lock) { rewriteRowsOf(mutableSnapshot.value) }

    /**
     * What an AI rewrite starts from, read in one step: the [RewriteSource.rows] (see [rewriteRows]) and, after an earlier rewrite, the
     * rewritten rows the person can be taken to have accepted ([RecordedTestStep.isAcceptedRewriteStep]): the next rewrite may keep
     * those exactly as they are.
     */
    internal fun rewriteSource(): RewriteSource = synchronized(lock) {
        val current = mutableSnapshot.value
        RewriteSource(rewriteRowsOf(current), if (current.rewriteApplied) current.steps.filter { it.isAcceptedRewriteStep() } else emptyList())
    }

    private fun rewriteRowsOf(current: TestStepRecordingSnapshot): List<RecordedTestStep> =
        reviewedRaw(current).filterNot { it.id in current.excludedSourceInputIds }

    /**
     * The raw review list in reviewed order (see the class comment): recorded rows and author rows, removed rows included.
     * Without history ([TestStepRecordingSnapshot.rawSteps] null) the visible rows are the list.
     */
    private fun reviewedRaw(current: TestStepRecordingSnapshot): List<RecordedTestStep> {
        val raw = current.rawSteps ?: return current.steps
        val rawById = raw.associateBy { it.id }
        val result = ArrayList<RecordedTestStep>(raw.size)
        val placed = HashSet<String>()

        fun place(row: RecordedTestStep) {
            if (placed.add(row.id)) result += row
        }
        current.steps.forEach { step ->
            // A recorded or author row stands for itself (its latest edits win); a rewritten row for the raw rows it covers.
            if (step.sourceInputIds.isEmpty()) place(step) else step.sourceInputIds.forEach { id -> rawById[id]?.let(::place) }
        }
        raw.forEachIndexed { index, row ->
            if (row.id in placed) return@forEachIndexed
            val anchor = (index - 1 downTo 0).map { raw[it].id }.firstOrNull { it in placed }
            result.add(anchor?.let { id -> result.indexOfFirst { it.id == id } + 1 } ?: 0, row)
            placed += row.id
        }
        return result
    }

    internal fun beginRewriteUsageAttempt(): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        if (current.active || current.pendingSnapshots > 0 || applicationReserved || current.rewriteInProgress) return@synchronized false
        completedRewriteUsage = sumAiUsage(listOf(completedRewriteUsage, current.rewriteUsage))
        if (current.rewriteAttemptCount > rewriteUsageAppliedThroughAttempt) {
            pendingRewriteUsage = sumAiUsage(listOf(pendingRewriteUsage, current.rewriteUsage))
        }
        mutableSnapshot.value = current.copy(
            rewriteUsage = AiUsageStats(partial = true),
            rewriteUsageTotal = completedRewriteUsage,
            rewriteUsagePending = pendingRewriteUsage,
            rewriteAttemptCount = current.rewriteAttemptCount + 1,
            rewriteInProgress = true,
        )
        true
    }

    internal fun updateRewriteUsage(usage: AiUsageStats) = synchronized(lock) {
        val current = mutableSnapshot.value
        mutableSnapshot.value = current.copy(
            rewriteUsage = usage,
            rewriteUsageTotal = sumAiUsage(listOf(completedRewriteUsage, usage)),
            rewriteUsagePending = if (current.rewriteAttemptCount > rewriteUsageAppliedThroughAttempt) {
                sumAiUsage(listOf(pendingRewriteUsage, usage))
            } else {
                pendingRewriteUsage
            },
        )
    }

    internal fun finishRewriteUsageAttempt(completed: Boolean) = synchronized(lock) {
        val current = mutableSnapshot.value
        val latest = (current.rewriteUsage ?: AiUsageStats(partial = true)).let { usage ->
            if (completed) usage else usage.copy(partial = true)
        }
        mutableSnapshot.value = current.copy(
            rewriteUsage = latest,
            rewriteUsageTotal = sumAiUsage(listOf(completedRewriteUsage, latest)),
            rewriteUsagePending = if (current.rewriteAttemptCount > rewriteUsageAppliedThroughAttempt) {
                sumAiUsage(listOf(pendingRewriteUsage, latest))
            } else {
                pendingRewriteUsage
            },
            rewriteInProgress = false,
        )
    }

    internal fun markRewriteUsageApplied(attemptCount: Int) = synchronized(lock) {
        rewriteUsageAppliedThroughAttempt = maxOf(rewriteUsageAppliedThroughAttempt, attemptCount)
        pendingRewriteUsage = null
        mutableSnapshot.value = mutableSnapshot.value.copy(rewriteUsagePending = null)
    }

    /**
     * Swaps the recorded rows for [rewritten], keeping them as [TestStepRecordingSnapshot.rawSteps]. False (nothing changes)
     * when the recording is active or being applied, or when its recorded rows are no longer the ones with ids [rawIds].
     */
    internal fun applyRewrite(rawIds: List<String>, rewritten: List<RecordedTestStep>, notes: String): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        val raw = reviewedRaw(current)
        val included = raw.filterNot { it.id in current.excludedSourceInputIds }
        if (current.active || applicationReserved || rewritten.isEmpty() || included.map { it.id } != rawIds) return@synchronized false
        mutableSnapshot.value = current.copy(steps = rewritten.take(MAX_RECORDED_STEPS), rawSteps = raw, rewriteNotes = notes, rewriteApplied = true)
        true
    }

    /**
     * Puts the non-excluded recorded rows back (same ids), in their reviewed order, with any author-added rows where they stood.
     * Exclusions remain explicit and recoverable separately.
     */
    fun restoreRaw(): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        if (current.rawSteps == null || !current.rewriteApplied) return@synchronized false
        if (current.active || current.rewriteInProgress || applicationReserved) return@synchronized false
        mutableSnapshot.value = current.copy(
            steps = rewriteRowsOf(current),
            rewriteNotes = "",
            rewriteApplied = false,
        )
        true
    }

    /** Restores the full raw review (reviewed order) and clears exclusions; this is the recovery path for removed source inputs. */
    fun restoreExcludedInputs(): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        if (!reviewMutationAvailable(current) || current.excludedSourceInputIds.isEmpty()) return@synchronized false
        mutableSnapshot.value = current.copy(
            steps = reviewedRaw(current),
            rawSteps = current.rawSteps,
            excludedSourceInputIds = emptySet(),
            rewriteNotes = "",
            rewriteApplied = false,
        )
        true
    }

    private fun reviewMutationAvailable(current: TestStepRecordingSnapshot): Boolean =
        !current.active && current.pendingSnapshots == 0 && !applicationReserved && !current.rewriteInProgress

    /**
     * Edits the reviewed rows. [actionsAndExpected] follow the current row order, or - when [allowReorder] and [expectedRowIds] is
     * given - the order of [expectedRowIds], which must then be a permutation of the current row ids and becomes the new review
     * order. Without [allowReorder], [expectedRowIds] must equal the current order (the apply path never reorders).
     */
    internal fun updateReviewedSteps(
        actionsAndExpected: List<Pair<String, String>>,
        expectedScreenshotIds: Set<String> = emptySet(),
        expectedRowIds: List<String>? = null,
        optionalOverrides: Map<String, Pair<Boolean, String?>> = emptyMap(),
        allowReorder: Boolean = false,
        performerEdits: Map<String, RecordedPerformerEdit> = emptyMap(),
        expectedScreenshotSelections: Map<String, RecordingExpectedScreenshotSelection> = emptyMap(),
    ): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        val original = current.steps
        val old = if (allowReorder && expectedRowIds != null) {
            val byId = original.associateBy { it.id }
            if (expectedRowIds.size != original.size || expectedRowIds.toSet().size != original.size || !byId.keys.containsAll(expectedRowIds)) {
                return@synchronized false
            }
            expectedRowIds.map { byId.getValue(it) }
        } else {
            original
        }
        if (expectedScreenshotIds != expectedScreenshotSelections.keys ||
            expectedScreenshotSelections.any { (id, selection) ->
                !selection.image.eligibleAsExpectedScreenshot || reviewFingerprint(mutableSnapshot.value, id) != selection.reviewFingerprint
            } ||
            !validReviewUpdate(old, actionsAndExpected, expectedScreenshotIds, expectedRowIds, optionalOverrides, performerEdits)
        ) {
            return@synchronized false
        }
        val edited = old.mapIndexed { index, row ->
            val optional = optionalOverrides[row.id]?.first ?: row.optional
            val condition = if (optionalOverrides.containsKey(row.id)) optionalOverrides[row.id]?.second?.trim()?.takeIf(String::isNotEmpty) else row.condition
            val action = actionsAndExpected[index].first
            val expected = actionsAndExpected[index].second
            val updated = row.copy(
                action = action,
                expected = expected,
                optional = optional,
                condition = condition,
                reviewReason = if (action != row.action || expected != row.expected || optional != row.optional) {
                    reviewReasonAfterEdit(row.reviewReason, action, expected, optional)
                } else {
                    row.reviewReason
                },
                useScreenshotAsExpected = row.id in expectedScreenshotIds,
                expectedScreenshotJpeg = expectedScreenshotSelections[row.id]?.image?.jpeg,
                expectedScreenshotSource = expectedScreenshotSelections[row.id]?.image?.source,
                expectedScreenshotTimestampMs = expectedScreenshotSelections[row.id]?.image?.timestampMs,
                expectedScreenshotAlignment = expectedScreenshotSelections[row.id]?.image?.alignmentConfidence,
            )
            val withPerformer = performerEdits[row.id]?.applyTo(updated) ?: updated
            val changed = reviewedContent(row) != reviewedContent(withPerformer)
            withPerformer.copy(humanReviewed = row.humanReviewed || changed)
        }
        mutableSnapshot.value = current.copy(
            steps = edited,
            rawSteps = current.rawSteps?.takeUnless { current.rewriteApplied }?.let { syncRawReviewRows(it, original, edited) } ?: current.rawSteps,
        )
        true
    }

    private fun syncRawReviewRows(raw: List<RecordedTestStep>, old: List<RecordedTestStep>, edited: List<RecordedTestStep>): List<RecordedTestStep> {
        val editedById = edited.associateBy { it.id }
        val oldIds = old.mapTo(HashSet()) { it.id }
        return raw.map { source ->
            if (source.id !in oldIds) source else editedById[source.id] ?: source
        }
    }

    private fun validReviewUpdate(
        old: List<RecordedTestStep>,
        actionsAndExpected: List<Pair<String, String>>,
        expectedScreenshotIds: Set<String>,
        expectedRowIds: List<String>?,
        optionalOverrides: Map<String, Pair<Boolean, String?>>,
        performerEdits: Map<String, RecordedPerformerEdit>,
    ): Boolean {
        val current = mutableSnapshot.value
        return !current.active && !applicationReserved && !current.rewriteInProgress && actionsAndExpected.size == old.size &&
            expectedScreenshotIds.all { id -> old.any { it.id == id } } &&
            optionalOverrides.keys.all { id -> old.any { it.id == id } } &&
            performerEdits.all { (id, edit) -> old.firstOrNull { it.id == id }?.let(edit::allowedOn) == true } &&
            old.mapIndexed { index, row -> row to actionsAndExpected[index] }.all { (row, text) ->
                val (optional, condition) = optionalOverrides[row.id] ?: (row.optional to row.condition)
                val action = text.first
                val expected = text.second
                val withText = row.copy(action = action, expected = expected, optional = optional, condition = condition)
                val withReview = withText.copy(
                    reviewReason = if (action != row.action || expected != row.expected || optional != row.optional) {
                        reviewReasonAfterEdit(row.reviewReason, action, expected, optional)
                    } else {
                        row.reviewReason
                    },
                )
                val effective = performerEdits[row.id]?.applyTo(withReview) ?: withReview
                // An unresolved row may remain unchanged while the reviewer edits a neighbor. Keep its review issue
                // for Apply to report, but don't reject the bulk edit; field/action/condition/script rules still apply.
                recordingRowIssues(effective, expected, effective.optional, effective.condition, requireExpected = false)
                    .none { it == RecordingRowIssue.ACTION || it == RecordingRowIssue.CONDITION || it == RecordingRowIssue.SCRIPT }
            } &&
            (expectedRowIds == null || expectedRowIds == old.map { it.id })
    }

    fun stop(): TestStepRecordingSnapshot {
        var finishingInput: DeviceInputHandle? = null
        var oldEnvironment: Closeable? = null
        val detached = synchronized(lock) {
            var oldSubscription: Closeable? = null
            if (mutableSnapshot.value.active) {
                touches.clear()
                oldEnvironment = environment
                environment = null
                finishingInput = deviceInput
                if (finishingInput == null) tailDone.countDown() else tailPending = true
                mutableSnapshot.value = mutableSnapshot.value.copy(active = false, pendingSnapshots = pendingCount())
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
        oldEnvironment?.close()
        // Not under the lock: the recorder reads the device and delivers the last typed text through deviceInputSink.
        finishingInput?.finish()
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
        val tailDrained = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { tailDone.await(tailDrainTimeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        }
        if (!tailDrained) {
            deviceInput?.close()
            synchronized(lock) {
                tailPending = false
                refreshPending()
                warn("The last input on the device could not be read before recording stopped; it may be missing from the steps.")
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
        deviceInput?.close()
        synchronized(lock) { environment.also { environment = null } }?.close()
        videoTimeline?.close()
        disposed.set(true)
        probeDisposed.set(true)
        val discarded = screenshotWorker.shutdownNow().size
        probeWorker.shutdownNow()
        queuedScreenshots.updateAndGet { (it - discarded).coerceAtLeast(0) }
        synchronized(lock) {
            tailPending = false
            tailDone.countDown()
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

    @Suppress("CyclomaticComplexMethod") // A saved step combines review choices, retained hints, and evidence provenance in one mapping.
    internal fun toTestSteps(rows: List<RecordedTestStep>, assetPathFor: (ByteArray) -> String? = { null }): List<TestStep> = rows.map { row ->
        val rewritten = row.sourceInputIds.isNotEmpty()
        val context = row.screenContext?.takeIf(String::isNotBlank)
        val selectedExpected = if (row.useScreenshotAsExpected) row.expectedScreenshotJpeg else null
        val assetPath = selectedExpected?.let(assetPathFor)
        val screenshotSource = row.expectedScreenshotSource ?: row.screenshotSource?.name?.lowercase()?.replace('_', ' ')
        // Only an AI step reads examples: a manual or script step is done by a person or the run itself.
        val byAgent = row.stepKind == StepKind.AGENT
        val examples = buildList {
            when {
                !byAgent -> Unit
                rewritten -> row.sourceHint?.takeIf(String::isNotBlank)?.let {
                    add(StepExample.ReferenceLog(newExampleId(), caption = REWRITE_HINT_CAPTION, text = it))
                }
                context != null ->
                    add(StepExample.ReferenceLog(newExampleId(), caption = "Recorded input context; see source timing", text = context))
            }
            if (byAgent && !rewritten && row.stateChanges.isNotEmpty()) {
                add(StepExample.ReferenceLog(newExampleId(), caption = STATE_CHANGES_CAPTION, text = row.stateChanges.joinToString("\n") { it.summary() }))
            }
            if (assetPath != null && row.useScreenshotAsExpected) {
                val caption = when {
                    rewritten -> "Reviewed expected screenshot; verified after evidence from ${screenshotSource ?: "screen probe"}"
                    row.expectedScreenshotJpeg != null ->
                        "Reviewed expected screenshot; verified after evidence from ${screenshotSource ?: "recording preview"}"
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
            intent = row.intent,
            precondition = row.precondition,
            gestureHints = gestureHintsFor(row),
            script = row.script,
            manualCompletion = row.manualCompletion,
        ).withKind(row.stepKind)
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
        val label = KEY_LABELS[keycode] ?: "Android key $keycode"
        if (label.startsWith("Android key")) warn("Key code $keycode has no friendly label; review this recorded action.")
        val kind = if (keycode == KEYCODE_BACK) RecordedInputKind.BACK else RecordedInputKind.KEY
        add("Press $label", "Android key input was ${channel()}.", frame, kind, nowMs)
    }

    private fun addSystem(action: String, kind: RecordedInputKind, frame: MirrorFrame?, nowMs: Long) {
        add(action, "This system control was ${channel()}.", frame, kind, nowMs)
    }

    private fun recordText(text: String, frame: MirrorFrame?, nowMs: Long, pasted: Boolean = false) {
        val action = boundedTextAction(if (pasted) PASTE_TEXT_PREFIX else ENTER_TEXT_PREFIX, text)
        val context = when {
            inputSource == InputSource.DEVICE -> "Text was typed on a keyboard attached to the device or emulator (adb getevent)."
            pasted -> "Pasted text was sent through the device mirror."
            else -> "Text was entered through the device mirror."
        }
        add(action, context, frame, RecordedInputKind.TEXT, nowMs)
    }

    private fun boundedTextAction(prefix: String, text: String): String {
        val bounded = text.take(MAX_RECORDED_TEXT)
        val suffix = if (text.length > MAX_RECORDED_TEXT) " (recorded only the first $MAX_RECORDED_TEXT of ${text.length} characters)" else ""
        if (suffix.isNotEmpty()) warn("Text input was truncated in the draft: ${text.length} characters received, first $MAX_RECORDED_TEXT retained.")
        return "$prefix$bounded$suffix"
    }

    /**
     * The row for a burst typed on the on-screen keyboard. It carries no key position: the keyboard's taps never reach [touch]. The
     * text is the field's content after the burst minus what the last screen state before it showed (see [typedTextDelta]); a
     * password field gives only the mask, and so does a field that an earlier screen state showed to be a password.
     */
    private fun keyboardTextLocked(reading: KeyboardTextReading, startMs: Long, endMs: Long) {
        inputSource = InputSource.DEVICE
        val baseline = baselineField(startMs)
        val password = reading.password || baseline?.password == true
        val field = reading.fieldLabel?.let { " into '${it.take(MAX_FIELD_LABEL_CHARS)}'" }.orEmpty()
        val text = reading.text
        val action = when {
            password -> ENTER_TEXT_PREFIX + MASKED_TEXT
            text == null -> KEYBOARD_UNREAD_ACTION
            else -> typedTextDelta(baseline?.text, text)?.let { boundedTextAction(ENTER_TEXT_PREFIX, it) }
                ?: CLEAR_FIELD_ACTION.takeIf { text.isEmpty() && !baseline?.text.isNullOrEmpty() }
        } ?: return
        val context = when {
            password -> "Typed in a password field on the on-screen keyboard$field; the text and the key positions are not recorded."
            text == null -> "Typed on the on-screen keyboard$field; the field text could not be read, and key positions are not recorded."
            else -> "Typed on the on-screen keyboard$field; the text was read from the field's UI hierarchy and key positions are not recorded."
        }
        add(action, context, null, RecordedInputKind.TEXT, endMs, startMs = startMs)
    }

    /** The focused field of the last screen state that finished at or before [beforeMs], as that state showed it. */
    private fun baselineField(beforeMs: Long): FocusedField? =
        screenStates.values.filter { it.finishedAt <= beforeMs && it.nodes.isNotEmpty() }.maxByOrNull { it.seq }?.let { focusedFieldOf(it.nodes) }

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
        val fromDevice = inputSource == InputSource.DEVICE
        val screenshotContext = when {
            frame != null -> "$context UI hierarchy isn't exposed by the mirror; the raw mirror preview has uncertain acquisition timing."
            screencap != null && fromDevice ->
                "$context A raw input preview is read through adb after the action, with its acquisition interval recorded."
            screencap != null ->
                "$context UI hierarchy isn't exposed by the mirror; a raw input preview is read through adb after the action, " +
                    "with its acquisition interval recorded."
            fromDevice -> "$context Screenshot frame unavailable."
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
        // A row that arrives after stop (the device recorder's last typed text) gets no new probe or image; the screen was read before.
        if (!current.active) return
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

    private fun pendingCount(): Int = queuedScreenshots.get() + probePending() + if (tailPending) 1 else 0

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

    /**
     * Selects one before image for the first input and one settled after image for the last input of a semantic step. Video decode is
     * bounded to those two stills and is called by the review UI on its IO dispatcher, never between recorded gestures.
     */
    @Suppress("CyclomaticComplexMethod") // This single snapshot applies sensitivity, time alignment, fallback, and reason policy together.
    internal fun previewEvidenceFor(rowId: String): RecordingPreviewEvidence {
        val (current, stateSnapshot, timeline) = synchronized(lock) { Triple(mutableSnapshot.value, screenStates.toMap(), videoTimeline) }
        val visible = current.steps.firstOrNull { it.id == rowId }
            ?: return RecordingPreviewEvidence(null, null, "UI context unavailable.", "This recording row is no longer available.")
        val fingerprint = reviewFingerprint(current, rowId)
        val sourceIds = visible.sourceInputIds.toSet().ifEmpty { setOf(visible.id) }
        val rawRows = (current.rawSteps ?: current.steps).filter { !it.authorAdded }
        val inputs = rewriteInputsOf(rawRows, stateSnapshot::get)
        val chronological = inputs.filterNot { it.fixed || it.row.isExternalEvent }.sortedBy { it.row.inputAtMs }
        val group = chronological.filter { input -> input.row.id in sourceIds }
        if (group.isEmpty()) {
            return RecordingPreviewEvidence(
                null, null, "UI hierarchy unavailable for this step.", "No recorded input is linked to this row.",
                reviewFingerprint = fingerprint,
            )
        }
        val sensitivity = RewriteSensitivity(inputs)
        if (group.any(sensitivity::inputWithheld)) {
            return RecordingPreviewEvidence(
                null, null, "UI context withheld because this step includes a password-sensitive input.",
                "Images withheld for this password-sensitive step.",
                reviewFingerprint = fingerprint,
            )
        }
        val first = group.first()
        val last = group.last()
        val next = chronological.firstOrNull { it.row.inputStartMs > last.row.inputAtMs }
        val beforeAt = first.row.inputStartMs - REWRITE_VIDEO_BEFORE_MS
        val afterAt = settledAtMs(last, next?.row?.inputStartMs)
        val afterScreenshot = settledAfterOf(last, next, sensitivity)
        val beforeScreenshot = first.screenshot("before")
        val needsBeforeVideo = beforeScreenshot == null || !hasEligibleBeforeScreenshot(beforeScreenshot)
        val needsAfterVideo = afterScreenshot.shot == null || !afterScreenshot.settled || !hasEligibleAfterScreenshot(afterScreenshot.shot)
        val video = timeline?.takeIf { it.summary().available && it.summary().finished }
        val requestedTimes = buildList {
            if (needsBeforeVideo) add(beforeAt)
            if (needsAfterVideo) add(afterAt)
        }
        val stills = if (requestedTimes.isNotEmpty()) {
            video?.stillsAt(requestedTimes.map(video::sessionOffsetMs), inputs).orEmpty()
        } else {
            emptyList()
        }
        var stillIndex = 0
        val beforeFrame = if (needsBeforeVideo) stills.getOrNull(stillIndex++)?.let { still ->
            video?.let { RewriteVideoFrame(still.image, it.wallClockOfSessionMs(still.sessionMs), still.sessionMs) }
        } else null
        val afterFrame = if (needsAfterVideo) stills.getOrNull(stillIndex)?.let { still ->
            video?.let { RewriteVideoFrame(still.image, it.wallClockOfSessionMs(still.sessionMs), still.sessionMs) }
        } else null
        val beforeSelection = selectRecordingPreviewEvidence(first, next, sensitivity, beforeFrame = beforeFrame)
        val afterSelection = selectRecordingPreviewEvidence(last, next, sensitivity, afterFrame = afterFrame)
        val videoSummary = timeline?.summary()
        val frameDecodeOrGapFailure = requestedTimes.isNotEmpty() && stills.none { it != null } && videoSummary?.available == true
        val timingOverlap = listOfNotNull(beforeScreenshot, afterScreenshot.shot).any { it.timingUncertain }
        val imageReason = when {
            beforeSelection.before != null && afterSelection.after != null -> null
            beforeSelection.imageAvailabilityMessage?.contains("decode", ignoreCase = true) == true -> beforeSelection.imageAvailabilityMessage
            afterSelection.imageAvailabilityMessage?.contains("decode", ignoreCase = true) == true -> afterSelection.imageAvailabilityMessage
            else -> buildList {
                if (timingOverlap) add("Screenshot timing overlapped or was uncertain during a gesture.")
                if (videoSummary?.warning != null) add("Recorded video: ${videoSummary.warning}")
                if (videoSummary == null || !videoSummary.available || !videoSummary.finished) {
                    add("No finished recorded video frame was available.")
                } else if (frameDecodeOrGapFailure) {
                    val gaps = if (videoSummary.gapCount > 0) " and reports ${videoSummary.gapCount} gap(s)" else ""
                    add("The recorded video had no decodable frame at the requested moment$gaps.")
                }
                if (beforeSelection.before == null) add("Before image unavailable.")
                if (afterSelection.after == null) add("After image unavailable.")
            }.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }
        val target = first.row.targetUnderTouch
        val hierarchy = first.before?.nodes.orEmpty()
        val targetResolved = target?.let { expected ->
            hierarchy.any { node ->
                (expected.resourceId.isNotBlank() && node.resourceId == expected.resourceId) ||
                    (node.left == expected.left && node.top == expected.top && node.right == expected.right && node.bottom == expected.bottom)
            }
        } ?: false
        val uiMessage = when {
            hierarchy.isEmpty() -> "UI hierarchy unavailable for this input."
            target != null && !targetResolved -> "UI hierarchy was captured, but the recorded target is unresolved."
            else -> "UI hierarchy was captured for this input."
        }
        return RecordingPreviewEvidence(
            beforeSelection.before, afterSelection.after, uiMessage, imageReason, beforeSelection.rawInput, fingerprint,
        )
    }

    internal fun expectedScreenshotSelectionFor(rowId: String): RecordingExpectedScreenshotSelection? {
        val evidence = previewEvidenceFor(rowId)
        val image = evidence.after?.takeIf { it.eligibleAsExpectedScreenshot } ?: return null
        return evidence.reviewFingerprint?.let { RecordingExpectedScreenshotSelection(image, it) }
    }

    private fun reviewFingerprint(snapshot: TestStepRecordingSnapshot, rowId: String): Int {
        val row = snapshot.steps.firstOrNull { it.id == rowId } ?: return 0
        val sourceIds = row.sourceInputIds.toSet().ifEmpty { setOf(row.id) }
        val rawRows = (snapshot.rawSteps ?: snapshot.steps).filter { it.id in sourceIds && !it.authorAdded }
        return REVIEW_HASH_MULTIPLIER * row.hashCode() + rawRows.hashCode()
    }

    internal fun gestureHintsForPreview(rowId: String): List<RecordedGestureHint> = synchronized(lock) {
        mutableSnapshot.value.steps.firstOrNull { it.id == rowId }?.let(::gestureHintsFor).orEmpty()
    }

    /** A tapped label is device-derived text too; withhold it using the complete raw-input timeline, just like gesture hints. */
    internal fun tappedElementForPreview(rowId: String): TappedElement? = synchronized(lock) {
        val visible = mutableSnapshot.value.steps.firstOrNull { it.id == rowId } ?: return@synchronized null
        val rows = (mutableSnapshot.value.rawSteps ?: mutableSnapshot.value.steps).filterNot { it.authorAdded }
        val inputs = rewriteInputsOf(rows, ::screenState)
        val sensitivity = RewriteSensitivity(inputs)
        val wantedIds = visible.sourceInputIds.ifEmpty { listOf(visible.id) }.toSet()
        val selectedInputs = inputs.filter { it.row.id in wantedIds }
        if (selectedInputs.isEmpty() || selectedInputs.any(sensitivity::inputWithheld) ||
            selectedInputs.any { sensitivity.spanWithheld(it.row.inputStartMs, it.row.inputAtMs) }
        ) {
            null
        } else {
            visible.tappedElement
        }
    }

    /** What is saved with the recording ("Keep recording evidence"): the review state, the screen readings and screenshots, the video. */
    internal fun evidence(): SessionEvidence = synchronized(lock) {
        SessionEvidence(mutableSnapshot.value, screenStates.values.toList(), probeScreenshots.toList(), videoTimeline, deviceSerial)
    }

    /**
     * Turns this fresh session into the stopped recording [stored] describes (see [restoreRecordingSession]): the reviewed rows, the raw
     * rows an AI rewrite replaced, the screen readings and screenshots. It never reads a device; the worker threads are shut down as after
     * [stop] so nothing more can arrive.
     */
    internal fun loadStopped(stored: StoredRecording, from: ReopenedRecording) {
        synchronized(lock) {
            stored.screenStates.forEach { screenStates[it.seq] = it }
            probeScreenshots += stored.probeScreenshots
            nextStateSeq = (screenStates.keys.maxOrNull() ?: 0) + 1
            stateImageBytes = screenStates.values.sumOf { (it.screenshotJpeg?.size ?: 0).toLong() } + probeScreenshots.sumOf { it.jpeg.size.toLong() }
            mutableSnapshot.value = TestStepRecordingSnapshot(
                active = false,
                steps = stored.rows.take(MAX_RECORDED_STEPS),
                warnings = (stored.warnings + from.notes).distinct().takeLast(MAX_RECORDED_WARNINGS),
                rawSteps = stored.rawRows,
                rewriteNotes = if (stored.rewriteApplied) stored.rewriteNotes else "",
                videoTimeline = videoTimeline?.summary(),
                excludedSourceInputIds = stored.excludedSourceInputIds,
                rewriteApplied = stored.rewriteApplied,
                reopened = from.info(),
            )
            reopened = from
            touches.clear()
            probeStopped.countDown()
            tailDone.countDown()
            screenshotWorker.shutdown()
            probeWorker.shutdown()
        }
    }

    /** Publishes [rows] with their before/after states, tapped element and password masking recomputed from the stored states. */
    private fun publishRows(rows: List<RecordedTestStep>) {
        val (withContext, masked) = withScreenContext(rows)
        mutableSnapshot.value = mutableSnapshot.value.copy(steps = withContext)
        if (masked.isNotEmpty()) {
            warn("${if (masked.size == 1) "Step" else "Steps"} ${stepRanges(masked)}: text typed into a password field was not recorded.")
        }
    }

    private fun withScreenContext(rows: List<RecordedTestStep>): Pair<List<RecordedTestStep>, List<Int>> {
        // Attachment needs chronological order; a reviewer may have moved rows, so sort by completion (a stable sort keeps list order for ties).
        // An external event is not something the person did: it neither has a screen of its own nor splits the gaps between inputs.
        val recorded = rows.indices.filter { rows[it].kind != null && rows[it].kind != RecordedInputKind.EXTERNAL }.sortedBy { rows[it].inputAtMs }
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
                targetUnderTouch = tappedTargetsFor(rows[index], before)?.underTouch,
                clickableTargets = tappedTargetsFor(rows[index], before)?.clickableAncestors.orEmpty(),
                targetScreenWidth = before?.screenWidth?.takeIf { it > 0 },
                targetScreenHeight = before?.screenHeight?.takeIf { it > 0 },
                targetOrientationQuarterTurns = before?.rotation,
                touchWithinTarget = touchWithinTargetFor(rows[index], before),
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

    private fun tappedTargetsFor(row: RecordedTestStep, before: RecordingScreenState?): ResolvedTappedTargets? {
        val tapped = row.kind == RecordedInputKind.TAP || row.kind == RecordedInputKind.LONG_PRESS
        val point = row.gesturePath.lastOrNull()
        return if (tapped && before != null && point != null) resolveTappedTargets(before, point) else null
    }

    private fun touchWithinTargetFor(row: RecordedTestStep, before: RecordingScreenState?): NormalizedGesturePoint? {
        val screen = before ?: return null
        val target = tappedTargetsFor(row, screen)?.underTouch ?: return null
        val point = row.gesturePath.lastOrNull() ?: return null
        if (point.screenWidth <= 0 || point.screenHeight <= 0 || target.right <= target.left || target.bottom <= target.top) return null
        val x = point.x.toLong() * screen.screenWidth / point.screenWidth
        val y = point.y.toLong() * screen.screenHeight / point.screenHeight
        return NormalizedGesturePoint(
            ((x - target.left).toDouble() / (target.right - target.left)).coerceIn(0.0, 1.0),
            ((y - target.top).toDouble() / (target.bottom - target.top)).coerceIn(0.0, 1.0),
        )
    }

    /** A rewritten semantic step inherits every source gesture in recorded order. */
    private fun gestureHintsFor(row: RecordedTestStep): List<RecordedGestureHint> {
        val snapshot = mutableSnapshot.value
        val allRows = snapshot.rawSteps ?: snapshot.steps
        val allInputs = rewriteInputsOf(allRows, ::screenState)
        val sensitivity = RewriteSensitivity(allInputs)
        val wantedIds = row.sourceInputIds.ifEmpty { listOf(row.id) }.toSet()
        return allInputs.asSequence()
            .filter { it.row.id in wantedIds }
            .filterNot(sensitivity::inputWithheld)
            .mapNotNull { gestureHintOf(it.row) }
            .take(MAX_GESTURE_HINTS)
            .toList()
    }

    private fun gestureHintOf(row: RecordedTestStep): RecordedGestureHint? {
        val kind = row.kind?.takeIf { it == RecordedInputKind.TAP || it == RecordedInputKind.LONG_PRESS || it == RecordedInputKind.SWIPE }
            ?: return null
        val source = row.gesturePath.firstOrNull() ?: return null
        val sourceWidth = source.screenWidth.takeIf { it > 0 } ?: return null
        val sourceHeight = source.screenHeight.takeIf { it > 0 } ?: return null
        val targetWidth = row.targetScreenWidth?.takeIf { it > 0 }
        val targetHeight = row.targetScreenHeight?.takeIf { it > 0 }

        fun target(value: RecordedUiTarget) = RecordedGestureTarget(
            text = value.text.take(MAX_GESTURE_TARGET_TEXT_CHARS),
            contentDescription = value.contentDesc.take(MAX_GESTURE_TARGET_TEXT_CHARS),
            resourceId = value.resourceId.take(MAX_GESTURE_TARGET_RESOURCE_ID_CHARS),
            className = value.className.take(MAX_GESTURE_TARGET_CLASS_CHARS),
            bounds = if (targetWidth != null && targetHeight != null) {
                NormalizedGestureBounds(
                    value.left.toDouble() / targetWidth,
                    value.top.toDouble() / targetHeight,
                    value.right.toDouble() / targetWidth,
                    value.bottom.toDouble() / targetHeight,
                )
            } else {
                null
            },
        )
        return RecordedGestureHint(
            kind = kind.name,
            path = row.gesturePath.take(MAX_GESTURE_SAMPLES + 2).map { point ->
                NormalizedGesturePoint(
                    point.x.toDouble() / point.screenWidth.coerceAtLeast(1),
                    point.y.toDouble() / point.screenHeight.coerceAtLeast(1),
                )
            },
            durationMs = row.durationMs,
            sourceScreenWidth = sourceWidth,
            sourceScreenHeight = sourceHeight,
            orientationQuarterTurns = row.targetOrientationQuarterTurns,
            targetUnderTouch = row.targetUnderTouch?.let(::target),
            clickableTargets = row.clickableTargets.take(MAX_GESTURE_CLICKABLE_ANCESTORS).map(::target),
            touchWithinTarget = row.touchWithinTarget,
        )
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
        const val MAX_GESTURE_HINTS = 24
        const val MAX_GESTURE_CLICKABLE_ANCESTORS = 3
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
        const val ATTRIBUTION_WINDOW_MS = 3_000L
        const val MAX_ENVIRONMENT_EVENTS = 60
        const val MAX_STATE_CHANGES_PER_ROW = 10
        const val DUPLICATE_WINDOW_MS = 50L
        const val DUPLICATE_TOUCH_TOLERANCE_PX = 4
        const val TAIL_DRAIN_TIMEOUT_MS = 12_000L
        const val MAX_FIELD_LABEL_CHARS = 60
        const val KEYBOARD_UNREAD_ACTION = "Type text on the on-screen keyboard"
        const val CLEAR_FIELD_ACTION = "Clear the text field"
        const val ENTER_TEXT_PREFIX = "Enter text: "
        const val PASTE_TEXT_PREFIX = "Paste text: "
        const val MASKED_TEXT = "••••"
        const val DELETE_ACTION = "Press Delete"
        const val REWRITE_HINT_CAPTION = "Recorded input (hint; prefer what is on screen)"
        const val STATE_CHANGES_CAPTION = "Observed on the device after this input while recording"
        const val PROBE_FAILED_WARNING = "The screen context (UI hierarchy) could not be read through adb; steps keep coordinates and images only."
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
