package com.indagium.testing.device

import kotlinx.coroutines.CancellationException

// Reading one property of the device for a StepCheck.DeviceState. The reader is a seam on the lane's TestDeviceSession: the real one
// below runs ONE bounded `adb shell` per key and knows the fixed set of keys listed here; unknown keys are an Unavailable reading with
// a message that names the supported ones, never a guess. The recording's environment watcher reads many keys with one shell script
// (RecordingEnvironmentWatcher.kt) but through the same [DeviceStateSource]s and parsers, so a key means the same in both.
//
// Values are plain text a check's regular expression is searched in (find, not full match). The toggles are `true` / `false`;
// `usb.devices` is one line per attached host-mode device, `0781:5581 Ultra`; the rest are described by [DEVICE_STATE_KEY_HINTS].

const val DEVICE_STATE_USB_DEVICES = "usb.devices"
const val DEVICE_STATE_BLUETOOTH_ENABLED = "bt.enabled"
const val DEVICE_STATE_BLUETOOTH_CONNECTED = "bt.connected"
const val DEVICE_STATE_WIFI_ENABLED = "wifi.enabled"
const val DEVICE_STATE_AIRPLANE = "airplane"
const val DEVICE_STATE_MOBILE_DATA = "mobile_data"
const val DEVICE_STATE_POWER_SOURCE = "power.source"
const val DEVICE_STATE_BATTERY_STATUS = "battery.status"
const val DEVICE_STATE_AUDIO_DEVICES = "audio.devices"
const val DEVICE_STATE_SCREEN_AWAKE = "screen.awake"
const val DEVICE_STATE_ROTATION = "rotation"

/** The keys this build can read, in the order they are listed to a person. */
val KNOWN_DEVICE_STATE_KEYS: List<String> = listOf(
    DEVICE_STATE_USB_DEVICES,
    DEVICE_STATE_BLUETOOTH_ENABLED,
    DEVICE_STATE_BLUETOOTH_CONNECTED,
    DEVICE_STATE_WIFI_ENABLED,
    DEVICE_STATE_AIRPLANE,
    DEVICE_STATE_MOBILE_DATA,
    DEVICE_STATE_POWER_SOURCE,
    DEVICE_STATE_BATTERY_STATUS,
    DEVICE_STATE_AUDIO_DEVICES,
    DEVICE_STATE_SCREEN_AWAKE,
    DEVICE_STATE_ROTATION,
)

/** What each key's value looks like, for the editors and the tool documentation. */
val DEVICE_STATE_KEY_HINTS: Map<String, String> = mapOf(
    DEVICE_STATE_USB_DEVICES to "One line per attached USB host device: vendor:product and name, such as 0781:5581 Ultra (empty: none).",
    DEVICE_STATE_BLUETOOTH_ENABLED to "true or false.",
    DEVICE_STATE_BLUETOOTH_CONNECTED to "The connected Bluetooth devices, one name per line (best effort; empty: none).",
    DEVICE_STATE_WIFI_ENABLED to "true or false.",
    DEVICE_STATE_AIRPLANE to "true or false.",
    DEVICE_STATE_MOBILE_DATA to "true or false.",
    DEVICE_STATE_POWER_SOURCE to "ac, usb, wireless, dock, or none when the device runs on its battery.",
    DEVICE_STATE_BATTERY_STATUS to "charging, discharging, not_charging, full or unknown.",
    DEVICE_STATE_AUDIO_DEVICES to "Wired or USB audio in use, one per line: headset, headphone, usb_headset, usb_device (best effort; empty: none).",
    DEVICE_STATE_SCREEN_AWAKE to "true while the screen is on, false when it is off or dozing.",
    DEVICE_STATE_ROTATION to "The display rotation in quarter turns: 0 upright, 1, 2 or 3.",
)

/**
 * One reading: the [Value] text, or why there is none. An empty [Value] is a real answer ("no USB device is attached").
 * [Unavailable.permanent] says retrying cannot help (an unknown key), as opposed to an adb hiccup.
 */
internal sealed interface DeviceStateReading {
    data class Value(val text: String) : DeviceStateReading

    data class Unavailable(val reason: String, val permanent: Boolean = false) : DeviceStateReading
}

/** Reads the current value of a device-state key. Never throws for an expected problem; cancellation passes through. */
internal fun interface DeviceStateReader {
    suspend fun read(key: String): DeviceStateReading
}

