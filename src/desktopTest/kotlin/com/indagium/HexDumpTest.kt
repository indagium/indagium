package com.indagium

import com.indagium.ui.decodeHexText
import com.indagium.ui.hexByteCountLabel
import com.indagium.ui.hexDumpLines
import com.indagium.ui.parseHexBytes
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HexDumpTest {
    @Test
    fun spaceSeparatedBytePairsAreHex() {
        val bytes = assertNotNull(parseHexBytes("53 6f 66 74 77 61 72 65"))
        assertContentEquals("Software".toByteArray(), bytes)
        assertNotNull(parseHexBytes("00 01 02 03 04 05 06 07"), "pairs of digits only are still byte pairs")
    }

    @Test
    fun aContiguousEvenRunIsHexOnlyWithAtLeastOneLetter() {
        val bytes = assertNotNull(parseHexBytes("536F667477617265"))
        assertContentEquals("Software".toByteArray(), bytes)
        assertNull(parseHexBytes("1794860790349975040"), "a decimal id is a number, not bytes")
        assertNull(parseHexBytes("1794860790349975"), "all digits: no a-f letter")
    }

    @Test
    fun shortOrMixedStringsAreNotHex() {
        assertNull(parseHexBytes("53 6f 66"), "fewer than 8 bytes")
        assertNull(parseHexBytes("536F66"), "fewer than 8 bytes, contiguous")
        assertNull(parseHexBytes("53 6f 66 74 77 61 72 6"), "a half pair")
        assertNull(parseHexBytes("53  6f 66 74 77 61 72 65"), "double space")
        assertNull(parseHexBytes("53 6f 66 74 77 61 72 65 "), "trailing space")
        assertNull(parseHexBytes("536F66747761726"), "odd length")
        assertNull(parseHexBytes("536F66747761726G"), "not a hex digit")
        assertNull(parseHexBytes("Software"), "plain text")
        assertNull(parseHexBytes(""), "empty")
        assertEquals(8, parseHexBytes("00 01 02 03 04 05 06 07")!!.size)
    }

    @Test
    fun printableUtf8DecodesAndEverythingElseDoesNot() {
        assertEquals("Software", decodeHexText("Software".toByteArray()))
        assertEquals("a\tb", decodeHexText("a\tb".toByteArray()), "a tab is allowed")
        assertEquals("héllo", decodeHexText("héllo".toByteArray()))
        assertNull(decodeHexText("line\nbreak".toByteArray()), "a newline is a control character")
        assertNull(decodeHexText(byteArrayOf(0x53, 0x00, 0x66, 0x74, 0x77, 0x61, 0x72, 0x65)), "NUL")
        assertNull(decodeHexText(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)), "not UTF-8")
        assertNull(decodeHexText(ByteArray(0)), "nothing to show")
    }

    @Test
    fun theCountLabelNamesTheBytes() {
        assertEquals("hex · 8 bytes", hexByteCountLabel(8))
        assertEquals("hex · 1 byte", hexByteCountLabel(1))
    }

    @Test
    fun dumpLinesCarryAnOffsetAndUpToSixteenLowercasePairs() {
        val bytes = ByteArray(20) { index -> if (index == 1) 0x0a else (0x41 + index).toByte() }
        val lines = hexDumpLines(bytes)
        assertEquals(2, lines.size)
        assertEquals("0000", lines[0].offset)
        assertEquals("41 0a 43 44 45 46 47 48 49 4a 4b 4c 4d 4e 4f 50", lines[0].hex)
        assertEquals("0010", lines[1].offset)
        assertEquals("51 52 53 54", lines[1].hex)
    }
}
