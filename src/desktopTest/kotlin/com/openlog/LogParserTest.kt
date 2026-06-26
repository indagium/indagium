package com.openlog

import com.openlog.model.LogLevel
import com.openlog.utils.parseLogcat
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class LogParserTest {
    @Test
    fun keepsRawLinesWhenNoLogcatFormatMatches() {
        val file = createTempFile(prefix = "openlog-raw", suffix = ".txt")
        file.writeText(
            """
            first plain line
            second plain line
            """.trimIndent(),
        )

        val entries = parseLogcat(file.toFile())

        assertEquals(2, entries.size)
        assertEquals(LogLevel.I, entries[0].level)
        assertEquals("RAW", entries[0].tag)
        assertEquals("first plain line", entries[0].msg)
        assertEquals("second plain line", entries[1].msg)
    }

    @Test
    fun keepsUnmatchedLinesAlongsideParsedLogcatRows() {
        val file = createTempFile(prefix = "openlog-mixed", suffix = ".log")
        file.writeText(
            """
            10:00:00.000 I/App: Parsed line
                at com.example.Crash.method(Crash.kt:12)
            """.trimIndent(),
        )

        val entries = parseLogcat(file.toFile())

        assertEquals(2, entries.size)
        assertEquals("App", entries[0].tag)
        assertEquals("Parsed line", entries[0].msg)
        assertEquals("RAW", entries[1].tag)
        assertEquals("    at com.example.Crash.method(Crash.kt:12)", entries[1].msg)
    }
}
