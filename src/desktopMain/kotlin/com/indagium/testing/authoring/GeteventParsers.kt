package com.indagium.testing.authoring

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorTouchAction

// Pure parsers for `adb shell getevent`: what `-lp` says about the input devices (which one is the touchscreen, which carry keys,
// which were made by the mirror), and a state machine that turns the `-lt` event stream into the same touch commands the mirror
// produces. Nothing here starts a process or touches a device; DeviceInputRecorder owns that.

/** The device names the mirror's own virtual input devices carry (scrcpy's UHID keyboard and mouse): their events are never recorded. */
private val MIRROR_VIRTUAL_DEVICE_NAME = Regex("scrcpy|uhid", RegexOption.IGNORE_CASE)

/** The raw value range of one absolute axis. */
internal data class GeteventAxis(val min: Int, val max: Int) {
    val span: Int get() = max - min + 1
}

/** A touchscreen: its event node, name, axes and which touch protocol its events use. */
internal data class GeteventTouchDevice(
    val path: String,
    val name: String,
    val x: GeteventAxis,
    val y: GeteventAxis,
    /** Multi-touch protocol B: contacts are `ABS_MT_SLOT`s with tracking ids. */
    val slotted: Boolean,
    /** Reports `ABS_MT_POSITION_*`; false for a single-touch (`ABS_X`/`ABS_Y` + `BTN_TOUCH`) screen. */
    val multiTouch: Boolean,
)

internal data class GeteventKeyDevice(val path: String, val name: String)

/** What `getevent -lp` found: the touchscreens, the devices with keys, and the names of mirror-made devices left out. */
internal data class GeteventDevices(
    val touchScreens: List<GeteventTouchDevice>,
    val keyDevices: List<GeteventKeyDevice>,
    val excludedNames: List<String>,
) {
    val isUsable: Boolean get() = touchScreens.isNotEmpty() || keyDevices.isNotEmpty()
}

private val ADD_DEVICE_LINE = Regex("^add device \\d+:\\s+(\\S+)")
private val DEVICE_NAME_LINE = Regex("^\\s+name:\\s+\"(.*)\"")
private val SECTION_HEADER = Regex("^\\s+([A-Z]+)\\s+\\([0-9a-fA-F]{4}\\):\\s*(.*)$")
private val ABS_ENTRY = Regex("(ABS_[A-Z0-9_]+)\\s*:\\s*value\\s+-?\\d+,\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)")
private val KEY_TOKEN = Regex("\\b((?:KEY|BTN)_[A-Z0-9_]+)\\b")
private val PROP_TOKEN = Regex("\\bINPUT_PROP_[A-Z_]+\\b")
private const val INPUT_PROPS_HEADER = "input props:"
private const val PROP_DIRECT = "INPUT_PROP_DIRECT"
private const val PROP_POINTER = "INPUT_PROP_POINTER"

private class DeviceBuilder(val path: String) {
    var name = ""
    val abs = HashMap<String, GeteventAxis>()
    val keys = HashSet<String>()
    val props = HashSet<String>()
}

/** Reads `getevent -lp` output. Tolerant of noise lines, missing sections and the older output without `input props`. */
internal object GeteventDeviceParser {
    fun parse(output: String): GeteventDevices {
        val devices = splitDevices(output)
        val anyProps = devices.any { it.props.isNotEmpty() }
        val excluded = ArrayList<String>()
        val touch = ArrayList<GeteventTouchDevice>()
        val keyed = ArrayList<GeteventKeyDevice>()
        for (device in devices) {
            if (MIRROR_VIRTUAL_DEVICE_NAME.containsMatchIn(device.name)) {
                excluded += device.name
                continue
            }
            val screen = touchScreenOf(device, anyProps)
            if (screen != null) {
                touch += screen
            } else if (device.keys.any { it.startsWith("KEY_") && deviceKeyFor(it) != null }) {
                keyed += GeteventKeyDevice(device.path, device.name)
            }
        }
        return GeteventDevices(touch, keyed, excluded)
    }

    private fun touchScreenOf(device: DeviceBuilder, anyProps: Boolean): GeteventTouchDevice? {
        val mtX = device.abs["ABS_MT_POSITION_X"]
        val mtY = device.abs["ABS_MT_POSITION_Y"]
        val singleX = device.abs["ABS_X"]
        val singleY = device.abs["ABS_Y"]
        val multiTouch = mtX != null && mtY != null
        val x = (if (multiTouch) mtX else singleX) ?: return null
        val y = (if (multiTouch) mtY else singleY) ?: return null
        val direct = PROP_DIRECT in device.props
        val acceptable = when {
            PROP_POINTER in device.props -> false
            direct -> true
            // Older getevent prints no properties at all: a device with touch axes and a touch button is the best available guess.
            !anyProps -> multiTouch || "BTN_TOUCH" in device.keys
            else -> false
        }
        if (!acceptable || x.span <= 1 || y.span <= 1) return null
        return GeteventTouchDevice(device.path, device.name, x, y, slotted = "ABS_MT_SLOT" in device.abs, multiTouch = multiTouch)
    }

