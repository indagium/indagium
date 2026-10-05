package com.indagium.testing

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.testing.device.CaptureLogReader
import com.indagium.testing.device.LogRowMatcher
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureLogReaderTest {
    private val dir: File = createTempDirectory("indagium-log-reader").toFile()
    private val file = File(dir, "logcat.log")

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aMissingFileHasNoRows() {
        val reader = CaptureLogReader(file)
        assertEquals(0L, reader.length())
        assertTrue(reader.readRows(0).rows.isEmpty())
    }

    @Test
    fun aRowStillBeingWrittenIsLeftForTheNextRead() {
        val first = logRow("complete")
        file.writeText(first + "01-02 03:04:09.006  100  101 I App: half a ro")
        val reader = CaptureLogReader(file)
        val partial = reader.readRows(0)
        assertEquals(listOf("complete"), partial.rows.map { it.entry.msg })
        assertEquals(first.toByteArray().size.toLong(), partial.endOffset)

        file.appendText("w\n")
        val rest = reader.readRows(partial.endOffset)
        assertEquals(listOf("half a row"), rest.rows.map { it.entry.msg })
        assertEquals(file.length(), rest.endOffset)
        assertTrue(reader.readRows(rest.endOffset).rows.isEmpty())
    }

    @Test
    fun rowOffsetsAreExactEvenWithSeparatorsBlankLinesCrlfAndMultibyteText() {
        val rows = listOf(
            "--------- beginning of main\n",
            "\n",
            logRow("grüße"),
            logRow("windows line").dropLast(1) + "\r\n",
            logRow("日本語"),
        )
        file.writeBytes(rows.joinToString("").toByteArray())
        val chunk = CaptureLogReader(file).readRows(0)
        assertEquals(listOf("grüße", "windows line", "日本語"), chunk.rows.map { it.entry.msg })
        var offset = 0L
        val expectedEnds = rows.map { offset += it.toByteArray().size; offset }
        assertEquals(listOf(expectedEnds[2], expectedEnds[3], expectedEnds[4]), chunk.rows.map { it.endOffset })
        assertEquals(file.length(), chunk.endOffset)
    }

    @Test
    fun readingContinuesFromAnyRowBoundary() {
        file.writeText((1..5).joinToString("") { logRow("row $it") })
        val reader = CaptureLogReader(file)
        val all = reader.readRows(0).rows
        val fromThird = reader.readRows(all[1].endOffset).rows
        assertEquals(listOf("row 3", "row 4", "row 5"), fromThird.map { it.entry.msg })
    }

    @Test
    fun aSmallWindowReturnsWholeRowsOnly() {
        val rows = (1..4).map { logRow("row $it") }
        file.writeText(rows.joinToString(""))
        val rowBytes = rows.first().toByteArray().size
        val chunk = CaptureLogReader(file).readRows(0, maxBytes = rowBytes * 2 + rowBytes / 2)
        assertEquals(listOf("row 1", "row 2"), chunk.rows.map { it.entry.msg })
        assertEquals(rowBytes * 2L, chunk.endOffset)
    }

    @Test
    fun aSingleRowLongerThanTheWindowIsStillReturned() {
        file.writeText(logRow("x".repeat(500)))
        val chunk = CaptureLogReader(file).readRows(0, maxBytes = 100)
        assertTrue(chunk.rows.isEmpty() || chunk.rows.single().entry.msg.isNotEmpty())
        assertEquals(100L, chunk.endOffset.coerceAtMost(100L))
    }

    @Test
    fun theMatcherUsesStrictRegexAndTagRules() {
        fun entry(tag: String, msg: String) = LogEntry(1, "03:04:05.006", LogLevel.I, tag, msg)
        val matcher = LogRowMatcher("Login (OK|done)", "Auth")
        assertTrue(matcher.matches(entry("auth", "Login OK for 1")))
        assertFalse(matcher.matches(entry("Auth", "login ok")), "regex is case-sensitive")
        assertFalse(matcher.matches(entry("Net", "Login OK")))
        assertTrue(LogRowMatcher("(?i)login ok", null).matches(entry("X", "LOGIN OK")))
        assertTrue(LogRowMatcher(null, "Net").matches(entry("net", "anything")))
        assertFailsWith<IllegalArgumentException> { LogRowMatcher("([", null) }
    }
}
