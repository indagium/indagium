package com.indagium.testing.authoring

import com.indagium.capture.CaptureCommandResult
import com.indagium.testing.device.MAX_UI_DUMP_BYTES
import com.indagium.testing.device.UI_DUMP_COMMAND
import com.indagium.testing.device.UI_DUMP_TIMEOUT_SECONDS
import com.indagium.testing.device.UiNode
import com.indagium.testing.device.parseUiAutomatorDump
import java.time.Duration

// What the recorder knows about the screen around one input. A probe is one bounded adb read (UI hierarchy, top activity,
// screenshot); the recording session decides when to run it and which input each result belongs to. Nothing here sends
// input to a device. Everything is best effort: any part may be missing and the caller keeps what did arrive.

/** The kind of device input a recorded row came from. */
enum class RecordedInputKind { TAP, LONG_PRESS, SWIPE, KEY, TEXT, BACK }

/** One sampled touch position in mirror coordinates, with the mirror screen size they refer to. */
data class RecordedPoint(val x: Int, val y: Int, val screenWidth: Int, val screenHeight: Int)

/** Where a recorded screenshot came from. Mirror timestamps are presentation times, not wall-clock acquisition times. */
enum class RecordingScreenshotSource { SCREEN_PROBE, ADB_INPUT, MIRROR_INPUT }

/** A screenshot whose acquisition interval and source stay separate from the UI/activity reading around it. */
data class RecordingScreenshotEvidence(
    val jpeg: ByteArray,
    val source: RecordingScreenshotSource,
    val acquiredAtMs: Long?,
    val acquisitionFinishedAtMs: Long?,
    val timingUncertain: Boolean,
)

/** The element under a tap, as the UI hierarchy described it. */
data class TappedElement(val text: String, val contentDesc: String, val resourceId: String, val className: String) {
    /** A short human label: the visible text, else the content description, else the id without its package, else the class. */
    fun label(): String = text.ifBlank { contentDesc }.ifBlank { resourceId.substringAfter(":id/") }.ifBlank { className }.take(MAX_ELEMENT_LABEL_CHARS)
}

/** A pointer to a stored [RecordingScreenState] plus the two facts a row hint needs without a lookup. */
data class RecordingScreenRef(val seq: Int, val packageName: String?, val activity: String?)

/**
 * One probe result. [startedAt]/[finishedAt] cover the complete UI/activity read; the optional screenshot timestamps cover
 * only the image acquisition and exclude encoding. Older in-memory states without screenshot times conservatively use the
 * complete probe interval for screenshot eligibility. [seq] is assigned when the state is stored.
 */
data class RecordingScreenState(
    val seq: Int,
    val startedAt: Long,
    val finishedAt: Long,
    val packageName: String?,
    val activity: String?,
    val nodes: List<UiNode>,
    val screenWidth: Int,
    val screenHeight: Int,
    val screenshotJpeg: ByteArray? = null,
    val screenshotStartedAt: Long? = null,
    val screenshotFinishedAt: Long? = null,
) {
    fun ref(): RecordingScreenRef = RecordingScreenRef(seq, packageName, activity)

    /**
     * True when a password field was probably being edited: a focused password node, or a password node on a screen where
     * no other node reports focus (the dump gives no better answer, so the text is hidden rather than guessed).
     */
    fun passwordFieldLikelyEdited(): Boolean {
        if (nodes.none { it.password }) return false
        val focused = nodes.filter { it.focused }
        return focused.isEmpty() || focused.any { it.password }
    }
}

/** An `adb -s <serial>` runner: arguments after the serial, timeout, stdout cap. */
fun interface RecordingAdb {
    fun run(arguments: List<String>, timeout: Duration, outputLimitBytes: Int): CaptureCommandResult
}

/**
 * Reads the screen once through adb. Never throws and never blocks the caller beyond the bounded commands; a part that
 * fails is left out. [read] returns null only when nothing at all could be read.
 */