    private fun splitDevices(output: String): List<DeviceBuilder> {
        val reader = DumpReader()
        output.lineSequence().forEach(reader::accept)
        return reader.devices
    }

    /** Reads `getevent -lp` line by line: a device header opens a device, a section header the section the following lines belong to. */
    private class DumpReader {
        val devices = ArrayList<DeviceBuilder>()
        private var current: DeviceBuilder? = null
        private var section = ""

        fun accept(line: String) {
            val add = ADD_DEVICE_LINE.find(line)
            val device = current
            when {
                add != null -> {
                    current = DeviceBuilder(add.groupValues[1]).also { devices += it }
                    section = ""
                }
                device == null -> Unit
                DEVICE_NAME_LINE.containsMatchIn(line) -> device.name = DEVICE_NAME_LINE.find(line)?.groupValues?.get(1).orEmpty()
                line.trim().equals(INPUT_PROPS_HEADER, ignoreCase = true) -> section = "PROPS"
                else -> readSectionLine(device, line)
            }
        }

        private fun readSectionLine(device: DeviceBuilder, line: String) {
            val header = SECTION_HEADER.find(line)
            if (header != null) {
                section = header.groupValues[1]
                readSectionBody(device, section, header.groupValues[2])
            } else if (line.startsWith("  ") && section.isNotEmpty()) {
                readSectionBody(device, section, line)
            }
        }
    }
}

private fun readSectionBody(device: DeviceBuilder, section: String, text: String) {
    when (section) {
        "KEY" -> KEY_TOKEN.findAll(text).forEach { device.keys += it.groupValues[1] }
        "ABS" -> ABS_ENTRY.find(text)?.let { match ->
            device.abs[match.groupValues[1]] = GeteventAxis(match.groupValues[2].toInt(), match.groupValues[3].toInt())
        }
        "PROPS" -> PROP_TOKEN.findAll(text).forEach { device.props += it.value }
    }
}

/** One line of `getevent -lt`. [kernelMicros] is the kernel timestamp, [value] the raw text (hex for axes, DOWN/UP/REPEAT for keys). */
internal data class GeteventEvent(val kernelMicros: Long, val path: String, val type: String, val code: String, val value: String) {
    /** The value of an axis event as a signed 32-bit integer; null when it is not hexadecimal. */
    fun axisValue(): Int? = value.toLongOrNull(HEX_RADIX)?.toInt()
}

private const val HEX_RADIX = 16
private const val MICROS_PER_SECOND = 1_000_000L
private val EVENT_LINE = Regex("^\\[\\s*(\\d+)\\.(\\d{1,6})\\]\\s+(\\S+):\\s+(EV_[A-Z]+)\\s+(\\S+)\\s+(\\S+)\\s*$")

/** Parses one `getevent -lt` line; null for anything else (device announcements, errors, partial lines). */
internal fun parseGeteventLine(line: String): GeteventEvent? {
    val match = EVENT_LINE.find(line.trimEnd('\r', '\n')) ?: return null
    val parts = match.groupValues
    val micros = parts[2].padEnd(MICROS_DIGITS, '0').toLong()
    return GeteventEvent(parts[1].toLong() * MICROS_PER_SECOND + micros, parts[3], parts[4], parts[5], parts[6])
}

private const val MICROS_DIGITS = 6

/** The natural (rotation 0) size of the display the touch axes map onto. */
internal data class DisplayGeometry(val naturalWidth: Int, val naturalHeight: Int) {
    /** The logical size of the display at [rotation] quarter turns: width and height swap on an odd rotation. */
    fun logicalSize(rotation: Int): Pair<Int, Int> =
        if (rotation % 2 == 0) naturalWidth to naturalHeight else naturalHeight to naturalWidth
}

/** One touch the parser decided on, with the kernel time it happened. */
internal data class TimedTouch(val command: MirrorControlCommand.Touch, val kernelMicros: Long)

private const val MAX_SLOTS = 32
private const val POINTER_ID_STRIDE = 100L
private const val QUARTER_TURNS = 4

