package com.indagium

import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatAttribute
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.model.ProfileEpochUnit
import com.indagium.model.ProfileTimestampKind
import com.indagium.utils.canonicalDltParsedTime
import com.indagium.utils.decodeLogFormatProfiles
import com.indagium.utils.deltaMillis
import com.indagium.utils.logFormatProfileJson
import com.indagium.utils.parseLogContent
import com.indagium.utils.validateLogFormatProfile
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogFormatParserCoreTest {
    @Test
    fun customRegexSupportsMultilineRecordsAndKeepsUnknownSeverityNeutral() {
        val profile = LogFormatProfile(
            id = "custom-regex",
            name = "Custom regex",
            description = "Multiline test profile",
            engine = LogFormatEngine.REGEX,
            regexPattern = "(?s)^\\[(\\S+)] ([A-Z]+) (.*)$",
            multilineStartPattern = "^\\[",
            fieldMappings = mapOf(
                LogFieldRole.TIMESTAMP to "1",
                LogFieldRole.SEVERITY to "2",
                LogFieldRole.MESSAGE to "3",
            ),
            attributes = listOf(LogFormatAttribute("full_message", "Full message", "3")),
            timestampKind = ProfileTimestampKind.ISO_8601,
            visibleColumns = listOf(LogFieldRole.TIMESTAMP, LogFieldRole.MESSAGE),
        )
        val parsed = parseLogContent(
            ByteArrayInputStream(
                (
                    "[2024-03-01T10:00:00Z] ERROR first line\ncontinued\n" +
                        "[2024-03-01T10:00:01Z] MYSTERY second\n"
                ).toByteArray(),
            ),
            profile = profile,
        )

        assertEquals(2, parsed.entries.size)
        assertEquals("first line\ncontinued", parsed.entries[0].msg)
        assertEquals(1L, parsed.entries[0].sourceStartLine)
        assertEquals(2L, parsed.entries[0].sourceEndLine)
        assertEquals("first line\ncontinued", parsed.entries[0].profileFields["full_message"])
        assertEquals(LogLevel.E, parsed.entries[0].level)
        assertTrue(parsed.entries[0].severityKnown)
        assertEquals("second", parsed.entries[1].msg)
        assertFalse(parsed.entries[1].severityKnown)
        assertEquals("MYSTERY", parsed.entries[1].originalSeverity)
    }

    @Test
    fun jsonExplicitMappingsAreExactAndNestedExtrasRemainAvailable() {
        val profile = LogFormatProfile(
            id = "exact-json",
            name = "Exact JSON",
            description = "Explicit mapping test",
            engine = LogFormatEngine.JSON_LINES,
            fieldMappings = mapOf(
                LogFieldRole.TIMESTAMP to "event.when",
                LogFieldRole.MESSAGE to "event.message",
                LogFieldRole.SEVERITY to "event.level",
            ),
            attributes = listOf(LogFormatAttribute("trace", "Trace", "event.trace", visibleByDefault = true)),
            visibleColumns = emptyList(),
        )
        val raw = """{"event":{"message":"ready","level":"WARN","trace":"trace-7"},"time":"2024-01-01T00:00:00Z","extra":true}"""
        val entry = parseLogContent(ByteArrayInputStream(raw.toByteArray()), profile = profile).entries.single()

        assertEquals("ready", entry.msg)
        assertEquals(LogLevel.W, entry.level)
        assertTrue(entry.severityKnown)
        assertNull(entry.parsedTime)
        assertEquals("trace-7", entry.profileFields["trace"])
        assertEquals("true", entry.profileFields["extra"])
        assertEquals(raw, entry.rawRecord)
        assertTrue(entry.recordMetadata?.usesProfilePresentation == true)
        // No role columns were chosen; only the attribute marked visible is projected.
        assertEquals(listOf("trace"), entry.recordMetadata?.displayColumns?.map { it.id })
    }

    @Test
    fun csvSupportsQuotedMultilineCellsAndRetainsMalformedTail() {
        val profile = LogFormatProfile(
            id = "csv-quoted",
            name = "Quoted CSV",
            description = "CSV quoting test",
            engine = LogFormatEngine.CSV,
            fieldMappings = mapOf(
                LogFieldRole.TIMESTAMP to "time",
                LogFieldRole.SEVERITY to "level",
                LogFieldRole.MESSAGE to "message",
            ),
            visibleColumns = listOf(LogFieldRole.MESSAGE),
        )
        val raw = "time,level,message,trace\n2024-01-01T00:00:00Z,INFO,\"first,\nsecond \"\"quoted\"\"\",trace-a\n" +
            "2024-01-01T00:00:01Z,WARN,\"unfinished\nlast line"
        val parsed = parseLogContent(ByteArrayInputStream(raw.toByteArray()), profile = profile)

        assertEquals(2, parsed.entries.size)
        assertEquals("first,\nsecond \"quoted\"", parsed.entries[0].msg)
        assertEquals(2L, parsed.entries[0].sourceStartLine)
        assertEquals(3L, parsed.entries[0].sourceEndLine)
        assertEquals("trace-a", parsed.entries[0].profileFields["trace"])
        assertEquals("2024-01-01T00:00:01Z,WARN,\"unfinished\nlast line", parsed.entries[1].msg)
        assertFalse(parsed.entries[1].severityKnown)
        assertTrue(parsed.diagnostics.any { "unterminated CSV" in it })
    }

    @Test
    fun malformedCsvQuotesStayRawAndExplicitTextProfilesOverrideDltViewerDetection() {
        val csvProfile = LogFormatProfile(
            id = "strict-csv",
            name = "Strict CSV",
            description = "Malformed quote retention",
            engine = LogFormatEngine.CSV,
            hasHeader = false,
            fieldMappings = mapOf(
                LogFieldRole.SEVERITY to "1",
                LogFieldRole.MESSAGE to "2",
            ),
        )
        val malformed = parseLogContent(
            ByteArrayInputStream("at,INFO,wo\"rd\nbt,INFO,\"closed\"junk\nct,INFO,works\n".toByteArray()),
            profile = csvProfile,
        )
        assertEquals(3, malformed.entries.size)
        assertEquals("at,INFO,wo\"rd", malformed.entries[0].msg)
        assertEquals("bt,INFO,\"closed\"junk", malformed.entries[1].msg)
        assertEquals("works", malformed.entries[2].msg)
        assertTrue(malformed.diagnostics.any { "invalid CSV" in it })

        val viewerCsv = "Time,ECU,AppId,ContextId,Type,Payload\n2026-01-02 03:04:05,ECU1,APP1,CTX1,ERROR,plain-selected\n"
        val explicitCsv = LogFormatProfile(
            id = "explicit-viewer-csv",
            name = "Explicit CSV",
            description = "CSV selection should take precedence over viewer sniffing",
            engine = LogFormatEngine.CSV,
            fieldMappings = mapOf(LogFieldRole.MESSAGE to "Payload"),
        )
        val parsedCsv = parseLogContent(ByteArrayInputStream(viewerCsv.toByteArray()), profile = explicitCsv)
        assertEquals("explicit-viewer-csv", parsedCsv.profile?.id)
        assertEquals("plain-selected", parsedCsv.entries.single().msg)

        val asciiViewer = "2026/01/02 03:04:05.123456 12.3456 7 ECU1 APP1 CTX1 42 log info verbose 1 selected-text\n".repeat(2)
        val explicitRegex = LogFormatProfile(
            id = "explicit-viewer-regex",
            name = "Explicit Regex",
            description = "Regex selection should take precedence over viewer sniffing",
            engine = LogFormatEngine.REGEX,
            regexPattern = "^(.*)$",
            multilineStartPattern = "^",
            fieldMappings = mapOf(LogFieldRole.MESSAGE to "1"),
        )
        val parsedRegex = parseLogContent(ByteArrayInputStream(asciiViewer.toByteArray()), profile = explicitRegex)
        assertEquals("explicit-viewer-regex", parsedRegex.profile?.id)
        assertEquals(2, parsedRegex.entries.size)
        assertTrue(parsedRegex.entries.first().msg.endsWith("selected-text"))

        val explicitPlain = plainTextProfile()
        val parsedPlain = parseLogContent(ByteArrayInputStream(viewerCsv.toByteArray()), profile = explicitPlain)
        assertEquals("Time,ECU,AppId,ContextId,Type,Payload", parsedPlain.entries.first().msg)
    }

    @Test
    fun epochNanosecondsAndInvalidValuesPreserveRowsAndOriginalSeverity() {
        val profile = LogFormatProfile(
            id = "epoch-json",
            name = "Epoch JSON",
            description = "Epoch unit test",
            engine = LogFormatEngine.JSON_LINES,
            fieldMappings = mapOf(
                LogFieldRole.TIMESTAMP to "time",
                LogFieldRole.SEVERITY to "level",
                LogFieldRole.MESSAGE to "msg",
            ),
            timestampKind = ProfileTimestampKind.EPOCH,
            epochUnit = ProfileEpochUnit.NANOS,
        )
        val parsed = parseLogContent(
            ByteArrayInputStream(
                (
                    """{"time":"1700000000000000000","level":"INFO","msg":"valid"}""" + "\n" +
                        """{"time":"NaN","level":"MYSTERY","msg":"invalid time remains"}""" + "\n" +
                        """{"time":"1e999999999","level":"INFO","msg":"exponent remains raw"}"""
                ).toByteArray(),
            ),
            profile = profile,
        )

        assertEquals(3, parsed.entries.size)
        assertEquals(1_700_000_000_000L, parsed.entries[0].parsedTime?.epochMillis)
        assertEquals("valid", parsed.entries[0].msg)
        assertFalse(parsed.entries[1].severityKnown)
        assertEquals("MYSTERY", parsed.entries[1].originalSeverity)
        assertEquals("NaN", parsed.entries[1].parsedTime?.original)
        assertNull(parsed.entries[1].parsedTime?.epochMillis)
        assertEquals("invalid time remains", parsed.entries[1].msg)
        assertEquals("1e999999999", parsed.entries[2].parsedTime?.original)
        assertNull(parsed.entries[2].parsedTime?.epochMillis)
        assertEquals("exponent remains raw", parsed.entries[2].msg)
    }

    @Test
    fun binaryDltClassificationOverridesTextProfileAndSuffixDoesNotSelectAnEngine() {
        val dlt = parseLogContent(ByteArrayInputStream(dltTestFrame("binary DLT")), profile = plainTextProfile())
        assertEquals("automotive-dlt", dlt.profile?.id)
        assertEquals("binary DLT", dlt.entries.single().msg)
        assertFailsWith<IllegalArgumentException> {
            parseLogContent(
                ByteArrayInputStream(byteArrayOf(0x41, 0, 0, 4)),
                fileName = "capture.dlt",
                profile = plainTextProfile(),
            )
        }

        val dltNamedText = parseLogContent(
            ByteArrayInputStream("ordinary text".toByteArray()),
            fileName = "capture.dlt",
        )
        assertEquals("android-logcat", dltNamedText.profile?.id)

        val text = parseLogContent(
            ByteArrayInputStream("ordinary text\nsecond line\n".toByteArray()),
            fileName = "capture.jsonl",
        )
        assertEquals("android-logcat", text.profile?.id)
        assertEquals(listOf("ordinary text", "second line"), text.entries.map { it.msg })
    }

    @Test
    fun viewerTimestampWithoutUptimeRetainsSourceLocalCalendarTime() {
        val sample = "Time,ECU,AppId,ContextId,Type,Payload\n" +
            "2026-01-02 03:04:05,ECU1,APP1,CTX1,ERROR,viewer row\n"
        val entry = parseLogContent(
            ByteArrayInputStream(sample.toByteArray()),
            sourceClockId = "viewer-source-a",
        ).entries.single()

        assertEquals("2026-01-02 03:04:05", entry.parsedTime?.original)
        assertEquals("viewer-source-a", entry.parsedTime?.clockIdentity?.removePrefix("dlt-viewer:"))
        assertTrue(entry.parsedTime?.localWallMillis != null)
        assertNull(entry.parsedTime?.epochMillis)
    }

    @Test
    fun utf16TextUsesTheSharedDecoder() {
        val body = "first line\nsecond line\n".toByteArray(Charsets.UTF_16LE)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body
        val parsed = parseLogContent(ByteArrayInputStream(bytes), profile = plainTextProfile())

        assertEquals(listOf("first line", "second line"), parsed.entries.map { it.msg })
    }

    @Test
    fun profileCodecRejectsUnsafeIdsAndInvalidEnumsAndRoundTripsEmptyColumns() {
        val profile = plainTextProfile().copy(visibleColumns = emptyList())
        val encoded = logFormatProfileJson(profile)
        val restored = decodeLogFormatProfiles(encoded).single()
        assertEquals(emptyList(), restored.visibleColumns)

        assertFailsWith<IllegalArgumentException> {
            decodeLogFormatProfiles(encoded.replace("\"id\":\"plain-test\"", "\"id\":\"../unsafe\""))
        }
        assertFailsWith<IllegalArgumentException> {
            decodeLogFormatProfiles(encoded.replace("\"engine\":\"PLAIN_TEXT\"", "\"engine\":\"UNKNOWN\""))
        }
        val reservedAttributeJson = encoded.replace(
            "\"attributes\":[]",
            """"attributes":[{"id":"time_delta","label":"Delta","source":"delta","type":"TEXT","visibleByDefault":true}]""",
        )
        assertFailsWith<IllegalArgumentException> { decodeLogFormatProfiles(reservedAttributeJson) }
        assertFailsWith<IllegalArgumentException> {
            logFormatProfileJson(profile.copy(attributes = listOf(LogFormatAttribute("message", "Extra message", "msg"))))
        }
    }

    @Test
    fun customLegacyLogcatPresentationRetainsEmptyColumnsWithoutRawTextFabrication() {
        val logcat = LogFormatProfile(
            id = "custom-logcat",
            name = "Custom Logcat",
            description = "Customized logcat presentation",
            engine = LogFormatEngine.LOGCAT,
            visibleColumns = emptyList(),
        )
        val parsed = parseLogContent(
            ByteArrayInputStream("11-16 10:00:00.000 10 20 I Worker: ready\n".toByteArray()),
            profile = logcat,
        )
        val entry = parsed.entries.single()

        assertTrue(entry.recordMetadata?.usesProfilePresentation == true)
        assertTrue(entry.recordMetadata?.usesLegacyClockSemantics == true)
        assertTrue(entry.recordMetadata?.displayColumns?.isEmpty() == true)
        assertNull(entry.rawRecord)
        assertEquals("10", entry.profileFields["pid"])
        assertEquals("20", entry.profileFields["tid"])
    }

    @Test
    fun canonicalDltClocksSeparateAbsoluteStorageFromSourceAndEcuRelativeUptime() {
        val storageEntry = parseLogContent(
            ByteArrayInputStream(dltStorageHeader() + dltTestFrame("storage", ecu = "ECU1")),
            sourceClockId = "storage-source",
        ).entries.single()
        val storageTime = canonicalDltParsedTime(storageEntry, "storage-source")
        assertEquals(1_700_000_000_123L, storageTime?.epochMillis)
        assertEquals("absolute-epoch", storageTime?.clockIdentity)

        val relativeEntry = parseLogContent(
            ByteArrayInputStream(dltTestFrame("relative", ecu = "ECU2", timestamp = 12_345)),
            sourceClockId = "relative-source",
        ).entries.single()
        val relativeTime = canonicalDltParsedTime(relativeEntry, "relative-source")
        assertEquals(1_234L, relativeTime?.relativeMillis)
        assertEquals("dlt:relative-source:ECU2", relativeTime?.clockIdentity)
    }

    @Test
    fun explicitDltRelativeClockIsStoredOnRowsForCrossSourceDeltas() {
        val profile = LogFormatProfile(
            id = "shared-dlt-clock",
            name = "Shared DLT clock",
            description = "Custom relative clock identity",
            engine = LogFormatEngine.DLT,
            relativeClock = "device-boot-1",
        )
        val first = parseLogContent(
            ByteArrayInputStream(dltTestFrame("first", ecu = "ECU1", timestamp = 12_345)),
            profile = profile,
            sourceClockId = "capture-a",
        ).entries.single()
        val second = parseLogContent(
            ByteArrayInputStream(dltTestFrame("second", ecu = "ECU2", timestamp = 12_445)),
            profile = profile,
            sourceClockId = "capture-b",
        ).entries.single()

        assertEquals("relative:device-boot-1", first.parsedTime?.clockIdentity)
        assertEquals(first.parsedTime?.clockIdentity, second.parsedTime?.clockIdentity)
        assertEquals(10L, deltaMillis(first, second))
        assertNull(first.rawRecord)
    }

    @Test
    fun customDltPresentationUsesTypedFieldsAndDoesNotInventRawText() {
        val profile = LogFormatProfile(
            id = "custom-dlt",
            name = "Custom DLT",
            description = "Customized DLT columns",
            engine = LogFormatEngine.DLT,
            visibleColumns = listOf(LogFieldRole.HOST, LogFieldRole.SERVICE, LogFieldRole.CONTAINER, LogFieldRole.MESSAGE),
            attributes = listOf(LogFormatAttribute("uptime", "Uptime", "dlt_timestamp", visibleByDefault = true)),
        )
        val entry = parseLogContent(
            ByteArrayInputStream(dltTestFrame("typed DLT", ecu = "ECU1", session = 34, timestamp = 1234)),
            profile = profile,
        ).entries.single()

        assertTrue(entry.recordMetadata?.usesProfilePresentation == true)
        assertTrue(entry.recordMetadata?.usesLegacyClockSemantics == true)
        assertNull(entry.rawRecord)
        assertEquals("ECU1", entry.profileFields["HOST"])
        assertEquals("APP1", entry.profileFields["SERVICE"])
        assertEquals("CTX1", entry.profileFields["CONTAINER"])
        assertEquals("1234", entry.profileFields["uptime"])
        assertEquals(listOf("host", "service", "container", "message", "uptime"), entry.recordMetadata?.displayColumns?.map { it.id })
    }

    @Test
    fun rowsWithTheSameKeysShareOneCompactFieldLayoutAndStillBehaveAsMaps() {
        val rows = """{"time":"2024-01-01T00:00:00Z","level":"INFO","msg":"one","user":"a"}""" + "\n" +
            """{"time":"2024-01-01T00:00:01Z","level":"INFO","msg":"two","user":"b"}"""
        val json = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.JSON_LINES }
        val (first, second) = parseLogContent(ByteArrayInputStream(rows.toByteArray()), profile = json).entries

        assertEquals("a", first.profileFields["user"])
        assertEquals("b", second.profileFields["user"])
        assertNull(first.profileFields["absent"])
        assertEquals(first.profileFields.keys.toList(), second.profileFields.keys.toList())
        // Equal to an ordinary map with the same content, in both directions, with the same hash.
        val copy = LinkedHashMap(first.profileFields)
        assertEquals<Map<String, String>>(copy, first.profileFields)
        assertEquals(first.profileFields, copy)
        assertEquals(copy.hashCode(), first.profileFields.hashCode())
        // Repeated short values are one object across rows.
        assertTrue(first.profileFields["level"] === second.profileFields["level"])
    }

    private fun plainTextProfile() = LogFormatProfile(
        id = "plain-test",
        name = "Plain test",
        description = "Parser test profile",
        engine = LogFormatEngine.PLAIN_TEXT,
    ).also(::validateLogFormatProfile)
}
