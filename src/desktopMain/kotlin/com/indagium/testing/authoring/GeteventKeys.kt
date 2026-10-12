@file:Suppress("MagicNumber") // Linux input label to Android keycode tables.

package com.indagium.testing.authoring

// Maps `getevent -l` key labels (KEY_BACK, KEY_A, ...) to what the recorder does with them, and merges printable keys from a hardware
// or emulator-host keyboard into typed text. Pure: no process, no clock of its own.

/** What one key label means to the recorder. */
internal sealed interface DeviceKey {
    /** A key that becomes a Key command with this Android keycode (Back, Home, Power, volume, Enter, ...). */
    data class Control(val keycode: Int) : DeviceKey

    /** A key that types a character: [plain] normally, [shifted] with Shift (letters also follow Caps Lock). */
    data class Printable(val plain: Char, val shifted: Char) : DeviceKey

    data object Shift : DeviceKey

    data object CapsLock : DeviceKey

    /** Ctrl, Alt or Meta: while one is held a printable key is a shortcut, not typing. */
    data object Modifier : DeviceKey

    data object Backspace : DeviceKey
}

internal const val KEYCODE_HOME = 3
internal const val KEYCODE_BACK = 4
internal const val KEYCODE_DPAD_UP = 19
internal const val KEYCODE_DPAD_DOWN = 20
internal const val KEYCODE_DPAD_LEFT = 21
internal const val KEYCODE_DPAD_RIGHT = 22
internal const val KEYCODE_VOLUME_UP = 24
internal const val KEYCODE_VOLUME_DOWN = 25
internal const val KEYCODE_POWER = 26
internal const val KEYCODE_TAB = 61
internal const val KEYCODE_ENTER = 66
internal const val KEYCODE_DELETE = 67
internal const val KEYCODE_MENU = 82
internal const val KEYCODE_MEDIA_PLAY_PAUSE = 85
internal const val KEYCODE_MEDIA_NEXT = 87
internal const val KEYCODE_MEDIA_PREVIOUS = 88
internal const val KEYCODE_ESCAPE = 111
internal const val KEYCODE_MEDIA_PLAY = 126
internal const val KEYCODE_MEDIA_PAUSE = 127
internal const val KEYCODE_VOLUME_MUTE = 164
internal const val KEYCODE_APP_SWITCH = 187

/** The name `press_key` (and so a REPLAY step) knows for an Android [keycode], or null for a key a lane cannot press. */
internal fun replayKeyNameFor(keycode: Int): String? = when (keycode) {
    KEYCODE_HOME -> "HOME"
    KEYCODE_BACK -> "BACK"
    KEYCODE_APP_SWITCH -> "RECENTS"
    KEYCODE_ENTER -> "ENTER"
    KEYCODE_DELETE -> "DEL"
    KEYCODE_TAB -> "TAB"
    KEYCODE_ESCAPE -> "ESCAPE"
    KEYCODE_DPAD_UP -> "DPAD_UP"
    KEYCODE_DPAD_DOWN -> "DPAD_DOWN"
    KEYCODE_DPAD_LEFT -> "DPAD_LEFT"
    KEYCODE_DPAD_RIGHT -> "DPAD_RIGHT"
    KEYCODE_VOLUME_UP -> "VOLUME_UP"
    KEYCODE_VOLUME_DOWN -> "VOLUME_DOWN"
    KEYCODE_VOLUME_MUTE -> "VOLUME_MUTE"
    KEYCODE_MENU -> "MENU"
    KEYCODE_POWER -> "POWER"
    KEYCODE_MEDIA_PLAY_PAUSE -> "MEDIA_PLAY_PAUSE"
    else -> null
}

