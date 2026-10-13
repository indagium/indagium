package com.indagium.testing.device

// The pure parsers behind the device-state keys (see DeviceState.kt). Each takes the text a fixed adb shell command printed and answers
// the value of its key, or null when the text does not say (a dump this build does not understand): never a guess. They are shared by
// the run-time reader and by the recording's environment watcher, so a key means the same thing in both. Nothing here talks to a device.

private const val USB_HEX_RADIX = 16
private const val USB_ID_HEX_DIGITS = 4
private const val QUARTER_TURN_DEGREES = 90
private const val SETTINGS_ON = "1"
private const val SETTINGS_OFF = "0"
private const val SETTINGS_ON_OVERRIDE = "2"
private const val BOOL_TRUE = "true"
private const val BOOL_FALSE = "false"
private const val MAX_PARSED_VALUE_CHARS = 200
private const val BATTERY_STATUS_CHARGING = 2
private const val BATTERY_STATUS_DISCHARGING = 3
private const val BATTERY_STATUS_NOT_CHARGING = 4
private const val BATTERY_STATUS_FULL = 5

/** The text a reading of a `settings get global` toggle gives: `true` / `false`, or null when the setting has no usable value. */
internal fun parseSettingsToggle(output: String, onValues: Set<String> = setOf(SETTINGS_ON)): String? = when (output.trim()) {
    in onValues -> BOOL_TRUE
    SETTINGS_OFF -> BOOL_FALSE
    else -> null
}

private val WIFI_STATUS = Regex("""(?im)^\s*Wi-?Fi is (enabled|disabled)\s*$""")

/** Read the Wi-Fi service's current state, rather than its persisted preference. Legacy numeric recordings remain readable. */
internal fun parseWifiEnabled(output: String): String? {
    val states = WIFI_STATUS.findAll(output).map { it.groupValues[1].lowercase(java.util.Locale.ROOT) }.toSet()
    return when {
        states.size > 1 -> null
        states.singleOrNull() == "enabled" -> BOOL_TRUE
        states.singleOrNull() == "disabled" -> BOOL_FALSE
        else -> parseSettingsToggle(output, setOf(SETTINGS_ON, SETTINGS_ON_OVERRIDE))
    }
}

/** The first 200 characters of what a setting answered, for a message. */
internal fun settingsAnswerExcerpt(output: String): String = output.trim().take(MAX_PARSED_VALUE_CHARS)

// ── USB ──────────────────────────────────────────────────────────────

private val USB_VENDOR = Regex("""vendor_id=(0x[0-9a-fA-F]+|\d+)""")
private val USB_PRODUCT = Regex("""product_id=(0x[0-9a-fA-F]+|\d+)""")
private val USB_PRODUCT_NAME = Regex("""product_name=([^,\n]*?)(?=\s+\w+=|,|$)""")
private val USB_FRAME_OPEN = Regex("""^\s*(?:([\w.\-]+)=)?([\[{])\s*$""")
private val USB_FRAME_CLOSE = Regex("""^\s*[\]}]\s*$""")
private val USB_FIELD = Regex("""^\s*([\w.\-]+)=(.*)$""")
private const val USB_HOST_SECTION = "host_manager"
private const val USB_DEVICE_LIST = "devices"

/** One `{ ... }` of a proto-style dump: [name] is the key before its bracket, [list] says it was a `[` list rather than a record. */
private class UsbFrame(val name: String, val list: Boolean) {
    val fields = HashMap<String, String>()
}

/**
 * The host-mode USB devices in `dumpsys usb` output, one `vvvv:pppp name` line each (lowercase hex ids, the product name when there
 * is one), distinct and in order; no device is an empty value.
 *
 * Two layouts are read. Modern Android prints the dump proto-style, every field on its own line inside `{ }` records:
 * `host_manager={ ... devices=[ { name=/dev/bus/usb/001/002  vendor_id=1921  product_id=21889 ... } ] }` (a list of one is printed
 * as `devices={ ... }` without the brackets). Only a record in the `devices` of `host_manager` is an attached device, and only its own
 * fields count (its configurations and interfaces are nested records): the `vendor_id=-1` filters of `settings_manager` and everything
 * under `device_manager` or `port_manager` are never devices. Older Android prints a device on one line,
 * `name=... vendor_id=1921 product_id=21889 ... product_name=Ultra`; such a line counts when it is outside any `{ }`.
 */
