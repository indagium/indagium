package com.indagium

import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.utils.parseLogContent
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises record boundaries across formats whose records can span or cannot span physical lines. */
class LogRecordBoundaryRegressionTest {
    private fun builtin(id: String): LogFormatProfile = BUILTIN_LOG_FORMAT_PROFILES.single { it.id == id }

    private fun parse(text: String, profile: LogFormatProfile) =
        parseLogContent(ByteArrayInputStream(text.toByteArray()), profile = profile)

    @Test
    fun quotedCsvAndConfiguredRegexKeepTwentyPhysicalLinesAsOneRow() {
        val payloadLines = (1..20).map { "message line %02d".format(it) }
        val payload = payloadLines.joinToString("\n")
        val csvProfile = LogFormatProfile(
            id = "boundary-csv",
            name = "Boundary CSV",
            engine = LogFormatEngine.CSV,
            fieldMappings = mapOf(LogFieldRole.TIMESTAMP to "time", LogFieldRole.MESSAGE to "message"),
            visibleColumns = listOf(LogFieldRole.MESSAGE),
        )
        val csv = parse(
            "time,message,trace\n" +
                "2024-01-01T00:00:00Z,\"$payload\",trace-a\n" +
                "2024-01-01T00:00:01Z,after,trace-b\n",
            csvProfile,
        )

        assertEquals(2, csv.entries.size, "CSV has two logical data records, not one row per message line")
        assertEquals(payload, csv.entries[0].msg)
        assertEquals(2L, csv.entries[0].sourceStartLine)
        assertEquals(21L, csv.entries[0].sourceEndLine)
        assertEquals("trace-a", csv.entries[0].profileFields["trace"])
        assertEquals(22L, csv.entries[1].sourceStartLine)
        assertEquals("after", csv.entries[1].msg)

        val regexProfile = LogFormatProfile(
            id = "boundary-regex",
            name = "Boundary Regex",
            engine = LogFormatEngine.REGEX,
            regexPattern = "(?s)^\\[(\\S+)] ([A-Z]+) (.*)$",
            multilineStartPattern = "^\\[",
            fieldMappings = mapOf(
                LogFieldRole.TIMESTAMP to "1",
                LogFieldRole.SEVERITY to "2",
                LogFieldRole.MESSAGE to "3",
            ),
        )
        val traceLines = (1..19).map { "\tat example.Frame%02d.run(Frame.java:%d)".format(it, it) }
        val regex = parse(
            (listOf("[2024-01-01T00:00:00Z] ERROR failed") + traceLines +
                "[2024-01-01T00:00:01Z] INFO recovered").joinToString("\n"),
            regexProfile,
        )

        assertEquals(2, regex.entries.size, "configured start pattern groups the 20-line event")
        assertEquals((listOf("failed") + traceLines).joinToString("\n"), regex.entries[0].msg)
        assertEquals(1L, regex.entries[0].sourceStartLine)
        assertEquals(20L, regex.entries[0].sourceEndLine)
        assertEquals(21L, regex.entries[1].sourceStartLine)
        assertEquals("recovered", regex.entries[1].msg)

        val lineRegex = parse(
            "[2024-01-01T00:00:00Z] ERROR failed\nframe one\n[2024-01-01T00:00:01Z] INFO recovered",
            regexProfile.copy(id = "boundary-regex-lines", multilineStartPattern = ""),
        )
        assertEquals(2, lineRegex.entries.size, "a line that does not match continues the record above it")
        assertEquals("failed\nframe one", lineRegex.entries[0].msg)
        assertEquals(1L, lineRegex.entries[0].sourceStartLine)
        assertEquals(2L, lineRegex.entries[0].sourceEndLine)
        assertEquals("recovered", lineRegex.entries[1].msg)

        val separate = parse(
            "[2024-01-01T00:00:00Z] ERROR failed\nframe one\n[2024-01-01T00:00:01Z] INFO recovered",
            regexProfile.copy(id = "boundary-regex-separate", multilineStartPattern = "", joinUnmatchedLines = false),
        )
        assertEquals(3, separate.entries.size, "with joining off every physical line is its own row")
        assertEquals("frame one", separate.entries[1].msg)
        assertFalse(separate.entries[1].severityKnown)
    }

    @Test
    fun criJoinsLongInterleavedFragmentsByStreamAndKeepsStartOrder() {
        val rows = mutableListOf<String>()
        val expectedStdout = (1..21).filter { it != 2 && it != 10 }.joinToString("") { "part%02d ".format(it) } + "finished"
        for (index in 1..22) {
            val timestamp = "2024-01-01T00:00:00.%09dZ".format(index)
            when (index) {
                2, 10 -> rows += "$timestamp stderr F side-$index"
                22 -> rows += "$timestamp stdout F finished"
                else -> rows += "$timestamp stdout P part%02d ".format(index)
            }
        }
        val profile = builtin("kubernetes-cri")
        val parsed = parse(rows.joinToString("\n"), profile)

        assertEquals(3, parsed.entries.size, "twenty stdout fragments and two stderr records form three events")
        assertEquals(expectedStdout, parsed.entries[0].msg)
        assertEquals("stdout", parsed.entries[0].profileFields["STREAM"])
        assertEquals(1L, parsed.entries[0].sourceStartLine)
        assertEquals(22L, parsed.entries[0].sourceEndLine)
        assertEquals(listOf("side-2", "side-10"), parsed.entries.drop(1).map { it.msg })
        assertEquals(listOf(2L, 10L), parsed.entries.drop(1).map { it.sourceStartLine })
    }

