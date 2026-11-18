package com.indagium

import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.CapabilityAvailability
import com.indagium.model.LogCapability
import com.indagium.model.LogEntry
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.ProfileTimestampKind
import com.indagium.model.deriveLogCapabilities
import com.indagium.utils.IncrementalProfileParser
import com.indagium.utils.parseLogContent
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CLOCK = "tail-clock"
private const val RANDOM_SEED = 42
private const val MAX_RANDOM_BATCH = 3

class IncrementalProfileParserTest {
    private val csvProfile = LogFormatProfile(
        id = "csv-tail",
        name = "CSV tail",
        engine = LogFormatEngine.CSV,
        fieldMappings = mapOf(
            LogFieldRole.TIMESTAMP to "time",
            LogFieldRole.SEVERITY to "level",
            LogFieldRole.MESSAGE to "message",
        ),
        timestampKind = ProfileTimestampKind.ISO_8601,
    )

    private val csvText = listOf(
        "time,level,message,trace",
        "2024-01-01T00:00:00Z,INFO,\"first,",
        "second \"\"quoted\"\"\",trace-a",
        "2024-01-01T00:00:01Z,WARN,plain,trace-b",
        "2024-01-01T00:00:02Z,ERROR,\"multi",
        "line",
        "cell\",trace-c",
        "2024-01-01T00:00:03Z,INFO,last,trace-d",
    )

    private val regexProfile = LogFormatProfile(
        id = "regex-tail",
        name = "Regex tail",
        engine = LogFormatEngine.REGEX,
        regexPattern = "(?s)^\\[(\\S+)] ([A-Z]+) (.*)$",
        multilineStartPattern = "^\\[",
        fieldMappings = mapOf(
            LogFieldRole.TIMESTAMP to "1",
            LogFieldRole.SEVERITY to "2",
            LogFieldRole.MESSAGE to "3",
        ),
        timestampKind = ProfileTimestampKind.ISO_8601,
    )

    private val regexText = listOf(
        "[2024-03-01T10:00:00Z] ERROR crash",
        "\tat a.b.C(C.java:1)",
        "\tat a.b.D(D.java:2)",
        "[2024-03-01T10:00:01Z] INFO ok",
        "[2024-03-01T10:00:02Z] WARN again",
        "\tat x.Y(Y.java:3)",
    )

    private val criProfile = BUILTIN_LOG_FORMAT_PROFILES.first { it.id == "kubernetes-cri" }

    private val criText = listOf(
        "2024-01-01T00:00:00.000000001Z stdout P hello ",
        "2024-01-01T00:00:00.000000002Z stderr F side-channel",
        "2024-01-01T00:00:00.000000003Z stdout P wor",
        "2024-01-01T00:00:00.000000004Z stderr P unfin",
        "2024-01-01T00:00:00.000000005Z stdout F ld",
        "2024-01-01T00:00:00.000000006Z stderr F ished",
        "2024-01-01T00:00:00.000000007Z stdout F tail",
    )

    private fun whole(profile: LogFormatProfile, lines: List<String>): List<LogEntry> =
        parseLogContent(
            ByteArrayInputStream((lines.joinToString("\n") + "\n").toByteArray()),
            profile = profile,
            sourceClockId = CLOCK,
        ).entries

    /** Feeds [batches] one by one, then flushes, the way a tail does; returns the entries the parser released after each step. */
    private fun feed(
        parser: IncrementalProfileParser,
        batches: List<List<String>>,
        firstLineOffset: Long = 0,
        firstId: Int = 1,
    ): List<List<LogEntry>> {
        var nextId = firstId
        var offset = firstLineOffset
        val steps = mutableListOf<List<LogEntry>>()
        for (batch in batches) {
            val result = parser.append(batch, nextId, offset)
            assertEquals(offset + batch.size, result.sourceLineCount, "absolute count of physical lines consumed")
            steps += result.entries
            nextId += result.entries.size
            offset = checkNotNull(result.sourceLineCount)
        }
        steps += parser.flush(nextId, offset).entries
        return steps
    }

