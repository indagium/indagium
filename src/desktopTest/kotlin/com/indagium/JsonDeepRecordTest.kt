package com.indagium

import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.LogEntry
import com.indagium.utils.IncrementalProfileParser
import com.indagium.utils.parseLogContent
import com.indagium.utils.planSplitOutputs
import com.indagium.utils.splitProfileRecordsToFiles
import java.io.ByteArrayInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DEEP_LEVELS = 130
private const val CLOCK = "deep-json-clock"

/** A JSON record nested deeper than the parser's depth limit is well-formed: it is kept as raw text and the events after it survive. */
class JsonDeepRecordTest {
    private val profile = BUILTIN_LOG_FORMAT_PROFILES.first { it.id == "json-lines" }

    private val deep = "{\"k\":".repeat(DEEP_LEVELS) + "1" + "}".repeat(DEEP_LEVELS)
    private val a = """{"message":"a"}"""
    private val b = """{"message":"b"}"""

    private fun whole(lines: List<String>): List<LogEntry> =
        parseLogContent(ByteArrayInputStream((lines.joinToString("\n") + "\n").toByteArray()), profile = profile, sourceClockId = CLOCK).entries

    @Test
    fun aDeepRecordIsKeptAsRawTextAndTheEventsAroundItOnTheSameLineSurvive() {
        val entries = whole(listOf(a + deep + b))
        assertEquals(listOf(a, deep, b), entries.map { it.rawRecord })
        assertEquals("a", entries[0].msg)
        assertEquals("b", entries[2].msg)
    }

    @Test
    fun aDeepRecordInAMultiLineFileKeepsTheLaterLines() {
        val entries = whole(listOf(a, deep + b, """{"message":"c"}"""))
        assertEquals(listOf(a, deep, b, """{"message":"c"}"""), entries.map { it.rawRecord })
    }

    @Test
    fun aTailParsesADeepRecordLikeAWholeParse() {
        val lines = listOf(a + deep + b, """{"message":"c"}""")
        val parser = IncrementalProfileParser(profile, CLOCK)
        val tailed = mutableListOf<LogEntry>()
        var offset = 0L
        for (line in lines) {
            val result = parser.append(listOf(line), tailed.size + 1, offset)
            tailed += result.entries
            offset = checkNotNull(result.sourceLineCount)
        }
        tailed += parser.flush(tailed.size + 1, offset).entries
        assertEquals(whole(lines).map { it.rawRecord }, tailed.map { it.rawRecord })
        assertEquals(listOf(a, deep, b, """{"message":"c"}"""), tailed.map { it.rawRecord })
    }

    private fun splitAndJoin(source: String): String {
        val directory = createTempDirectory("deep-json-split").toFile()
        try {
            val bytes = source.toByteArray()
            val outputs = planSplitOutputs("events.jsonl", directory, "part", 3)
            splitProfileRecordsToFiles(ByteArrayInputStream(bytes), outputs, bytes.size.toLong(), profile)
            return outputs.joinToString("") { it.readText() }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun String.withoutWhitespace() = filterNot(Char::isWhitespace)

    @Test
    fun splittingAFileWithADeepRecordMidLineLosesNothing() {
        val source = listOf(a + deep + b, a, deep + b, b).joinToString("\n") + "\n"
        val joined = splitAndJoin(source)
        assertEquals(source.withoutWhitespace(), joined.withoutWhitespace())
        assertTrue(joined.lines().any { it.trim() == b })
    }

    @Test
    fun splittingAFileWithAMalformedCloserMidLineKeepsTheRestOfTheLine() {
        // The `]` closes nothing: the parse keeps the candidate and discards the line's suffix, a split must still copy it.
        val source = listOf(a, """{"message":"x"]$b${deep}tail""", b).joinToString("\n") + "\n"
        val joined = splitAndJoin(source)
        assertEquals(source.withoutWhitespace(), joined.withoutWhitespace())
        assertTrue(joined.lines().any { it.contains(b) })
    }

    @Test
    fun aMalformedCloserStillDiscardsTheSuffixInAWholeParse() {
        val entries = whole(listOf("""{"message":"x"]$b"""))
        assertEquals(listOf("""{"message":"x"]"""), entries.map { it.rawRecord })
    }
}