    @Test
    fun unmatchedLinesContinueTheRecordAboveWhereTheFormatHasAHeaderLine() {
        val logcat = parse(
            "11-16 10:00:00.000 10 20 E Worker: java.lang.IllegalStateException\n" +
                "    at example.Worker.run(Worker.java:20)\n" +
                "11-16 10:00:01.000 10 20 I Worker: recovered\n",
            builtin("android-logcat"),
        )
        assertEquals(3, logcat.entries.size)
        assertEquals("java.lang.IllegalStateException", logcat.entries[0].msg)
        assertEquals("RAW", logcat.entries[1].tag)
        assertEquals("recovered", logcat.entries[2].msg)

        val plain = parse("first\n\nsecond\nthird\n", builtin("plain-text"))
        assertEquals(3, plain.entries.size)
        assertEquals(listOf("first", "second", "third"), plain.entries.map { it.msg })

        val rfc5424 = parse(
            "<34>1 2024-11-16T10:00:00Z host app 123 ID47 - failed\n" +
                "  caused by lower layer\n" +
                "<34>1 2024-11-16T10:00:01Z host app 123 ID48 - recovered\n",
            builtin("syslog-rfc5424"),
        )
        assertEquals(2, rfc5424.entries.size)
        assertEquals("failed\n  caused by lower layer", rfc5424.entries[0].msg)
        assertTrue(rfc5424.entries[0].severityKnown)
        assertEquals(2L, rfc5424.entries[0].sourceEndLine)
        assertEquals("recovered", rfc5424.entries[1].msg)

        val rfc3164 = parse(
            "Nov 16 10:00:00 host app[123]: failed\n" +
                "  caused by lower layer\n" +
                "Nov 16 10:00:01 host app[123]: recovered\n",
            builtin("syslog-rfc3164"),
        )
        assertEquals(2, rfc3164.entries.size)
        assertEquals("failed\n  caused by lower layer", rfc3164.entries[0].msg)
        assertEquals("recovered", rfc3164.entries[1].msg)

        val dmesg = parse(
            "[  10.250] kernel failure\n    stack continuation\n[  10.500] device recovered\n",
            builtin("dmesg-boot-time"),
        )
        assertEquals(2, dmesg.entries.size)
        assertEquals("kernel failure\n    stack continuation", dmesg.entries[0].msg)
        assertEquals("device recovered", dmesg.entries[1].msg)

        val dltAscii = parse(
            "2026/01/02 03:04:05.123456 12.3456 7 ECU1 APP1 CTX1 42 log error verbose 1 failure\n" +
                "    frame continuation\n" +
                "2026/01/02 03:04:06.123456 13.3456 8 ECU1 APP1 CTX1 42 log info verbose 1 recovered\n",
            builtin("automotive-dlt"),
        )
        assertEquals(3, dltAscii.entries.size)
        assertEquals("failure", dltAscii.entries[0].msg)
        assertEquals("RAW", dltAscii.entries[1].tag)
        assertEquals("recovered", dltAscii.entries[2].msg)

        val http = parse(
            "127.0.0.1 - - [16/Nov/2024:10:00:00 +0000] \"GET / HTTP/1.1\" 200 42 \"-\" \"agent start\n" +
                "agent continuation\"\n",
            builtin("http-common-combined"),
        )
        assertEquals(2, http.entries.size)
        assertTrue(http.entries.all { !it.severityKnown }, "access log rows remain available as raw text when a line is malformed")
    }

    @Test
    fun logfmtEscapeIsOneMessageWhileARealPhysicalNewlineEndsTheRecord() {
        val logfmt = parse(
            "time=2024-01-01T00:00:00Z level=INFO msg=\"first\\nsecond\"\n" +
                "time=2024-01-01T00:00:01Z level=WARN msg=next\n",
            builtin("logfmt"),
        )
        assertEquals(2, logfmt.entries.size)
        assertEquals("first\nsecond", logfmt.entries[0].msg)
        assertEquals("next", logfmt.entries[1].msg)

        val literalNewline = parse(
            "time=2024-01-01T00:00:00Z level=INFO msg=\"first\nsecond\"",
            builtin("logfmt"),
        )
        assertEquals(2, literalNewline.entries.size)
        assertEquals("time=2024-01-01T00:00:00Z level=INFO msg=\"first", literalNewline.entries[0].msg)
        assertEquals("second\"", literalNewline.entries[1].msg)
        assertTrue(literalNewline.diagnostics.any { "malformed logfmt" in it })
    }

    @Test
    fun binaryDltNewlinesStayInsideTheirFramePayload() {
        val firstMessage = (1..20).joinToString("\n") { "payload line $it" }
        val bytes = dltTestFrame(firstMessage) + dltTestFrame("following event")
        val parsed = parseLogContent(ByteArrayInputStream(bytes))

        assertEquals(2, parsed.entries.size)
        assertEquals(firstMessage, parsed.entries[0].msg)
        assertEquals("following event", parsed.entries[1].msg)
    }

    @Test
    fun dltViewerCsvKeepsTwentyQuotedPayloadLinesAsOneExportRow() {
        val payload = (1..20).joinToString("\n") { "payload line $it" }
        val csv = "Time,ECU,AppId,ContextId,Type,Payload\n" +
            "2026-01-02 03:04:05,ECU1,APP1,CTX1,ERROR,\"$payload\"\n" +
            "2026-01-02 03:04:06,ECU1,APP1,CTX1,INFO,following event\n"
        val parsed = parseLogContent(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(2, parsed.entries.size)
        assertEquals(payload, parsed.entries[0].msg)
        assertEquals("following event", parsed.entries[1].msg)
    }
}
