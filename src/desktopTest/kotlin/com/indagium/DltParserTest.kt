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

    @Test fun rendersFinalUnsupportedArraysAndStructuresAsDeterministicHex() {
        // A V1 array has U16 dimensions followed by one U16 entry count per dimension, then
        // its values. The parser intentionally leaves its type-specific representation opaque.
        val array = typed(0x141, u16(1, true) + u16(2, true) + byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        val finalArray = parseDltContent(ByteArrayInputStream(frame(noar = 1, payload = array))).entries.single()
        assertEquals("0x00010002AABB", finalArray.msg)

        val following = typed(0x41, u8(7))
        assertFailsWith<IllegalArgumentException> {
            parseDltContent(ByteArrayInputStream(frame(noar = 2, payload = array + following)))
        }

        val structure = typed(0x4001, byteArrayOf(0x00, 0x02, 0xAA.toByte(), 0xBB.toByte()))
        val structureEntry = parseDltContent(ByteArrayInputStream(frame(noar = 1, payload = structure))).entries.single()
        assertEquals("0x0002AABB", structureEntry.msg)
        assertFailsWith<IllegalArgumentException> {
            parseDltContent(ByteArrayInputStream(frame(noar = 2, payload = structure + following)))
        }
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

    @Test fun rejectsTruncationInvalidLengthsMalformedArgumentsStorageAndV2() {
        assertFailsWith<IllegalArgumentException> { parseDltContent(ByteArrayInputStream(frame(payload = verboseString("x", true)).copyOf(8))) }
        assertFailsWith<IllegalArgumentException> { parseDltContent(ByteArrayInputStream(byteArrayOf(0x21, 0, 0, 3))) }
        val truncatedStorage = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 1, 0, 0)
        assertFailsWith<IllegalArgumentException> { parseLogContent(ByteArrayInputStream(truncatedStorage)) }
        assertFailsWith<IllegalArgumentException> { parseDltContent(ByteArrayInputStream(byteArrayOf(0x41, 0, 0, 4))) }
        assertFailsWith<IllegalArgumentException> {
            parseDltContent(ByteArrayInputStream(frame(noar = 1, payload = typed(0x200, u16(5, true) + byteArrayOf('x'.code.toByte())))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseDltContent(ByteArrayInputStream(storageHeader("ECU1") + frame(payload = verboseString("ok", true)) + ByteArray(16)))
        }
        val v2 = byteArrayOf(0x41, 0, 0, 4)
        val error = assertFailsWith<IllegalArgumentException> { parseLogContent(ByteArrayInputStream(v2)) }
        assertTrue(error.message.orEmpty().contains("v2 is not supported"))
        val storageV2 = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 2) + "text header".toByteArray()
        val storageError = assertFailsWith<IllegalArgumentException> { parseLogContent(ByteArrayInputStream(storageV2)) }
        assertTrue(storageError.message.orEmpty().contains("v2 is not supported"))
        val directStorageError = assertFailsWith<IllegalArgumentException> { parseDltContent(ByteArrayInputStream(storageV2)) }
        assertTrue(directStorageError.message.orEmpty().contains("v2 is not supported"))
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

    private fun attr(name: String, unit: String, msbf: Boolean): ByteArray =
        lengthPrefixed(name.toByteArray(), msbf) + lengthPrefixed(unit.toByteArray(), msbf)

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
