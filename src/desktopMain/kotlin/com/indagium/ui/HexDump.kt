package com.indagium.ui

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Fewest bytes a string must hold to be shown as a hex dump; shorter runs are ids or numbers more often than bytes. */
internal const val HEX_MIN_BYTES = 8

/** Bytes per dump line. */
internal const val HEX_BYTES_PER_LINE = 16

private const val HEX_DIGITS_PER_BYTE = 2
private const val HEX_RADIX = 16
private const val OFFSET_DIGITS = 4
private const val BYTE_MASK = 0xff
private val SPACED_HEX_PAIRS = Regex("[0-9a-fA-F]{2}( [0-9a-fA-F]{2})*")

/**
 * The bytes [text] spells when it is nothing but hex: space-separated byte pairs ("53 6f 66 74") or one contiguous even-length run
 * ("536F6674"), at least [HEX_MIN_BYTES] bytes either way. A contiguous run must hold at least one a–f letter, so a decimal id such as
 * "1794860790349975040" stays a number; the spaced form is explicit enough to need no such letter. Null when [text] is not hex bytes.
 */
internal fun parseHexBytes(text: String): ByteArray? {
    val digits = when {
        text.contains(' ') -> text.takeIf { SPACED_HEX_PAIRS.matches(it) }?.replace(" ", "")
        text.length % HEX_DIGITS_PER_BYTE == 0 && text.all(::isHexDigit) && text.any { it.lowercaseChar() in 'a'..'f' } -> text
        else -> null
    } ?: return null
    if (digits.length / HEX_DIGITS_PER_BYTE < HEX_MIN_BYTES) return null
    return ByteArray(digits.length / HEX_DIGITS_PER_BYTE) { index ->
        digits.substring(index * HEX_DIGITS_PER_BYTE, (index + 1) * HEX_DIGITS_PER_BYTE).toInt(HEX_RADIX).toByte()
    }
}

private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

/** [bytes] as UTF-8 text when they decode cleanly to printable characters (a tab is allowed), else null. */
internal fun decodeHexText(bytes: ByteArray): String? {
    val text = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull() ?: return null
    return text.takeIf { decoded -> decoded.isNotEmpty() && decoded.none { it != '\t' && Character.isISOControl(it) } }
}

/** "hex · 8 bytes": the dim suffix of a hex field's collapsed line. */
internal fun hexByteCountLabel(byteCount: Int): String = "hex · $byteCount ${if (byteCount == 1) "byte" else "bytes"}"

/** The dump of [bytes]: one entry per [HEX_BYTES_PER_LINE] bytes. */
internal fun hexDumpLines(bytes: ByteArray): List<HexDumpLine> =
    bytes.toList().chunked(HEX_BYTES_PER_LINE).mapIndexed { index, chunk ->
        HexDumpLine(
            offset = (index * HEX_BYTES_PER_LINE).toString(HEX_RADIX).padStart(OFFSET_DIGITS, '0'),
            hex = chunk.joinToString(" ") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_DIGITS_PER_BYTE, '0') },
        )
    }

/** The offset and byte pairs are separate so the view can colour the offset. */
internal data class HexDumpLine(val offset: String, val hex: String)
