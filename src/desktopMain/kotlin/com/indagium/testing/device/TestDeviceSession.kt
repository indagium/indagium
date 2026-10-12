package com.indagium.testing.device

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureMirrorMode
import com.indagium.capture.CaptureProcessRunner
import com.indagium.capture.CaptureRecorder
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureTools
import com.indagium.capture.ProcessBuilderCaptureRunner
import com.indagium.capture.withMirrorMode
import com.indagium.debug.BoundedDeviceScreenImage
import com.indagium.debug.DEVICE_KEY_CODES
import com.indagium.debug.DeviceScreenCoordinateSpace
import com.indagium.debug.SWIPE_DURATION_MAX_MS
import com.indagium.debug.SWIPE_DURATION_MIN_MS
import com.indagium.debug.encodeBoundedDeviceScreen
import com.indagium.debug.mapDisplayedScreenCoordinatesToDevice
import com.indagium.debug.requireDeviceSwipe
import com.indagium.debug.requireSafeAndroidInputText
import com.indagium.debug.requireSafeDeviceUrl
import com.indagium.debug.requireValidAndroidPackageName
import com.indagium.model.LogEntry
import com.indagium.testing.script.AdbScriptTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

// One device lane of an AI test run. It records through a [LaneCapture] (production: a real capture controller, with or
// without a live tab, see ui/LaneCaptures.kt; the engine tests: a standalone CaptureRecorder in the lane's folder) and drives
// the device through adb alone. This class touches no AppState and none of its locks: the capture implementation owns every
// UI-side concern. A serial the live capture (or another lane) holds is refused when the capture is opened.
//
// Coordinates the agent uses (tap, swipe, UI-tree bounds) are in the pixel space of the LAST screenshot it was
// given; the session maps them onto physical device pixels with the same rule device_tap uses.

/** The folder of a lane (under the run folder `lanes/<laneId>/`) that holds its capture sessions. */
const val LANE_CAPTURE_DIRECTORY = "capture"
private const val INPUT_TIMEOUT_SECONDS = 5L
private const val ACCELEROMETER_ROTATION_SETTING = "accelerometer_rotation"
private const val USER_ROTATION_SETTING = "user_rotation"
private const val ROTATION_LOCKED = 0
private const val MIN_ROTATION = 0
private const val MAX_ROTATION = 3
private const val LAUNCH_TIMEOUT_SECONDS = 10L
private const val MAX_SEQUENCE_ACTIONS = 8
private const val MAX_SEQUENCE_DELAY_MS = 2_000L
private const val MAX_SEQUENCE_ACTION_DELAY_MS = 1_000L
private const val MAX_UI_TREE_READ_TIMEOUT_MS = 2_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L

internal sealed interface DeviceInputAction {
    val delayMs: Long
    val tool: String

    data class Tap(val x: Int, val y: Int, override val delayMs: Long = 0L) : DeviceInputAction {
        override val tool: String = "tap"
    }

    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Int,
        override val delayMs: Long = 0L,
    ) : DeviceInputAction {
        override val tool: String = "swipe"
    }

    data class Key(val key: String, override val delayMs: Long = 0L) : DeviceInputAction {
        override val tool: String = "press_key"
    }

    /** A finger following [points] (screenshot pixels, times in ms from touch-down) with its timing; see GestureScript.kt. */
    data class Gesture(val points: List<GesturePoint>, override val delayMs: Long = 0L) : DeviceInputAction {
        override val tool: String = "gesture"
    }

    /** The notification shade or quick settings opened, or either closed (`cmd statusbar`), as a replay needs them. */
    data class Panel(val panel: SystemPanel, override val delayMs: Long = 0L) : DeviceInputAction {
        override val tool: String = "open_system_panel"
    }
}

internal data class DeviceInputActionResult(
    val action: String,
    val succeeded: Boolean,
    val detail: String? = null,
    val durationMs: Long = 0L,
)

internal data class DeviceInputSequenceResult(
    val actions: List<DeviceInputActionResult>,
    val finalScreen: SessionScreenshot?,
    val finalScreenFailure: String? = null,
    val durationMs: Long = 0L,
)

internal sealed interface UiTreeRead {
    data class Available(val tree: UiTreeParse) : UiTreeRead

    data class Unavailable(val reason: String) : UiTreeRead
}

private const val DEVICE_STATE_TIMEOUT_SECONDS = 8L
private const val ADB_ERROR_MESSAGE_MAX_CHARS = 500
private const val LOG_POLL_INTERVAL_MS = 100L
private const val MAX_LOG_WAIT_MS = 10 * 60 * 1000L
private const val DEFAULT_LOG_READ_ROWS = 100
private const val MAX_LOG_READ_ROWS = 500
private const val MAX_LOG_SCAN_BYTES = 16L * 1024 * 1024
private const val DEFAULT_SWIPE_MS = 350
private const val MAX_REPLAY_ACTIONS = 100
private const val MAX_REPLAY_DELAY_MS = 10_000L
private const val LIVE_CAPTURE_REFUSAL = "is held by the live capture in the main window; stop that capture or choose another device."

