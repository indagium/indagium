package com.indagium.testing.authoring

import com.indagium.testing.device.DEVICE_STATE_AIRPLANE
import com.indagium.testing.device.DEVICE_STATE_AUDIO_DEVICES
import com.indagium.testing.device.DEVICE_STATE_BATTERY_STATUS
import com.indagium.testing.device.DEVICE_STATE_BLUETOOTH_CONNECTED
import com.indagium.testing.device.DEVICE_STATE_BLUETOOTH_ENABLED
import com.indagium.testing.device.DEVICE_STATE_MOBILE_DATA
import com.indagium.testing.device.DEVICE_STATE_POWER_SOURCE
import com.indagium.testing.device.DEVICE_STATE_ROTATION
import com.indagium.testing.device.DEVICE_STATE_SCREEN_AWAKE
import com.indagium.testing.device.DEVICE_STATE_USB_DEVICES
import com.indagium.testing.device.DEVICE_STATE_WIFI_ENABLED
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.newCheckId

// What the recording's environment watcher reports: a change of one device-state key between two polls, how it is told in words, and
// the step it proposes ("Connect the USB device 'Ultra' to the phone" with a device-state check that notices it done). The differ
// debounces so a value that flaps is not reported; nothing here talks to a device or to a session.

/**
 * [key] changed from [from] to [to] (the keys' values, see DeviceState.kt; an empty text is an empty value). [atMs] is when a poll
 * first saw the new value, [sinceMs] when a poll last saw the old one: the change happened in between.
 */
data class EnvironmentEvent(val key: String, val from: String, val to: String, val atMs: Long, val sinceMs: Long = atMs)

/** Keys whose values are lists, one item per line; their changes are told item by item. */
internal val LIST_STATE_KEYS: Set<String> = setOf(DEVICE_STATE_USB_DEVICES, DEVICE_STATE_BLUETOOTH_CONNECTED, DEVICE_STATE_AUDIO_DEVICES)

/** Keys that are only ever evidence on an input; a change of one never becomes a row of its own. */
internal val EVIDENCE_ONLY_STATE_KEYS: Set<String> = setOf(DEVICE_STATE_BATTERY_STATUS)

/** The value of a list key in one canonical form (lines sorted and distinct), so the order a dump happens to print never looks like a change. */
internal fun normalizeStateValue(key: String, value: String): String =
    if (key in LIST_STATE_KEYS) value.lines().map(String::trim).filter(String::isNotEmpty).distinct().sorted().joinToString("\n") else value.trim()

/**
 * Turns successive snapshots (key to value) into [EnvironmentEvent]s. The first value of a key is its baseline and never an event. A
 * different value is only reported once a second poll sees the same different value (so a value that flaps, or a read that glitches
 * once, is not reported); it is then reported once, timed at the poll that first saw it. A key missing from a snapshot (its read
 * failed) changes nothing. Single-threaded.
 */
internal class EnvironmentDiffer {
    private class KeyState(var stable: String, var stableSeenAtMs: Long) {
        var candidate: String? = null
        var candidateSinceMs = 0L
    }

    private val states = HashMap<String, KeyState>()

    fun accept(snapshot: Map<String, String>, atMs: Long): List<EnvironmentEvent> {
        val events = ArrayList<EnvironmentEvent>()
        for ((key, value) in snapshot) {
            val state = states[key]
            when {
                state == null -> states[key] = KeyState(value, atMs)
                value == state.stable -> {
                    state.stableSeenAtMs = atMs
                    state.candidate = null
                }
                value != state.candidate -> {
                    state.candidate = value
                    state.candidateSinceMs = atMs
                }
                else -> {
                    events += EnvironmentEvent(key, state.stable, value, state.candidateSinceMs, state.stableSeenAtMs)
                    state.stable = value
                    state.stableSeenAtMs = atMs
                    state.candidate = null
                }
            }
        }
        return events
    }
}

// ── Words ────────────────────────────────────────────────────────────

