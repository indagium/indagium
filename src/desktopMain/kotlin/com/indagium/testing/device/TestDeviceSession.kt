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
import com.indagium.debug.encodeBoundedDeviceScreen
import com.indagium.debug.mapDisplayedScreenCoordinatesToDevice
import com.indagium.debug.requireDeviceSwipe
import com.indagium.debug.requireSafeAndroidInputText
import com.indagium.debug.requireSafeDeviceUrl
import com.indagium.debug.requireValidAndroidPackageName
import com.indagium.model.LogEntry
import com.indagium.testing.script.AdbScriptTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
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
private const val LAUNCH_TIMEOUT_SECONDS = 10L
private const val UI_DUMP_TIMEOUT_SECONDS = 20L
private const val MAX_UI_DUMP_BYTES = 4 * 1024 * 1024
private const val ADB_ERROR_MESSAGE_MAX_CHARS = 500
private const val LOG_POLL_INTERVAL_MS = 100L
private const val MAX_LOG_WAIT_MS = 10 * 60 * 1000L
private const val DEFAULT_LOG_READ_ROWS = 100
private const val MAX_LOG_READ_ROWS = 500
private const val MAX_LOG_SCAN_BYTES = 16L * 1024 * 1024
private const val DEFAULT_SWIPE_MS = 350
private const val LIVE_CAPTURE_REFUSAL = "is held by the live capture in the main window; stop that capture or choose another device."

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
    private val logReader = CaptureLogReader(capture.logFile)
    private val inputLock = Mutex()

    @Volatile
    private var space: DeviceScreenCoordinateSpace? = null

    val logFile: File get() = capture.logFile

    /** False once the logcat recording ended (device unplugged, storage limit): input still works, new log rows stop. */
    val isRecording: Boolean get() = capture.isRecording

    /** The recorder's own diagnostics (video start failures, adb stderr lines), for a run report. */
    val diagnostics: List<String> get() = capture.diagnostics

    fun adbTarget(): AdbScriptTarget = AdbScriptTarget(tools, serial)

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
            val result = adb(listOf("shell") + command, INPUT_TIMEOUT_SECONDS)
            check(!result.timedOut) { "The bounded adb input command timed out" }
            check(result.exitCode == 0) {
                "adb device input failed: ${(result.stderrText() + "\n" + result.stdoutText()).trim().take(ADB_ERROR_MESSAGE_MAX_CHARS)}"
            }
        }
    }

    private suspend fun adb(arguments: List<String>, timeoutSeconds: Long, outputLimitBytes: Int? = null) = withContext(Dispatchers.IO) {
        if (outputLimitBytes == null) {
            tools.runAdb(serial, arguments, Duration.ofSeconds(timeoutSeconds))
        } else {
            tools.runAdb(serial, arguments, Duration.ofSeconds(timeoutSeconds), outputLimitBytes)
        }
    }

    // ── UI tree ──────────────────────────────────────────────────────

    /**
     * The visible UI as a compact node list. Bounds and tap points are converted into screenshot pixels, so an agent
     * can pass a node's `tapX`/`tapY` straight to [tap]. A screenshot is taken first when the agent has none yet or the
     * screen size changed since (rotation), so the conversion is never stale.
     */
    suspend fun dumpUiTree(): UiTreeSnapshot {
        val result = adb(listOf("exec-out", "uiautomator", "dump", "/dev/tty"), UI_DUMP_TIMEOUT_SECONDS, MAX_UI_DUMP_BYTES)
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
        capture.stop()
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