/** The system panels a lane can open or close with `cmd statusbar`; [command] is the fixed sub-command, [toolValue] what the agent passes. */
internal enum class SystemPanel(val toolValue: String, val command: String) {
    NOTIFICATIONS("notifications", "expand-notifications"),
    QUICK_SETTINGS("quick_settings", "expand-settings"),
    CLOSE("close", "collapse"),
    ;

    companion object {
        fun fromToolValue(value: String): SystemPanel =
            entries.firstOrNull { it.toolValue == value.trim().lowercase() }
                ?: throw IllegalArgumentException("panel must be one of ${entries.joinToString(", ") { it.toolValue }}")
    }
}

/** What the rotation settings were before the lane first rotated the device: null means the setting did not exist. */
private data class RotationRestore(val accelerometer: Int?, val user: Int?)

/** A screenshot as the agent sees it, plus the full-resolution PNG kept for evidence. */
internal class SessionScreenshot(
    val png: ByteArray,
    val image: BoundedDeviceScreenImage,
    val space: DeviceScreenCoordinateSpace,
)

internal data class GestureResult(val imageCoordinates: List<Int>, val deviceCoordinates: List<Int>)

/** UI-tree node with bounds and tap point in screenshot pixels. */
internal data class UiTreeNodeView(val node: UiNode, val bounds: List<Int>, val tapX: Int, val tapY: Int)

internal data class UiTreeSnapshot(
    val imageWidth: Int,
    val imageHeight: Int,
    val nodes: List<UiTreeNodeView>,
    val totalNodes: Int,
    val truncated: Boolean,
)

/** [rows] are at most the requested limit; [nextOffset] continues after the last row returned; [more] says rows may remain. */
internal data class LogRead(val rows: List<LogEntry>, val nextOffset: Long, val more: Boolean)

internal sealed interface LogWaitResult {
    /** The matching row and the offset right after it. */
    data class Matched(val entry: LogEntry, val endOffset: Long, val waitedMs: Long) : LogWaitResult

    data class TimedOut(val endOffset: Long, val waitedMs: Long, val recording: Boolean) : LogWaitResult
}

internal sealed interface LogAbsentResult {
    /** Nothing matched for the whole window. */
    data class Absent(val endOffset: Long, val waitedMs: Long) : LogAbsentResult

    /** A matching row appeared; the window ended early. */
    data class Found(val entry: LogEntry, val waitedMs: Long) : LogAbsentResult
}

