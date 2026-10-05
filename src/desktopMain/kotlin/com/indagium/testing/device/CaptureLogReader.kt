package com.indagium.testing.device

import com.indagium.model.LogEntry
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.containsPattern
import com.indagium.utils.isValidRegexPattern
import com.indagium.utils.parseLogcatLines
import java.io.File
import java.io.RandomAccessFile

// Reads rows out of a growing logcat file a capture recorder is appending to. Everything is addressed by BYTE
// OFFSET into the file: an offset is the file length at some moment (a "marker"), and reading from it returns the
// complete rows written after it. A row still being written (no newline yet) is never returned; the offset after
// the last complete row is, so the next read picks the row up whole.

private const val DEFAULT_CHUNK_BYTES = 1024 * 1024
private const val NEWLINE = '\n'.code.toByte()
private const val CARRIAGE_RETURN = '\r'.code.toByte()

/** One parsed row and the offset of the first byte after it. */
internal data class LogRow(val entry: LogEntry, val endOffset: Long)

/** [rows] are the complete rows read; [endOffset] is where the next read must start. */
internal data class LogChunk(val rows: List<LogRow>, val endOffset: Long)

internal class CaptureLogReader(private val file: File) {
    fun length(): Long = if (file.isFile) file.length() else 0L

    /** Reads up to [maxBytes] of complete rows starting at [fromOffset]; empty when nothing complete has been written yet. */
    fun readRows(fromOffset: Long, maxBytes: Int = DEFAULT_CHUNK_BYTES, firstEntryId: Int = 1): LogChunk {
        require(fromOffset >= 0) { "Log offset must not be negative" }
        val length = length()
        if (fromOffset >= length) return LogChunk(emptyList(), fromOffset)
        val toRead = minOf(length - fromOffset, maxBytes.toLong()).toInt()
        val buffer = ByteArray(toRead)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(fromOffset)
            raf.readFully(buffer)
        }
        // Only whole rows: cut at the last newline. A single row longer than the whole window is taken as it is.
        val lastNewline = buffer.lastIndexOf(NEWLINE)
        val usable = if (lastNewline >= 0) lastNewline + 1 else if (toRead >= maxBytes) toRead else 0
        if (usable == 0) return LogChunk(emptyList(), fromOffset)
        val lines = ArrayList<String>()
        val ends = ArrayList<Long>()
        var lineStart = 0
        for (index in 0 until usable) {
            val atEnd = buffer[index] == NEWLINE || index == usable - 1
            if (!atEnd) continue
            var lineEnd = if (buffer[index] == NEWLINE) index else index + 1
            if (lineEnd > lineStart && buffer[lineEnd - 1] == CARRIAGE_RETURN) lineEnd--
            val text = String(buffer, lineStart, lineEnd - lineStart, Charsets.UTF_8)
            // The parser skips blank rows and the "--------- beginning of" separators; skipping them here keeps rows and entries 1:1.
            if (text.isNotBlank() && !text.trim().startsWith("-----")) {
                lines += text
                ends += fromOffset + index + 1
            }
            lineStart = index + 1
        }
        val entries = parseLogcatLines(lines.asSequence(), firstEntryId)
        return LogChunk(entries.mapIndexed { i, entry -> LogRow(entry, ends[i]) }, fromOffset + usable)
    }
}

/**
 * Matches a parsed row by an optional tag (compared ignoring case) and a regular expression applied to the message
 * with strict regex semantics (case-sensitive unless the pattern says `(?i)`). One instance serves one operation, so
 * the backtracking budget of [RegexEvaluationContext] is per operation.
 */
internal class LogRowMatcher(regex: String?, private val tag: String?) {
    private val pattern = regex?.takeIf { it.isNotEmpty() }
    private val context = RegexEvaluationContext()

    init {
        require(pattern == null || isValidRegexPattern(pattern, ignoreCase = false)) { "Invalid regular expression: $regex" }
    }

    fun matches(entry: LogEntry): Boolean {
        if (tag != null && !entry.tag.equals(tag, ignoreCase = true)) return false
        return pattern == null || containsPattern(entry.msg, pattern, regex = true, ignoreCase = false, regexContext = context)
    }
}
