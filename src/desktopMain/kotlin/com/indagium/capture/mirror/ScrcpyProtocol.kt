@file:Suppress("MagicNumber")

package com.indagium.capture.mirror

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Device-side coordinates after viewport conversion. */
internal data class DevicePoint(val x: Int, val y: Int)

/** Maps a fitted mirror surface to device coordinates, including quarter-turn rotation. */
internal class MirrorCoordinateMapper(
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    private val deviceWidth: Int,
    private val deviceHeight: Int,
    private val rotationDegrees: Int = 0,
) {
    init {
        require(viewportWidth > 0 && viewportHeight > 0) { "viewport dimensions must be positive" }
        require(deviceWidth > 0 && deviceHeight > 0) { "device dimensions must be positive" }
        require(rotationDegrees % 90 == 0) { "rotation must be a multiple of 90 degrees" }
    }

    val screenWidth: Int get() = deviceWidth
    val screenHeight: Int get() = deviceHeight

    fun map(viewportX: Float, viewportY: Float): DevicePoint? {
        val rotated = rotationDegrees.absoluteQuarterTurns() % 2 == 1
        val contentWidth = if (rotated) deviceHeight else deviceWidth
        val contentHeight = if (rotated) deviceWidth else deviceHeight
        val scale = minOf(viewportWidth.toFloat() / contentWidth, viewportHeight.toFloat() / contentHeight)
        val drawnWidth = contentWidth * scale
        val drawnHeight = contentHeight * scale
        val left = (viewportWidth - drawnWidth) / 2f
        val top = (viewportHeight - drawnHeight) / 2f
        if (viewportX < left || viewportX >= left + drawnWidth || viewportY < top || viewportY >= top + drawnHeight) {
            return null
        }
        val x = ((viewportX - left) / scale).roundToInt().coerceIn(0, contentWidth - 1)
        val y = ((viewportY - top) / scale).roundToInt().coerceIn(0, contentHeight - 1)
        return when (rotationDegrees.mod(360)) {
            0 -> DevicePoint(x, y)
            90 -> DevicePoint(deviceWidth - 1 - y, x)
            180 -> DevicePoint(deviceWidth - 1 - x, deviceHeight - 1 - y)
            270 -> DevicePoint(y, deviceHeight - 1 - x)
            else -> error("unreachable rotation")
        }
    }
}

internal enum class MirrorTouchAction(val wireValue: Int) { DOWN(0), UP(1), MOVE(2), CANCEL(3) }

internal enum class MirrorKeyAction(val wireValue: Int) { DOWN(0), UP(1) }

internal enum class MirrorClipboardCopyKey(val wireValue: Int) { COPY(0), CUT(1), NONE(2) }

internal sealed interface MirrorControlCommand {
    data class Touch(
        val action: MirrorTouchAction,
        val pointerId: Long,
        val x: Int,
        val y: Int,
        val pressure: Float = 1f,
        val actionButton: Long = 0,
        val buttons: Long = 0,
        val screenWidth: Int = 1,
        val screenHeight: Int = 1,
    ) : MirrorControlCommand {
        init {
            require(screenWidth > 0 && screenHeight > 0) { "touch screen dimensions must be positive" }
            require(screenWidth <= 0xffff && screenHeight <= 0xffff) {
                "touch screen dimensions must fit scrcpy's unsigned 16-bit fields"
            }
        }
    }

    data class Key(
        val action: MirrorKeyAction,
        val keycode: Int,
        val repeat: Int = 0,
        val metaState: Int = 0,
    ) : MirrorControlCommand

    data class Text(val value: String) : MirrorControlCommand

    data class Clipboard(val value: String, val paste: Boolean = false, val sequence: Long = 0) : MirrorControlCommand

    data class GetClipboard(val copyKey: MirrorClipboardCopyKey = MirrorClipboardCopyKey.NONE) : MirrorControlCommand

    data class Back(val action: MirrorKeyAction = MirrorKeyAction.UP) : MirrorControlCommand

    data object ExpandNotifications : MirrorControlCommand

    data object ExpandSettings : MirrorControlCommand

    data object CollapsePanels : MirrorControlCommand

    data object SetDisplayPowerOn : MirrorControlCommand

    data object RotateDevice : MirrorControlCommand

    data object CollapseNotifications : MirrorControlCommand
}

