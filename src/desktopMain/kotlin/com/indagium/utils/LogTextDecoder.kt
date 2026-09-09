package com.indagium.utils

import java.io.BufferedReader
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Encoding selected for a static log import. UTF-8 remains the fallback so malformed UTF-8 keeps
 * the JDK decoder's normal replacement-character behavior instead of making an otherwise readable
 * file fail to open.
 */
internal enum class LogTextEncoding(
    val charset: Charset,
    val isUtf16: Boolean = false,
) {
    UTF_8(StandardCharsets.UTF_8),
    UTF_8_BOM(StandardCharsets.UTF_8),
    UTF_16_BE(StandardCharsets.UTF_16BE, isUtf16 = true),
    UTF_16_LE(StandardCharsets.UTF_16LE, isUtf16 = true),
}

private const val LOG_ENCODING_SAMPLE_BYTES = 8 * 1024
private const val MIN_UTF16_ZERO_BYTES = 4

private data class LogTextDecoding(
    val encoding: LogTextEncoding,
    val bomLength: Int = 0,
    val leadingBytesToSkip: Int = 0,
)

/**
 * Detects a Unicode BOM first, then recognizes BOM-less UTF-16 using the usual NUL-byte layout:
 * ASCII-heavy UTF-16BE has NULs at even indexes, while UTF-16LE has them at odd indexes. The
 * threshold is deliberately conservative so a binary file with a couple of NULs is not promoted
 * to text merely because its name looks like a log. A split UTF-16LE part can begin with the
 * high byte of the preceding newline (a leading NUL); decoding that part as BE still reconstructs
 * ASCII logcat text correctly, while preserving the important property that the part is treated
 * as UTF-16 rather than UTF-8 with embedded NULs.
 */
internal fun detectLogTextEncoding(sample: ByteArray): LogTextEncoding {
    return detectLogTextDecoding(sample).encoding
}

private fun detectLogTextDecoding(sample: ByteArray): LogTextDecoding {
    if (sample.startsWithBytes(0xEF, 0xBB, 0xBF)) {
        return LogTextDecoding(LogTextEncoding.UTF_8_BOM, bomLength = 3)
    }
    if (sample.startsWithBytes(0xFE, 0xFF)) {
        return LogTextDecoding(LogTextEncoding.UTF_16_BE, bomLength = 2)
    }
    if (sample.startsWithBytes(0xFF, 0xFE)) {
        return LogTextDecoding(LogTextEncoding.UTF_16_LE, bomLength = 2)
    }

    val pairLength = sample.size - (sample.size % 2)
    if (pairLength < MIN_UTF16_ZERO_BYTES * 2) return LogTextDecoding(LogTextEncoding.UTF_8)
    var zeroAtEven = 0
    var zeroAtOdd = 0
    for (index in 0 until pairLength) {
        if (sample[index].toInt() != 0) continue
        if (index % 2 == 0) zeroAtEven++ else zeroAtOdd++
    }
    val dominant = maxOf(zeroAtEven, zeroAtOdd)
    val other = minOf(zeroAtEven, zeroAtOdd)
    // Require several aligned NULs, a meaningful fraction of the sample, and a clear winner.
    // This leaves ordinary UTF-8/control-heavy text on the UTF-8 fallback path.
    if (dominant < MIN_UTF16_ZERO_BYTES || dominant * 8 < pairLength || dominant < other * 2) {
        return LogTextDecoding(LogTextEncoding.UTF_8)
    }
    if (zeroAtEven <= zeroAtOdd) return LogTextDecoding(LogTextEncoding.UTF_16_LE)

    // A UTF-16LE split part starts with the high NUL byte of the preceding newline. In that
    // shifted layout the simple parity test looks like UTF-16BE. For ASCII-only logs both
    // interpretations happen to produce the same text, so only switch to the offset-aware LE
    // interpretation when its decoded sample has materially better text/log structure. This
    // preserves BE detection while keeping non-ASCII messages intact across LE split parts.
    if (sample.firstOrNull()?.toInt() == 0 && sample.size > MIN_UTF16_ZERO_BYTES * 2) {
        val beScore = scoreUtf16Candidate(String(sample, StandardCharsets.UTF_16BE))
        val leScore = scoreUtf16Candidate(String(sample.copyOfRange(1, sample.size), StandardCharsets.UTF_16LE))
        if (leScore > beScore) {
            return LogTextDecoding(LogTextEncoding.UTF_16_LE, leadingBytesToSkip = 1)
        }
    }
    return LogTextDecoding(LogTextEncoding.UTF_16_BE)
}

/**
 * Opens a reader after looking at a small prefix. The prefix is pushed back so detection does not
 * consume content; only a detected BOM is omitted from the decoder input. This works for regular
 * files, bounded compressed streams, archive entries, nested tar entries, and split parts alike.
 */