    private fun assertSameRows(expected: List<LogEntry>, actual: List<LogEntry>, label: String) {
        assertEquals(expected.size, actual.size, "$label: row count")
        expected.zip(actual).forEachIndexed { index, (want, got) ->
            val at = "$label row ${index + 1}"
            assertEquals(want.id, got.id, "$at id")
            assertEquals(index + 1, got.id, "$at ids are consecutive")
            assertEquals(want.msg, got.msg, "$at msg")
            assertEquals(want.tag, got.tag, "$at tag")
            assertEquals(want.level, got.level, "$at level")
            assertEquals(want.severityKnown, got.severityKnown, "$at severityKnown")
            assertEquals(want.profileFields.toMap(), got.profileFields.toMap(), "$at fields")
            assertEquals(want.sourceStartLine, got.sourceStartLine, "$at start line")
            assertEquals(want.sourceEndLine, got.sourceEndLine, "$at end line")
            assertEquals(want.rawRecord, got.rawRecord, "$at raw record")
            assertEquals(want.parsedTime, got.parsedTime, "$at parsed time")
        }
    }

    private fun splitsOf(lines: List<String>): Map<String, List<List<String>>> {
        val splits = linkedMapOf<String, List<List<String>>>()
        splits["one batch"] = listOf(lines)
        for (size in listOf(1, 2, 3, 5)) splits["chunks of $size"] = lines.chunked(size)
        for (cut in 1 until lines.size) splits["cut at $cut"] = listOf(lines.take(cut), lines.drop(cut))
        val random = Random(RANDOM_SEED)
        repeat(20) { round ->
            val batches = mutableListOf<List<String>>()
            var from = 0
            while (from < lines.size) {
                val size = random.nextInt(1, MAX_RANDOM_BATCH + 1)
                batches += lines.subList(from, minOf(lines.size, from + size))
                from += size
            }
            splits["random $round"] = batches
        }
        return splits
    }

    private fun assertEverySplitMatchesTheWholeFile(profile: LogFormatProfile, lines: List<String>) {
        val expected = whole(profile, lines)
        for ((name, batches) in splitsOf(lines)) {
            val parser = IncrementalProfileParser(profile, CLOCK)
            val actual = feed(parser, batches).flatten()
            assertSameRows(expected, actual, name)
        }
    }

    @Test
    fun csvInAnySplitMatchesTheWholeFile() {
        assertEverySplitMatchesTheWholeFile(csvProfile, csvText)
        val all = feed(IncrementalProfileParser(csvProfile, CLOCK), csvText.chunked(2)).flatten()
        assertTrue(all.none { it.rawRecord == csvText.first() }, "the header is never a row")
        assertEquals(4, all.size)
    }

    @Test
    fun csvWithoutHeaderInAnySplitMatchesTheWholeFile() {
        val profile = csvProfile.copy(
            id = "csv-tail-plain",
            hasHeader = false,
            fieldMappings = mapOf(LogFieldRole.SEVERITY to "1", LogFieldRole.MESSAGE to "2"),
            timestampKind = ProfileTimestampKind.AUTO,
        )
        assertEverySplitMatchesTheWholeFile(profile, csvText.drop(1))
    }

    @Test
    fun csvQuotedCellSplitAcrossTwoBatchesIsHeldUntilItsQuoteCloses() {
        val parser = IncrementalProfileParser(csvProfile, CLOCK)
        assertEquals(emptyList(), parser.append(csvText.take(1), 1, 0).entries, "the header row is not a record")
        val open = parser.append(csvText.subList(1, 2), 1, 1)
        assertEquals(emptyList(), open.entries, "an open quote holds the record")
        assertEquals(2L, open.sourceLineCount)
        val closed = parser.append(csvText.subList(2, 4), 1, 2)
        assertEquals(listOf("first,\nsecond \"quoted\"", "plain"), closed.entries.map { it.msg })
        assertEquals(listOf(2L, 4L), closed.entries.map { it.sourceStartLine })
        assertEquals(listOf(3L, 4L), closed.entries.map { it.sourceEndLine })
        assertEquals(emptyList(), parser.flush(3, 4).entries)
    }

