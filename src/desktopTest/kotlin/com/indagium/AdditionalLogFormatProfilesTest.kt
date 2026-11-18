package com.indagium

import com.indagium.model.ADDITIONAL_BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.LogEntry
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.utils.detectAdditionalLogProfile
import com.indagium.utils.detectAdditionalLogProfileResult
import com.indagium.utils.parseCriProfile
import com.indagium.utils.parseLogContent
import com.indagium.utils.parseLogfmtProfile
import com.indagium.utils.validateLogFormatProfile
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdditionalLogFormatProfilesTest {
    private fun profile(id: String): LogFormatProfile = ADDITIONAL_BUILTIN_LOG_FORMAT_PROFILES.single { it.id == id }

    private fun LogEntry.column(id: String): String? = recordMetadata?.displayColumns?.firstOrNull { it.id == id }?.value

    private fun parseJson(text: String, profile: LogFormatProfile) =
        parseLogContent(ByteArrayInputStream(text.toByteArray()), profile = profile)

    @Test
    fun everyAdditionalBuiltInProfilePassesValidationAndHasAUniqueIdentity() {
        (BUILTIN_LOG_FORMAT_PROFILES + ADDITIONAL_BUILTIN_LOG_FORMAT_PROFILES).forEach(::validateLogFormatProfile)

        val allIds = BUILTIN_LOG_FORMAT_PROFILES.map { it.id }
        assertEquals(allIds.size, allIds.distinct().size)
        assertTrue(ADDITIONAL_BUILTIN_LOG_FORMAT_PROFILES.all { it.id in allIds })
        assertTrue(ADDITIONAL_BUILTIN_LOG_FORMAT_PROFILES.all { it.readOnly })
    }

    @Test
    fun pinoJsonMapsNumericLevelsEpochMillisecondsAndLoggerMetadata() {
        val row = """{"level":50,"time":1700000000123,"pid":42,"hostname":"worker-2","name":"api","msg":"request failed","traceId":"abc","reqId":"request-7"}"""
        val parsed = parseJson(row, profile("pino-json"))
        val entry = parsed.entries.single()

        assertEquals(LogLevel.E, entry.level)
        assertTrue(entry.severityKnown)
        assertEquals("50", entry.originalSeverity)
        assertEquals("api", entry.tag)
        assertEquals(42, entry.pid)
        assertEquals("worker-2", entry.profileFields["HOST"])
        assertEquals("abc", entry.profileFields["TRACE_ID"])
        assertEquals("request-7", entry.profileFields["REQUEST_ID"])
        assertEquals(1_700_000_000_123L, entry.parsedTime?.epochMillis)
        assertEquals(row, entry.rawRecord)
        assertEquals(1L, entry.sourceStartLine)
        // The row shows the instant as an ISO time in UTC and the level by its Pino name; the record keeps both numbers.
        assertEquals("2023-11-14T22:13:20.123Z", entry.column("timestamp"))
        assertEquals("ERROR", entry.column("severity"))
        assertEquals("22:13:20.123", entry.ts)
    }

    @Test
    fun prettyPinoObjectUsesTheSameMultilineJsonBoundaryAndDetection() {
        val raw = buildString {
            appendLine("{")
            appendLine("  \"level\": 50,")
            appendLine("  \"time\": 1700000000123,")
            appendLine("  \"pid\": 42,")
            appendLine("  \"name\": \"api\",")
            appendLine("  \"msg\": \"pretty request failed\",")
            appendLine("  \"context\": {")
            repeat(22) { index -> appendLine("    \"field$index\": $index,") }
            appendLine("    \"traceId\": \"trace-7\"")
            appendLine("  }")
            append("}")
        }
        val selected = detectAdditionalLogProfile(raw.toByteArray())
        val parsed = parseJson(raw, profile("pino-json"))

        assertEquals("pino-json", selected?.id)
        assertEquals(1, parsed.entries.size)
        assertEquals("pretty request failed", parsed.entries.single().msg)
        assertEquals(raw, parsed.entries.single().rawRecord)
        assertEquals(1L, parsed.entries.single().sourceStartLine)
        assertEquals(raw.count { it == '\n' }.toLong() + 1, parsed.entries.single().sourceEndLine)
    }

    @Test
    fun pinoNumericLevelScaleMapsAllDocumentedValuesAndKeepsUnknownValuesNeutral() {
        val expected = mapOf(10 to LogLevel.V, 20 to LogLevel.D, 30 to LogLevel.I, 40 to LogLevel.W, 50 to LogLevel.E, 60 to LogLevel.A)

        expected.forEach { (numeric, level) ->
            val row = """{"level":$numeric,"time":1700000000000,"pid":5,"msg":"level"}"""
            val entry = parseJson(row, profile("pino-json")).entries.single()
            assertEquals(level, entry.level, "Pino level $numeric")
            assertTrue(entry.severityKnown, "Pino level $numeric should be known")
        }

        val unknown = parseJson("""{"level":15,"time":1700000000000,"pid":5,"msg":"custom"}""", profile("pino-json")).entries.single()
        assertFalse(unknown.severityKnown)
        assertEquals("15", unknown.originalSeverity)
        assertEquals("15", unknown.column("severity"), "a value off the scale shows as written")
        val names = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")
        expected.keys.zip(names).forEach { (numeric, name) ->
            val row = """{"level":$numeric,"time":1700000000000,"pid":5,"msg":"level"}"""
            assertEquals(name, parseJson(row, profile("pino-json")).entries.single().column("severity"))
        }
    }

    @Test
    fun dockerJsonFileRetainsStreamWithoutTreatingStderrAsSeverity() {
        val row = """{"log":"failed\n","stream":"stderr","time":"2019-01-01T11:11:11.111111111Z"}"""
        val entry = parseJson(row, profile("docker-json-file")).entries.single()

        // The line break Docker stores at the end of `log` is not part of the row's text.
        assertEquals("failed", entry.msg)
        assertEquals("failed\n", entry.profileFields["log"])
        assertEquals("stderr", entry.profileFields["STREAM"])
        assertFalse(entry.severityKnown)
        assertNull(entry.originalSeverity)
        assertEquals(1_546_341_071_111L, entry.parsedTime?.epochMillis)
        assertEquals(row, entry.rawRecord)
    }

    @Test
    fun systemdJsonUsesMicrosecondsAndSyslogPrioritySemantics() {
        val row = """{"MESSAGE":"device disconnected","PRIORITY":"2","__REALTIME_TIMESTAMP":"1700000000123456",""" +
            """"_SYSTEMD_UNIT":"example.service","_PID":"8","_HOSTNAME":"node-a","_BOOT_ID":"boot-1",""" +
            """"__MONOTONIC_TIMESTAMP":"9212345"}"""
        val entry = parseJson(row, profile("systemd-journal-json")).entries.single()

        assertEquals("device disconnected", entry.msg)
        assertEquals("example.service", entry.tag)
        assertEquals(8, entry.pid)
        assertEquals(LogLevel.A, entry.level)
        assertEquals("2", entry.originalSeverity)
        assertTrue(entry.severityKnown)
        assertEquals(1_700_000_000_123L, entry.parsedTime?.epochMillis)
        assertEquals("boot-1", entry.profileFields["boot_id"])
        assertEquals("9212345", entry.profileFields["monotonic_timestamp"])
        // Microseconds read as UTC like Pino's milliseconds; priority 2 is CRIT, not the bucket it shares with 0 and 1.
        assertEquals("2023-11-14T22:13:20.123Z", entry.column("timestamp"))
        assertEquals("CRIT", entry.column("severity"))
    }

    @Test
    fun dmesgKeepsBootTimeRelativeToTheInputClock() {
        val parsed = parseLogContent(
            ByteArrayInputStream("[    1.250000] usb 1-1: device attached\n".toByteArray()),
            profile = profile("dmesg-boot-time"),
            sourceClockId = "kernel-capture-a",
        )
        val entry = parsed.entries.single()

        assertEquals("usb 1-1: device attached", entry.msg)
        assertEquals(1_250L, entry.parsedTime?.relativeMillis)
        assertEquals("relative:kernel-capture-a", entry.parsedTime?.clockIdentity)
        assertNull(entry.parsedTime?.epochMillis)
        assertEquals("[    1.250000] usb 1-1: device attached", entry.rawRecord)
    }

    @Test
    fun httpCommonAndCombinedRowsExposeRequestStatusAndEscapedUserAgent() {
        val row = "127.0.0.1 - frank [10/Oct/2000:13:55:36 -0700] \"GET /apache_pb.gif HTTP/1.1\" 404 2326 " +
            "\"https://example.test/start\" \"Agent \\\"quoted\\\"\""
        val entry = parseLogContent(ByteArrayInputStream((row + "\n").toByteArray()), profile = profile("http-common-combined")).entries.single()

        assertEquals("127.0.0.1", entry.profileFields["HOST"])
        assertEquals("GET /apache_pb.gif HTTP/1.1", entry.msg)
        assertEquals("GET", entry.profileFields["HTTP_METHOD"])
        assertEquals("/apache_pb.gif", entry.profileFields["HTTP_PATH"])
        assertEquals("404", entry.profileFields["http_status"])
        assertEquals("2326", entry.profileFields["bytes_sent"])
        assertEquals("https://example.test/start", entry.profileFields["referrer"])
        assertEquals("Agent \\\"quoted\\\"", entry.profileFields["user_agent"])
        assertEquals("10/Oct/2000:13:55:36 -0700", entry.parsedTime?.original)
        assertTrue(entry.parsedTime?.epochMillis != null)
        assertFalse(entry.severityKnown)
        assertEquals(row, entry.rawRecord)
    }

    @Test
    fun httpCommonVariantParsesRowsWithoutCombinedHeaders() {
        val row = "192.0.2.4 - - [10/Oct/2000:13:55:36 -0700] \"HEAD /health HTTP/1.0\" 204 -"
        val entry = parseLogContent(ByteArrayInputStream(row.toByteArray()), profile = profile("http-common-combined")).entries.single()

        assertEquals("HEAD /health HTTP/1.0", entry.msg)
        assertEquals("HEAD", entry.profileFields["HTTP_METHOD"])
        assertEquals("/health", entry.profileFields["HTTP_PATH"])
        assertEquals("204", entry.profileFields["http_status"])
        assertEquals("-", entry.profileFields["bytes_sent"])
        assertFalse(entry.severityKnown)
    }

    @Test
    fun logfmtDecodesQuotedEscapesAndRetainsMalformedRowsRaw() {
        val raw = "time=2024-02-03T04:05:06Z level=INFO msg=\"hello \\\"world\\\"\\nnext\" pid=17 trace_id=t-1 empty="
        val malformed = "time=2024-02-03T04:05:06Z level=INFO msg=\"unfinished"
        val parsed = parseLogfmtProfile(sequenceOf(raw, malformed), profile("logfmt"), sourceClockId = "app-a")
        val first = parsed.entries[0]
        val second = parsed.entries[1]

        assertEquals("hello \"world\"\nnext", first.msg)
        assertEquals(LogLevel.I, first.level)
        assertEquals("INFO", first.originalSeverity)
        assertTrue(first.severityKnown)
        assertEquals(17, first.pid)
        assertEquals("t-1", first.profileFields["TRACE_ID"])
        assertEquals("", first.profileFields["empty"])
        assertEquals(1L, first.sourceStartLine)
        assertEquals(raw, first.rawRecord)
        assertFalse(second.severityKnown)
        assertEquals(malformed, second.msg)
        assertEquals(malformed, second.rawRecord)
        assertTrue(parsed.diagnostics.any { "malformed logfmt" in it })
    }

    @Test
    fun criInterleavedStreamsReassembleIndependentlyAndStayInSourceOrder() {
        val rows = sequenceOf(
            "2024-01-01T00:00:00.000000001Z stdout P hello ",
            "2024-01-01T00:00:00.000000002Z stderr F side-channel",
            "2024-01-01T00:00:00.000000003Z stdout F world",
            "2024-01-01T00:00:00.000000004Z stderr P unfinished",
        )
        val parsed = parseCriProfile(rows, profile("kubernetes-cri"), sourceClockId = "pod-log-a")

        assertEquals(listOf("hello world", "side-channel", "unfinished"), parsed.entries.map { it.msg })
        assertEquals(listOf("stdout", "stderr", "stderr"), parsed.entries.map { it.profileFields["STREAM"] })
        assertEquals(listOf(1L, 2L, 4L), parsed.entries.map { it.sourceStartLine })
        assertEquals(listOf(3L, 2L, 4L), parsed.entries.map { it.sourceEndLine })
        assertTrue(parsed.entries.last().rawRecord.orEmpty().contains("stderr P unfinished"))
        assertTrue(parsed.entries.first().rawRecord.orEmpty().contains("stdout P hello "))
        assertEquals(1_704_067_200_000L, parsed.entries.first().parsedTime?.epochMillis)
        assertTrue(parsed.diagnostics.any { "incomplete CRI" in it })
    }

    @Test
    fun contentDetectionSelectsSpecificBuiltinsButLeavesGenericJsonAndTextAlone() {
        val docker = """{"log":"ok","stream":"stdout","time":"2024-01-01T00:00:00Z"}""".toByteArray()
        val pino = """{"level":30,"time":1700000000000,"pid":5,"msg":"ok"}""".toByteArray()
        val journal = """{"MESSAGE":"ok","PRIORITY":"6","__REALTIME_TIMESTAMP":"1700000000000000","_PID":"5"}""".toByteArray()
        val http = "127.0.0.1 - - [10/Oct/2000:13:55:36 -0700] \"GET / HTTP/1.1\" 200 2\n".toByteArray()
        val dmesg = "[  10.250] usb 1-1: attached\n[  10.500] usb 1-1: configured\n".toByteArray()
        val cri = "2024-01-01T00:00:00Z stdout F app started\n".toByteArray()
        val logfmt = "time=2024-01-01T00:00:00Z level=INFO msg=started\n".toByteArray()
        val generic = """{"timestamp":"2024-01-01T00:00:00Z","level":"WARN","message":"ok"}""".toByteArray()
        val mixed = listOf(
            """{"log":"one","stream":"stdout","time":"2024-01-01T00:00:00Z"}""",
            """{"log":"two","stream":"stderr","time":"2024-01-01T00:00:01Z"}""",
            """{"level":30,"time":1700000000000,"pid":5,"msg":"one"}""",
            """{"level":40,"time":1700000000001,"pid":5,"msg":"two"}""",
        ).joinToString("\n").toByteArray()

        assertEquals("docker-json-file", detectAdditionalLogProfile(docker)?.id)
        assertEquals("pino-json", detectAdditionalLogProfile(pino)?.id)
        assertEquals("systemd-journal-json", detectAdditionalLogProfile(journal)?.id)
        assertEquals("http-common-combined", detectAdditionalLogProfile(http)?.id)
        assertEquals("dmesg-boot-time", detectAdditionalLogProfile(dmesg)?.id)
        assertEquals("kubernetes-cri", detectAdditionalLogProfile(cri)?.id)
        assertEquals("logfmt", detectAdditionalLogProfile(logfmt)?.id)
        assertNull(detectAdditionalLogProfile(generic))
        assertNull(detectAdditionalLogProfile("ordinary text\nnot a log signature\n".toByteArray()))
        assertTrue(detectAdditionalLogProfileResult(mixed).ambiguous)
        assertNull(detectAdditionalLogProfileResult(mixed).profile)
    }

    @Test
    fun parserRoutesClearDockerSignaturesButDoesNotInferFromLogExtensionOrArbitraryJson() {
        val docker = """{"log":"ok","stream":"stdout","time":"2024-01-01T00:00:00Z"}"""
        val routedDocker = parseLogContent(ByteArrayInputStream(docker.toByteArray()), fileName = "container.log")
        assertEquals("docker-json-file", routedDocker.profile?.id)
        assertEquals("ok", routedDocker.entries.single().msg)

        val routedLogfmt = parseLogContent(ByteArrayInputStream("time=2024-01-01T00:00:00Z level=INFO msg=ready".toByteArray()))
        assertEquals("logfmt", routedLogfmt.profile?.id)
        assertEquals("ready", routedLogfmt.entries.single().msg)

        val cri = "2024-01-01T00:00:00Z stdout F app started"
        val routedCri = parseLogContent(ByteArrayInputStream(cri.toByteArray()))
        assertEquals("kubernetes-cri", routedCri.profile?.id)
        assertEquals("app started", routedCri.entries.single().msg)

        val ordinaryText = parseLogContent(ByteArrayInputStream("plain first\nplain second\n".toByteArray()), fileName = "arbitrary.log")
        // Unrecognised text keeps going to the Logcat parser, as RAW rows.
        assertEquals("android-logcat", ordinaryText.profile?.id)
        assertEquals(listOf("plain first", "plain second"), ordinaryText.entries.map { it.msg })
        assertEquals(listOf("RAW", "RAW"), ordinaryText.entries.map { it.tag })

        val arbitraryJson = parseLogContent(ByteArrayInputStream("""{"field":"value"}""".toByteArray()))
        assertEquals("android-logcat", arbitraryJson.profile?.id)
        assertEquals("{\"field\":\"value\"}", arbitraryJson.entries.single().msg)
    }

    @Test
    fun genericJsonEngineStillMapsCommonAliasesWhenExplicitlySelected() {
        val generic = """{"timestamp":"2024-01-01T00:00:00Z","level":"WARN","message":"ready","logger":"worker"}"""
        val jsonProfile = BUILTIN_LOG_FORMAT_PROFILES.single { it.id == "json-lines" }
        val entry = parseJson(generic, jsonProfile).entries.single()

        assertEquals("ready", entry.msg)
        assertEquals("worker", entry.tag)
        assertEquals(LogLevel.W, entry.level)
        assertTrue(entry.severityKnown)
    }

    @Test
    fun malformedSpecializedJsonRemainsRawAndDoesNotInventFields() {
        val raw = "{not valid json"
        val entry = parseJson(raw, profile("docker-json-file")).entries.single()

        assertEquals(raw, entry.msg)
        assertEquals(raw, entry.rawRecord)
        assertFalse(entry.severityKnown)
        assertNull(entry.parsedTime)
    }
}
