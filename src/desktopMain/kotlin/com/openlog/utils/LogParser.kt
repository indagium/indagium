package com.openlog.utils

import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import java.io.File
import java.io.InputStream

// Threadtime:  MM-DD HH:MM:SS.mmm  PID  TID Level Tag: message
private val RE_THREADTIME = Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+([^:]+):\s*(.*)$""")

// Time:        MM-DD HH:MM:SS.mmm Level/Tag( PID): message
private val RE_TIME       = Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+([VDIWEA])/([^(]+)\(\s*(\d+)\):\s*(.*)$""")

// Brief:       Level/Tag( PID): message
private val RE_BRIEF      = Regex("""^([VDIWEA])/([^(]+)\(\s*(\d+)\):\s*(.*)$""")

// Bare time:   HH:MM:SS.mmm Level/Tag: message
private val RE_BARE       = Regex("""^(\d{2}:\d{2}:\d{2}\.\d+)\s+([VDIWEA])/([^:]+):\s*(.*)$""")

private const val TEXT_SNIFF_BYTES = 8000

// Sniffs the first few KB for NUL bytes (the same heuristic git/most editors use to
// distinguish text from binary) so files can be opened by content, not just by extension.
fun isLikelyTextFile(file: File): Boolean {
    if (!file.isFile) return false
    return runCatching { file.inputStream().use { isLikelyTextStream(it) } }.getOrDefault(false)
}

// Same NUL-byte sniff as isLikelyTextFile, but for a stream that isn't backed by a File — e.g.
// a zip entry (see BugReportZip.kt), which can't be opened as a File without extracting it.
fun isLikelyTextStream(stream: InputStream): Boolean =
    runCatching { stream.readNBytes(TEXT_SNIFF_BYTES).none { it == 0.toByte() } }.getOrDefault(false)

fun parseLogcat(file: File): List<LogEntry> =
    file.bufferedReader().useLines { lines -> parseLogcatLines(lines) }

// Reusable core: parseLogcat(file) is the common case, but a bug-report zip entry (BugReportZip.kt)
// or a live-tailed file (future work) needs to parse a Sequence<String> without a backing File,
// optionally continuing a tab's existing id counter rather than restarting at 1.
fun parseLogcatLines(lines: Sequence<String>, startId: Int = 1): List<LogEntry> {
    var id = startId
    // A multi-GB logcat has millions of lines but only a handful of distinct tags — interning
    // them collapses millions of duplicate Strings into shared references (hundreds of MB saved).
    val tagCache = HashMap<String, String>()

    fun intern(tag: String): String = tagCache.getOrPut(tag) { tag }

    return lines.mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("-----")) return@mapNotNull null

        parseThreadtimeFast(line)?.let { p ->
            return@mapNotNull LogEntry(id++, p.ts, p.level, intern(p.tag), p.msg, pid = p.pid, tid = p.tid)
        }
        RE_THREADTIME.matchEntire(line)?.let { m ->
            return@mapNotNull LogEntry(
                id++, m.groupValues[1].substringAfter(' '),
                LogLevel.from(m.groupValues[4][0]), intern(m.groupValues[5].trim()), m.groupValues[6],
                pid = m.groupValues[2].toIntOrNull() ?: 0,
                tid = m.groupValues[3].toIntOrNull() ?: 0,
            )
        }
        RE_TIME.matchEntire(line)?.let { m ->
            return@mapNotNull LogEntry(
                id++, m.groupValues[1].substringAfter(' '),
                LogLevel.from(m.groupValues[2][0]), intern(m.groupValues[3].trim()), m.groupValues[5],
                pid = m.groupValues[4].toIntOrNull() ?: 0,
            )
        }
        RE_BARE.matchEntire(line)?.let { m ->
            return@mapNotNull LogEntry(id++, m.groupValues[1], LogLevel.from(m.groupValues[2][0]), intern(m.groupValues[3].trim()), m.groupValues[4])
        }
        RE_BRIEF.matchEntire(line)?.let { m ->
            return@mapNotNull LogEntry(
                id++, "", LogLevel.from(m.groupValues[1][0]), intern(m.groupValues[2].trim()), m.groupValues[4],
                pid = m.groupValues[3].toIntOrNull() ?: 0,
            )
        }

        LogEntry(id++, "", LogLevel.I, "RAW", raw.trimEnd())
    }.toList()
}