class RecordingScreenProbe(
    private val adb: RecordingAdb,
    private val screencap: (() -> ByteArray)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun read(): RecordingScreenState? = readInternal(null)

    /** Sends bounded screenshot evidence to [onScreenshotAcquired] before the slower hierarchy/activity reads begin. */
    fun read(onScreenshotAcquired: (RecordingScreenshotEvidence) -> Unit): RecordingScreenState? = readInternal(onScreenshotAcquired)

    private fun readInternal(onScreenshotAcquired: ((RecordingScreenshotEvidence) -> Unit)?): RecordingScreenState? {
        // Read the image first so a slow hierarchy/activity command cannot make a useful, quick frame appear later than it was.
        // The interval brackets capture only; bounding/resizing the PNG happens after the timestamps have been recorded.
        val screenshotStartedAt = if (screencap != null) clock() else null
        val screenshotPng = if (Thread.currentThread().isInterrupted) null else readScreenshotBytes()
        val screenshotFinishedAt = if (screencap != null) clock() else null
        val screenshot = screenshotPng?.let { runCatching { encodeRecordingScreencap(it) }.getOrNull() }
        if (screenshot != null && screenshotStartedAt != null && screenshotFinishedAt != null) {
            runCatching {
                onScreenshotAcquired?.invoke(
                    RecordingScreenshotEvidence(
                        jpeg = screenshot,
                        source = RecordingScreenshotSource.SCREEN_PROBE,
                        acquiredAtMs = screenshotStartedAt,
                        acquisitionFinishedAtMs = screenshotFinishedAt,
                        timingUncertain = false,
                    ),
                )
            }
        }

        val startedAt = clock()
        val tree = readTree()
        val activity = if (Thread.currentThread().isInterrupted) null else readTopActivity()
        val finishedAt = clock()
        val packageName = activity?.first ?: tree?.packageName?.takeIf(String::isNotBlank)
        if (tree == null && activity == null && screenshot == null) return null
        return RecordingScreenState(
            seq = 0,
            startedAt = startedAt,
            finishedAt = finishedAt,
            packageName = packageName,
            activity = activity?.second,
            nodes = tree?.nodes.orEmpty(),
            screenWidth = tree?.screenWidth ?: 0,
            screenHeight = tree?.screenHeight ?: 0,
            screenshotJpeg = screenshot,
            screenshotStartedAt = screenshotStartedAt,
            screenshotFinishedAt = screenshotFinishedAt,
        )
    }

    private fun readTree() = runCatching {
        adb.run(UI_DUMP_COMMAND, Duration.ofSeconds(UI_DUMP_TIMEOUT_SECONDS), MAX_UI_DUMP_BYTES)
    }.getOrNull()?.takeUnless { it.timedOut }?.let { parseUiAutomatorDump(it.stdoutText()) }

    private fun readTopActivity(): Pair<String, String>? = runCatching {
        adb.run(ACTIVITY_DUMP_COMMAND, Duration.ofSeconds(ACTIVITY_DUMP_TIMEOUT_SECONDS), MAX_ACTIVITY_DUMP_BYTES)
    }.getOrNull()?.takeIf { !it.timedOut && it.exitCode == 0 }?.let { parseTopActivity(it.stdoutText()) }

    private fun readScreenshotBytes(): ByteArray? = screencap?.let { capture -> runCatching(capture).getOrNull() }
}

private val ACTIVITY_DUMP_COMMAND = listOf("shell", "dumpsys", "activity", "activities")
private const val ACTIVITY_DUMP_TIMEOUT_SECONDS = 5L
private const val MAX_ACTIVITY_DUMP_BYTES = 2 * 1024 * 1024
private const val MAX_ELEMENT_LABEL_CHARS = 80

// `topResumedActivity=ActivityRecord{1a2b u0 com.app/.Main t12}` (Android 10+), `ResumedActivity: ActivityRecord{...}` and the
// older `mResumedActivity: ActivityRecord{...}`; a `null` record never matches.
private val TOP_RESUMED_ACTIVITY = Regex("topResumedActivity=ActivityRecord\\{\\S+ u\\d+ ([\\w.]+)/([\\w.\\$]+)")
private val RESUMED_ACTIVITY = Regex("[mM]?ResumedActivity:\\s*ActivityRecord\\{\\S+ u\\d+ ([\\w.]+)/([\\w.\\$]+)")

/** The (package, activity) of the foreground activity in `dumpsys activity activities` output, or null when none is named. */
internal fun parseTopActivity(output: String): Pair<String, String>? {
    val match = TOP_RESUMED_ACTIVITY.find(output) ?: RESUMED_ACTIVITY.find(output) ?: return null
    return match.groupValues[1] to match.groupValues[2]
}

/**
 * The element a tap at [point] landed on, or null when the state has no hierarchy or the point cannot be mapped.
 * The point is scaled from the mirror's screen size to the dump's pixel size. The smallest node under the point wins when
 * it carries a label (text, content description or id); otherwise its nearest labelled clickable ancestor; otherwise the
 * smallest clickable node under the point.
 */
internal fun resolveTappedElement(state: RecordingScreenState, point: RecordedPoint): TappedElement? {
    if (state.nodes.isEmpty() || state.screenWidth <= 0 || state.screenHeight <= 0) return null
    if (point.screenWidth <= 0 || point.screenHeight <= 0) return null
    if ((state.screenWidth > state.screenHeight) != (point.screenWidth > point.screenHeight)) return null // rotated between mirror and dump
    val x = point.x.toLong() * state.screenWidth / point.screenWidth
    val y = point.y.toLong() * state.screenHeight / point.screenHeight
    val under = state.nodes.filter { x >= it.left && x < it.right && y >= it.top && y < it.bottom }
    // Later nodes are drawn above earlier ones, so a tie on size goes to the later node.
    val smallest = under.withIndex().minWithOrNull(compareBy({ area(it.value) }, { -it.index }))?.value ?: return null
    val chosen = smallest.takeIf { it.hasLabel() }
        ?: under.filter { it !== smallest && it.hasLabel() && it.clickable && contains(it, smallest) }.minByOrNull(::area)
        ?: under.filter { it.clickable }.minByOrNull(::area)
        ?: return null
    return TappedElement(chosen.text, chosen.contentDesc, chosen.resourceId, chosen.className)
}

private fun UiNode.hasLabel() = text.isNotBlank() || contentDesc.isNotBlank() || resourceId.isNotBlank()

private fun area(node: UiNode): Long = (node.right - node.left).toLong() * (node.bottom - node.top)

private fun contains(outer: UiNode, inner: UiNode) =
    outer.left <= inner.left && outer.top <= inner.top && outer.right >= inner.right && outer.bottom >= inner.bottom