/**
 * Turns `getevent -lt` touch events into [MirrorControlCommand.Touch] DOWN/MOVE/UP values in logical display coordinates: the raw
 * axis position is scaled to natural display pixels, then turned by the display rotation that [rotation] returns at that moment.
 * Handles multi-touch protocol B (slots and tracking ids), protocol A (SYN_MT_REPORT groups) and single-touch screens.
 */
internal class GeteventTouchParser(
    devices: List<GeteventTouchDevice>,
    private val geometry: DisplayGeometry,
    private val rotation: () -> Int,
) {
    private val states = devices.mapIndexed { index, device -> device.path to DeviceState(device, index) }.toMap()

    /** Feeds one event; returns the touches it completes (only a SYN_REPORT completes any). */
    fun accept(event: GeteventEvent): List<TimedTouch> {
        val state = states[event.path] ?: return emptyList()
        state.apply(event)
        return if (event.type == "EV_SYN" && event.code == "SYN_REPORT") state.report(event.kernelMicros) else emptyList()
    }

    private class Contact(var x: Int = 0, var y: Int = 0, var hasX: Boolean = false, var hasY: Boolean = false) {
        val known: Boolean get() = hasX && hasY
    }

    private class Slot {
        val contact = Contact()
        var active = false
        var pendingDown = false
        var pendingUp = false
        var moved = false

        /** The contact is over; protocol B retains this slot's coordinates for its next tracking id. */
        fun release() {
            active = false
            pendingDown = false
            pendingUp = false
        }
    }

    private inner class DeviceState(val device: GeteventTouchDevice, val index: Int) {
        private val slots = Array(MAX_SLOTS) { Slot() }
        private var currentSlot = 0

        // Protocol A: the contacts of the frame being read, and the ones pressed after the last frame (key to pointer index).
        private var pending = Contact()
        private var trackingIdA: Int? = null
        private val frame = ArrayList<Pair<Int?, Contact>>()
        private val pressedA = LinkedHashMap<Int, Contact>()
        private val pressedKeys = LinkedHashMap<Int, Int>()

        // Single touch.
        private val single = Contact()
        private var singleDown = false
        private var singlePendingDown = false
        private var singlePendingUp = false
        private var singleMoved = false

        fun apply(event: GeteventEvent) {
            when {
                device.slotted -> applySlotted(event)
                device.multiTouch -> applyProtocolA(event)
                else -> applySingle(event)
            }
        }

        private fun applySlotted(event: GeteventEvent) {
            if (event.type != "EV_ABS") return
            val value = event.axisValue() ?: return
            val slot = slots[currentSlot]
            when (event.code) {
                "ABS_MT_SLOT" -> if (value in 0 until MAX_SLOTS) currentSlot = value
                "ABS_MT_TRACKING_ID" -> if (value == -1) {
                    slot.pendingUp = slot.active || slot.pendingDown
                } else {
                    slot.pendingDown = true
                    slot.pendingUp = false
                }
                "ABS_MT_POSITION_X" -> {
                    setX(slot.contact, value)
                    slot.moved = true
                }
                "ABS_MT_POSITION_Y" -> {
                    setY(slot.contact, value)
                    slot.moved = true
                }
            }
        }

        private fun applyProtocolA(event: GeteventEvent) {
            when {
                event.type == "EV_ABS" -> {
                    val value = event.axisValue() ?: return
                    when (event.code) {
                        "ABS_MT_POSITION_X" -> setX(pending, value)
                        "ABS_MT_POSITION_Y" -> setY(pending, value)
                        "ABS_MT_TRACKING_ID" -> trackingIdA = value
                    }
                }
                event.type == "EV_SYN" && event.code == "SYN_MT_REPORT" -> {
                    if (pending.known && frame.size < MAX_SLOTS) frame += trackingIdA to pending
                    pending = Contact()
                    trackingIdA = null
                }
            }
        }

        private fun applySingle(event: GeteventEvent) {
            when {
                event.type == "EV_ABS" -> {
                    val value = event.axisValue() ?: return
                    when (event.code) {
                        "ABS_X" -> {
                            setX(single, value)
                            singleMoved = true
                        }
                        "ABS_Y" -> {
                            setY(single, value)
                            singleMoved = true
                        }
                    }
                }
                event.type == "EV_KEY" && event.code == "BTN_TOUCH" -> when (event.value) {
                    "DOWN", "00000001" -> singlePendingDown = true
                    "UP", "00000000" -> singlePendingUp = true
                }
            }
        }

        private fun setX(contact: Contact, value: Int) {
            contact.x = value
            contact.hasX = true
        }

        private fun setY(contact: Contact, value: Int) {
            contact.y = value
            contact.hasY = true
        }

        fun report(at: Long): List<TimedTouch> = when {
            device.slotted -> reportSlotted(at)
            device.multiTouch -> reportProtocolA(at)
            else -> reportSingle(at)
        }

        private fun reportSlotted(at: Long): List<TimedTouch> {
            val out = ArrayList<TimedTouch>()
            slots.forEachIndexed { index, slot ->
                if (slot.pendingDown && slot.contact.known) {
                    out += touch(MirrorTouchAction.DOWN, index, slot.contact, at)
                    slot.active = true
                    slot.pendingDown = false
                    if (slot.pendingUp) {
                        out += touch(MirrorTouchAction.UP, index, slot.contact, at)
                        slot.release()
                    }
                } else if (slot.pendingUp) {
                    if (slot.active) out += touch(MirrorTouchAction.UP, index, slot.contact, at)
                    slot.release()
                } else if (slot.active && slot.moved) {
                    out += touch(MirrorTouchAction.MOVE, index, slot.contact, at)
                }
                slot.moved = false
            }
            return out
        }

        private fun reportProtocolA(at: Long): List<TimedTouch> {
            val out = ArrayList<TimedTouch>()
            val keyed = frame.mapIndexed { order, (id, contact) -> (id ?: -(order + 1)) to contact }
            val present = keyed.map { it.first }.toSet()
            for ((key, contact) in keyed) {
                val before = pressedA[key]
                if (before == null) {
                    val pointer = (0 until MAX_SLOTS).first { it !in pressedKeys.values }
                    pressedKeys[key] = pointer
                    pressedA[key] = Contact(contact.x, contact.y, hasX = true, hasY = true)
                    out += touch(MirrorTouchAction.DOWN, pointer, contact, at)
                } else if (before.x != contact.x || before.y != contact.y) {
                    before.x = contact.x
                    before.y = contact.y
                    out += touch(MirrorTouchAction.MOVE, pressedKeys.getValue(key), contact, at)
                }
            }
            for (key in pressedA.keys.toList()) {
                if (key !in present) {
                    out += touch(MirrorTouchAction.UP, pressedKeys.getValue(key), pressedA.getValue(key), at)
                    pressedA.remove(key)
                    pressedKeys.remove(key)
                }
            }
            frame.clear()
            return out
        }

        private fun reportSingle(at: Long): List<TimedTouch> {
            val out = ArrayList<TimedTouch>()
            if (singlePendingDown && single.known) {
                out += touch(MirrorTouchAction.DOWN, 0, single, at)
                singleDown = true
                singlePendingDown = false
            } else if (singlePendingUp) {
                if (singleDown) out += touch(MirrorTouchAction.UP, 0, single, at)
                singleDown = false
                singlePendingUp = false
                singlePendingDown = false
            } else if (singleDown && singleMoved) {
                out += touch(MirrorTouchAction.MOVE, 0, single, at)
            }
            singleMoved = false
            return out
        }

        private fun touch(action: MirrorTouchAction, pointer: Int, contact: Contact, at: Long): TimedTouch {
            val turns = rotation().mod(QUARTER_TURNS)
            val natural = naturalPixels(contact.x, contact.y)
            val (x, y) = rotated(natural.first, natural.second, turns)
            val (width, height) = geometry.logicalSize(turns)
            return TimedTouch(
                MirrorControlCommand.Touch(
                    action = action,
                    pointerId = index * POINTER_ID_STRIDE + pointer,
                    x = x.coerceIn(0, width - 1),
                    y = y.coerceIn(0, height - 1),
                    pressure = if (action == MirrorTouchAction.UP) 0f else 1f,
                    screenWidth = width,
                    screenHeight = height,
                ),
                at,
            )
        }

        private fun naturalPixels(rawX: Int, rawY: Int): Pair<Int, Int> {
            val x = ((rawX - device.x.min).toLong() * geometry.naturalWidth / device.x.span).toInt()
            val y = ((rawY - device.y.min).toLong() * geometry.naturalHeight / device.y.span).toInt()
            return x.coerceIn(0, geometry.naturalWidth - 1) to y.coerceIn(0, geometry.naturalHeight - 1)
        }

        /** Android's own rule: rotation 90 sends natural (x, y) to (y, W-1-x), 180 to (W-1-x, H-1-y), 270 to (H-1-y, x). */
        private fun rotated(x: Int, y: Int, turns: Int): Pair<Int, Int> = when (turns) {
            1 -> y to geometry.naturalWidth - 1 - x
            2 -> geometry.naturalWidth - 1 - x to geometry.naturalHeight - 1 - y
            3 -> geometry.naturalHeight - 1 - y to x
            else -> x to y
        }
    }
}