/** Encodes the scrcpy control socket's big-endian command messages. */
internal object ScrcpyControlEncoder {
    fun encode(command: MirrorControlCommand): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            when (command) {
                is MirrorControlCommand.Touch -> {
                    output.writeByte(2)
                    output.writeByte(command.action.wireValue)
                    output.writeLong(command.pointerId)
                    output.writeInt(command.x)
                    output.writeInt(command.y)
                    output.writeShort(command.screenWidth)
                    output.writeShort(command.screenHeight)
                    output.writeShort((command.pressure.coerceIn(0f, 1f) * 0xffff).roundToInt())
                    output.writeInt(command.actionButton.toInt())
                    output.writeInt(command.buttons.toInt())
                }
                is MirrorControlCommand.Key -> {
                    output.writeByte(0)
                    output.writeByte(command.action.wireValue)
                    output.writeInt(command.keycode)
                    output.writeInt(command.repeat)
                    output.writeInt(command.metaState)
                }
                is MirrorControlCommand.Text -> {
                    output.writeByte(1)
                    output.writeInt(command.value.toByteArray(Charsets.UTF_8).size)
                    output.write(command.value.toByteArray(Charsets.UTF_8))
                }
                is MirrorControlCommand.Clipboard -> {
                    output.writeByte(9)
                    output.writeLong(command.sequence)
                    output.writeBoolean(command.paste)
                    val text = command.value.toByteArray(Charsets.UTF_8)
                    output.writeInt(text.size)
                    output.write(text)
                }
                is MirrorControlCommand.GetClipboard -> {
                    output.writeByte(8)
                    output.writeByte(command.copyKey.wireValue)
                }
                is MirrorControlCommand.Back -> {
                    output.writeByte(4)
                    output.writeByte(command.action.wireValue)
                }
                MirrorControlCommand.ExpandNotifications -> output.writeByte(5)
                MirrorControlCommand.ExpandSettings -> output.writeByte(6)
                MirrorControlCommand.CollapsePanels -> output.writeByte(7)
                MirrorControlCommand.CollapseNotifications -> output.writeByte(7)
                MirrorControlCommand.SetDisplayPowerOn -> {
                    output.writeByte(10)
                    output.writeByte(1)
                }
                MirrorControlCommand.RotateDevice -> output.writeByte(11)
            }
        }
    }.toByteArray()
}

/** Extracts Annex-B NAL units without retaining an unbounded stream. */
internal class H264AnnexBFramer {
    private var pending = ByteArray(0)

    fun append(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        pending += bytes
        val starts = mutableListOf<Int>()
        var index = 0
        while (index + 3 < pending.size) {
            val length = when {
                pending[index] == 0.toByte() && pending[index + 1] == 0.toByte() &&
                    pending[index + 2] == 1.toByte() -> 3
                index + 4 < pending.size && pending[index] == 0.toByte() && pending[index + 1] == 0.toByte() &&
                    pending[index + 2] == 0.toByte() && pending[index + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (length != 0) {
                starts += index
                index += length
            } else {
                index++
            }
        }
        if (starts.size < 2) return emptyList()
        val output = starts.dropLast(1).map { start -> pending.copyOfRange(start, starts[starts.indexOf(start) + 1]) }
        pending = pending.copyOfRange(starts.last(), pending.size)
        return output
    }

    fun finish(): List<ByteArray> {
        if (pending.isEmpty()) return emptyList()
        val result = listOf(pending)
        pending = ByteArray(0)
        return result
    }
}

/** Small helper for tests and any future length-prefixed server extension. */
internal class BigEndianLengthFramer(private val maxPacketBytes: Int = 16 * 1024 * 1024) {
    private var pending = ByteArray(0)

    init { require(maxPacketBytes > 0) }

    fun append(bytes: ByteArray): List<ByteArray> {
        pending += bytes
        val packets = mutableListOf<ByteArray>()
        while (pending.size >= 4) {
            val length = ByteBuffer.wrap(pending, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            require(length in 0..maxPacketBytes) { "packet length $length exceeds limit $maxPacketBytes" }
            if (pending.size < 4 + length) break
            packets += pending.copyOfRange(4, 4 + length)
            pending = pending.copyOfRange(4 + length, pending.size)
        }
        return packets
    }
}

private fun Int.absoluteQuarterTurns(): Int = (this.mod(360) + 360).mod(360) / 90