private const val MAX_DEVICE_NAME_CHARS = 60
private val CONTROL_OR_SPACE_RUN = Regex("[\\p{Cntrl}\\s]+")

/** Text a device supplied, made safe to show and to put in a sentence: no control characters, one line, bounded. */
internal fun cleanDeviceText(raw: String): String = raw.replace(CONTROL_OR_SPACE_RUN, " ").trim().take(MAX_DEVICE_NAME_CHARS)

/** The lines of a list value that [this] event added / removed. */
internal fun EnvironmentEvent.addedItems(): List<String> = items(to) - items(from).toSet()

internal fun EnvironmentEvent.removedItems(): List<String> = items(from) - items(to).toSet()

private fun items(value: String): List<String> = value.lines().map(String::trim).filter(String::isNotEmpty)

private fun onOff(value: String) = if (value == "true") "on" else "off"

private val ROTATION_NAMES = mapOf("0" to "portrait", "1" to "landscape (90°)", "2" to "upside-down portrait", "3" to "landscape (270°)")

/** One USB line `0781:5581 Ultra` as `'Ultra' (0781:5581)`, or just the ids when the device has no name. */
private fun usbLabel(line: String): String {
    val id = line.substringBefore(' ')
    val name = cleanDeviceText(line.substringAfter(' ', ""))
    return if (name.isEmpty()) id else "'$name' ($id)"
}

private fun audioLabel(item: String): String = when (item) {
    "headset" -> "Wired headset"
    "headphone" -> "Wired headphones"
    "usb_headset" -> "USB headset"
    "usb_device" -> "USB audio device"
    "usb_accessory" -> "USB audio accessory"
    else -> "Audio device '${cleanDeviceText(item)}'"
}

private fun quoted(name: String) = "'${cleanDeviceText(name)}'"

/** [this] event as a short past-tense sentence a person reads: "USB device 'Ultra' (0781:5581) connected", "Bluetooth turned off". */
internal fun EnvironmentEvent.summary(): String = when (key) {
    DEVICE_STATE_USB_DEVICES -> listItemText(::usbLabel) { label, state -> "USB device $label $state" }
    DEVICE_STATE_BLUETOOTH_CONNECTED -> listItemText(::quoted) { label, state -> "Bluetooth device $label $state" }
    DEVICE_STATE_AUDIO_DEVICES -> listItemText(::audioLabel) { label, state -> "$label $state" }
    DEVICE_STATE_BLUETOOTH_ENABLED -> "Bluetooth turned ${onOff(to)}"
    DEVICE_STATE_WIFI_ENABLED -> "Wi-Fi turned ${onOff(to)}"
    DEVICE_STATE_AIRPLANE -> "Airplane mode turned ${onOff(to)}"
    DEVICE_STATE_MOBILE_DATA -> "Mobile data turned ${onOff(to)}"
    DEVICE_STATE_SCREEN_AWAKE -> "Screen turned ${onOff(to)}"
    DEVICE_STATE_POWER_SOURCE -> powerSourceText()
    DEVICE_STATE_BATTERY_STATUS -> "Battery status changed to ${to.replace('_', ' ')}"
    DEVICE_STATE_ROTATION -> "Screen rotated to ${ROTATION_NAMES[to] ?: "rotation $to"}"
    else -> "$key changed from '${cleanDeviceText(from)}' to '${cleanDeviceText(to)}'"
}

private fun EnvironmentEvent.listItemText(label: (String) -> String, sentence: (String, String) -> String): String =
    (addedItems().map { sentence(label(it), "connected") } + removedItems().map { sentence(label(it), "disconnected") })
        .joinToString("; ").ifEmpty { "$key changed" }

private fun EnvironmentEvent.powerSourceText(): String = when {
    to == "none" -> "Charger disconnected"
    from == "none" -> "Charger connected (${to.uppercase()})"
    else -> "Power source changed from ${from.uppercase()} to ${to.uppercase()}"
}

// ── Proposed step ────────────────────────────────────────────────────

