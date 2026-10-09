package com.indagium.testing.authoring

import com.indagium.testing.device.MAX_UI_DUMP_BYTES
import com.indagium.testing.device.UI_DUMP_COMMAND
import com.indagium.testing.device.UiNode
import com.indagium.testing.device.parseUiAutomatorDump
import java.time.Duration

// What the device input recorder asks the device besides the event stream: the display size and rotation, whether the on-screen
// keyboard is up and where, and the text of the focused field. Every command is a fixed adb argument list (no user or device text
// is ever put into one) with a timeout and an output cap. The parsers are pure and take the dump text.

internal val GETEVENT_DEVICES_COMMAND = listOf("shell", "getevent", "-lp")

// `-tt` forces a pty: without one getevent's stdout is a pipe and the device's stdio buffers it in blocks, so events arrive late and
// the last ones before Stop are lost when the process is killed. A pty also makes killing the adb client hang up getevent reliably.
// Lines then end in CR LF; parseGeteventLine trims the CR.
internal val GETEVENT_STREAM_COMMAND = listOf("shell", "-tt", "getevent", "-lt")
internal val WM_SIZE_COMMAND = listOf("shell", "wm", "size")
internal val DISPLAY_ROTATION_COMMAND = listOf("shell", "dumpsys", "window", "displays")
internal val IME_STATE_COMMAND = listOf("shell", "dumpsys", "input_method")
internal val IME_WINDOW_COMMAND = listOf("shell", "dumpsys", "window", "windows")

private const val DEVICE_LIST_TIMEOUT_SECONDS = 8L
private const val SMALL_COMMAND_TIMEOUT_SECONDS = 5L
private const val FIELD_READ_TIMEOUT_SECONDS = 8L
private const val MAX_DEVICE_LIST_BYTES = 512 * 1024
private const val MAX_SMALL_DUMP_BYTES = 1024 * 1024
private const val MAX_WINDOW_DUMP_BYTES = 6 * 1024 * 1024
private const val QUARTER_TURN_DEGREES = 90
private const val QUARTER_TURNS = 4

/** A rectangle in logical display pixels; [contains] treats the right and bottom edges as outside. */
internal data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** Whether the on-screen keyboard is up, and its window frame when the device said where it is. */
internal data class ImeState(val shown: Boolean, val frame: ScreenRect?)

/** The focused editable field of a UI dump. A password field never carries its text. */
internal data class FocusedField(val text: String, val label: String, val password: Boolean)

private val WM_SIZE = Regex("(Override|Physical) size:\\s*(\\d+)x(\\d+)")

/** The natural display size from `wm size`; an override size (what the screen currently shows) wins over the physical one. */
internal fun parseWmSize(output: String): DisplayGeometry? {
    val matches = WM_SIZE.findAll(output).toList()
    val chosen = matches.firstOrNull { it.groupValues[1] == "Override" } ?: matches.firstOrNull() ?: return null
    val width = chosen.groupValues[2].toIntOrNull() ?: return null
    val height = chosen.groupValues[3].toIntOrNull() ?: return null
    return if (width > 0 && height > 0) DisplayGeometry(width, height) else null
}

private val ROTATION_NAMED = Regex("\\b(?:mCurrentRotation|mRotation|rotation)=ROTATION_(0|90|180|270)\\b")
private val ROTATION_INDEX = Regex("\\b(?:mCurrentRotation|mRotation)=([0-3])\\b")

/** The display rotation in quarter turns (0..3) from `dumpsys window displays`; null when the dump names none. */
internal fun parseDisplayRotation(output: String): Int? {
    ROTATION_NAMED.find(output)?.let { return it.groupValues[1].toInt() / QUARTER_TURN_DEGREES }
    return ROTATION_INDEX.find(output)?.groupValues?.get(1)?.toInt()
}

private val IME_SHOWN_TRUE = Regex("\\b(?:mInputShown|mIsInputViewShown|isInputViewShown)=true\\b")
private val IME_SHOWN_FALSE = Regex("\\b(?:mInputShown|mIsInputViewShown|isInputViewShown)=false\\b")

/** True or false from `dumpsys input_method` when it says whether the keyboard is showing; null when it says nothing either way. */
internal fun parseImeShown(output: String): Boolean? = when {
    IME_SHOWN_TRUE.containsMatchIn(output) -> true
    IME_SHOWN_FALSE.containsMatchIn(output) -> false
    else -> null
}

