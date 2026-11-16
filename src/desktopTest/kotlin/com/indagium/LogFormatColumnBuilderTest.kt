package com.indagium

import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.model.ProfileEpochUnit
import com.indagium.model.ProfileTimestampKind
import com.indagium.utils.ColumnKind
import com.indagium.utils.ColumnSeparator
import com.indagium.utils.PieceWrap
import com.indagium.utils.buildColumnFormat
import com.indagium.utils.guessColumnKinds
import com.indagium.utils.guessTimestampSettings
import com.indagium.utils.parseLogContent
import com.indagium.utils.splitSampleColumns
import com.indagium.utils.validateLogFormatProfile
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogFormatColumnBuilderTest {
    private fun profileFor(
        sample: String,
        separator: ColumnSeparator = ColumnSeparator.WHITESPACE,
        custom: String = "",
        kinds: List<ColumnKind>? = null,
        extraNames: Map<Int, String> = emptyMap(),
    ): LogFormatProfile {
        val pieces = splitSampleColumns(sample, separator, custom)
        val format = buildColumnFormat(pieces, kinds ?: guessColumnKinds(pieces), separator, custom, extraNames).getOrThrow()
        return LogFormatProfile(
            id = "built",
            name = "Built",
            engine = LogFormatEngine.REGEX,
            regexPattern = format.regexPattern,
            fieldMappings = format.fieldMappings,
            attributes = format.attributes,
            visibleColumns = format.visibleColumns,
            timestampKind = format.timestampKind,
            epochUnit = format.epochUnit ?: ProfileEpochUnit.MILLIS,
        ).also(::validateLogFormatProfile)
    }

    private fun parse(profile: LogFormatProfile, text: String) =
        parseLogContent(ByteArrayInputStream(text.toByteArray()), profile = profile).entries

    @Test
    fun levelThenTextThenTimestampIsGuessedAndParsesLinesWithAnyNumberOfWords() {
        val sample = "ERROR Connection lost 2024-03-01T10:00:00Z"
        val pieces = splitSampleColumns(sample, ColumnSeparator.WHITESPACE)
        assertEquals(
            listOf(ColumnKind.LEVEL, ColumnKind.MESSAGE, ColumnKind.MESSAGE, ColumnKind.TIMESTAMP),
            guessColumnKinds(pieces),
        )
        val profile = profileFor(sample)
        assertEquals(mapOf(LogFieldRole.SEVERITY to "1", LogFieldRole.MESSAGE to "2", LogFieldRole.TIMESTAMP to "3"), profile.fieldMappings)
        assertEquals(listOf(LogFieldRole.SEVERITY, LogFieldRole.MESSAGE, LogFieldRole.TIMESTAMP), profile.visibleColumns)
        assertEquals(ProfileTimestampKind.ISO_8601, profile.timestampKind)

        val rows = parse(profile, "$sample\nWARN retrying the request in 5 seconds 2024-03-01T10:00:01Z\nINFO ok 2024-03-01T10:00:02Z\n")
        assertEquals(listOf(LogLevel.E, LogLevel.W, LogLevel.I), rows.map { it.level })
        assertEquals(listOf("Connection lost", "retrying the request in 5 seconds", "ok"), rows.map { it.msg })
        assertTrue(rows.all { it.parsedTime?.epochMillis != null })
    }

    @Test
    fun logcatLikeLineGetsTimestampFromTwoWordsIdsLevelTagAndText() {
        val sample = "2024-03-01 10:00:00.123  1234  5678 I NetworkStack: socket closed by peer"
        val pieces = splitSampleColumns(sample, ColumnSeparator.WHITESPACE)
        assertEquals(
            listOf(
                ColumnKind.TIMESTAMP, ColumnKind.TIMESTAMP, ColumnKind.PID, ColumnKind.TID, ColumnKind.LEVEL,
                ColumnKind.TAG, ColumnKind.MESSAGE, ColumnKind.MESSAGE, ColumnKind.MESSAGE, ColumnKind.MESSAGE,
            ),
            guessColumnKinds(pieces),
        )
        val row = parse(profileFor(sample), sample).single()
        assertEquals("NetworkStack", row.tag)
        assertEquals("socket closed by peer", row.msg)
        assertEquals(1234, row.pid)
        assertEquals(5678, row.tid)
        assertEquals(LogLevel.I, row.level)
        assertEquals("2024-03-01 10:00:00.123", row.parsedTime?.original)
    }

    @Test
    fun bracketedColumnsStayOnePieceAndTheirBracketsAreNotCaptured() {
        val sample = "[2024-03-01 10:00:00] [WARN] (worker-3) cache miss for key 42"
        val pieces = splitSampleColumns(sample, ColumnSeparator.WHITESPACE)
        assertEquals(listOf(PieceWrap.SQUARE, PieceWrap.SQUARE, PieceWrap.ROUND), pieces.take(3).map { it.wrap })
        assertEquals("2024-03-01 10:00:00", pieces.first().text)
        val kinds = guessColumnKinds(pieces).toMutableList().also { it[2] = ColumnKind.EXTRA }
        val profile = profileFor(sample, kinds = kinds, extraNames = mapOf(2 to "Thread name"))
        val row = parse(profile, sample).single()

        assertEquals(LogLevel.W, row.level)
        assertEquals("cache miss for key 42", row.msg)
        assertEquals("worker-3", row.profileFields["thread_name"])
        assertEquals("Thread name", profile.attributes.single().label)
        assertEquals("2024-03-01 10:00:00", row.parsedTime?.original)
    }

    @Test
    fun aCustomSeparatorSplitsColumnsAndTheTextMayContainSpaces() {
        val sample = "1709287200123 | payment-service | ERROR | card declined for order 77"
        val kinds = listOf(ColumnKind.TIMESTAMP, ColumnKind.TAG, ColumnKind.LEVEL, ColumnKind.MESSAGE)
        val profile = profileFor(sample, ColumnSeparator.CUSTOM, "|", kinds)
        val row = parse(profile, sample).single()

        assertEquals(ProfileTimestampKind.EPOCH, profile.timestampKind)
        assertEquals(ProfileEpochUnit.MILLIS, profile.epochUnit)
        assertEquals("payment-service", row.tag)
        assertEquals(LogLevel.E, row.level)
        assertEquals("card declined for order 77", row.msg)
        assertEquals(1709287200123L, row.parsedTime?.epochMillis)
    }

    @Test
    fun tabSeparatedColumnsAndSkippedColumnsWork() {
        val sample = "10:00:00.500\tignored\tDEBUG\tstarting up now"
        val kinds = listOf(ColumnKind.TIMESTAMP, ColumnKind.SKIP, ColumnKind.LEVEL, ColumnKind.MESSAGE)
        val profile = profileFor(sample, ColumnSeparator.TAB, kinds = kinds)
        val row = parse(profile, sample).single()

        assertEquals(mapOf(LogFieldRole.TIMESTAMP to "1", LogFieldRole.SEVERITY to "2", LogFieldRole.MESSAGE to "3"), profile.fieldMappings)
        assertEquals(ProfileTimestampKind.TIME_OF_DAY, profile.timestampKind)
        assertEquals(LogLevel.D, row.level)
        assertEquals("starting up now", row.msg)
    }

    @Test
    fun aLevelGluedToItsTagWithASlashIsSplitWithoutAnySetting() {
        val sample = "12:20:42.440  D/SDK  brand: \"iOS\""
        val pieces = splitSampleColumns(sample, ColumnSeparator.WHITESPACE)
        assertEquals(listOf("12:20:42.440", "D", "SDK", "brand", "\"iOS\""), pieces.map { it.text })
        assertEquals('/', pieces[2].joinBefore)
        assertEquals(
            listOf(ColumnKind.TIMESTAMP, ColumnKind.LEVEL, ColumnKind.TAG, ColumnKind.MESSAGE, ColumnKind.MESSAGE),
            guessColumnKinds(pieces),
        )
        val profile = profileFor(sample)
        assertEquals(ProfileTimestampKind.TIME_OF_DAY, profile.timestampKind)

        val rows = parse(profile, "$sample\n12:20:42.441  W/Net.Http  retry 2 of 3 for https://example.com/a/b\n12:20:43.000  E/SDK  failed\n")
        assertEquals(listOf(LogLevel.D, LogLevel.W, LogLevel.E), rows.map { it.level })
        assertEquals(listOf("SDK", "Net.Http", "SDK"), rows.map { it.tag })
        assertEquals(
            listOf("brand: \"iOS\"", "retry 2 of 3 for https://example.com/a/b", "failed"),
            rows.map { it.msg },
        )
        assertEquals("12:20:42.440", rows.first().ts)
    }

    @Test
    fun charactersTheUserNamesAlsoSplitGluedColumns() {
        val sample = "10:00:00|worker-1|INFO started the job"
        // Without help the first word is one piece.
        assertEquals(4, splitSampleColumns(sample, ColumnSeparator.WHITESPACE).size)
        val pieces = splitSampleColumns(sample, ColumnSeparator.WHITESPACE, splitChars = "|")
        assertEquals(listOf("10:00:00", "worker-1", "INFO", "started", "the", "job"), pieces.map { it.text })
        val kinds = listOf(
            ColumnKind.TIMESTAMP, ColumnKind.TAG, ColumnKind.LEVEL, ColumnKind.MESSAGE, ColumnKind.MESSAGE, ColumnKind.MESSAGE,
        )
        val format = buildColumnFormat(pieces, kinds, ColumnSeparator.WHITESPACE).getOrThrow()
        val profile = LogFormatProfile(
            id = "glued",
            name = "Glued",
            engine = LogFormatEngine.REGEX,
            regexPattern = format.regexPattern,
            fieldMappings = format.fieldMappings,
            visibleColumns = format.visibleColumns,
            timestampKind = format.timestampKind,
        ).also(::validateLogFormatProfile)
        val row = parse(profile, "10:00:01|db-pool|WARN slow query took 2 s").single()

        assertEquals("db-pool", row.tag)
        assertEquals(LogLevel.W, row.level)
        assertEquals("slow query took 2 s", row.msg)
    }

    @Test
    fun choicesThatCannotWorkAreRefusedWithAMessageForTheUser() {
        val pieces = splitSampleColumns("ERROR one two INFO", ColumnSeparator.WHITESPACE)
        val twice = buildColumnFormat(
            pieces,
            listOf(ColumnKind.LEVEL, ColumnKind.MESSAGE, ColumnKind.MESSAGE, ColumnKind.LEVEL),
            ColumnSeparator.WHITESPACE,
        )
        assertTrue(twice.exceptionOrNull()?.message.orEmpty().startsWith("Level is chosen"))

        val noMessage = buildColumnFormat(pieces, List(pieces.size) { ColumnKind.SKIP }, ColumnSeparator.WHITESPACE)
        assertEquals("Choose which column is the message.", noMessage.exceptionOrNull()?.message)

        assertEquals("Paste a sample line first.", buildColumnFormat(emptyList(), emptyList(), ColumnSeparator.WHITESPACE).exceptionOrNull()?.message)
        assertTrue(splitSampleColumns("a;b", ColumnSeparator.CUSTOM, "").isEmpty())
    }

    @Test
    fun timestampTextIsClassifiedByItsShape() {
        assertEquals(ProfileTimestampKind.EPOCH to ProfileEpochUnit.SECONDS, guessTimestampSettings("1709287200"))
        assertEquals(ProfileTimestampKind.EPOCH to ProfileEpochUnit.MICROS, guessTimestampSettings("1709287200123456"))
        assertEquals(ProfileTimestampKind.RELATIVE to ProfileEpochUnit.SECONDS, guessTimestampSettings("12.345678"))
        assertEquals(ProfileTimestampKind.TIME_OF_DAY to null, guessTimestampSettings("10:00:00.123"))
        assertEquals(ProfileTimestampKind.ISO_8601 to null, guessTimestampSettings("2024-03-01T10:00:00Z"))
        assertEquals(ProfileTimestampKind.AUTO to null, guessTimestampSettings("2024-03-01 10:00:00.123"))
    }
}
