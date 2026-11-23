@file:Suppress("MagicNumber")

package com.indagium

import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.utils.parseLogContent
import com.indagium.utils.parseLogFormatPreview
import com.indagium.utils.parseMetadataJson
import java.io.ByteArrayInputStream
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the editor's preview shows of a bounded sample: it must agree with what open and Reparse read of the same bytes. */
class LogFormatPreviewSamplingTest {
    private fun builtin(id: String) = BUILTIN_LOG_FORMAT_PROFILES.single { it.id == id }

    private val regexProfile = LogFormatProfile(
        id = "round9-regex", name = "Round 9 regex", description = "One row per line",
        engine = LogFormatEngine.REGEX,
        regexPattern = "^(row \\d+)$",
        fieldMappings = mapOf(LogFieldRole.MESSAGE to "1"),
    )
    private val csvProfile = LogFormatProfile(
        id = "round9-csv", name = "Round 9 CSV", description = "One row per line",
        engine = LogFormatEngine.CSV, hasHeader = false,
        fieldMappings = mapOf(LogFieldRole.MESSAGE to "0"),
    )

    private fun rows(count: Int, separator: String, width: Int = 8) =
        (1..count).joinToString("") { "row ${it.toString().padStart(width, '0')}$separator" }

    private fun assertPreviewIsThePrefixOfTheWholeParse(profile: LogFormatProfile, text: String, expectedRows: Int) {
        val whole = parseLogContent(ByteArrayInputStream(text.toByteArray()), profile = profile)
        val preview = parseLogFormatPreview(ByteArrayInputStream(text.toByteArray()), profile, maxBytes = 1024)
        assertTrue(preview.entries.isNotEmpty(), "${profile.name}: the preview of a CR-only source must show rows")
        assertEquals(expectedRows, preview.entries.size, "${profile.name}: every line the sample completes")
        assertEquals(whole.entries.take(expectedRows).map { it.msg }, preview.entries.map { it.msg })
        assertTrue(preview.diagnostics.any { "Preview is limited" in it })
    }

    @Test
    fun aCrOnlySourceLargerThanThePreviewLimitStillPreviewsItsCompleteLines() {
        val text = rows(300, "\r") // 14 bytes a row; the 1024-byte sample ends inside row 74
        val expected = text.take(1024).count { it == '\r' }
        assertTrue(expected in 1 until 300)
        assertPreviewIsThePrefixOfTheWholeParse(builtin("plain-text"), text, expected)
        assertPreviewIsThePrefixOfTheWholeParse(regexProfile, text, expected)
        assertPreviewIsThePrefixOfTheWholeParse(csvProfile, text, expected)
    }

    @Test
    fun aCrlfSampleCutBetweenTheCrAndTheLfKeepsTheLineTheCrEnds() {
        // 25-byte rows: the 1024-byte sample ends exactly after the CR of row 41, whose LF is the first byte outside it.
        val text = rows(100, "\r\n", width = 19)
        assertEquals('\r', text[1023])
        assertEquals('\n', text[1024])
        assertPreviewIsThePrefixOfTheWholeParse(builtin("plain-text"), text, expectedRows = 41)
        assertPreviewIsThePrefixOfTheWholeParse(regexProfile, text, expectedRows = 41)
    }

    @Test
    fun lfAndCrlfSamplesStillStopAtTheLastCompleteLine() {
        val lf = rows(300, "\n")
        val cut = lf.take(1024)
        assertPreviewIsThePrefixOfTheWholeParse(builtin("plain-text"), lf, cut.count { it == '\n' })
        val crlf = rows(300, "\r\n")
        // The sample ends inside a row (after its LF or in its text): only rows whose LF is inside count.
        assertPreviewIsThePrefixOfTheWholeParse(builtin("plain-text"), crlf, crlf.take(1024).count { it == '\n' })
    }

    // ---- compact metadata JSON ----

    private fun packet(index: Int, pad: String = "p".repeat(90)) =
        """{"metadata":{"identity":"HCI.P$index","source":"Demo","system_time":${BASE + index}},""" +
            """"Summary":{"Note":"packet $index","Pad":"$pad","Flag":true,"None":null,"Ratio":12.5}}"""

    private fun compactDocument(count: Int) = """{"Root":{"HCI":[${(1..count).joinToString(",") { packet(it) }}]}}"""

    private fun completePacketsWithin(chars: Int, count: Int): Int {
        var end = """{"Root":{"HCI":[""".length
        var complete = 0
        for (index in 1..count) {
            end += packet(index).length
            if (end <= chars) complete++ else break
            end += 1 // the comma
        }
        return complete
    }

    @Test
    fun aCompactMetadataJsonDocumentPreviewsTheRecordsThatClosedInsideTheSample() {
        val document = compactDocument(60)
        val maxBytes = 2048
        assertTrue(document.length > maxBytes && '\n' !in document)
        val expected = completePacketsWithin(maxBytes, 60)
        assertTrue(expected in 5..15, "about ten packets fit, was $expected")

        val preview = parseLogFormatPreview(document.byteInputStream(), builtin("metadata-json"), maxBytes = maxBytes)

        assertEquals((1..expected).map { "packet $it" }, preview.entries.map { it.msg }, "complete records only, no cut one")
        assertFalse(preview.diagnostics.any { "damaged or truncated" in it }, preview.diagnostics.toString())
        assertTrue(preview.diagnostics.any { "Preview is limited" in it })
    }