/** What one device shell command answered; [stdout] is what the parsers read. */
internal class DeviceShellOutput(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

/**
 * One fixed device command and the keys its output answers. [section] names it in the watcher's combined script. [command] is the
 * argument list `adb shell` runs; every element is a constant, so no user or device text can ever reach a command.
 */
internal enum class DeviceStateSource(val section: String, val command: List<String>, val keys: List<String>) {
    USB("usb", listOf("dumpsys", "usb"), listOf(DEVICE_STATE_USB_DEVICES)),
    BATTERY("battery", listOf("dumpsys", "battery"), listOf(DEVICE_STATE_POWER_SOURCE, DEVICE_STATE_BATTERY_STATUS)),
    BLUETOOTH_ENABLED("bt_on", listOf("settings", "get", "global", "bluetooth_on"), listOf(DEVICE_STATE_BLUETOOTH_ENABLED)),
    BLUETOOTH_CONNECTED("bt_dump", listOf("dumpsys", "bluetooth_manager"), listOf(DEVICE_STATE_BLUETOOTH_CONNECTED)),
    WIFI("wifi", listOf("cmd", "wifi", "status"), listOf(DEVICE_STATE_WIFI_ENABLED)),
    AIRPLANE("airplane", listOf("settings", "get", "global", "airplane_mode_on"), listOf(DEVICE_STATE_AIRPLANE)),
    MOBILE_DATA("mobile", listOf("settings", "get", "global", "mobile_data"), listOf(DEVICE_STATE_MOBILE_DATA)),
    AUDIO("audio", listOf("dumpsys", "audio"), listOf(DEVICE_STATE_AUDIO_DEVICES)),
    POWER("power", listOf("dumpsys", "power"), listOf(DEVICE_STATE_SCREEN_AWAKE)),
    ROTATION("rotation", listOf("dumpsys", "window", "displays"), listOf(DEVICE_STATE_ROTATION)),
    ;

    /** The value of each of this source's keys in [output] (what [command] printed), or why a key has none. */
    fun parse(output: String): Map<String, DeviceStateReading> = keys.associateWith { key -> parseKey(key, output) }

    companion object {
        fun forKey(key: String): DeviceStateSource? = entries.firstOrNull { key in it.keys }
    }
}

private const val MAX_STATE_ERROR_CHARS = 200
private val FAILED_COMMAND_MARKERS = listOf("Can't find service", "Permission Denial", "Unknown command", "not found")

/** True when [output] is not a dump at all: empty, or the shell's or `dumpsys`'s own complaint. */
internal fun deviceCommandFailed(output: String): Boolean {
    val text = output.trim()
    return text.isEmpty() || (text.length < MAX_STATE_ERROR_CHARS && FAILED_COMMAND_MARKERS.any { text.contains(it, ignoreCase = true) })
}

private fun DeviceStateSource.parseKey(key: String, output: String): DeviceStateReading {
    if (deviceCommandFailed(output)) {
        return DeviceStateReading.Unavailable(
            "'$key' has no usable value on this device (${command.joinToString(" ")} answered '${settingsAnswerExcerpt(output)}').",
        )
    }
    val value = when (key) {
        DEVICE_STATE_USB_DEVICES -> parseUsbDevices(output)
        DEVICE_STATE_BLUETOOTH_ENABLED, DEVICE_STATE_AIRPLANE, DEVICE_STATE_MOBILE_DATA -> parseSettingsToggle(output)
        DEVICE_STATE_WIFI_ENABLED -> parseWifiEnabled(output)
        DEVICE_STATE_BLUETOOTH_CONNECTED -> parseBluetoothConnected(output)
        DEVICE_STATE_POWER_SOURCE -> parsePowerSource(output)
        DEVICE_STATE_BATTERY_STATUS -> parseBatteryStatus(output)
        DEVICE_STATE_AUDIO_DEVICES -> parseAudioDevices(output)
        DEVICE_STATE_SCREEN_AWAKE -> parseScreenAwake(output)
        DEVICE_STATE_ROTATION -> parseDisplayRotation(output)?.toString()
        else -> return DeviceStateReading.Unavailable(unknownKeyMessage(key), permanent = true)
    }
    return value?.let { DeviceStateReading.Value(it) }
        ?: DeviceStateReading.Unavailable("'$key' has no usable value on this device (the output was not understood).")
}

/** The production reader: one `adb shell` per key through [shell] (which bounds it with a timeout). */
internal class AdbDeviceStateReader(private val shell: suspend (command: List<String>) -> DeviceShellOutput) : DeviceStateReader {
    @Suppress("TooGenericExceptionCaught") // A failing adb must become a readable reading, whatever it throws.
    override suspend fun read(key: String): DeviceStateReading {
        val source = DeviceStateSource.forKey(key) ?: return DeviceStateReading.Unavailable(unknownKeyMessage(key), permanent = true)
        val output = try {
            shell(source.command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return DeviceStateReading.Unavailable("Could not read '$key': ${failure.message ?: failure::class.simpleName}")
        }
        if (output.timedOut) return DeviceStateReading.Unavailable("Reading '$key' timed out.")
        if (output.exitCode != 0) {
            return DeviceStateReading.Unavailable("Reading '$key' failed: ${output.stderr.trim().take(MAX_STATE_ERROR_CHARS)}")
        }
        return parseDeviceState(key, output.stdout)
    }
}

internal fun unknownKeyMessage(key: String): String =
    "Unknown device-state key '$key'. Supported keys: ${KNOWN_DEVICE_STATE_KEYS.joinToString(", ")}."

/** The reading for [key] from the shell [output] of its command; pure, so every format is testable without a device. */
internal fun parseDeviceState(key: String, output: String): DeviceStateReading =
    DeviceStateSource.forKey(key)?.parse(output)?.get(key) ?: DeviceStateReading.Unavailable(unknownKeyMessage(key), permanent = true)