internal fun openLogTextReader(stream: InputStream): BufferedReader {
    val pushback = PushbackInputStream(stream, LOG_ENCODING_SAMPLE_BYTES)
    val sample = pushback.readNBytes(LOG_ENCODING_SAMPLE_BYTES)
    val decoding = detectLogTextDecoding(sample)
    val encoding = decoding.encoding
    // A BOM-less split part uses the same UTF-16 enum value but must retain its first two bytes.
    val contentOffset = (decoding.bomLength + decoding.leadingBytesToSkip).coerceAtMost(sample.size)
    if (sample.size > contentOffset) {
        pushback.unread(sample, contentOffset, sample.size - contentOffset)
    }
    val hasUtf16Bom = decoding.bomLength > 0 && encoding.isUtf16
    // A BOM-less UTF-16BE split part is already code-unit aligned. A BOM-less UTF-16LE file (or
    // the first part of one) can legitimately end on the low byte of a newline, so pad that case;
    // shifted LE parts are detected as BE and must not be padded, or their final high NUL would
    // become a spurious U+0000 RAW row.
    val needsUtf16Padding = hasUtf16Bom || encoding == LogTextEncoding.UTF_16_LE && decoding.leadingBytesToSkip == 0
    val decodedInput = if (encoding.isUtf16) {
        Utf16BoundaryInputStream(pushback, padIncompleteByte = needsUtf16Padding)
    } else {
        pushback
    }
    return BufferedReader(InputStreamReader(decodedInput, encoding.charset))
}

/** Returns true when a file is a UTF-16 source that must not be live-tailed as UTF-8 bytes. */
internal fun isUtf16LogFile(file: File): Boolean =
    if (!file.isFile) false else runCatching {
        file.inputStream().use { stream ->
            val sample = stream.readNBytes(LOG_ENCODING_SAMPLE_BYTES)
            detectLogTextEncoding(sample).isUtf16
        }
    }.getOrDefault(false)

internal fun isLikelyTextSample(sample: ByteArray): Boolean =
    sample.none { it.toInt() == 0 } || detectLogTextEncoding(sample).isUtf16

private fun scoreUtf16Candidate(text: String): Int {
    val stable = text.trimEnd('\uFFFD')
    var score = stable.count { it == '\n' } * 100
    for (char in stable) {
        score += when {
            char == '\u0000' -> -20
            char == '\t' || char == '\r' || char == '\n' -> 0
            char.isISOControl() -> -10
            char.isLetterOrDigit() || char in " .,:;!?/\\-_()[]{}@#%+*=<>|\"'" -> 1
            else -> 0
        }
    }
    return score
}

private fun ByteArray.startsWithBytes(vararg expected: Int): Boolean {
    if (size < expected.size) return false
    return expected.indices.all { index -> (this[index].toInt() and 0xFF) == expected[index] }
}

/**
 * SplitWriter rotates at the first raw LF byte. For UTF-16LE that byte is the first half of the
 * newline code unit, leaving its trailing NUL at the start of the next part and an odd byte at the
 * end of the previous part. Pad an incomplete code unit for an unshifted UTF-16LE part, and drop
 * the unmatched trailing byte from a shifted part, so a split boundary remains a real line
 * boundary instead of becoming a replacement-character/RAW row. Complete UTF-16 input is passed
 * through byte-for-byte.
 */
private class Utf16BoundaryInputStream(
    delegate: InputStream,
    private val padIncompleteByte: Boolean,
) : FilterInputStream(delegate) {
    private val sourceBuffer = ByteArray(8 * 1024)
    private var pendingByte = -1
    private var totalByteParity = 0
    private var eof = false

    override fun read(): Int {
        val one = ByteArray(1)
        while (true) {
            val count = read(one, 0, 1)
            if (count < 0) return -1
            if (count > 0) return one[0].toInt() and 0xFF
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (eof) return -1
        val count = super.read(sourceBuffer, 0, minOf(length, sourceBuffer.size))
        if (count > 0) {
            var written = 0
            for (index in 0 until count) {
                if (pendingByte >= 0) {
                    buffer[offset + written++] = pendingByte.toByte()
                }
                pendingByte = sourceBuffer[index].toInt() and 0xFF
                totalByteParity = totalByteParity xor 1
            }
            return written
        }
        if (count == 0) return 0

        eof = true
        if (pendingByte < 0) return -1
        val finalByte = pendingByte
        pendingByte = -1
        return if (totalByteParity == 0) {
            buffer[offset] = finalByte.toByte()
            1
        } else if (padIncompleteByte) {
            buffer[offset] = 0
            1
        } else {
            -1
        }
    }
}
