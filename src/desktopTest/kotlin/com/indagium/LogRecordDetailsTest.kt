package com.indagium

import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.LogEntry
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatAttribute
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.model.ParsedTime
import com.indagium.utils.buildLogRecordDetails
import com.indagium.utils.hasRecordDetails
import com.indagium.utils.parseLogContent
import com.indagium.utils.recordInspectionFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogRecordDetailsTest {
    private val jsonProfile = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.JSON_LINES }

    private fun jsonRow(raw: String): LogEntry =
        parseLogContent(ByteArrayInputStream((raw + "\n").toByteArray()), profile = jsonProfile).entries.single()

    private fun summary(entry: LogEntry) = buildLogRecordDetails(entry).summary.associate { it.key to it.value }

    @Test
    fun aLogcatRowOffersNoDetails() {
        val plain = LogEntry(1, "10:00:00.000", LogLevel.I, "Tag", "message")
        assertFalse(plain.hasRecordDetails())
        // An unparsed Logcat row carries metadata, but neither fields nor a raw record.
        val unparsed = plain.copy(recordMetadata = LogRecordMetadata(severityKnown = false))
        assertFalse(unparsed.hasRecordDetails())
    }

    @Test
    fun jsonIsInspectableAndShortRawTextStaysCompact() {
        assertTrue(jsonRow("""{"level":"INFO","message":"hi"}""").hasRecordDetails())
        val rawOnly = LogEntry(1, "", LogLevel.V, "", "x", recordMetadata = LogRecordMetadata(rawRecord = "x"))
        assertFalse(rawOnly.hasRecordDetails())
    }

    @Test
    fun theHeaderShowsTimeLevelComponentAndSourceLine() {
        val entry = jsonRow(
            """{"timestamp":"2024-01-01T00:00:00Z","level":"WARNING","logger":"net.Client","message":"slow","retries":"3"}""",
        )
        val details = buildLogRecordDetails(entry)
        val lines = details.summary.associate { it.key to it.value }
        assertEquals("2024-01-01T00:00:00Z", lines["time"])
        assertEquals("2024-01-01T00:00:00Z (absolute, UTC)", lines["parsed-time"])
        assertEquals("Warn (original: WARNING)", lines["level"])
        assertEquals("net.Client", lines["component"])
        assertEquals("line 1", lines["source"])
        assertEquals("slow", details.fields.first { it.first == "message" }.second)
        assertEquals("3", details.fields.first { it.first == "retries" }.second)
        assertEquals(entry.rawRecord, details.rawRecord)
    }

    @Test
    fun aKnownLevelWrittenTheSameWayShowsNoOriginalAndAnUnknownOneShowsIt() {
        assertEquals("Error", summary(jsonRow("""{"level":"error","message":"x"}"""))["level"])
        assertEquals("Unknown (original: MYSTERY)", summary(jsonRow("""{"level":"MYSTERY","message":"x"}"""))["level"])
        assertEquals("Unknown", summary(jsonRow("""{"message":"x"}"""))["level"])
    }

    @Test
    fun aMultiLineRecordShowsItsLineSpanAndTimeKindsAreDescribed() {
        val span = LogEntry(
            1, "", LogLevel.E, "", "m",
            recordMetadata = LogRecordMetadata(sourceStartLine = 12, sourceEndLine = 15, rawRecord = "m"),
        )
        assertEquals("lines 12–15", summary(span)["source"])
        val relative = span.copy(recordMetadata = span.recordMetadata!!.copy(parsedTime = ParsedTime("12.5", relativeMillis = 12_500)))
        assertEquals("12500 ms (relative)", summary(relative)["parsed-time"])
        assertEquals("12.5", summary(relative)["time"])
        val noTime = summary(span)
        assertNull(noTime["time"])
        assertNull(noTime["parsed-time"])
    }

    @Test
    fun fieldsAsJsonKeepRowOrderAndEscapeValues() {
        val entry = jsonRow("""{"zeta":"last? \"quoted\"","alpha":"first","message":"line1\nline2"}""")
        val details = buildLogRecordDetails(entry)
        val parsed = Json.parseToJsonElement(details.fieldsJson).jsonObject
        assertEquals(details.fields.map { it.first }, parsed.keys.toList())
        assertEquals("last? \"quoted\"", parsed.getValue("zeta").jsonPrimitive.content)
        assertEquals("line1\nline2", parsed.getValue("message").jsonPrimitive.content)
    }

    @Test
    fun anRfc5424RecordListsItsStructuredDataKeyByKeyAndNotAgainAsOneString() {
        val profile = BUILTIN_LOG_FORMAT_PROFILES.single { it.id == "syslog-rfc5424" }
        val line = "<165>1 2026-11-20T10:00:00Z host app 4100 ID47 [exampleSDID@32473 iut=\"3\" eventSource=\"Application\"] An application event"
        val entry = parseLogContent(ByteArrayInputStream((line + "\n").toByteArray()), profile = profile).entries.single()
        val keys = buildLogRecordDetails(entry).fields.map { it.first }
        assertTrue("sd.exampleSDID@32473.iut" in keys, keys.toString())
        assertTrue(keys.none { it.equals("structured_data", ignoreCase = true) }, keys.toString())
        // The in-row record tree shows the same fields as Message details.
        assertTrue(entry.recordInspectionFields().keys.none { it.equals("structured_data", ignoreCase = true) })
    }

    @Test
    fun anRfc5424RecordListsItsFieldsInTheOrderOfTheSourceLineWithTheMessageLast() {
        val profile = BUILTIN_LOG_FORMAT_PROFILES.single { it.id == "syslog-rfc5424" }
        // <PRI>VERSION TIMESTAMP HOST APP PROCID MSGID [SD] MSG
        val line = "<165>1 2026-11-20T10:00:00Z host app 4100 ID47 [exampleSDID@32473 iut=\"3\" eventSource=\"Application\"] An application event"
        val entry = parseLogContent(ByteArrayInputStream((line + "\n").toByteArray()), profile = profile).entries.single()
        val expected = listOf(
            "timestamp", "host", "component", "process_id", "message_id",
            "sd.exampleSDID@32473.iut", "sd.exampleSDID@32473.eventSource", "message",
        )
        assertEquals(expected, buildLogRecordDetails(entry).fields.map { it.first })
        assertEquals(expected, entry.recordInspectionFields().keys.toList())
        // A record with nil structured data ("-") and no timestamp keeps the source segment before its message.
        val plain = "<165>1 - host app 4100 ID47 - just text"
        val bare = parseLogContent(ByteArrayInputStream((plain + "\n").toByteArray()), profile = profile).entries.single()
        assertEquals(listOf("host", "component", "process_id", "message_id", "structured_data", "message"), buildLogRecordDetails(bare).fields.map { it.first })
    }

    @Test
    fun csvDetailsListTheFieldsInTheOrderOfTheSourceLine() {
        val profile = LogFormatProfile(
            id = "ordered-csv", name = "Ordered CSV", engine = LogFormatEngine.CSV,
            fieldMappings = mapOf(
                LogFieldRole.MESSAGE to "message", LogFieldRole.TIMESTAMP to "time",
                LogFieldRole.SEVERITY to "level", LogFieldRole.SERVICE to "service",
            ),
            visibleColumns = listOf(LogFieldRole.TIMESTAMP, LogFieldRole.SEVERITY, LogFieldRole.MESSAGE),
            attributes = listOf(
                LogFormatAttribute(id = "duration_ms", label = "Duration", source = "duration_ms"),
                LogFormatAttribute(id = "status", label = "Status", source = "status"),
            ),
        )
        val header = "time,level,service,host,message,request_id,duration_ms,status"
        val line = "2026-11-20T10:00:00Z,INFO,api,node-1,served,r-7,42,200"
        val entry = parseLogContent(ByteArrayInputStream("$header\n$line\n".toByteArray()), profile = profile).entries.single()
        val details = buildLogRecordDetails(entry)
        assertEquals(header.split(','), details.fields.map { it.first }, details.fields.toString())
        assertEquals("42", details.fields.single { it.first == "duration_ms" }.second)
        // Message details and the in-row record tree list the same fields in the same order.
        assertEquals(details.fields.map { it.first }, entry.recordInspectionFields().keys.toList())
    }

    @Test
    fun dltDetailsUseLowercaseSnakeCaseKeysLikeTheOtherFormats() {
        val entry = LogEntry(
            1, "10:00:00.500", LogLevel.I, "ECU1/APP1/CTX1", "Request accepted", pid = 4100,
            dltEcuId = "ECU1", dltAppId = "APP1", dltContextId = "CTX1", dltMessageType = "log",
            dltTimestamp = 1_700_000_000L, dltTimestampSource = "storage", dltUptime = 1_234_567L,
        )
        val keys = buildLogRecordDetails(entry).fields.map { it.first }
        assertEquals(
            listOf("ecu", "apid", "ctid", "session_id", "message_type", "storage_time", "ecu_uptime", "message"),
            keys,
        )
        assertEquals("123.4567 s", buildLogRecordDetails(entry).fields.single { it.first == "ecu_uptime" }.second)
        assertEquals(keys, entry.recordInspectionFields().keys.toList())
    }
}
