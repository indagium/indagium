package com.openlog.utils

import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import java.io.File

// Threadtime:  MM-DD HH:MM:SS.mmm  PID  TID Level Tag: message
private val RE_THREADTIME = Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+([^:]+):\s*(.*)$""")

// Time:        MM-DD HH:MM:SS.mmm Level/Tag( PID): message
private val RE_TIME       = Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+([VDIWEA])/([^(]+)\(\s*(\d+)\):\s*(.*)$""")

// Brief:       Level/Tag( PID): message
private val RE_BRIEF      = Regex("""^([VDIWEA])/([^(]+)\(\s*(\d+)\):\s*(.*)$""")

// Bare time:   HH:MM:SS.mmm Level/Tag: message
private val RE_BARE       = Regex("""^(\d{2}:\d{2}:\d{2}\.\d+)\s+([VDIWEA])/([^:]+):\s*(.*)$""")

// Sniffs the first few KB for NUL bytes (the same heuristic git/most editors use to
// distinguish text from binary) so files can be opened by content, not just by extension.
fun isLikelyTextFile(file: File): Boolean {
    if (!file.isFile) return false
    return runCatching {
        file.inputStream().use { it.readNBytes(8000) }.none { it == 0.toByte() }
    }.getOrDefault(false)
}

fun parseLogcat(file: File): List<LogEntry> {
    var id = 1
    return file.readLines().mapNotNull { raw ->
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
    }
}