private val WINDOW_HEADER = Regex("^\\s*Window #\\d+ Window\\{[^}]*\\}")
private val IME_WINDOW_HEADER = Regex("^\\s*Window #\\d+ Window\\{[^}]*\\bInputMethod\\}")
private val FRAME_BRACKETS = Regex("\\b(?:mFrame|frame)=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
private val FRAME_RECT = Regex("\\b(?:mFrame|frame)=Rect\\((-?\\d+), (-?\\d+) - (-?\\d+), (-?\\d+)\\)")

/** The InputMethod window frame from `dumpsys window windows` in logical display pixels; null when no such window or frame is listed. */
internal fun parseImeWindowFrame(output: String): ScreenRect? {
    var inIme = false
    for (line in output.lineSequence()) {
        if (WINDOW_HEADER.containsMatchIn(line)) inIme = IME_WINDOW_HEADER.containsMatchIn(line)
        if (!inIme) continue
        val match = FRAME_BRACKETS.find(line) ?: FRAME_RECT.find(line) ?: continue
        val numbers = match.groupValues.drop(1).filter(String::isNotEmpty).map(String::toInt)
        val rect = ScreenRect(numbers[0], numbers[1], numbers[2], numbers[3])
        if (rect.right > rect.left && rect.bottom > rect.top) return rect
    }
    return null
}

private val EDITOR_CLASSES = listOf("EditText", "AutoComplete", "TextInput")

/** The field the user is typing into: the first focused node that is a password field or an edit control; null when none is focused. */
internal fun focusedFieldOf(nodes: List<UiNode>): FocusedField? {
    val node = nodes.firstOrNull { it.focused && (it.password || EDITOR_CLASSES.any { name -> it.className.contains(name) }) } ?: return null
    val label = node.resourceId.substringAfter(":id/").ifBlank { node.contentDesc }
    return FocusedField(if (node.password) "" else node.text, label, node.password)
}

/**
 * The text a keyboard burst added, given the field text [baseline] read before it (null when unknown) and [final] read after it.
 * Null when nothing was added (the field is unchanged or empty); the whole field text when the baseline is not its prefix.
 */
internal fun typedTextDelta(baseline: String?, final: String): String? = when {
    final.isEmpty() -> null
    baseline != null && final == baseline -> null
    !baseline.isNullOrEmpty() && final.startsWith(baseline) -> final.removePrefix(baseline).ifEmpty { null }
    else -> final
}

/** The adb reads the recorder makes besides the event stream. All are blocking and bounded; each returns null when it failed. */
internal interface DeviceInputEnvironment {
    fun rotation(): Int?

    fun imeShown(): Boolean?

    fun imeFrame(): ScreenRect?

    fun focusedField(): FocusedField?
}

/** [DeviceInputEnvironment] over adb. Never throws. */
internal class AdbDeviceInputEnvironment(private val adb: RecordingAdb) : DeviceInputEnvironment {
    override fun rotation(): Int? =
        read(DISPLAY_ROTATION_COMMAND, SMALL_COMMAND_TIMEOUT_SECONDS, MAX_SMALL_DUMP_BYTES)?.let(::parseDisplayRotation)

    override fun imeShown(): Boolean? = read(IME_STATE_COMMAND, SMALL_COMMAND_TIMEOUT_SECONDS, MAX_SMALL_DUMP_BYTES)?.let(::parseImeShown)

    override fun imeFrame(): ScreenRect? = read(IME_WINDOW_COMMAND, SMALL_COMMAND_TIMEOUT_SECONDS, MAX_WINDOW_DUMP_BYTES)?.let(::parseImeWindowFrame)

    override fun focusedField(): FocusedField? =
        read(UI_DUMP_COMMAND, FIELD_READ_TIMEOUT_SECONDS, MAX_UI_DUMP_BYTES)?.let(::parseUiAutomatorDump)?.let { focusedFieldOf(it.nodes) }

    private fun read(arguments: List<String>, timeoutSeconds: Long, limit: Int): String? =
        runCatching { adb.run(arguments, Duration.ofSeconds(timeoutSeconds), limit) }.getOrNull()
            ?.takeIf { !it.timedOut }?.stdoutText()
}

/** The outcome of looking at a device before observing its input. */
internal sealed interface DeviceInputPreflight {
    /** [devices] are usable, [geometry] is null when the display size could not be read (then no touchscreen is listed). */
    data class Ready(
        val devices: GeteventDevices,
        val geometry: DisplayGeometry?,
        val rotation: Int,
        val warnings: List<String>,
    ) : DeviceInputPreflight

    data class Unavailable(val reason: String) : DeviceInputPreflight
}

/** Runs `getevent -lp` and `wm size` (plus one rotation read) through [adb]. Blocking and bounded: call off the UI thread. */
internal fun preflightDeviceInput(adb: RecordingAdb): DeviceInputPreflight {
    val listing = runCatching { adb.run(GETEVENT_DEVICES_COMMAND, Duration.ofSeconds(DEVICE_LIST_TIMEOUT_SECONDS), MAX_DEVICE_LIST_BYTES) }
        .getOrNull()
    if (listing == null || listing.timedOut || listing.exitCode != 0 || listing.stdout.isEmpty()) {
        return DeviceInputPreflight.Unavailable("getevent is not available to adb on this device.")
    }
    val found = GeteventDeviceParser.parse(listing.stdoutText())
    if (!found.isUsable) return DeviceInputPreflight.Unavailable("getevent lists no touchscreen or key input device.")
    val size = runCatching { adb.run(WM_SIZE_COMMAND, Duration.ofSeconds(SMALL_COMMAND_TIMEOUT_SECONDS), MAX_SMALL_DUMP_BYTES) }.getOrNull()
        ?.takeIf { !it.timedOut }?.let { parseWmSize(it.stdoutText()) }
    val warnings = ArrayList<String>()
    var devices = found
    if (size == null && found.touchScreens.isNotEmpty()) {
        if (found.keyDevices.isEmpty()) return DeviceInputPreflight.Unavailable("The display size could not be read from the device.")
        devices = found.copy(touchScreens = emptyList())
        warnings += "The display size could not be read, so touches on the device itself are not recorded; buttons and keys still are."
    }
    if (found.touchScreens.size > 1) {
        warnings += "${found.touchScreens.size} touchscreens were found; touches on all of them are mapped onto the main display."
    }
    val rotation = AdbDeviceInputEnvironment(adb).rotation()?.mod(QUARTER_TURNS) ?: 0
    return DeviceInputPreflight.Ready(devices, size, rotation, warnings)
}
