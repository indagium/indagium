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

fun parseLogcat(file: File): List<LogEntry> = parseLogcatLines(file.readLines().asSequence())

// Reusable core: parseLogcat(file) is the common case, but a bug-report zip entry (BugReportZip.kt)
// or a live-tailed file (future work) needs to parse a Sequence<String> without a backing File,
// optionally continuing a tab's existing id counter rather than restarting at 1.
fun parseLogcatLines(lines: Sequence<String>, startId: Int = 1): List<LogEntry> {
    var id = startId
    return lines.mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("-----")) return@mapNotNull null

        RE_THREADTIME.matchEntire(line)?.let { m ->
            return@mapNotNull LogEntry(
                id++, m.groupValues[1].substringAfter(' '),
                LogLevel.from(m.groupValues[4][0]), m.groupValues[5].trim(), m.groupValues[6],
                pid = m.groupValues[2].toIntOrNull() ?: 0,
                tid = m.groupValues[3].toIntOrNull() ?: 0,
            )
        }
        RE_TIME.matchEntire(line)?.let { m ->
            return@mapNotNull LogEntry(
                id++, m.groupValues[1].substringAfter(' '),
                LogLevel.from(m.groupValues[2][0]), m.groupValues[3].trim(), m.groupValues[5],
                pid = m.groupValues[4].toIntOrNull() ?: 0,
            )
        }
        RE_BARE.matchEntire(line)?.let { m ->
            return@mapNotNull LogEntry(id++, m.groupValues[1], LogLevel.from(m.groupValues[2][0]), m.groupValues[3].trim(), m.groupValues[4])
        }
        RE_BRIEF.matchEntire(line)?.let { m ->
            return@mapNotNull LogEntry(
                id++, "", LogLevel.from(m.groupValues[1][0]), m.groupValues[2].trim(), m.groupValues[4],
                pid = m.groupValues[3].toIntOrNull() ?: 0,
            )
        }

        LogEntry(id++, "", LogLevel.I, "RAW", raw.trimEnd())
    }.toList()
}
