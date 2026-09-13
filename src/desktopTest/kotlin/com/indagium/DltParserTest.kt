package com.indagium

import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import com.indagium.utils.parseDltContent
import com.indagium.utils.parseLogContent
import com.indagium.utils.parseLogFileResult
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DltParserTest {
    private companion object {
        const val DLT_V1_EXTENDED_HEADER = 0x21
        const val DLT_HTYP_MSBF = 0x02
        const val DLT_TYPE_STRING = 0x200
        const val STORAGE_MICROSECONDS_LOW_BYTE = 0x78
    }

    @Test fun parsesStorageAndRawV1FramesWithBigEndianHeaders() {
        listOf(true, false).forEach { msbf ->
            val payload = verboseString(if (msbf) "big" else "little", msbf)
            val storage = storageHeader("ECU1") + frame(msbf = msbf, ecu = "ECU1", payload = payload)
            val entry = parseLogContent(ByteArrayInputStream(storage)).entries.single()
            assertEquals(if (msbf) "big" else "little", entry.msg)
            assertEquals(LogLevel.I, entry.level)
            assertEquals("ECU1", entry.dltEcuId)
            assertEquals("storage", entry.dltTimestampSource)
            assertTrue(entry.ts.matches(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")))

            val raw = parseDltContent(ByteArrayInputStream(frame(msbf = msbf, payload = payload)))
            assertEquals(LogFormat.DLT, raw.format)
            assertEquals(if (msbf) "big" else "little", raw.entries.single().msg)
        }
    }

    @Test fun parsesEveryOptionalStandardHeaderCombinationInSpecifiedOrder() {
        for (mask in 0..7) {
            val ecu = "ECU2".takeIf { mask and 1 != 0 }
            val session = 7.takeIf { mask and 2 != 0 }
            val timestamp = 1234L.takeIf { mask and 4 != 0 }
            val raw = frame(
                msbf = false,
                ecu = ecu,
                session = session,
                timestamp = timestamp,
                payload = verboseString("optional-$mask", false),
            )
            val entry = parseDltContent(ByteArrayInputStream(raw)).entries.single()
            assertEquals(ecu, entry.dltEcuId, "mask=$mask")
            assertEquals(timestamp, entry.dltTimestamp, "mask=$mask")
            assertEquals(if (timestamp == null) null else "relative", entry.dltTimestampSource, "mask=$mask")
            assertEquals("optional-$mask", entry.msg, "mask=$mask")
        }
    }

    @Test fun parsesMultipleVerboseScalarsAndAttributes() {
        val payload = verboseString("hello", true) + typed(0x11, u8(1)) + typed(0x21, u8(0xFE)) +
            typed(0x43, u32(42, true)) + typed(0x42, u16(65530, true)) +
            typed(0x83, u32(1.5f.toRawBits().toLong(), true)) +
            typed(0x84, u64(2.25.toRawBits(), true)) + typed(0x24, u64(-3L, true)) +
            typed(0x44, u64(-1L, true)) +
            typed(0x841, attr("rpm", "1/min", true) + u8(9)) +
            typed(0x400, lengthPrefixed(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), true)) +
            typed(0x2000, lengthPrefixed(byteArrayOf(0x01, 0x02), true))
        val entry = parseDltContent(ByteArrayInputStream(frame(msbf = true, noar = 12, payload = payload))).entries.single()
        assertEquals("hello true -2 42 65530 1.5 2.25 -3 18446744073709551615 rpm=9 [1/min] 0xABCD 0x0102", entry.msg)
    }

    @Test fun rendersUnsupportedArraysAndStructuresAsDeterministicHexAndStopsAtTheFirstOne() {
        // A V1 array has U16 dimensions followed by one U16 entry count per dimension, then
        // its values. The parser intentionally leaves its type-specific representation opaque.
        val array = typed(0x141, u16(1, true) + u16(2, true) + byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        val finalArray = parseDltContent(ByteArrayInputStream(frame(noar = 1, payload = array))).entries.single()
        assertEquals("0x00010002AABB", finalArray.msg)

        // A non-final ARAY/STRU can't self-delimit its length, so the parser renders the rest of
        // the payload (including whatever followed it) as one opaque hex blob and stops — it never
        // throws, and any argument count claimed beyond this one is simply left undecoded.
        val following = typed(0x41, u8(7))
        val nonFinalArray = parseDltContent(ByteArrayInputStream(frame(noar = 2, payload = array + following))).entries.single()
        assertEquals("0x00010002AABB0000004107", nonFinalArray.msg)

        val structure = typed(0x4001, byteArrayOf(0x00, 0x02, 0xAA.toByte(), 0xBB.toByte()))
        val structureEntry = parseDltContent(ByteArrayInputStream(frame(noar = 1, payload = structure))).entries.single()
        assertEquals("0x0002AABB", structureEntry.msg)
        val nonFinalStructure = parseDltContent(ByteArrayInputStream(frame(noar = 2, payload = structure + following))).entries.single()
        assertEquals("0x0002AABB0000004107", nonFinalStructure.msg)
    }

    @Test fun mapsMstpAndMtinIndependently() {
        val warning = parseDltContent(ByteArrayInputStream(frame(msin = 0x31, payload = verboseString("warn", true)))).entries.single()
        assertEquals(LogLevel.W, warning.level); assertEquals("log", warning.dltMessageType)
        val trace = parseDltContent(ByteArrayInputStream(frame(msin = 0x35, payload = verboseString("trace", true)))).entries.single()
        assertEquals(LogLevel.I, trace.level); assertEquals("network_trace", trace.dltMessageType)
        val types = listOf(0x01 to "log", 0x03 to "app_trace", 0x05 to "network_trace", 0x07 to "control")
        types.forEach { (msin, type) ->
            val entry = parseDltContent(ByteArrayInputStream(frame(msin = msin, payload = verboseString(type, true)))).entries.single()
            assertEquals(type, entry.dltMessageType)
            assertEquals(LogLevel.I, entry.level)
        }
    }

    @Test fun preservesNonVerboseMessageIdsAndUsesDeterministicHex() {
        val payload = u32(0x10203040, false) + byteArrayOf(0xFF.toByte(), 0x80.toByte())
        val entry = parseDltContent(ByteArrayInputStream(frame(msbf = false, msin = 0x40, noar = 0, payload = payload))).entries.single()
        assertEquals("messageId=0x10203040 0xFF80", entry.msg)
        val printable = parseDltContent(ByteArrayInputStream(
            frame(msbf = true, msin = 0x40, noar = 0, payload = u32(0xA0B0C0D0, true) + "text".toByteArray()),
        )).entries.single()
        assertEquals("messageId=0xA0B0C0D0 text", printable.msg)
    }

    @Test fun parsesMultipleFramesWithMonotonicIds() {
        val bytes = frame(payload = verboseString("first", true)) + frame(payload = verboseString("second", true))
        val result = parseDltContent(ByteArrayInputStream(bytes), startId = 7)
        assertEquals(listOf(7, 8), result.entries.map { it.id })
        assertEquals(listOf("first", "second"), result.entries.map { it.msg })
    }

    @Test fun rejectsZeroFrameStreamsAndV2WhileTruncationElsewhereIsTolerant() {
        // These all fail to produce even one complete frame, so the "zero frames decoded" rule
        // still throws (a non-DLT/all-garbage stream must remain an error, not a silent empty tab).
        assertFailsWith<IllegalArgumentException> { parseDltContent(ByteArrayInputStream(frame(payload = verboseString("x", true)).copyOf(8))) }
        assertFailsWith<IllegalArgumentException> { parseDltContent(ByteArrayInputStream(byteArrayOf(0x21, 0, 0, 3))) }
        val truncatedStorage = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 1, 0, 0)
        assertFailsWith<IllegalArgumentException> { parseLogContent(ByteArrayInputStream(truncatedStorage)) }
        assertFailsWith<IllegalArgumentException> { parseDltContent(ByteArrayInputStream(byteArrayOf(0x41, 0, 0, 4))) }

        // A headerless (no "DLT" storage magic) v2-looking frame is deliberately name-gated to
        // ".dlt" by the shared classifier (DltDetection.kt) — otherwise ordinary archive binaries
        // whose first byte happens to carry v2's version bits would misclassify as DLT. Pass a
        // ".dlt" filename here to exercise that gated path; see DltDetectionTest for the ungated
        // (no filename) case, which now falls through to the text fallback instead of throwing.
        val v2 = byteArrayOf(0x41, 0, 0, 4)
        val error = assertFailsWith<IllegalArgumentException> {
            parseLogContent(ByteArrayInputStream(v2), fileName = "capture.dlt")
        }
        assertTrue(error.message.orEmpty().contains("v2 is not supported"))
        val storageV2 = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 2) + "text header".toByteArray()
        val storageError = assertFailsWith<IllegalArgumentException> { parseLogContent(ByteArrayInputStream(storageV2)) }
        assertTrue(storageError.message.orEmpty().contains("v2 is not supported"))
        val directStorageError = assertFailsWith<IllegalArgumentException> { parseDltContent(ByteArrayInputStream(storageV2)) }
        assertTrue(directStorageError.message.orEmpty().contains("v2 is not supported"))
    }

    @Test fun rendersUndecodablePayloadTailInsteadOfThrowingOnAMalformedArgument() {
        // A STRG argument claims 5 bytes of string data but only 1 is actually present — payload
        // decode must never throw; it renders whatever was decoded so far (nothing, here) plus the
        // remaining raw bytes (type info + length + the 1 stray byte) as an undecodable hex tail.
        val malformed = typed(0x200, u16(5, true) + byteArrayOf('x'.code.toByte()))
        val entry = parseDltContent(ByteArrayInputStream(frame(noar = 1, payload = malformed))).entries.single()
        assertTrue(entry.msg.startsWith("[undecodable payload 0x"), entry.msg)
    }

    @Test fun tolerantlyResyncsPastTrailingGarbageAfterTheLastValidStorageRecord() {
        val bytes = storageHeader("ECU1") + frame(payload = verboseString("ok", true)) + ByteArray(16)
        val result = parseLogContent(ByteArrayInputStream(bytes))
        assertEquals(2, result.entries.size)
        assertEquals("ok", result.entries[0].msg)
        assertEquals(LogLevel.W, result.entries[1].level)
        assertEquals("RAW", result.entries[1].tag)
        assertTrue(result.entries[1].msg.contains("skipped 16 bytes"), result.entries[1].msg)
    }

    @Test fun tolerantlyMarksATruncatedFinalRawFrameWithoutDroppingEarlierRows() {
        val good = frame(payload = verboseString("first", true))
        val bad = frame(payload = verboseString("second-will-be-cut", true))
        val bytes = good + bad.copyOf(bad.size - 3)
        val result = parseDltContent(ByteArrayInputStream(bytes))
        assertEquals(2, result.entries.size)
        assertEquals("first", result.entries[0].msg)
        assertEquals(LogLevel.W, result.entries[1].level)
        assertEquals("RAW", result.entries[1].tag)
        assertTrue(result.entries[1].msg.contains("truncated frame"), result.entries[1].msg)
    }

    @Test fun tolerantlyResyncsPastGarbageBetweenTwoStorageRecordsAndReportsTheSkipCount() {
        val garbage = "GARBAGE!".toByteArray() // 8 bytes, no accidental "DLT" prefix inside
        val bytes = storageHeader("ECU1") + frame(payload = verboseString("rec1", true)) +
            garbage + storageHeader("ECU1") + frame(payload = verboseString("rec2", true))
        // parseDltContent on purpose: its old prefix check misread the storage magic's 'D' as v2.
        val result = parseDltContent(ByteArrayInputStream(bytes))
        assertEquals(3, result.entries.size)
        assertEquals("rec1", result.entries[0].msg)
        assertEquals(LogLevel.W, result.entries[1].level)
        assertEquals("RAW", result.entries[1].tag)
        assertTrue(result.entries[1].msg.contains("skipped 8 bytes"), result.entries[1].msg)
        assertEquals("rec2", result.entries[2].msg)
    }

    @Test fun mapsSessionIdToPid() {
        val entry = parseDltContent(ByteArrayInputStream(frame(session = 55, payload = verboseString("session", true)))).entries.single()
        assertEquals(55, entry.pid)
        assertEquals(0, entry.tid)
    }

    @Test fun formatsRelativeTimestampFromTmspAsWallClockTimeOfDayWrappingAt24Hours() {
        // TMSP is 0.1ms units. 37_230_040 units => 3_723_004ms => 01:02:03.004.
        val entry = parseDltContent(ByteArrayInputStream(frame(timestamp = 37_230_040L, payload = verboseString("rel", true)))).entries.single()
        assertEquals("01:02:03.004", entry.ts)
        assertEquals("relative", entry.dltTimestampSource)

        // 900_000_000 units => 90_000_000ms => 25h == 01:00:00.000 after wrapping mod 24.
        val wrapped = parseDltContent(ByteArrayInputStream(frame(timestamp = 900_000_000L, payload = verboseString("rel", true)))).entries.single()
        assertEquals("01:00:00.000", wrapped.ts)
    }

    @Test fun rendersVariAttributesForBoolStringRawAndFixedPointNumbers() {
        val boolPayload = typed(0x810, attrName("flag", true) + u8(1))
        val boolEntry = parseDltContent(ByteArrayInputStream(frame(noar = 1, payload = boolPayload))).entries.single()
        assertEquals("flag=true", boolEntry.msg)

        val stringData = ("hello" + '\u0000').toByteArray()
        val stringPayload = typed(0xA00, u16(stringData.size, true) + u16("path".toByteArray().size, true) + "path".toByteArray() + stringData)
        val stringEntry = parseDltContent(ByteArrayInputStream(frame(noar = 1, payload = stringPayload))).entries.single()
        assertEquals("path=hello", stringEntry.msg)

        val rawBytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte())
        val rawPayload = typed(0xC00, u16(rawBytes.size, true) + u16("blob".toByteArray().size, true) + "blob".toByteArray() + rawBytes)
        val rawEntry = parseDltContent(ByteArrayInputStream(frame(noar = 1, payload = rawPayload))).entries.single()
        assertEquals("blob=0xDEAD", rawEntry.msg)

        // UINT with VARI, no FIXP — exercises the real nameLen/unitLen/name/unit ordering.
        val uintPayload = typed(0x841, attr("rpm", "1/min", true) + u8(9))
        val uintEntry = parseDltContent(ByteArrayInputStream(frame(noar = 1, payload = uintPayload))).entries.single()
        assertEquals("rpm=9 [1/min]", uintEntry.msg)

        // UINT with FIXP (no VARI): quantization=0.5 (exact in binary), offset=5, raw=20 ->
        // physical = 20*0.5+5 = 15.0 exactly (avoids float-precision drift in the assertion).
        val fixpPayload = typed(0x1042, f32(0.5f, true) + i32(5, true) + u16(20, true))
        val fixpEntry = parseDltContent(ByteArrayInputStream(frame(noar = 1, payload = fixpPayload))).entries.single()
        assertEquals("15.0", fixpEntry.msg)
    }

    @Test fun parsesViewerCsvAndStreamingLogcatWithoutMaterializingText() {
        val csv = "Time,ECU,AppId,ContextId,Type,Payload\n" +
            "2026-01-02 03:04:05.123,ECU1,APP1,CTX1,ERROR,DLT MCP verification message\n"
        val dlt = parseLogContent(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(LogFormat.DLT, dlt.format)
        assertEquals(LogLevel.E, dlt.entries.single().level)
        val logcat = buildString { repeat(2_000) { append("01-02 03:04:05.123  1  2 I Tag: row$it\n") } }
        val parsed = parseLogContent(ByteArrayInputStream(logcat.toByteArray()))
        assertEquals(LogFormat.LOGCAT, parsed.format)
        assertEquals(2_000, parsed.entries.size)
        assertEquals("row1999", parsed.entries.last().msg)
    }

    @Test fun routesBomlessUtf16LogcatAheadOfPlausibleRawDlt() {
        val source = "01-02 03:04:05.123  1  2 I Tag: unicode\n"
        listOf(Charsets.UTF_16LE, Charsets.UTF_16BE).forEach { charset ->
            val bytes = source.toByteArray(charset)
            val inMemory = parseLogContent(ByteArrayInputStream(bytes))
            assertEquals(LogFormat.LOGCAT, inMemory.format)
            assertEquals("unicode", inMemory.entries.single().msg)

            val file = Files.createTempFile("openlog-utf16-", ".log").toFile()
            try {
                file.writeBytes(bytes)
                val fromFile = parseLogFileResult(file)
                assertEquals(LogFormat.LOGCAT, fromFile.format)
                assertEquals("unicode", fromFile.entries.single().msg)
            } finally {
                file.delete()
            }
        }
    }

    private fun storageHeader(ecu: String): ByteArray =
        byteArrayOf(
            'D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 1,
            1, 0, 0, 0, STORAGE_MICROSECONDS_LOW_BYTE.toByte(), 0xE0.toByte(), 1, 0,
        ) + ecu.padEnd(4, '\u0000').take(4).toByteArray()

    private fun frame(
        msbf: Boolean = true,
        msin: Int = 0x41,
        noar: Int = 1,
        ecu: String? = null,
        session: Int? = null,
        timestamp: Long? = null,
        payload: ByteArray,
    ): ByteArray {
        var htyp = DLT_V1_EXTENDED_HEADER
        if (msbf) htyp = htyp or DLT_HTYP_MSBF
        val extras = mutableListOf<Byte>()
        if (ecu != null) {
            htyp = htyp or 0x04
            extras += ecu.padEnd(4, '\u0000').take(4).toByteArray().toList()
        }
        if (session != null) {
            htyp = htyp or 0x08
            extras += u32(session.toLong(), true).toList()
        }
        if (timestamp != null) {
            htyp = htyp or 0x10
            extras += u32(timestamp, true).toList()
        }
        val body = extras.toByteArray() + byteArrayOf(msin.toByte(), noar.toByte()) +
            "APP1CTX1".toByteArray() + payload
        val length = 4 + body.size
        return byteArrayOf(htyp.toByte(), 1, (length ushr 8).toByte(), length.toByte()) + body
    }

    private fun verboseString(value: String, msbf: Boolean): ByteArray =
        typed(DLT_TYPE_STRING, lengthPrefixed((value + '\u0000').toByteArray(), msbf), msbf)

    private fun typed(type: Int, value: ByteArray, msbf: Boolean = true): ByteArray = u32(type.toLong(), msbf) + value

    // dlt-daemon's numeric-with-VARI layout writes both lengths before either string:
    // nameLen, unitLen, name, unit — not name-then-unit interleaved with their own lengths.
    private fun attr(name: String, unit: String, msbf: Boolean): ByteArray {
        val nameBytes = name.toByteArray(); val unitBytes = unit.toByteArray()
        return u16(nameBytes.size, msbf) + u16(unitBytes.size, msbf) + nameBytes + unitBytes
    }

    private fun attrName(name: String, msbf: Boolean): ByteArray = u16(name.toByteArray().size, msbf) + name.toByteArray()

    private fun f32(value: Float, bigEndian: Boolean): ByteArray = u32(value.toRawBits().toLong(), bigEndian)

    private fun i32(value: Int, bigEndian: Boolean): ByteArray = u32(value.toLong(), bigEndian)

    private fun lengthPrefixed(value: ByteArray, msbf: Boolean): ByteArray = u16(value.size, msbf) + value

    private fun u8(value: Int): ByteArray = byteArrayOf(value.toByte())

    private fun u16(value: Int, bigEndian: Boolean): ByteArray =
        if (bigEndian) byteArrayOf((value ushr 8).toByte(), value.toByte())
        else byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private fun u32(value: Long, bigEndian: Boolean): ByteArray {
        val bytes = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
        return if (bigEndian) bytes else bytes.reversedArray()
    }

    private fun u64(value: Long, bigEndian: Boolean): ByteArray {
        val bytes = ByteArray(Long.SIZE_BYTES) { index -> (value ushr ((7 - index) * 8)).toByte() }
        return if (bigEndian) bytes else bytes.reversedArray()
    }
}