    @Test
    fun csvTailStartedMidFileReadsItsHeaderFromTheSourceFile() {
        val dir = createTempDirectory("incremental-csv-header").toFile()
        try {
            // The tab already holds the header and one row; the tail continues after line 2.
            val existing = listOf(csvText.first(), "2023-12-31T23:59:59Z,INFO,earlier,trace-0")
            val rest = csvText.drop(1)
            val file = File(dir, "data.csv").apply { writeText(existing.joinToString("\n") + "\n") }
            val parser = assertNotNull(IncrementalProfileParser.forFile(csvProfile, CLOCK, file))
            val actual = feed(parser, rest.chunked(2), firstLineOffset = 2, firstId = 2).flatten()
            val expected = whole(csvProfile, existing + rest).drop(1)
            assertEquals(expected.map { it.msg }, actual.map { it.msg })
            assertEquals(expected.map { it.profileFields.toMap() }, actual.map { it.profileFields.toMap() })
            assertEquals(expected.map { it.sourceStartLine }, actual.map { it.sourceStartLine })
            assertEquals(expected.map { it.sourceEndLine }, actual.map { it.sourceEndLine })
            assertEquals(listOf(3L, 5L, 6L, 9L), actual.map { it.sourceStartLine })
            assertTrue(actual.none { it.profileFields["time"] == "time" }, "no header row was emitted")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun csvTailSeedsAnUnterminatedQuotedRowAndRevisesItsExistingId() {
        val dir = createTempDirectory("incremental-csv-seed").toFile()
        try {
            val initialLines = listOf(
                csvText.first(),
                "2024-01-01T00:00:00Z,INFO,first,trace-a",
                "2024-01-01T00:00:01Z,WARN,\"before",
            )
            val file = File(dir, "data.csv").apply { writeText(initialLines.joinToString("\n", postfix = "\n")) }
            val initial = whole(csvProfile, initialLines)
            assertEquals(2, initial.size)
            assertFalse(initial.last().severityKnown, "the initial whole-file parse flushes an unterminated row as raw")

            val parser = assertNotNull(IncrementalProfileParser.forFile(csvProfile, CLOCK, file, initial))
            val appended = listOf("after\"", "2024-01-01T00:00:02Z,ERROR,third,trace-c")
            val updated = parser.append(appended, 3, initialLines.size.toLong())
            assertEquals(setOf(initial.last().id), parser.replacedEntryIds)
            assertEquals(listOf(2, 3), updated.entries.map { it.id })
            assertEquals(listOf(3L, 5L), updated.entries.map { it.sourceStartLine })
            assertEquals(listOf(4L, 5L), updated.entries.map { it.sourceEndLine })
            assertEquals("before\nafter", updated.entries.first().msg)

            val combined = initial.dropLast(1) + updated.entries
            assertSameRows(whole(csvProfile, initialLines + appended), combined, "seeded CSV continuation")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun csvWithAHeaderAndNoReadableHeaderRefusesToGuess() {
        val dir = createTempDirectory("incremental-csv-noheader").toFile()
        try {
            val file = File(dir, "empty.csv").apply { writeText("") }
            assertNull(IncrementalProfileParser.forFile(csvProfile, CLOCK, file))
            assertNull(IncrementalProfileParser.forFile(csvProfile, CLOCK, File(dir, "missing.csv")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun multilineRegexInAnySplitMatchesTheWholeFile() = assertEverySplitMatchesTheWholeFile(regexProfile, regexText)

    @Test
    fun multilineRegexHoldsItsLastRecordUntilTheNextStartLineOrFlush() {
        val parser = IncrementalProfileParser(regexProfile, CLOCK)
        val first = parser.append(regexText.take(3), 1, 0)
        assertEquals(emptyList(), first.entries, "the first record may still grow")
        val second = parser.append(regexText.subList(3, 4), 1, 3)
        assertEquals(listOf("crash\n\tat a.b.C(C.java:1)\n\tat a.b.D(D.java:2)"), second.entries.map { it.msg })
        assertEquals(listOf(1L), second.entries.map { it.sourceStartLine })
        assertEquals(listOf(3L), second.entries.map { it.sourceEndLine })
        val third = parser.append(regexText.drop(4), 2, 4)
        assertEquals(listOf("ok"), third.entries.map { it.msg })
        assertEquals(6L, third.sourceLineCount)
        val flushed = parser.flush(3, 6)
        assertEquals(listOf("again\n\tat x.Y(Y.java:3)"), flushed.entries.map { it.msg })
        assertEquals(3, flushed.entries.single().id)
        assertEquals(5L, flushed.entries.single().sourceStartLine)
        assertEquals(6L, flushed.entries.single().sourceEndLine)
        assertEquals(emptyList(), parser.flush(4, 6).entries, "a second flush has nothing left")
    }

    @Test
    fun multilineRegexTailSeedsAndRevisesTheInitiallyFlushedLastRecord() {
        val dir = createTempDirectory("incremental-regex-seed").toFile()
        try {
            val initialLines = listOf("[2024-03-01T10:00:00Z] ERROR first", "\tat first")
            val file = File(dir, "app.log").apply { writeText(initialLines.joinToString("\n", postfix = "\n")) }
            val initial = whole(regexProfile, initialLines)
            assertEquals(1, initial.size)

            val parser = assertNotNull(IncrementalProfileParser.forFile(regexProfile, CLOCK, file, initial))
            val appended = listOf("\tat continuation", "[2024-03-01T10:00:01Z] INFO second")
            val updated = parser.append(appended, 2, initialLines.size.toLong())
            assertEquals(setOf(initial.single().id), parser.replacedEntryIds)
            assertEquals(1, updated.entries.size, "the seeded first record is revised; the new EOF record remains open")
            assertEquals(initial.single().id, updated.entries.single().id)
            assertEquals(1L, updated.entries.single().sourceStartLine)
            assertEquals(3L, updated.entries.single().sourceEndLine)
            assertEquals("first\n\tat first\n\tat continuation", updated.entries.single().msg)

            val final = parser.flush(2, (initialLines + appended).size.toLong())
            assertEquals(emptySet(), parser.replacedEntryIds)
            assertEquals(2, final.entries.single().id)
            assertEquals(4L, final.entries.single().sourceStartLine)
            assertSameRows(whole(regexProfile, initialLines + appended), updated.entries + final.entries, "seeded regex continuation")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun criInAnySplitMatchesTheWholeFile() = assertEverySplitMatchesTheWholeFile(criProfile, criText)

    @Test
    fun criPartialFragmentsJoinTheirFinalLineFromALaterBatch() {
        val parser = IncrementalProfileParser(criProfile, CLOCK)
        // stdout is still open (P without F), and it started first, so nothing may be released yet.
        assertEquals(emptyList(), parser.append(criText.take(3), 1, 0).entries)
        val second = parser.append(criText.subList(3, 5), 1, 3)
        assertEquals(listOf("hello world", "side-channel"), second.entries.map { it.msg })
        assertEquals(listOf(1L, 2L), second.entries.map { it.sourceStartLine })
        assertEquals(listOf(5L, 2L), second.entries.map { it.sourceEndLine })
        val third = parser.append(criText.drop(5), 3, 5)
        assertEquals(listOf("unfinished", "tail"), third.entries.map { it.msg })
        assertEquals(listOf("stderr", "stdout"), third.entries.map { it.profileFields["STREAM"] })
        assertEquals(7L, third.sourceLineCount)
    }

    @Test
    fun criTailSeedsOnlyOpenStreamsAndKeepsInterleavedCompletedRowsAndIds() {
        val dir = createTempDirectory("incremental-cri-seed").toFile()
        try {
            val initialLines = listOf(
                "2024-01-01T00:00:00.000000001Z stdout P hello ",
                "2024-01-01T00:00:00.000000002Z stderr F side-channel",
                "2024-01-01T00:00:00.000000003Z stdout P wor",
            )
            val file = File(dir, "container.log").apply { writeText(initialLines.joinToString("\n", postfix = "\n")) }
            val initial = whole(criProfile, initialLines)
            assertEquals(listOf("hello wor", "side-channel"), initial.map { it.msg })
            assertEquals("P", initial.first().profileFields["cri_tag"])

            val parser = assertNotNull(IncrementalProfileParser.forFile(criProfile, CLOCK, file, initial))
            val appended = listOf(
                "2024-01-01T00:00:00.000000004Z stdout F ld",
                "2024-01-01T00:00:00.000000005Z stderr F next",
            )
            val updated = parser.append(appended, 3, initialLines.size.toLong())
            assertEquals(setOf(initial.first().id), parser.replacedEntryIds)
            assertEquals(listOf(1, 3), updated.entries.map { it.id })
            assertEquals(listOf(1L, 5L), updated.entries.map { it.sourceStartLine })
            assertEquals(listOf(4L, 5L), updated.entries.map { it.sourceEndLine })
            assertEquals("hello world", updated.entries.first().msg)

            val mergedById = (initial + updated.entries).associateBy { it.id }
            val stable = listOf(mergedById.getValue(1), mergedById.getValue(2), mergedById.getValue(3))
            assertSameRows(whole(criProfile, initialLines + appended), stable, "seeded CRI stream")
            assertEquals(2L, stable[1].sourceStartLine, "the interleaved completed row keeps its source line and id")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun criTailDoesNotReseedAPartialThatWasFollowedByACompletedRecordOnItsStream() {
        val dir = createTempDirectory("incremental-cri-complete").toFile()
        try {
            val initialLines = listOf(
                "2024-01-01T00:00:00.000000001Z stdout P hello ",
                "2024-01-01T00:00:00.000000002Z stdout F world",
            )
            val file = File(dir, "container.log").apply { writeText(initialLines.joinToString("\n", postfix = "\n")) }
            val initial = whole(criProfile, initialLines)
            val parser = assertNotNull(IncrementalProfileParser.forFile(criProfile, CLOCK, file, initial))
            val next = parser.append(listOf("2024-01-01T00:00:00.000000003Z stdout F later"), 2, 2)
            assertEquals(emptySet(), parser.replacedEntryIds)
            assertEquals(listOf(2), next.entries.map { it.id })
            assertEquals(listOf("later"), next.entries.map { it.msg })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun lineIndependentEnginesJustDelegate() {
        val profile = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.JSON_LINES }
        val lines = listOf(
            """{"timestamp":"2024-01-01T00:00:00Z","level":"INFO","message":"a"}""",
            """{"timestamp":"2024-01-01T00:00:01Z","level":"WARN","message":"b"}""",
            """{"timestamp":"2024-01-01T00:00:02Z","level":"ERROR","message":"c"}""",
        )
        val actual = feed(IncrementalProfileParser(profile, CLOCK), lines.chunked(2)).flatten()
        assertSameRows(whole(profile, lines), actual, "json lines")
    }

    @Test
    fun jsonTailPreservesAllRecordsWhenSeveralStartOnOneLine() {
        val profile = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.JSON_LINES }
        val lines = listOf(
            """[{"message":"a"},{"message":"b"}]""",
            """{"message":"c"} {"message":"d"}""",
            """[{"message":"e"},{"message":"f","nested":""",
            """  ["one", "two"]}]""",
        )
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), whole(profile, lines).map { it.msg })
        assertEverySplitMatchesTheWholeFile(profile, lines)
    }

    @Test
    fun jsonTailReplacesOpenPrettyObjectAndRootArrayElementInPlace() {
        val profile = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.JSON_LINES }
        val directory = createTempDirectory("incremental-json-seed").toFile()
        try {
            val prettyInitial = listOf(
                "{",
                "  \"timestamp\": \"2024-01-01T00:00:00Z\",",
                "  \"message\": \"continued\"",
            )
            val objectFile = File(directory, "open.json").apply {
                writeText(prettyInitial.joinToString("\n", postfix = "\n"))
            }
            val initialObjectRows = parseLogContent(
                objectFile.inputStream(), profile = profile, sourceClockId = CLOCK,
            ).entries
            assertEquals(1, initialObjectRows.size)
            val objectParser = assertNotNull(IncrementalProfileParser.forFile(profile, CLOCK, objectFile, initialObjectRows))
            val completed = objectParser.append(listOf("}"), 2, prettyInitial.size.toLong())
            assertEquals(setOf(initialObjectRows.single().id), objectParser.replacedEntryIds)
            assertEquals(1, completed.entries.size)
            assertEquals("continued", completed.entries.single().msg)
            assertEquals(1L, completed.entries.single().sourceStartLine)
            assertEquals(4L, completed.entries.single().sourceEndLine)

            val arrayInitial = listOf(
                "[",
                "  {\"timestamp\":\"2024-01-01T00:00:00Z\",\"message\":\"first\"},",
                "  {\"timestamp\":\"2024-01-01T00:00:01Z\",\"message\":\"second\"",
            )
            val arrayFile = File(directory, "open-array.json").apply {
                writeText(arrayInitial.joinToString("\n", postfix = "\n"))
            }
            val initialArrayRows = parseLogContent(
                arrayFile.inputStream(), profile = profile, sourceClockId = CLOCK,
            ).entries
            assertEquals(2, initialArrayRows.size)
            assertTrue(initialArrayRows.last().rawRecord.orEmpty().contains("second"))
            val arrayParser = assertNotNull(IncrementalProfileParser.forFile(profile, CLOCK, arrayFile, initialArrayRows))
            val appended = arrayParser.append(listOf("  }", "]"), 3, arrayInitial.size.toLong())
            assertEquals(setOf(initialArrayRows.last().id), arrayParser.replacedEntryIds)
            assertEquals(listOf("second"), appended.entries.map { it.msg })
            assertEquals(3L, appended.entries.single().sourceStartLine)
            assertEquals(4L, appended.entries.single().sourceEndLine)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun dltIsRefused() {
        val dlt = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.DLT }
        assertFailsWith<IllegalArgumentException> { IncrementalProfileParser(dlt) }
    }

    @Test
    fun anOversizedHeldMultilineRecordIsEmittedRawLikeTheWholeFileParse() {
        val huge = "[2024-03-01T10:00:00Z] ERROR " + "x".repeat(300 * 1024)
        val lines = listOf(huge, "[2024-03-01T10:00:01Z] INFO next")
        val expected = whole(regexProfile, lines)
        val actual = feed(IncrementalProfileParser(regexProfile, CLOCK), lines.map { listOf(it) }).flatten()
        assertSameRows(expected, actual, "oversized")
    }

    @Test
    fun incrementalTailCapabilityIsAvailableForTheStatefulEnginesAndStillRefusedForDlt() {
        fun tail(profile: LogFormatProfile) = deriveLogCapabilities(profile, emptyList())[LogCapability.INCREMENTAL_TAIL]!!
        assertEquals(CapabilityAvailability.AVAILABLE, tail(csvProfile).availability)
        assertEquals(CapabilityAvailability.AVAILABLE, tail(criProfile).availability)
        assertEquals(CapabilityAvailability.AVAILABLE, tail(regexProfile).availability)
        assertEquals(
            CapabilityAvailability.UNAVAILABLE,
            tail(BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.DLT }).availability,
        )
    }

    @Test
    fun aRecordShownEarlyIsReplacedInPlaceWhenMoreLinesJoinItInALaterBatch() {
        val syslog = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.SYSLOG_RFC5424 }
        val head = "<34>1 2024-11-16T10:00:00Z host app 123 ID47 - failed"
        val next = "<34>1 2024-11-16T10:00:01Z host app 123 ID48 - recovered"
        val parser = IncrementalProfileParser(syslog, "clock")

        val first = parser.append(listOf(head), startId = 1, sourceLineOffset = 0)
        assertEquals(listOf("failed"), first.entries.map { it.msg }, "the newest line is visible at once")
        assertTrue(parser.replacedEntryIds.isEmpty())

        val second = parser.append(listOf("  caused by lower layer", "    at frame"), startId = 2, sourceLineOffset = 1)
        assertEquals(listOf(1), second.entries.map { it.id }, "the same row is extended, not a new one added")
        assertEquals(setOf(1), parser.replacedEntryIds)
        assertEquals("failed\n  caused by lower layer\n    at frame", second.entries.single().msg)
        assertEquals(3L, second.entries.single().sourceEndLine)

        val third = parser.append(listOf(next), startId = 2, sourceLineOffset = 3)
        assertEquals(listOf(2), third.entries.map { it.id })
        assertEquals(listOf("recovered"), third.entries.map { it.msg })
        assertTrue(parser.replacedEntryIds.isEmpty())
        assertTrue(parser.flush(startId = 3, sourceLineOffset = 4).entries.isEmpty(), "nothing is emitted twice at stop")

        // Any split gives the rows of the whole file.
        val whole = parseLogContent(
            ByteArrayInputStream(listOf(head, "  caused by lower layer", "    at frame", next).joinToString("\n").toByteArray()),
            profile = syslog,
        ).entries
        assertEquals(listOf("failed\n  caused by lower layer\n    at frame", "recovered"), whole.map { it.msg })
    }
}
