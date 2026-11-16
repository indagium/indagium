package com.indagium

import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.LogEntry
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.model.ParsedTime
import com.indagium.utils.buildLogRecordDetails
import com.indagium.utils.hasRecordDetails
import com.indagium.utils.parseLogContent
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
    fun aRowWithFieldsOrARawRecordOffersDetails() {
        assertTrue(jsonRow("""{"level":"INFO","message":"hi"}""").hasRecordDetails())
        val rawOnly = LogEntry(1, "", LogLevel.V, "", "x", recordMetadata = LogRecordMetadata(rawRecord = "x"))
        assertTrue(rawOnly.hasRecordDetails())
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
}