private val CONTROL_KEYS: Map<String, Int> = mapOf(
    "KEY_BACK" to KEYCODE_BACK,
    "KEY_HOME" to KEYCODE_HOME,
    "KEY_HOMEPAGE" to KEYCODE_HOME,
    "KEY_APPSELECT" to KEYCODE_APP_SWITCH,
    "KEY_MENU" to KEYCODE_MENU,
    "KEY_POWER" to KEYCODE_POWER,
    "KEY_VOLUMEUP" to KEYCODE_VOLUME_UP,
    "KEY_VOLUMEDOWN" to KEYCODE_VOLUME_DOWN,
    "KEY_MUTE" to KEYCODE_VOLUME_MUTE,
    "KEY_PLAYPAUSE" to KEYCODE_MEDIA_PLAY_PAUSE,
    "KEY_PLAYCD" to KEYCODE_MEDIA_PLAY,
    "KEY_PAUSECD" to KEYCODE_MEDIA_PAUSE,
    "KEY_NEXTSONG" to KEYCODE_MEDIA_NEXT,
    "KEY_PREVIOUSSONG" to KEYCODE_MEDIA_PREVIOUS,
    "KEY_ENTER" to KEYCODE_ENTER,
    "KEY_KPENTER" to KEYCODE_ENTER,
    "KEY_TAB" to KEYCODE_TAB,
    "KEY_ESC" to KEYCODE_ESCAPE,
    "KEY_UP" to KEYCODE_DPAD_UP,
    "KEY_DOWN" to KEYCODE_DPAD_DOWN,
    "KEY_LEFT" to KEYCODE_DPAD_LEFT,
    "KEY_RIGHT" to KEYCODE_DPAD_RIGHT,
)

private val PRINTABLE_KEYS: Map<String, DeviceKey.Printable> = buildMap {
    for (letter in 'a'..'z') put("KEY_${letter.uppercaseChar()}", DeviceKey.Printable(letter, letter.uppercaseChar()))
    "1234567890".zip("!@#$%^&*()").forEachIndexed { index, (digit, symbol) ->
        put("KEY_${(index + 1) % 10}", DeviceKey.Printable(digit, symbol))
        put("KEY_KP${(index + 1) % 10}", DeviceKey.Printable(digit, digit))
    }
    put("KEY_SPACE", DeviceKey.Printable(' ', ' '))
    put("KEY_MINUS", DeviceKey.Printable('-', '_'))
    put("KEY_EQUAL", DeviceKey.Printable('=', '+'))
    put("KEY_LEFTBRACE", DeviceKey.Printable('[', '{'))
    put("KEY_RIGHTBRACE", DeviceKey.Printable(']', '}'))
    put("KEY_SEMICOLON", DeviceKey.Printable(';', ':'))
    put("KEY_APOSTROPHE", DeviceKey.Printable('\'', '"'))
    put("KEY_GRAVE", DeviceKey.Printable('`', '~'))
    put("KEY_BACKSLASH", DeviceKey.Printable('\\', '|'))
    put("KEY_COMMA", DeviceKey.Printable(',', '<'))
    put("KEY_DOT", DeviceKey.Printable('.', '>'))
    put("KEY_SLASH", DeviceKey.Printable('/', '?'))
}

/** The words a recorded row uses for a key: "Press Power". Keys not listed fall back to their number. */
internal val KEY_LABELS: Map<Int, String> = mapOf(
    KEYCODE_HOME to "Home",
    KEYCODE_BACK to "Back",
    KEYCODE_TAB to "Tab",
    KEYCODE_ENTER to "Enter",
    KEYCODE_DELETE to "Delete",
    KEYCODE_ESCAPE to "Escape",
    KEYCODE_DPAD_UP to "Up",
    KEYCODE_DPAD_DOWN to "Down",
    KEYCODE_DPAD_LEFT to "Left",
    KEYCODE_DPAD_RIGHT to "Right",
    KEYCODE_POWER to "Power",
    KEYCODE_VOLUME_UP to "Volume up",
    KEYCODE_VOLUME_DOWN to "Volume down",
    KEYCODE_VOLUME_MUTE to "Mute",
    KEYCODE_APP_SWITCH to "Recents",
    KEYCODE_MEDIA_PLAY_PAUSE to "Play/Pause",
    KEYCODE_MEDIA_PLAY to "Play",
    KEYCODE_MEDIA_PAUSE to "Pause",
    KEYCODE_MEDIA_NEXT to "Next track",
    KEYCODE_MEDIA_PREVIOUS to "Previous track",
    KEYCODE_MENU to "Menu",
)

private val MODIFIER_KEYS = setOf("KEY_LEFTCTRL", "KEY_RIGHTCTRL", "KEY_LEFTALT", "KEY_RIGHTALT", "KEY_LEFTMETA", "KEY_RIGHTMETA")
private val SHIFT_KEYS = setOf("KEY_LEFTSHIFT", "KEY_RIGHTSHIFT")