/** What a tester is asked to do for [this] event ("Connect the USB device 'Ultra' (0781:5581) to the phone"). */
internal fun EnvironmentEvent.instruction(): String = when (key) {
    DEVICE_STATE_USB_DEVICES -> listItemInstruction(
        { "Connect the USB device ${usbLabel(it)} to the phone" },
        { "Disconnect the USB device ${usbLabel(it)} from the phone" },
    )
    DEVICE_STATE_BLUETOOTH_CONNECTED -> listItemInstruction(
        { "Connect the Bluetooth device ${quoted(it)}" },
        { "Disconnect the Bluetooth device ${quoted(it)}" },
    )
    DEVICE_STATE_AUDIO_DEVICES -> listItemInstruction({ "Plug in: ${audioLabel(it).lowercase()}" }, { "Unplug: ${audioLabel(it).lowercase()}" })
    DEVICE_STATE_BLUETOOTH_ENABLED -> "Turn Bluetooth ${onOff(to)}"
    DEVICE_STATE_WIFI_ENABLED -> "Turn Wi-Fi ${onOff(to)}"
    DEVICE_STATE_AIRPLANE -> "Turn airplane mode ${onOff(to)}"
    DEVICE_STATE_MOBILE_DATA -> "Turn mobile data ${onOff(to)}"
    DEVICE_STATE_SCREEN_AWAKE -> if (to == "true") "Wake the device so the screen is on" else "Turn the screen off"
    DEVICE_STATE_POWER_SOURCE -> when {
        to == "none" -> "Disconnect the charger from the phone"
        from == "none" -> "Connect the charger (${to.uppercase()}) to the phone"
        else -> "Switch the power source from ${from.uppercase()} to ${to.uppercase()}"
    }
    DEVICE_STATE_ROTATION -> "Rotate the device to ${ROTATION_NAMES[to] ?: "rotation $to"}"
    else -> summary()
}

private fun EnvironmentEvent.listItemInstruction(added: (String) -> String, removed: (String) -> String): String =
    (addedItems().map(added) + removedItems().map(removed)).joinToString("; ").ifEmpty { summary() }

private const val ABSENT_PATTERN_PREFIX = "\\A(?![\\s\\S]*"
private val PLAIN_LITERAL = Regex("[A-Za-z0-9:_ -]+")

/** [text] as a regular expression that matches exactly that text: plain when it has nothing special in it, else quoted. */
internal fun literalRegex(text: String): String = if (PLAIN_LITERAL.matches(text)) text else Regex.escape(text)

/** A regular expression that is true of a list value exactly when it lacks [item]. */
private fun absentRegex(item: String): String = "$ABSENT_PATTERN_PREFIX${literalRegex(item)})"

/** The expression a device-state check watches for after [this] event, or null when none can say it. */
internal fun EnvironmentEvent.expectedRegex(): String? = when (key) {
    DEVICE_STATE_USB_DEVICES -> addedItems().firstOrNull()?.let { literalRegex(it.substringBefore(' ')) }
        ?: removedItems().firstOrNull()?.let { absentRegex(it.substringBefore(' ')) }
    DEVICE_STATE_BLUETOOTH_CONNECTED, DEVICE_STATE_AUDIO_DEVICES -> addedItems().firstOrNull()?.let { "(?m)^${literalRegex(it)}$" }
        ?: removedItems().firstOrNull()?.let { absentRegex(it) }
    DEVICE_STATE_BATTERY_STATUS -> null
    else -> literalRegex(to)
}

/** The step a person is proposed for an external event: the tester's [action] and, when the event can be told apart, the [check] that notices it. */
internal class ExternalProposal(val action: String, val check: StepCheck.DeviceState?)

internal fun proposeExternalStep(event: EnvironmentEvent, withinMs: Long): ExternalProposal =
    ExternalProposal(event.instruction(), event.expectedRegex()?.let { StepCheck.DeviceState(newCheckId(), event.key, it, withinMs) })