    @Test
    fun aPrettyPrintedMetadataJsonDocumentStillPreviewsItsCompleteRecords() {
        val document = "{\n  \"Root\": {\n    \"HCI\": [\n" + (1..60).joinToString(",\n") { "      " + packet(it) } + "\n    ]\n  }\n}\n"
        val maxBytes = 2048
        val preview = parseLogFormatPreview(document.byteInputStream(), builtin("metadata-json"), maxBytes = maxBytes)

        val shown = preview.entries.map { it.msg }
        assertTrue(shown.size in 5..15, "was ${shown.size}")
        assertEquals((1..shown.size).map { "packet $it" }, shown)
        assertTrue(preview.diagnostics.any { "Preview is limited" in it })

        val whole = parseLogFormatPreview(document.byteInputStream(), builtin("metadata-json"), maxBytes = 1024 * 1024)
        assertEquals(60, whole.entries.size, "a sample that holds the whole document is not truncated")
        assertFalse(whole.diagnostics.any { "Preview is limited" in it || "damaged or truncated" in it })
    }

    @Test
    fun aMetadataJsonSampleCutAnywhereReportsNoDamageAndKeepsNoPartialRecord() {
        val document = compactDocument(3)
        var checked = 0
        for (length in 1 until document.length) {
            val prefix = document.substring(0, length)
            val parsed = parseMetadataJson(StringReader(prefix), truncatedAtChar = length.toLong())
            val complete = completePacketsWithin(length, 3)
            assertEquals(complete, parsed.entries.size, "cut at $length")
            assertFalse(
                parsed.diagnostics.any { "damaged" in it || "Incomplete" in it },
                "cut at $length: ${parsed.diagnostics}",
            )
            checked++
        }
        assertTrue(checked > 300)
        // Without the cut being declared the same prefix is reported as damaged.
        assertTrue(parseMetadataJson(StringReader(document.take(300))).diagnostics.any { "damaged or truncated" in it })
    }

    @Test
    fun aMetadataJsonSampleWithRealDamageBeforeItsEndStillReportsIt() {
        val broken = compactDocument(3).replaceFirst("\"Root\"", "Root") + " ".repeat(10)
        val parsed = parseMetadataJson(StringReader(broken), truncatedAtChar = broken.length.toLong())
        assertTrue(parsed.diagnostics.any { "damaged or truncated" in it })
    }

    // ---- binary DLT ----

    private fun dltMessage(text: String) = dltStorageHeader() + dltTestFrame(text) // every storage message carries its own header

    private fun dltBytes() = dltMessage("first") + dltMessage("second")

    @Test
    fun aBinaryDltSamplePreviewsAsDltWhateverProfileWasRequested() {
        val viaDlt = parseLogFormatPreview(ByteArrayInputStream(dltBytes()), builtin("automotive-dlt"))
        assertEquals(listOf("first", "second"), viaDlt.entries.map { it.msg })

        for (id in listOf("json-lines", "plain-text", "metadata-json", "android-logcat")) {
            val preview = parseLogFormatPreview(ByteArrayInputStream(dltBytes()), builtin(id), fileName = "capture.dlt")
            assertEquals(viaDlt.entries.map { it.msg }, preview.entries.map { it.msg }, id)
            assertFalse(preview.diagnostics.any { "invalid" in it }, "$id: ${preview.diagnostics}")
            if (id != "android-logcat") assertTrue(preview.diagnostics.any { "binary DLT" in it }, "$id: ${preview.diagnostics}")
        }
        // Open and Reparse read the same bytes the same way.
        val reparsed = parseLogContent(ByteArrayInputStream(dltBytes()), profile = builtin("json-lines"))
        assertEquals(viaDlt.entries.map { it.msg }, reparsed.entries.map { it.msg })
    }

    @Test
    fun aTruncatedBinaryDltSamplePreviewsItsCompleteFrames() {
        val frames = (1..200).map { dltMessage("message $it") }.reduce(ByteArray::plus)
        val preview = parseLogFormatPreview(ByteArrayInputStream(frames), builtin("json-lines"), maxBytes = 1024)
        assertTrue(preview.entries.size in 2..199, "was ${preview.entries.size}")
        assertEquals("message 1", preview.entries.first().msg)
        assertTrue(preview.diagnostics.any { "Preview is limited" in it })
    }

    @Test
    fun aDltViewerTextExportStillPreviewsWithTheRequestedTextProfile() {
        val viewerCsv = "Time,ECU,AppId,ContextId,Type,Payload\n2026-01-02 03:04:05,ECU1,APP1,CTX1,ERROR,plain-selected\n"
        val preview = parseLogFormatPreview(ByteArrayInputStream(viewerCsv.toByteArray()), builtin("plain-text"))
        assertEquals("Time,ECU,AppId,ContextId,Type,Payload", preview.entries.first().msg)
        assertFalse(preview.diagnostics.any { "binary DLT" in it })
    }

    private companion object {
        const val BASE = 1_790_000_000_000_000_000L
    }
}