internal class TestDeviceSession private constructor(
    val serial: String,
    val laneDir: File,
    private val tools: CaptureTools,
    val capture: LaneCapture,
) {
    private val logReader = CaptureLogReader(capture.readLogFile)
    private val inputLock = Mutex()
    private val rotationRestore = AtomicReference<RotationRestore?>(null)

    @Volatile
    private var space: DeviceScreenCoordinateSpace? = null

    val logFile: File get() = capture.logFile

    /** Live source while a manual capture is borrowed; otherwise the lane's own file. */
    internal val readLogFile: File get() = capture.readLogFile

    /** False once the logcat recording ended (device unplugged, storage limit): input still works, new log rows stop. */
    val isRecording: Boolean get() = capture.isRecording

    /** The recorder's own diagnostics (video start failures, adb stderr lines), for a run report. */
    val diagnostics: List<String> get() = capture.diagnostics

    fun adbTarget(): AdbScriptTarget = AdbScriptTarget(tools, serial)

    /**
     * How a device-state check reads a property of the device (see DeviceState.kt). The default runs one bounded `adb shell` per key;
     * a richer probe, or a test, replaces it here.
     */
    @Volatile
    internal var deviceStateReader: DeviceStateReader = AdbDeviceStateReader { command -> deviceShell(command) }

    suspend fun readDeviceState(key: String): DeviceStateReading = deviceStateReader.read(key)

    private suspend fun deviceShell(command: List<String>): DeviceShellOutput {
        val result = adb(listOf("shell") + command, DEVICE_STATE_TIMEOUT_SECONDS)
        return DeviceShellOutput(result.exitCode, result.stdoutText(), result.stderrText(), result.timedOut)
    }

    // ── Screen ───────────────────────────────────────────────────────

    suspend fun screenshot(): SessionScreenshot {
        // readScreen waits on adb's blocking process API. Moving that wait to IO alone does not
        // make a coroutine deadline interrupt it, so use runInterruptible at the blocking edge.
        val png = runInterruptible(Dispatchers.IO) { capture.readScreen() }
        val image = encodeBoundedDeviceScreen(png)
        val current = DeviceScreenCoordinateSpace(image.width, image.height, image.sourceWidth, image.sourceHeight)
        space = current
        return SessionScreenshot(png, image, current)
    }

    /** The coordinate space of the last screenshot, taking one first when the agent has not looked yet. */
    private suspend fun currentSpace(): DeviceScreenCoordinateSpace = space ?: screenshot().space

    // ── Input ────────────────────────────────────────────────────────

    suspend fun tap(x: Int, y: Int): GestureResult {
        val (deviceX, deviceY) = mapDisplayedScreenCoordinatesToDevice(x, y, currentSpace())
        runInput(listOf("input", "tap", deviceX.toString(), deviceY.toString()))
        return GestureResult(listOf(x, y), listOf(deviceX, deviceY))
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = DEFAULT_SWIPE_MS): GestureResult {
        val current = currentSpace()
        requireDeviceSwipe(x1, y1, x2, y2, durationMs, current.imageWidth, current.imageHeight)
        val (deviceX1, deviceY1) = mapDisplayedScreenCoordinatesToDevice(x1, y1, current)
        val (deviceX2, deviceY2) = mapDisplayedScreenCoordinatesToDevice(x2, y2, current)
        runInput(listOf("input", "swipe", deviceX1.toString(), deviceY1.toString(), deviceX2.toString(), deviceY2.toString(), durationMs.toString()))
        return GestureResult(listOf(x1, y1, x2, y2), listOf(deviceX1, deviceY1, deviceX2, deviceY2))
    }

    suspend fun pressKey(key: String): String {
        val name = key.trim().uppercase()
        val keyCode = DEVICE_KEY_CODES[name] ?: throw IllegalArgumentException("Supported keys are ${DEVICE_KEY_CODES.keys.joinToString(", ")}")
        runInput(listOf("input", "keyevent", keyCode))
        return name
    }

    /** Opens the notification shade or quick settings, or closes them, with `cmd statusbar`. */
    suspend fun openSystemPanel(panel: SystemPanel) {
        runInput(listOf("cmd", "statusbar", panel.command))
    }

    /**
     * Turns the display to [rotation] quarter turns (0 upright, 1..3 counterclockwise) by switching auto-rotate off and setting the
     * user rotation. The settings the device had are remembered the first time and put back when the lane ends ([closeBlocking]).
     * Refused when those settings cannot be read, since they could not be restored. The screenshot coordinate space is dropped: take a
     * new screenshot before the next tap.
     */
    suspend fun rotateDevice(rotation: Int) {
        require(rotation in MIN_ROTATION..MAX_ROTATION) { "rotation must be 0, 1, 2 or 3 (quarter turns)" }
        inputLock.withLock {
            if (rotationRestore.get() == null) {
                val accelerometer = readSystemSetting(ACCELEROMETER_ROTATION_SETTING)
                val user = readSystemSetting(USER_ROTATION_SETTING)
                rotationRestore.set(RotationRestore(accelerometer, user))
            }
            putSystemSetting(ACCELEROMETER_ROTATION_SETTING, ROTATION_LOCKED)
            putSystemSetting(USER_ROTATION_SETTING, rotation)
        }
        space = null
    }

    /** The integer value of a `settings get system` key; null when the key is unset. Throws when the device could not be asked. */
    private suspend fun readSystemSetting(key: String): Int? {
        val result = adb(listOf("shell", "settings", "get", "system", key), INPUT_TIMEOUT_SECONDS)
        check(!result.timedOut && result.exitCode == 0) {
            "Could not read the device's rotation settings, so a rotation could not be undone when the lane ends."
        }
        return result.stdoutText().trim().toIntOrNull()
    }

    private suspend fun putSystemSetting(key: String, value: Int) {
        val result = adb(listOf("shell", "settings", "put", "system", key, value.toString()), INPUT_TIMEOUT_SECONDS)
        check(!result.timedOut && result.exitCode == 0) {
            "Could not set $key: ${(result.stderrText() + "\n" + result.stdoutText()).trim().take(ADB_ERROR_MESSAGE_MAX_CHARS)}"
        }
    }

    suspend fun inputText(text: String) {
        requireSafeAndroidInputText(text)
        runInput(listOf("input", "text", text.replace(" ", "%s")))
    }

    suspend fun launchApp(packageName: String) {
        requireValidAndroidPackageName(packageName)
        val result = adb(listOf("shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"), LAUNCH_TIMEOUT_SECONDS)
        val output = result.stdoutText() + "\n" + result.stderrText()
        check(!result.timedOut) { "The bounded adb launch command timed out" }
        // monkey often exits 0 even when nothing was launchable, so its own message is checked as well.
        check(result.exitCode == 0 && !output.contains("No activities found", ignoreCase = true)) {
            "Could not launch $packageName; it may not be installed or has no launchable activity: ${output.trim().take(ADB_ERROR_MESSAGE_MAX_CHARS)}"
        }
    }

    suspend fun openUrl(url: String) {
        requireSafeDeviceUrl(url)
        // ONE remote command string with the (already validated) URL single-quoted, as device_open_url does.
        val result = adb(listOf("shell", "am start -a android.intent.action.VIEW -d '$url'"), LAUNCH_TIMEOUT_SECONDS)
        check(!result.timedOut) { "The bounded adb open-url command timed out" }
        check(result.exitCode == 0) { "Could not open URL: ${(result.stderrText() + "\n" + result.stdoutText()).trim().take(ADB_ERROR_MESSAGE_MAX_CHARS)}" }
    }

    private suspend fun runInput(command: List<String>) {
        inputLock.withLock {
            runInputLocked(command)
        }
    }

    private suspend fun runInputLocked(command: List<String>) {
        val result = adb(listOf("shell") + command, INPUT_TIMEOUT_SECONDS)
        check(!result.timedOut) { "The bounded adb input command timed out" }
        check(result.exitCode == 0) {
            "adb device input failed: ${(result.stderrText() + "\n" + result.stdoutText()).trim().take(ADB_ERROR_MESSAGE_MAX_CHARS)}"
        }
    }

    /** Runs one already-parsed short input batch under one input lock, after validating every primitive and coordinate. */
    @Suppress("TooGenericExceptionCaught") // adb adapters report device-specific input and capture failures through several exception types.
    suspend fun performInputSequence(
        actions: List<DeviceInputAction>,
        onActionResult: (index: Int, result: DeviceInputActionResult) -> Unit = { _, _ -> },
        onFinalCaptureAttempted: () -> Unit = {},
        onInterrupted: (nextActionIndex: Int, reason: String) -> Unit = { _, _ -> },
    ): DeviceInputSequenceResult = inputLock.withLock {
        val sequenceStarted = System.nanoTime()
        require(actions.size in 1..MAX_SEQUENCE_ACTIONS) { "An input sequence must contain 1 to $MAX_SEQUENCE_ACTIONS actions." }
        require(actions.sumOf { it.delayMs } <= MAX_SEQUENCE_DELAY_MS) { "An input sequence may wait at most $MAX_SEQUENCE_DELAY_MS ms in total." }
        val current = try {
            currentSpace()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) { onInterrupted(1, "Cancelled while preparing the coordinate space; no input action was dispatched.") }
            throw cancelled
        }
        actions.forEach { action -> validateInputAction(action, current) }
        val results = ArrayList<DeviceInputActionResult>(actions.size)
        var nextActionIndex = 1
        var interruptionReason = "Input sequence cancelled before completion."
        for ((index, action) in actions.withIndex()) {
            nextActionIndex = index + 1
            val actionStarted = System.nanoTime()
            var result: DeviceInputActionResult
            try {
                interruptionReason = "Cancelled while action ${index + 1} was running."
                val detail = runActionLocked(action, current)
                result = DeviceInputActionResult(action.tool, true, detail, durationMs = elapsedDurationMs(actionStarted))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                result = DeviceInputActionResult(action.tool, false, "Cancelled while this input was running.", elapsedDurationMs(actionStarted))
                results += result
                withContext(NonCancellable) { onActionResult(index + 1, result) }
                withContext(NonCancellable) { onInterrupted(index + 1, interruptionReason) }
                throw cancelled
            } catch (failure: Exception) {
                result = DeviceInputActionResult(action.tool, false, failure.message ?: "Device input failed.", elapsedDurationMs(actionStarted))
                results += result
                onActionResult(index + 1, result)
                break
            }
            results += result
            onActionResult(index + 1, result)
            if (action.delayMs > 0) {
                nextActionIndex = index + 2
                interruptionReason = "Cancelled during the delay after action ${index + 1}; the next action was not dispatched."
                try {
                    delay(action.delayMs)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    withContext(NonCancellable) { onInterrupted(nextActionIndex, interruptionReason) }
                    throw cancelled
                }
            }
        }
        var finalFailure: String? = null
        nextActionIndex = actions.size + 1
        interruptionReason = "Cancelled while capturing the final screenshot."
        onFinalCaptureAttempted()
        val finalScreen = try {
            screenshot()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) { onInterrupted(actions.size + 1, "Cancelled while capturing the final screenshot.") }
            throw cancelled
        } catch (failure: Exception) {
            finalFailure = failure.message ?: "The final screenshot could not be captured."
            null
        }
        DeviceInputSequenceResult(results, finalScreen, finalFailure, elapsedDurationMs(sequenceStarted))
    }

    private fun elapsedDurationMs(started: Long): Long = ((System.nanoTime() - started) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)

    /** Runs one validated action under the input lock; a gesture returns a note when it had to be approximated. */
    private suspend fun runActionLocked(action: DeviceInputAction, current: DeviceScreenCoordinateSpace): String? =
        if (action is DeviceInputAction.Gesture) {
            playGestureLocked(action.points, current).note
        } else {
            runInputLocked(inputCommand(action, current))
            null
        }

    /**
     * Plays a recorded or planned input list in order under one input lock, WITHOUT a final screenshot and without the 8-action and
     * 2-second limits of [performInputSequence]: each action's [DeviceInputAction.delayMs] is the pause after it (up to
     * [MAX_REPLAY_DELAY_MS]), so a recorded rhythm survives. Stops at the first failing action. Every action is validated first, so
     * nothing is injected when one is out of the screen. [onActionResult] hears each result as it happens.
     */
    @Suppress("TooGenericExceptionCaught") // adb adapters report device-specific input failures through several exception types.
    suspend fun performReplay(
        actions: List<DeviceInputAction>,
        onActionResult: (index: Int, result: DeviceInputActionResult) -> Unit = { _, _ -> },
    ): List<DeviceInputActionResult> = inputLock.withLock {
        require(actions.size in 1..MAX_REPLAY_ACTIONS) { "A replay must contain 1 to $MAX_REPLAY_ACTIONS actions." }
        val current = currentSpace()
        actions.forEach { action -> validateInputAction(action, current, MAX_REPLAY_DELAY_MS) }
        val results = ArrayList<DeviceInputActionResult>(actions.size)
        for ((index, action) in actions.withIndex()) {
            val started = System.nanoTime()
            val result = try {
                val detail = runActionLocked(action, current)
                DeviceInputActionResult(action.tool, true, detail, elapsedDurationMs(started))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DeviceInputActionResult(action.tool, false, failure.message ?: "Device input failed.", elapsedDurationMs(started))
            }
            results += result
            onActionResult(index + 1, result)
            if (!result.succeeded) break
            if (action.delayMs > 0) delay(action.delayMs)
        }
        results
    }

    /** The coordinate space of the latest screenshot (one is taken first when there is none): what normalized positions are scaled into. */
    suspend fun screenSpace(): DeviceScreenCoordinateSpace = currentSpace()

    // ── Gestures ─────────────────────────────────────────────────────

    @Volatile
    private var motionEventSupport: Boolean? = null

    /** The Android API level read for the gesture decision; null before the first gesture or when the device would not say. */
    @Volatile
    var androidApiLevel: Int? = null
        private set

    /**
     * Follows [points] (pixels of the latest screenshot, times in ms from touch-down; the first at 0) with their timing: the finger goes
     * down at the first, is at each point at its time and lifts at the last. Android 10 and newer (`input motionevent`) reproduce the
     * path and the hold before the lift; an older device gets a straight `input swipe` from the first to the last point over the whole
     * time, and the result says so ([GesturePlayResult.mode], [GesturePlayResult.note]).
     */
    suspend fun gesture(points: List<GesturePoint>): GesturePlayResult {
        val current = currentSpace()
        validateInputAction(DeviceInputAction.Gesture(points), current)
        return inputLock.withLock { playGestureLocked(points, current) }
    }

    private suspend fun motionEventsSupported(): Boolean {
        motionEventSupport?.let { return it }
        val result = adb(listOf("shell", "getprop", "ro.build.version.sdk"), INPUT_TIMEOUT_SECONDS)
        val sdk = if (!result.timedOut && result.exitCode == 0) parseSdkLevel(result.stdoutText()) else null
        androidApiLevel = sdk
        // An answer that cannot be read is not a reason to refuse: try, and the output check of the run itself catches an old device.
        return (sdk == null || sdk >= MOTION_EVENT_MIN_API).also { motionEventSupport = it }
    }

    @Suppress("TooGenericExceptionCaught") // A gesture must always try to lift the finger again, whatever failed.
    private suspend fun playGestureLocked(points: List<GesturePoint>, current: DeviceScreenCoordinateSpace): GesturePlayResult {
        val device = points.map { point ->
            mapDisplayedScreenCoordinatesToDevice(point.x, point.y, current).let { (x, y) -> GesturePoint(x, y, point.tMs) }
        }
        val duration = device.last().tMs
        val hold = gestureHoldMs(device)
        if (!motionEventsSupported()) {
            val why = "Android API ${androidApiLevel ?: "?"} has no `input motionevent` (it needs API $MOTION_EVENT_MIN_API)"
            return swipeFallbackLocked(points, device, hold, why)
        }
        val script = gestureShellScript(device)
        val result = try {
            adb(listOf("shell", script), gestureTimeoutSeconds(device))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            liftFingerAfterInterruption(device)
            throw cancelled
        } catch (failure: Exception) {
            liftFingerAfterInterruption(device)
            throw failure
        }
        if (result.timedOut) {
            liftFingerAfterInterruption(device)
            error("The bounded adb gesture command timed out")
        }
        val output = (result.stdoutText() + "\n" + result.stderrText()).trim()
        if (mayFallbackAfterMotionScript(output)) {
            motionEventSupport = false
            return swipeFallbackLocked(points, device, hold, "this device does not know `input motionevent`")
        }
        if (looksLikeMotionEventUnsupported(output)) {
            liftFingerAfterInterruption(device)
            error("A device rejected a motion event after touch-down; the gesture was not retried to avoid a second action.")
        }
        if (result.exitCode != 0) {
            liftFingerAfterInterruption(device)
            error("adb device input failed: ${output.take(ADB_ERROR_MESSAGE_MAX_CHARS)}")
        }
        return GesturePlayResult(points, device, GestureMode.MOTION_EVENTS, duration, hold)
    }

    private suspend fun swipeFallbackLocked(
        points: List<GesturePoint>,
        device: List<GesturePoint>,
        hold: Long,
        why: String,
    ): GesturePlayResult {
        runInputLocked(gestureSwipeFallback(device, SWIPE_DURATION_MIN_MS, SWIPE_DURATION_MAX_MS))
        val lost = if (device.size > 2 || hold > 0L) {
            "; the intermediate points, the timing along the path and the hold before release were not reproduced"
        } else {
            ""
        }
        val swipeMs = device.last().tMs.coerceIn(SWIPE_DURATION_MIN_MS.toLong(), SWIPE_DURATION_MAX_MS.toLong())
        val note = "Fallback: $why, so the gesture was approximated by a straight `input swipe` from its first to its last point over $swipeMs ms$lost."
        return GesturePlayResult(points, device, GestureMode.SWIPE_FALLBACK, device.last().tMs, hold, note)
    }

    /** Best effort, never throws: a gesture that was interrupted may have left the finger down; a CANCEL puts it up again. */
    private suspend fun liftFingerAfterInterruption(device: List<GesturePoint>) {
        withContext(NonCancellable) {
            runCatching { adb(listOf("shell") + gestureCancelCommand(device.last()), INPUT_TIMEOUT_SECONDS) }
        }
    }

    private fun validateInputAction(
        action: DeviceInputAction,
        current: DeviceScreenCoordinateSpace,
        maxDelayMs: Long = MAX_SEQUENCE_ACTION_DELAY_MS,
    ) {
        fun point(x: Int, y: Int) {
            require(x in 0 until current.imageWidth && y in 0 until current.imageHeight) {
                "Input coordinates must be inside the current screenshot (${current.imageWidth}x${current.imageHeight})."
            }
        }
        when (action) {
            is DeviceInputAction.Tap -> point(action.x, action.y)
            is DeviceInputAction.Swipe -> {
                point(action.x1, action.y1)
                point(action.x2, action.y2)
                requireDeviceSwipe(action.x1, action.y1, action.x2, action.y2, action.durationMs, current.imageWidth, current.imageHeight)
            }
            is DeviceInputAction.Key -> require(DEVICE_KEY_CODES.containsKey(action.key.trim().uppercase())) {
                "Supported keys are ${DEVICE_KEY_CODES.keys.joinToString(", ")}."
            }
            is DeviceInputAction.Gesture -> {
                action.points.forEach { point(it.x, it.y) }
                gestureProblem(action.points)?.let { throw IllegalArgumentException(it) }
            }
            is DeviceInputAction.Panel -> Unit
        }
        require(action.delayMs in 0L..maxDelayMs) {
            "Each input delay must be between 0 and $maxDelayMs ms."
        }
    }

    private fun inputCommand(action: DeviceInputAction, current: DeviceScreenCoordinateSpace): List<String> = when (action) {
        is DeviceInputAction.Tap -> mapDisplayedScreenCoordinatesToDevice(action.x, action.y, current).let { (x, y) ->
            listOf("input", "tap", x.toString(), y.toString())
        }
        is DeviceInputAction.Swipe -> {
            val (x1, y1) = mapDisplayedScreenCoordinatesToDevice(action.x1, action.y1, current)
            val (x2, y2) = mapDisplayedScreenCoordinatesToDevice(action.x2, action.y2, current)
            listOf("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), action.durationMs.toString())
        }
        is DeviceInputAction.Key -> listOf("input", "keyevent", DEVICE_KEY_CODES.getValue(action.key.trim().uppercase()))
        is DeviceInputAction.Panel -> listOf("cmd", "statusbar", action.panel.command)
        is DeviceInputAction.Gesture -> throw IllegalArgumentException("A gesture is played by playGestureLocked, not as one command.")
    }

    /** A bounded uiautomator read for wait_for_ui; timeout/error is distinct from a successful tree with no selector. */
    @Suppress("TooGenericExceptionCaught") // Convert adapter-specific read failures to unavailable evidence, never absence.
    suspend fun readUiTreeBounded(timeoutMs: Long = MAX_UI_TREE_READ_TIMEOUT_MS): UiTreeRead {
        require(timeoutMs in 1..MAX_UI_TREE_READ_TIMEOUT_MS) {
            "A UI tree read must be bounded to $MAX_UI_TREE_READ_TIMEOUT_MS ms."
        }
        val result = try {
            runInterruptible(Dispatchers.IO) {
                tools.runAdb(serial, UI_DUMP_COMMAND, Duration.ofMillis(timeoutMs), MAX_UI_DUMP_BYTES)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return UiTreeRead.Unavailable(failure.message ?: "The UI tree could not be read.")
        }
        if (result.timedOut) return UiTreeRead.Unavailable("The UI tree read timed out.")
        if (result.exitCode != 0) return UiTreeRead.Unavailable("The UI tree command exited with status ${result.exitCode}.")
        val parsed = parseUiAutomatorDump(result.stdoutText())
            ?: return UiTreeRead.Unavailable("The device returned no UI hierarchy.")
        return UiTreeRead.Available(parsed)
    }

    private suspend fun adb(arguments: List<String>, timeoutSeconds: Long, outputLimitBytes: Int? = null) = runInterruptible(Dispatchers.IO) {
        if (outputLimitBytes == null) tools.runAdb(serial, arguments, Duration.ofSeconds(timeoutSeconds))
        else tools.runAdb(serial, arguments, Duration.ofSeconds(timeoutSeconds), outputLimitBytes)
    }

    // ── UI tree ──────────────────────────────────────────────────────

    /**
     * The visible UI as a compact node list. Bounds and tap points are converted into screenshot pixels, so an agent
     * can pass a node's `tapX`/`tapY` straight to [tap]. A screenshot is taken first when the agent has none yet or the
     * screen size changed since (rotation), so the conversion is never stale.
     */
    suspend fun dumpUiTree(): UiTreeSnapshot {
        val result = adb(UI_DUMP_COMMAND, UI_DUMP_TIMEOUT_SECONDS, MAX_UI_DUMP_BYTES)
        check(!result.timedOut) { "The UI dump timed out" }
        val parsed = parseUiAutomatorDump(result.stdoutText())
            ?: error("The device returned no UI hierarchy: ${(result.stderrText() + result.stdoutText()).trim().take(ADB_ERROR_MESSAGE_MAX_CHARS)}")
        var current = currentSpace()
        if (parsed.screenWidth > 0 && (current.deviceWidth != parsed.screenWidth || current.deviceHeight != parsed.screenHeight)) {
            current = screenshot().space
        }
        val views = parsed.nodes.map { node ->
            UiTreeNodeView(
                node = node,
                bounds = listOf(
                    toImage(node.left, current.deviceWidth, current.imageWidth),
                    toImage(node.top, current.deviceHeight, current.imageHeight),
                    toImage(node.right, current.deviceWidth, current.imageWidth),
                    toImage(node.bottom, current.deviceHeight, current.imageHeight),
                ),
                tapX = toImage(node.centerX, current.deviceWidth, current.imageWidth),
                tapY = toImage(node.centerY, current.deviceHeight, current.imageHeight),
            )
        }
        return UiTreeSnapshot(current.imageWidth, current.imageHeight, views, parsed.totalNodes, parsed.truncated)
    }

    /** The inverse of [mapDisplayedScreenCoordinatesToDevice]: a device pixel position as a screenshot pixel position. */
    private fun toImage(value: Int, deviceSize: Int, imageSize: Int): Int =
        if (deviceSize <= 1 || imageSize <= 1) 0 else (value.toDouble() * (imageSize - 1) / (deviceSize - 1)).roundToInt().coerceIn(0, imageSize - 1)

    /** Map a bounded-read node to the latest screenshot coordinate space, matching [dumpUiTree]. */
    suspend fun mapUiNodeToScreenshot(node: UiNode, treeWidth: Int, treeHeight: Int): UiTreeNodeView {
        var current = currentSpace()
        if (treeWidth > 0 && treeHeight > 0 && (current.deviceWidth != treeWidth || current.deviceHeight != treeHeight)) {
            current = screenshot().space
        }
        return UiTreeNodeView(
            node,
            listOf(
                toImage(node.left, current.deviceWidth, current.imageWidth),
                toImage(node.top, current.deviceHeight, current.imageHeight),
                toImage(node.right, current.deviceWidth, current.imageWidth),
                toImage(node.bottom, current.deviceHeight, current.imageHeight),
            ),
            toImage(node.centerX, current.deviceWidth, current.imageWidth),
            toImage(node.centerY, current.deviceHeight, current.imageHeight),
        )
    }

    // ── Log ──────────────────────────────────────────────────────────

    /**
     * The log's size right now, after flushing the recorder's buffers, as a marker to read from later. Rows written
     * after this call have offsets at or beyond it.
     */
    suspend fun logMarker(): Long = withContext(Dispatchers.IO) { capture.flushedLogLength() }

    /** Complete rows written at or after [offset], at most [limit] (1..500), optionally only those matching [tag] and [regex]. */
    suspend fun readLogSince(offset: Long, limit: Int = DEFAULT_LOG_READ_ROWS, tag: String? = null, regex: String? = null): LogRead =
        withContext(Dispatchers.IO) {
            val matcher = LogRowMatcher(regex, tag)
            val wanted = limit.coerceIn(1, MAX_LOG_READ_ROWS)
            val rows = ArrayList<LogEntry>()
            var cursor = offset
            var scanned = 0L
            while (rows.size < wanted && scanned < MAX_LOG_SCAN_BYTES) {
                val chunk = logReader.readRows(cursor)
                if (chunk.rows.isEmpty()) break
                scanned += chunk.endOffset - cursor
                for (row in chunk.rows) {
                    cursor = row.endOffset
                    if (matcher.matches(row.entry)) rows += row.entry
                    if (rows.size == wanted) break
                }
                if (rows.size < wanted) cursor = chunk.endOffset
            }
            val stoppedEarly = rows.size == wanted || scanned >= MAX_LOG_SCAN_BYTES
            LogRead(rows, cursor, more = stoppedEarly && cursor < logReader.length())
        }

    /**
     * Waits up to [timeoutMs] for a row (written at or after [sinceOffset]) whose message matches [regex], optionally
     * restricted to [tag]. Rows already in the file count. Polls the log file; never blocks a thread while it waits.
     */
    suspend fun waitForLog(regex: String, tag: String?, sinceOffset: Long, timeoutMs: Long): LogWaitResult {
        require(timeoutMs in 0..MAX_LOG_WAIT_MS) { "timeoutMs must be between 0 and $MAX_LOG_WAIT_MS" }
        val matcher = LogRowMatcher(regex, tag)
        val startedNanos = System.nanoTime()
        var cursor = sinceOffset
        var flushedAtDeadline = false
        while (true) {
            val scan = scanForMatch(matcher, cursor)
            cursor = scan.cursor
            scan.match?.let { return LogWaitResult.Matched(it.entry, it.endOffset, elapsedMs(startedNanos)) }
            if (scan.sawRows) continue
            val remaining = timeoutMs - elapsedMs(startedNanos)
            if (remaining <= 0) {
                // Rows still in the recorder's buffer must count; flush once, then do a last scan.
                if (flushedAtDeadline) return LogWaitResult.TimedOut(cursor, elapsedMs(startedNanos), isRecording)
                logMarker()
                flushedAtDeadline = true
                continue
            }
            delay(minOf(LOG_POLL_INTERVAL_MS, remaining))
        }
    }

    /** Watches [forMs] from [sinceOffset]: fails as soon as a matching row appears, passes when none did for the whole window. */
    suspend fun ensureAbsent(regex: String, tag: String?, sinceOffset: Long, forMs: Long): LogAbsentResult {
        require(forMs in 0..MAX_LOG_WAIT_MS) { "forMs must be between 0 and $MAX_LOG_WAIT_MS" }
        val matcher = LogRowMatcher(regex, tag)
        val startedNanos = System.nanoTime()
        var cursor = sinceOffset
        var flushedAtDeadline = false
        while (true) {
            val scan = scanForMatch(matcher, cursor)
            cursor = scan.cursor
            scan.match?.let { return LogAbsentResult.Found(it.entry, elapsedMs(startedNanos)) }
            if (scan.sawRows) continue
            val remaining = forMs - elapsedMs(startedNanos)
            if (remaining <= 0) {
                if (flushedAtDeadline) return LogAbsentResult.Absent(cursor, elapsedMs(startedNanos))
                logMarker()
                flushedAtDeadline = true
                continue
            }
            delay(minOf(LOG_POLL_INTERVAL_MS, remaining))
        }
    }

    private class MatchScan(val match: LogRow?, val cursor: Long, val sawRows: Boolean)

    /** Reads one chunk from [cursor]; [MatchScan.sawRows] says there may be more to read right away. */
    private suspend fun scanForMatch(matcher: LogRowMatcher, cursor: Long): MatchScan = withContext(Dispatchers.IO) {
        val chunk = logReader.readRows(cursor)
        val hit = chunk.rows.firstOrNull { matcher.matches(it.entry) }
        MatchScan(hit, if (hit != null) hit.endOffset else chunk.endOffset, chunk.rows.isNotEmpty())
    }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI

    // ── Lifecycle ────────────────────────────────────────────────────

    /** Stops the recording (finalizing the log and any video) on an IO thread; interruptible, so a bounded caller can give up. Safe to call more than once. */
    suspend fun close() {
        runInterruptible(Dispatchers.IO) { closeBlocking() }
    }

    /** For callers that are already off the UI and request threads (an IO lane's own cleanup, tests). */
    fun closeBlocking() {
        restoreRotationBlocking()
        capture.stop()
    }

    /** Puts back the rotation settings the lane changed; best effort, bounded, and only when the lane rotated the device. */
    private fun restoreRotationBlocking() {
        val before = rotationRestore.getAndSet(null) ?: return
        // The user rotation first, so the display does not jump when auto-rotate comes back on.
        restoreSystemSetting(USER_ROTATION_SETTING, before.user)
        restoreSystemSetting(ACCELEROMETER_ROTATION_SETTING, before.accelerometer)
    }

    private fun restoreSystemSetting(key: String, value: Int?) {
        val arguments = if (value == null) {
            listOf("shell", "settings", "delete", "system", key)
        } else {
            listOf("shell", "settings", "put", "system", key, value.toString())
        }
        runCatching { tools.runAdb(serial, arguments, Duration.ofSeconds(INPUT_TIMEOUT_SECONDS)) }
    }

    companion object {
        /**
         * Opens a lane on [serial] with the headless recorder: starts a logcat capture (and, with [recordVideo], the embedded screen
         * recording) under `<laneDir>/capture`. Refused with [IllegalArgumentException] when [isLiveCaptureSerial] says the live UI
         * capture holds the device. [recorderFactory] is the seam tests use for a recorder with a fast watchdog. Production lanes use
         * [forCapture] with a real capture controller instead.
         */
        @Suppress("TooGenericExceptionCaught") // Any start failure must close the half-open recorder, then reach the caller unchanged.
        suspend fun open(
            serial: String,
            laneDir: File,
            tools: CaptureTools,
            runner: CaptureProcessRunner = ProcessBuilderCaptureRunner(),
            recordVideo: Boolean = false,
            isLiveCaptureSerial: (String) -> Boolean = { false },
            recorderFactory: (File, CaptureProcessRunner) -> CaptureRecorder = { root, processRunner -> CaptureRecorder(root, processRunner) },
        ): TestDeviceSession = withContext(Dispatchers.IO) {
            require(serial.isNotBlank()) { "Device serial cannot be blank" }
            require(!isLiveCaptureSerial(serial)) { "Device $serial $LIVE_CAPTURE_REFUSAL" }
            val root = File(laneDir, LANE_CAPTURE_DIRECTORY).apply { mkdirs() }
            val recorder = recorderFactory(root, runner)
            val settings = CaptureSettings(recordVideo = recordVideo, includeBufferedLogs = false).withMirrorMode(CaptureMirrorMode.DISABLED)
            val started = try {
                recorder.start(CaptureDevice(serial, state = "device"), settings, tools)
            } catch (failure: Exception) {
                runCatching { recorder.close() }
                throw failure
            }
            TestDeviceSession(serial, laneDir, tools, StandaloneLaneCapture(recorder, started))
        }

        /** A lane on [serial] that records through [capture] (already started), driving the device through [tools]. */
        fun forCapture(serial: String, laneDir: File, tools: CaptureTools, capture: LaneCapture): TestDeviceSession {
            require(serial.isNotBlank()) { "Device serial cannot be blank" }
            return TestDeviceSession(serial, laneDir, tools, capture)
        }
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