private class ThreadtimeParts(
    val ts: String,
    val level: LogLevel,
    val tag: String,
    val msg: String,
    val pid: Int,
    val tid: Int,
)

private const val LEVEL_CHARS = "VDIWEA"

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

// Space or tab only — deliberately narrower than regex \s (which also matches \n\r\f\x0B, chars
// that can't survive line splitting anyway); anything narrower just falls back to the regex.
private fun Char.isSeparator(): Boolean = this == ' ' || this == '\t'

// Hand-rolled equivalent of RE_THREADTIME for the format that realistically appears at >1GB —
// several times faster than four sequential Regex.matchEntire attempts per line. Deliberately
// accepts a subset of what the regex accepts and produces identical fields for those lines;
// anything it can't parse falls back to the regex chain, so behavior can only match, never
// diverge (e.g. digit/whitespace classes here are ASCII-only, stricter than \d/\s).
@Suppress("ReturnCount", "CyclomaticComplexMethod")
private fun parseThreadtimeFast(line: String): ThreadtimeParts? {
    val n = line.length
    var i = 0

    fun digits(from: Int, count: Int): Boolean {
        if (from + count > n) return false
        for (k in from until from + count) if (!line[k].isAsciiDigit()) return false
        return true
    }

    // MM-DD
    if (n < 5 || !digits(0, 2) || line[2] != '-' || !digits(3, 2)) return null
    i = 5
    val dateEnd = i
    while (i < n && line[i].isSeparator()) i++
    if (i == dateEnd) return null
    // HH:MM:SS.d+
    if (i + 8 >= n || !digits(i, 2) || !digits(i + 3, 2) || !digits(i + 6, 2)) return null
    if (line[i + 2] != ':' || line[i + 5] != ':' || line[i + 8] != '.') return null
    i += 9
    val fracStart = i
    while (i < n && line[i].isAsciiDigit()) i++
    if (i == fracStart) return null
    // Regex path: group(1) is "MM-DD<ws>HH:MM:SS.d+", then .substringAfter(' ').
    val ts = line.substring(0, i).substringAfter(' ')

    fun spacesThenInt(start: Int): Pair<Int, Int>? {
        var p = start
        while (p < n && line[p].isSeparator()) p++
        if (p == start) return null
        val numStart = p
        var value = 0L
        var overflow = false
        while (p < n && line[p].isAsciiDigit()) {
            value = value * 10 + (line[p] - '0')
            if (value > Int.MAX_VALUE) {
                overflow = true
                value = 0
            }
            p++
        }
        if (p == numStart) return null
        // toIntOrNull-compatible: an overflowing pid/tid becomes 0, same as the regex path.
        return (if (overflow) 0 else value.toInt()) to p
    }

    val (pid, afterPid) = spacesThenInt(i) ?: return null
    val (tid, afterTid) = spacesThenInt(afterPid) ?: return null

    var p = afterTid
    while (p < n && line[p].isSeparator()) p++
    if (p == afterTid || p >= n || line[p] !in LEVEL_CHARS) return null
    val level = LogLevel.from(line[p])
    p++
    val beforeTag = p
    while (p < n && line[p].isSeparator()) p++
    if (p == beforeTag) return null
    val tagStart = p
    val colon = line.indexOf(':', tagStart)
    if (colon <= tagStart) return null
    val tag = line.substring(tagStart, colon).trim()
    if (tag.isEmpty()) return null
    p = colon + 1
    while (p < n && line[p].isSeparator()) p++
    return ThreadtimeParts(ts, level, tag, line.substring(p), pid, tid)
}
