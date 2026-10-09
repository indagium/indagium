package com.indagium.testing.device

import kotlinx.coroutines.CancellationException

// Reading one property of the device for a StepCheck.DeviceState. The reader is a seam on the lane's TestDeviceSession: the real one
// below runs ONE bounded `adb shell` per key and knows a small fixed set of keys; unknown keys are an Unavailable reading with a
// message that names the supported ones, never a guess. The environment probe of the recording work (one shell script for many
// keys) can replace the reader without any caller changing: callers only see DeviceStateReader.
//
// Values are plain text a check's regular expression is searched in (find, not full match): `usb.devices` is one line per attached
// host-mode device, `0781:5581 Ultra`; the toggles are `true` / `false`.

const val DEVICE_STATE_USB_DEVICES = "usb.devices"
const val DEVICE_STATE_BLUETOOTH_ENABLED = "bt.enabled"
const val DEVICE_STATE_WIFI_ENABLED = "wifi.enabled"
const val DEVICE_STATE_AIRPLANE = "airplane"

/** The keys this build can read, in the order they are listed to a person. */
val KNOWN_DEVICE_STATE_KEYS: List<String> =
    listOf(DEVICE_STATE_USB_DEVICES, DEVICE_STATE_BLUETOOTH_ENABLED, DEVICE_STATE_WIFI_ENABLED, DEVICE_STATE_AIRPLANE)

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

private const val SETTINGS_ON = "1"
private const val SETTINGS_OFF = "0"
private const val BOOL_TRUE = "true"
private const val BOOL_FALSE = "false"
private const val USB_HEX_RADIX = 16
private const val USB_ID_HEX_DIGITS = 4
private const val MAX_STATE_ERROR_CHARS = 200
private val USB_VENDOR = Regex("""vendor_id=(0x[0-9a-fA-F]+|\d+)""")
private val USB_PRODUCT = Regex("""product_id=(0x[0-9a-fA-F]+|\d+)""")
private val USB_PRODUCT_NAME = Regex("""product_name=([^,\n]*?)(?=\s+\w+=|,|$)""")

/** The production reader: one `adb shell` per key through [shell] (which bounds it with a timeout). */
internal class AdbDeviceStateReader(private val shell: suspend (command: List<String>) -> DeviceShellOutput) : DeviceStateReader {
    @Suppress("TooGenericExceptionCaught") // A failing adb must become a readable reading, whatever it throws.
    override suspend fun read(key: String): DeviceStateReading {
        val command = commandFor(key) ?: return DeviceStateReading.Unavailable(unknownKeyMessage(key), permanent = true)
        val output = try {
            shell(command)
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

    private fun commandFor(key: String): List<String>? = when (key) {
        DEVICE_STATE_USB_DEVICES -> listOf("dumpsys", "usb")
        DEVICE_STATE_BLUETOOTH_ENABLED -> listOf("settings", "get", "global", "bluetooth_on")
        DEVICE_STATE_WIFI_ENABLED -> listOf("settings", "get", "global", "wifi_on")
        DEVICE_STATE_AIRPLANE -> listOf("settings", "get", "global", "airplane_mode_on")
        else -> null
    }
}

internal fun unknownKeyMessage(key: String): String =
    "Unknown device-state key '$key'. Supported keys: ${KNOWN_DEVICE_STATE_KEYS.joinToString(", ")}."

/** The reading for [key] from the shell [output] of its command; pure, so every format is testable without a device. */
internal fun parseDeviceState(key: String, output: String): DeviceStateReading = when (key) {
    DEVICE_STATE_USB_DEVICES -> DeviceStateReading.Value(parseUsbDevices(output))
    DEVICE_STATE_BLUETOOTH_ENABLED, DEVICE_STATE_WIFI_ENABLED, DEVICE_STATE_AIRPLANE -> parseSettingsToggle(key, output)
    else -> DeviceStateReading.Unavailable(unknownKeyMessage(key), permanent = true)
}

private fun parseSettingsToggle(key: String, output: String): DeviceStateReading = when (val raw = output.trim()) {
    SETTINGS_ON -> DeviceStateReading.Value(BOOL_TRUE)
    SETTINGS_OFF -> DeviceStateReading.Value(BOOL_FALSE)
    else -> DeviceStateReading.Unavailable("'$key' has no usable value on this device (the setting answered '${raw.take(MAX_STATE_ERROR_CHARS)}').")
}

/**
 * The host-mode USB devices in `dumpsys usb` output: every line naming a `vendor_id=` and a `product_id=` (decimal, as Android
 * prints them, or 0x hex) becomes `vvvv:pppp` (lowercase hex) followed by the product name when the line has one. Lines are
 * de-duplicated and kept in order; no such line means no device (an empty value).
 */
internal fun parseUsbDevices(output: String): String = output.lineSequence().mapNotNull { line ->
    val vendor = USB_VENDOR.find(line)?.groupValues?.get(1)?.let(::usbIdHex) ?: return@mapNotNull null
    val product = USB_PRODUCT.find(line)?.groupValues?.get(1)?.let(::usbIdHex) ?: return@mapNotNull null
    val name = USB_PRODUCT_NAME.find(line)?.groupValues?.get(1)?.trim().orEmpty()
    "$vendor:$product" + if (name.isEmpty()) "" else " $name"
}.distinct().joinToString("\n")

private fun usbIdHex(raw: String): String? {
    val value = if (raw.startsWith("0x") || raw.startsWith("0X")) raw.drop(2).toIntOrNull(USB_HEX_RADIX) else raw.toIntOrNull()
    return value?.toString(USB_HEX_RADIX)?.padStart(USB_ID_HEX_DIGITS, '0')
}