internal fun parseUsbDevices(output: String): String {
    val found = LinkedHashSet<String>()
    val stack = ArrayList<UsbFrame>()
    for (line in output.lineSequence()) {
        val open = USB_FRAME_OPEN.find(line)
        when {
            open != null -> {
                val bracket = open.groupValues[2]
                val name = open.groupValues[1].ifEmpty { stack.lastOrNull()?.takeIf { it.list }?.name.orEmpty() }
                stack += UsbFrame(name, list = bracket == "[")
            }
            USB_FRAME_CLOSE.matches(line) -> if (stack.isNotEmpty()) {
                val closed = stack.removeAt(stack.lastIndex)
                if (!closed.list && closed.name == USB_DEVICE_LIST && stack.any { it.name == USB_HOST_SECTION }) {
                    usbDeviceLine(closed.fields)?.let(found::add)
                }
            }
            stack.isNotEmpty() -> USB_FIELD.find(line)?.let { field ->
                val frame = stack.last()
                if (!frame.list) frame.fields[field.groupValues[1]] = field.groupValues[2].trim()
            }
            else -> legacyUsbDeviceLine(line)?.let(found::add)
        }
    }
    return found.joinToString("\n")
}

private fun usbDeviceLine(fields: Map<String, String>): String? {
    val vendor = fields["vendor_id"]?.let(::usbIdHex) ?: return null
    val product = fields["product_id"]?.let(::usbIdHex) ?: return null
    return usbLine(vendor, product, fields["product_name"].orEmpty())
}

private fun legacyUsbDeviceLine(line: String): String? {
    val vendor = USB_VENDOR.find(line)?.groupValues?.get(1)?.let(::usbIdHex) ?: return null
    val product = USB_PRODUCT.find(line)?.groupValues?.get(1)?.let(::usbIdHex) ?: return null
    return usbLine(vendor, product, USB_PRODUCT_NAME.find(line)?.groupValues?.get(1).orEmpty())
}

private fun usbLine(vendor: String, product: String, rawName: String): String {
    val name = rawName.trim().takeUnless { it.isEmpty() || it == "null" }
    return "$vendor:$product" + if (name == null) "" else " $name"
}

private fun usbIdHex(raw: String): String? {
    val value = if (raw.startsWith("0x") || raw.startsWith("0X")) raw.drop(2).toIntOrNull(USB_HEX_RADIX) else raw.toIntOrNull()
    return value?.takeIf { it >= 0 }?.toString(USB_HEX_RADIX)?.padStart(USB_ID_HEX_DIGITS, '0')
}

// ── Battery and power ────────────────────────────────────────────────

private val BATTERY_SOURCES = listOf("AC" to "ac", "USB" to "usb", "Wireless" to "wireless", "Dock" to "dock")
private val BATTERY_STATUS = Regex("""^\s*status:\s*(\d+)""", RegexOption.MULTILINE)
private val POWERED = Regex("""^\s*(AC|USB|Wireless|Dock) powered:\s*(true|false)""", RegexOption.MULTILINE)

/** `dumpsys battery`: where the device is charging from (`ac`, `usb`, `wireless`, `dock`), or `none` on battery. Null for an unknown dump. */
internal fun parsePowerSource(output: String): String? {
    val powered = POWERED.findAll(output).associate { it.groupValues[1] to (it.groupValues[2] == BOOL_TRUE) }
    if (powered.isEmpty()) return null
    return BATTERY_SOURCES.firstOrNull { powered[it.first] == true }?.second ?: "none"
}

/** `dumpsys battery`: `charging`, `discharging`, `not_charging`, `full` or `unknown` from the numeric status; null when it names none. */
internal fun parseBatteryStatus(output: String): String? = when (BATTERY_STATUS.find(output)?.groupValues?.get(1)?.toIntOrNull()) {
    null -> null
    BATTERY_STATUS_CHARGING -> "charging"
    BATTERY_STATUS_DISCHARGING -> "discharging"
    BATTERY_STATUS_NOT_CHARGING -> "not_charging"
    BATTERY_STATUS_FULL -> "full"
    else -> "unknown"
}

private val WAKEFULNESS = Regex("""\bmWakefulness=(\w+)""")
private val AWAKE_STATES = setOf("Awake", "Dreaming")
private val ASLEEP_STATES = setOf("Asleep", "Dozing")

/** `dumpsys power`: `true` while the screen is on (Awake, or a screen saver Dreaming), `false` when Asleep or Dozing. Null otherwise. */
internal fun parseScreenAwake(output: String): String? = when (WAKEFULNESS.find(output)?.groupValues?.get(1)) {
    in AWAKE_STATES -> BOOL_TRUE
    in ASLEEP_STATES -> BOOL_FALSE
    else -> null
}

// ── Display rotation ─────────────────────────────────────────────────