/** The meaning of a `getevent -l` key [label], or null for a key the recorder ignores. */
internal fun deviceKeyFor(label: String): DeviceKey? = when {
    label in SHIFT_KEYS -> DeviceKey.Shift
    label == "KEY_CAPSLOCK" -> DeviceKey.CapsLock
    label in MODIFIER_KEYS -> DeviceKey.Modifier
    label == "KEY_BACKSPACE" -> DeviceKey.Backspace
    else -> CONTROL_KEYS[label]?.let { DeviceKey.Control(it) } ?: PRINTABLE_KEYS[label]
}

/** Typed text from one pause-free stretch of key presses. */
internal data class TypedRun(val text: String, val startMs: Long, val endMs: Long)

/** What the accumulator hands on, in order: typed text, or a control key (always after the text typed before it). */
internal sealed interface KeyOutput {
    data class Text(val run: TypedRun) : KeyOutput

    data class Control(val keycode: Int, val atMs: Long) : KeyOutput
}

internal const val KEY_TEXT_IDLE_MS = 1_500L

/**
 * Merges printable key presses into text runs. Shift and Caps Lock are tracked, Ctrl/Alt/Meta combinations are ignored, Backspace
 * edits the run being typed (or becomes a Delete key when there is nothing to edit) and a run ends after [idleMs] without a key, or
 * at any control key. Feed time-ordered events only.
 */
internal class KeyTextAccumulator(private val idleMs: Long = KEY_TEXT_IDLE_MS) {
    private val text = StringBuilder()
    private var startMs = 0L
    private var lastMs = 0L
    private var shiftHeld = 0
    private var modifiersHeld = 0
    private var capsLock = false

    val hasText: Boolean get() = text.isNotEmpty()

    /** One key event: [down] is a press (or auto-repeat, which only printable keys honour via [repeat]), false a release. */
    fun feed(key: DeviceKey, down: Boolean, repeat: Boolean, atMs: Long): List<KeyOutput> {
        val out = ArrayList<KeyOutput>()
        if (text.isNotEmpty() && atMs - lastMs >= idleMs) flush()?.let { out += KeyOutput.Text(it) }
        when (key) {
            DeviceKey.Shift -> shiftHeld = held(shiftHeld, down, repeat)
            DeviceKey.Modifier -> modifiersHeld = held(modifiersHeld, down, repeat)
            DeviceKey.CapsLock -> if (down && !repeat) capsLock = !capsLock
            is DeviceKey.Printable -> if (down && modifiersHeld == 0) type(key, atMs)
            DeviceKey.Backspace -> if (down) backspace(out, atMs)
            is DeviceKey.Control -> if (down && !repeat) {
                flush()?.let { out += KeyOutput.Text(it) }
                out += KeyOutput.Control(key.keycode, atMs)
            }
        }
        return out
    }

    /** The run that [idleMs] of silence has ended at [nowMs], or null. */
    fun flushIfIdle(nowMs: Long): TypedRun? = if (text.isNotEmpty() && nowMs - lastMs >= idleMs) flush() else null

    /** Ends the run being typed whatever the time. */
    fun flush(): TypedRun? {
        if (text.isEmpty()) return null
        val run = TypedRun(text.toString(), startMs, lastMs)
        text.setLength(0)
        return run
    }

    /** The count of held keys of one group after this event: a fresh press adds one, a release takes one, auto-repeat changes nothing. */
    private fun held(count: Int, down: Boolean, repeat: Boolean): Int = when {
        !down -> (count - 1).coerceAtLeast(0)
        repeat -> count
        else -> count + 1
    }

    private fun type(key: DeviceKey.Printable, atMs: Long) {
        val shifted = shiftHeld > 0
        val upper = if (key.plain.isLetter()) shifted != capsLock else shifted
        if (text.isEmpty()) startMs = atMs
        text.append(if (upper) key.shifted else key.plain)
        lastMs = atMs
    }

    private fun backspace(out: MutableList<KeyOutput>, atMs: Long) {
        if (text.isNotEmpty()) {
            text.setLength(text.length - 1)
            lastMs = atMs
        } else {
            out += KeyOutput.Control(KEYCODE_DELETE, atMs)
        }
    }
}
