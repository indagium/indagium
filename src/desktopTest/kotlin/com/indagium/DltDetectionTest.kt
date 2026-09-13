package com.indagium

import com.indagium.utils.CONTENT_SNIFF_BYTES
import com.indagium.utils.LogContentKind
import com.indagium.utils.classifyLogContent
import com.indagium.utils.isDltViewerAsciiLine
import com.indagium.utils.looksLikeDltCsvHeaderLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Direct unit tests for the shared classifier (utils/DltDetection.kt) that parseLogContent,
// sniffCandidateContent/isLikelyDltSourceFile, and candidateKindFromContent all route through.
class DltDetectionTest {
    private fun classify(sample: ByteArray, atEof: Boolean = true, fileName: String? = null): LogContentKind =
        classifyLogContent(sample, atEof, fileName)

    // ── Storage-header magic (unconditional, wins over everything else) ──────────

    @Test
    fun storageMagicIsRecognizedRegardlessOfFilenameOrTrailingContent() {
        val v1 = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 1) + ByteArray(12) { 0x20 }
        val v2 = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 2) + ByteArray(12) { 0x20 }

        assertEquals(LogContentKind.DLT_STORAGE, classify(v1))
        assertEquals(LogContentKind.DLT_UNSUPPORTED_V2, classify(v2))
        // No filename needed — unlike the headerless-v2 rule below, storage magic is unambiguous.
        assertEquals(LogContentKind.DLT_UNSUPPORTED_V2, classify(v2, fileName = "notes.txt"))
    }

    // ── Text routing: DLT Viewer CSV / ASCII export / plain TEXT ─────────────────

    @Test
    fun csvHeaderWithTheExistingTestAliasesIsRecognized() {
        assertTrue(looksLikeDltCsvHeaderLine("Time,ECU,AppId,ContextId,Type,Payload"))
        val sample = "Time,ECU,AppId,ContextId,Type,Payload\n2026-01-02 03:04:05,ECU1,APP1,CTX1,ERROR,hi\n".toByteArray()
        assertEquals(LogContentKind.DLT_VIEWER_CSV, classify(sample))
    }

    @Test
    fun dltViewerDefaultHeaderIsRecognizedQuotedWithEitherDelimiter() {
        val commaHeader =
            """"Index","Time","Timestamp","Count","Ecuid","Apid","Ctid","SessionId","Type","Subtype","Mode","#Args","Payload""""
        val semicolonHeader = commaHeader.replace(',', ';')

        assertTrue(looksLikeDltCsvHeaderLine(commaHeader))
        assertTrue(looksLikeDltCsvHeaderLine(semicolonHeader))
        assertEquals(LogContentKind.DLT_VIEWER_CSV, classify((commaHeader + "\n").toByteArray()))
        assertEquals(LogContentKind.DLT_VIEWER_CSV, classify((semicolonHeader + "\n").toByteArray()))
    }

    @Test
    fun csvHeaderWithApidDescAndCtidDescAliasesIsRecognized() {
        val header = "Index,Time,Timestamp,Count,Ecuid,\"Apid Desc\",\"Ctid Desc\",SessionId,Type,Subtype,Mode,#Args,Payload"
        assertTrue(looksLikeDltCsvHeaderLine(header))
        val sample = (header + "\n1,2026/01/02 03:04:05.123456,12.3456,7,ECU1,APP1,CTX1,42,log,info,verbose,0,hi\n").toByteArray()
        assertEquals(LogContentKind.DLT_VIEWER_CSV, classify(sample))
    }

    @Test
    fun csvHeaderWithTabDelimiterIsRecognized() {
        val header = "Time\tEcuid\tApid\tCtid\tType\tPayload"
        assertTrue(looksLikeDltCsvHeaderLine(header))
        val sample = (header + "\n2026/01/02 03:04:05.123456\tECU1\tAPP1\tCTX1\tlog\thi\n").toByteArray()
        assertEquals(LogContentKind.DLT_VIEWER_CSV, classify(sample))
    }

    @Test
    fun csvLikeFirstLineThatIsNotADltHeaderStaysText() {
        // Regression for the "Executing application in context" style false positive: the word
        // "context" alone must not resolve the context-id column.
        val sample = "Executing application in context\nsecond line\n".toByteArray()
        assertFalse(looksLikeDltCsvHeaderLine("Executing application in context"))
        assertEquals(LogContentKind.TEXT, classify(sample))
    }

    @Test
    fun strictAsciiViewerLineMatcherRequiresAllAnchorFields() {
        val line = "2026/01/02 03:04:05.123456 12.3456 7 ECU1 APP1 CTX1 42 log info verbose 1 hello world"
        assertTrue(isDltViewerAsciiLine(line))
        val withIndex = "9 $line"
        assertTrue(isDltViewerAsciiLine(withIndex))

        assertFalse(isDltViewerAsciiLine("01-02 03:04:05.123  1  2 I Tag: not a viewer line"))
        assertFalse(isDltViewerAsciiLine("[12:00:00.123] [main] [INFO] started"))
    }

    @Test
    fun asciiViewerExportIsRecognizedFromTwoOfTheFirstFiveLines() {
        val good = "2026/01/02 03:04:05.123456 12.3456 7 ECU1 APP1 CTX1 42 log info verbose 1 hello"
        val sample = (List(2) { good } + listOf("not a viewer line at all")).joinToString("\n").toByteArray()
        assertEquals(LogContentKind.DLT_VIEWER_TEXT, classify(sample))
    }

    @Test
    fun aSingleLineSampleNeedsThatOneLineToMatchTheAsciiLayout() {
        val good = "2026/01/02 03:04:05.123456 12.3456 7 ECU1 APP1 CTX1 42 log info verbose 1 hello"
        assertEquals(LogContentKind.DLT_VIEWER_TEXT, classify(good.toByteArray()))
        assertEquals(LogContentKind.TEXT, classify("just one ordinary line".toByteArray()))
    }

    @Test
    fun bracketedThreadStyleLogsAreNotMisdetectedAsDltViewerText() {
        val sample = "[12:00:00.123] [main] [INFO] started\n[12:00:00.456] [main] [INFO] running\n".toByteArray()
        assertEquals(LogContentKind.TEXT, classify(sample))
    }

    @Test
    fun ordinaryLogcatTextIsNeverRoutedToDltViewer() {
        val sample = buildString { repeat(50) { append("01-02 03:04:05.123  1  2 I Tag: row$it\n") } }.toByteArray()
        assertEquals(LogContentKind.TEXT, classify(sample, atEof = false))
    }

    // ── Raw v1 chained-frame validation (no storage header) ──────────────────────

    private fun rawFrame(weid: Boolean = false, ueh: Boolean = false, mstp: Int = 0, payloadSize: Int = 0): ByteArray {
        var htyp = 0x20 // version 1, bits 5..7 = 001
        val extras = mutableListOf<Byte>()
        if (weid) {
            htyp = htyp or 0x04
            extras += "ECU1".toByteArray().toList()
        }
        val extendedHeader = if (ueh) {
            htyp = htyp or 0x01
            val msin = (mstp shl 1).toByte()
            listOf(msin, 1.toByte()) + "APP1".toByteArray().toList() + "CTX1".toByteArray().toList()
        } else {
            emptyList()
        }
        val body = extras + extendedHeader + List(payloadSize) { 0x41.toByte() }
        val length = 4 + body.size
        return byteArrayOf(htyp.toByte(), 0, (length ushr 8).toByte(), length.toByte()) + body.toByteArray()
    }

    @Test
    fun threeChainedValidFramesAreAcceptedEvenWhenNotAtEof() {
        val sample = rawFrame(payloadSize = 4) + rawFrame(weid = true, payloadSize = 2) + rawFrame(ueh = true, payloadSize = 1)
        assertEquals(LogContentKind.DLT_RAW, classify(sample, atEof = false))
    }

    @Test
    fun aSingleValidFrameIsAcceptedOnlyWhenTheSampleIsTheWholeStream() {
        val sample = rawFrame(payloadSize = 4)
        assertEquals(LogContentKind.DLT_RAW, classify(sample, atEof = true))
        // Same bytes, but the caller says more data follows — one frame alone isn't enough
        // evidence to call it DLT rather than some other format that happens to start this way.
        assertEquals(LogContentKind.OTHER, classify(sample, atEof = false))
    }

    @Test
    fun twoValidFramesFollowedByGarbageAreNotAcceptedWithoutAtEof() {
        val sample = rawFrame(payloadSize = 4) + rawFrame(payloadSize = 2) + byteArrayOf(0x00, 0x00, 0x00, 0x00)
        assertEquals(LogContentKind.OTHER, classify(sample, atEof = false))
        assertEquals(LogContentKind.OTHER, classify(sample, atEof = true)) // walk doesn't end at sample end either
    }

    @Test
    fun extendedHeaderWithMstpAboveThreeBreaksTheChain() {
        val invalid = rawFrame(ueh = true, mstp = 5, payloadSize = 0)
        assertEquals(LogContentKind.OTHER, classify(invalid, atEof = true))
    }

    @Test
    fun extendedHeaderWithNonPrintableApidBreaksTheChain() {
        var htyp = 0x21 // version 1 + UEH
        val extendedHeader = byteArrayOf(0, 1, 0x01, 0x02, 0x03, 0x04, 'C'.code.toByte(), 'T'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte())
        val length = 4 + extendedHeader.size
        val sample = byteArrayOf(htyp.toByte(), 0, (length ushr 8).toByte(), length.toByte()) + extendedHeader
        assertEquals(LogContentKind.OTHER, classify(sample, atEof = true))
    }

    @Test
    fun nonV1VersionBitsNeverEnterTheRawChain() {
        val garbage = byteArrayOf(0, 1, 2, 3, 0x89.toByte(), 'H'.code.toByte(), 'E'.code.toByte(), 'A'.code.toByte())
        assertEquals(LogContentKind.OTHER, classify(garbage, atEof = true))
    }

    @Test
    fun commonBinarySignaturesAreNotDltEvenUnderDltEligibleNames() {
        // Name-gating lets .bin/.log entries reach the sniff, so the content rules alone must reject
        // these: zip/apk ("PK", v2 bits), SQLite ("S", v2 bits), protobuf (0x22, v1 bits, LEN 0).
        val samples = listOf(
            byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x08, 0x00) + ByteArray(64),
            "SQLite format 3".toByteArray() + ByteArray(85),
            byteArrayOf(0x22, 0x10, 0x00, 0x00, 0x2A, 0x05) + ByteArray(64) { 0x11 },
        )
        for (sample in samples) {
            for (name in listOf("capture.bin", "events.log")) {
                assertEquals(LogContentKind.OTHER, classify(sample, atEof = true, fileName = name), name)
            }
        }
    }

    // ── Headerless v2 frame: name-gated to .dlt ───────────────────────────────────

    @Test
    fun headerlessV2FrameIsGatedByDltFilenameExtension() {
        val v2ish = byteArrayOf(0x41, 0, 0, 4) // version bits = 2, no storage magic
        assertEquals(LogContentKind.OTHER, classify(v2ish, fileName = null))
        assertEquals(LogContentKind.OTHER, classify(v2ish, fileName = "capture.bin"))
        assertEquals(LogContentKind.DLT_UNSUPPORTED_V2, classify(v2ish, fileName = "capture.dlt"))
        assertEquals(LogContentKind.DLT_UNSUPPORTED_V2, classify(v2ish, fileName = "nested/path/capture.DLT"))
    }

    @Test
    fun sampleSizeNeverExceedsTheSharedSniffBudgetConstant() {
        assertEquals(8 * 1024, CONTENT_SNIFF_BYTES)
    }
}