private val ROTATION_NAMED = Regex("\\b(?:mCurrentRotation|mRotation|rotation)=ROTATION_(0|90|180|270)\\b")
private val ROTATION_INDEX = Regex("\\b(?:mCurrentRotation|mRotation)=([0-3])\\b")

/** The display rotation in quarter turns (0..3) from `dumpsys window displays`; null when the dump names none. */
internal fun parseDisplayRotation(output: String): Int? {
    ROTATION_NAMED.find(output)?.let { return it.groupValues[1].toInt() / QUARTER_TURN_DEGREES }
    return ROTATION_INDEX.find(output)?.groupValues?.get(1)?.toInt()
}

// ── Bluetooth ────────────────────────────────────────────────────────

private val BT_CONNECTED_HEADER = Regex("""^(\s*)Connected devices:\s*(\d+)\s*$""")
private val BT_ADAPTER_STATE = Regex("""^\s*State:\s+(\w+)\s*$""", RegexOption.MULTILINE)
private val BT_CONNECTION_STATE = Regex("""\bConnectionState:\s*(STATE_\w+)""")
private val BT_ADDRESS = Regex("""^[0-9A-Fa-fX]{2}(?::[0-9A-Fa-fX]{2}){5}""")
private val BT_BRACKETS = Regex("""\[[^\]]*]""")
private const val BT_ON = "ON"
private const val BT_STATE_DISCONNECTED = "STATE_DISCONNECTED"
private const val MAX_BT_DEVICES = 20

/**
 * `dumpsys bluetooth_manager`, best effort: the names of the connected devices, one per line (an unnamed one by its address), an empty
 * value when none is connected or Bluetooth is off. The device lines under `Connected devices: N` are read; when the dump has no such
 * block the adapter's `ConnectionState` says only whether anything is connected. Null when the dump says neither.
 */
internal fun parseBluetoothConnected(output: String): String? {
    val lines = output.lines()
    val header = lines.indexOfFirst { BT_CONNECTED_HEADER.matches(it) }
    if (header >= 0) return connectedBluetoothNames(lines, header)
    val adapterState = BT_ADAPTER_STATE.find(output)?.groupValues?.get(1)
    if (adapterState != null && adapterState != BT_ON) return ""
    return BT_CONNECTION_STATE.find(output)?.groupValues?.get(1)?.let { if (it == BT_STATE_DISCONNECTED) "" else "connected device" }
}

private fun connectedBluetoothNames(lines: List<String>, header: Int): String {
    val match = requireNotNull(BT_CONNECTED_HEADER.matchEntire(lines[header]))
    val indent = match.groupValues[1].length
    val count = match.groupValues[2].toIntOrNull() ?: 0
    if (count == 0) return ""
    val names = ArrayList<String>()
    for (line in lines.drop(header + 1)) {
        if (line.isBlank() || line.length - line.trimStart().length <= indent) break
        names += bluetoothDeviceName(line.trim())
        if (names.size >= MAX_BT_DEVICES) break
    }
    return names.ifEmpty { List(count.coerceAtMost(MAX_BT_DEVICES)) { "unnamed device ${it + 1}" } }.joinToString("\n")
}

private fun bluetoothDeviceName(entry: String): String {
    val address = BT_ADDRESS.find(entry)?.value
    val name = entry.removePrefix(address.orEmpty()).replace(BT_BRACKETS, " ").trim().ifEmpty { null }
    return name ?: address ?: "unnamed device"
}

// ── Audio ────────────────────────────────────────────────────────────

private val AUDIO_DEVICES_LINE = Regex("""^\s*Devices:\s*(.+)$""", RegexOption.MULTILINE)
private val AUDIO_DEVICE_SUFFIX = Regex("""\(.*\)$""")

/** The output device names `dumpsys audio` uses for something plugged in: a wired headset or headphones, or a USB audio device. */
internal val WIRED_AUDIO_DEVICES: List<String> = listOf("headset", "headphone", "usb_headset", "usb_device", "usb_accessory")

/**
 * `dumpsys audio`, best effort: the wired or USB audio outputs in use, one per line from [WIRED_AUDIO_DEVICES] (an empty value when
 * only the speaker and similar are). They are read from the `Devices:` line of each stream; null when the dump has no such line.
 */
internal fun parseAudioDevices(output: String): String? {
    val lines = AUDIO_DEVICES_LINE.findAll(output).map { it.groupValues[1] }.toList()
    if (lines.isEmpty()) return null
    val names = lines.flatMap { it.split(',', '|', ' ', '\t') }.map { it.trim().replace(AUDIO_DEVICE_SUFFIX, "").lowercase() }
    return WIRED_AUDIO_DEVICES.filter { it in names }.joinToString("\n")
}
