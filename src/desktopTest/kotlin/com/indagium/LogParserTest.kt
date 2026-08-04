package com.indagium

import com.indagium.model.LogLevel
import com.indagium.utils.parseLogcat
import com.indagium.utils.parseMillisOfDay
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

    @Test
    fun preservesLongRawLinesInsteadOfDroppingThem() {
        val longLine = "payload=" + "x".repeat(9000)
        val file = createTempFile(prefix = "openlog-long", suffix = ".txt")
        file.writeText(longLine)

        val entries = parseLogcat(file.toFile())

        assertEquals(1, entries.size)
        assertEquals("RAW", entries.single().tag)
        assertEquals(longLine, entries.single().msg)
    }

    // ── Format-specific parsing ──────────────────────────────────────────────

    @Test
    fun parsesThreadtimeFormat() {
        val file = createTempFile(prefix = "openlog-tt", suffix = ".log")
        file.writeText("06-29 12:34:56.789 1234 5678 D MyApp: Hello world")

        val entries = parseLogcat(file.toFile())

        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("12:34:56.789", e.ts)
        assertEquals(LogLevel.D, e.level)
        assertEquals("MyApp", e.tag)
        assertEquals("Hello world", e.msg)
        assertEquals(1234, e.pid)
        assertEquals(5678, e.tid)
    }

    @Test
    fun parsesTimeFormat() {
        val file = createTempFile(prefix = "openlog-time", suffix = ".log")
        file.writeText("06-29 12:34:56.789 W/NetworkStack( 999): Timeout")

        val entries = parseLogcat(file.toFile())

        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("12:34:56.789", e.ts)
        assertEquals(LogLevel.W, e.level)
        assertEquals("NetworkStack", e.tag)
        assertEquals("Timeout", e.msg)
        assertEquals(999, e.pid)
        assertEquals(0, e.tid)
    }

    @Test
    fun parsesBriefFormat() {
        val file = createTempFile(prefix = "openlog-brief", suffix = ".log")
        file.writeText("E/CrashHandler( 42): Fatal error")

        val entries = parseLogcat(file.toFile())

        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("", e.ts)
        assertEquals(LogLevel.E, e.level)
        assertEquals("CrashHandler", e.tag)
        assertEquals("Fatal error", e.msg)
        assertEquals(42, e.pid)
        assertEquals(0, e.tid)
    }

    @Test
    fun parsesBareFormat() {
        val file = createTempFile(prefix = "openlog-bare", suffix = ".log")
        file.writeText("12:34:56.789 V/BtGatt.GattService: onServerConnected")

        val entries = parseLogcat(file.toFile())

        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("12:34:56.789", e.ts)
        assertEquals(LogLevel.V, e.level)
        assertEquals("BtGatt.GattService", e.tag)
        assertEquals("onServerConnected", e.msg)
        assertEquals(0, e.pid)
        assertEquals(0, e.tid)
    }

    @Test
    fun filtersSeparatorLines() {
        val file = createTempFile(prefix = "openlog-sep", suffix = ".log")
        file.writeText(
            """
            --------- beginning of main
            06-29 12:34:56.789 1 1 I App: Started
            --------- beginning of system
            """.trimIndent(),
        )

        val entries = parseLogcat(file.toFile())

        assertEquals(1, entries.size)
        assertEquals("App", entries.single().tag)
    }

    @Test
    fun emptyFileProducesNoEntries() {
        val file = createTempFile(prefix = "openlog-empty", suffix = ".log")
        file.writeText("")

        val entries = parseLogcat(file.toFile())

        assertEquals(0, entries.size)
    }

    @Test
    fun lineWithNonNumericPidFallsBackToRaw() {
        // Brief format requires numeric PID — non-numeric fails regex → RAW
        val file = createTempFile(prefix = "openlog-badpid", suffix = ".log")
        file.writeText("D/MyApp(???): message with bad pid")

        val entries = parseLogcat(file.toFile())

        assertEquals(1, entries.size)
        assertEquals("RAW", entries.single().tag)
    }

    @Test
    fun mixedFormatFileAllEntriesParsed() {
        val file = createTempFile(prefix = "openlog-multi", suffix = ".log")
        file.writeText(
            """
            06-29 10:00:00.000 100 200 I ThreadTime: threadtime line
            06-29 10:00:01.000 I/TimeFormat( 100): time line
            10:00:02.000 D/BareFormat: bare line
            V/BriefFormat( 100): brief line
            """.trimIndent(),
        )

        val entries = parseLogcat(file.toFile())

        assertEquals(4, entries.size)
        assertEquals("ThreadTime", entries[0].tag)
        assertEquals("TimeFormat", entries[1].tag)
        assertEquals("BareFormat", entries[2].tag)
        assertEquals("BriefFormat", entries[3].tag)
    }

    @Test
    fun unicodeMessagePreservedInRoundTrip() {
        val file = createTempFile(prefix = "openlog-unicode", suffix = ".log")
        file.writeText("06-29 12:34:56.789 1 1 I App: emoji 😀 and CJK 中文")

        val entries = parseLogcat(file.toFile())

        assertEquals(1, entries.size)
        assertEquals("emoji 😀 and CJK 中文", entries.single().msg)
    }

    // ts must come out as a bare, immediately-parseable "HH:MM:SS.frac" no matter how the log
    // separated the date column from the time — dumpstate/bug-report logs use two spaces or a tab
    // where plain `adb logcat` uses one. Stripping the date with substringAfter(' ') left a leading
    // space (two spaces) or the whole date (tab), both of which LogTime.parseMillisOfDay rejects,
    // so every row silently became untimestamped: no Δt column and, because the elapsed timeline
    // came out empty, no video<->log time mapping at all.
    @Test
    fun stripsTheDatePrefixForEverySeparatorWidth() {
        val separators = mapOf("one space" to " ", "two spaces" to "  ", "tab" to "\t", "tab and space" to "\t ")
        for ((label, separator) in separators) {
            val file = createTempFile(prefix = "openlog-sep", suffix = ".txt")
            file.writeText("06-29${separator}12:34:56.789  1234  5678 I App: hello")

            val entry = parseLogcat(file.toFile()).single()

            assertEquals("12:34:56.789", entry.ts, "ts for $label separator")
            assertEquals(45_296_789L, parseMillisOfDay(entry.ts), "parsed millis for $label separator")
        }
    }

    // Same requirement for the `-v time` format, which shares the dated-timestamp capture group.
    @Test
    fun stripsTheDatePrefixForTheTimeFormatToo() {
        val file = createTempFile(prefix = "openlog-time-sep", suffix = ".txt")
        file.writeText("06-29  12:34:56.789 I/App( 1234): hello")

        val entry = parseLogcat(file.toFile()).single()

        assertEquals("12:34:56.789", entry.ts)
        assertEquals(45_296_789L, parseMillisOfDay(entry.ts))
    }
}
