package com.indagium

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.indagium.utils.MinstdRand0
import com.indagium.utils.kloggVariedColors
import com.indagium.utils.parseQtColor
import com.indagium.utils.qtDarker
import com.indagium.utils.qtLighter
import com.indagium.utils.uniformInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KloggColorTest {
    @Suppress("MagicNumber") // 8-bit channel extraction
    private fun rgb(c: Color): Triple<Int, Int, Int> = c.toArgb().let { Triple((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) }

    @Test
    fun minstdRand0MatchesTheReferenceSequence() {
        // The C++ standard pins the 10000th output of a default-seeded (1) minstd_rand0.
        assertEquals(16807L, MinstdRand0(1).next())
        var last = 0L
        val fresh = MinstdRand0(1)
        repeat(10_000) { last = fresh.next() }
        assertEquals(1043618065L, last)
    }

    @Test
    fun aSeedDivisibleByTheModulusBehavesLikeSeedOne() {
        assertEquals(MinstdRand0(1).next(), MinstdRand0(0).next())
        assertEquals(MinstdRand0(1).next(), MinstdRand0(MinstdRand0.MODULUS).next())
    }

    @Test
    fun uniformIntDownscalesLikeLibstdcxx() {
        // First draw 16807 -> (16807 - 1) / (2147483645 / 31) = 0 -> the lower bound.
        assertEquals(85, MinstdRand0(1).uniformInt(85, 115))
    }

    @Test
    fun darkerHalvesTheValueLikeQt() {
        // QColor("white").darker(200) is #7f7f7f
        assertEquals(Triple(127, 127, 127), rgb(qtDarker(Color.White, 200)))
        assertEquals(Triple(255, 0, 0), rgb(qtDarker(Color.Red, 100)))
    }

    @Test
    fun lighterScalesValueAndDrainsSaturationOnOverflow() {
        assertEquals(Triple(96, 96, 96), rgb(qtLighter(Color(64, 64, 64), 150)))
        assertEquals(Triple(255, 127, 127), rgb(qtLighter(Color(255, 0, 0), 150)))
    }

    @Test
    fun aFactorBelowOneHundredFlipsTheDirection() {
        val c = Color(200, 100, 50)
        assertEquals(qtLighter(c, 200), qtDarker(c, 50))
        assertEquals(qtDarker(c, 200), qtLighter(c, 50))
        assertEquals(c, qtDarker(c, 0))
    }

    @Test
    fun alphaSurvivesTheShading() {
        val shaded = qtDarker(Color(0x80, 0x40, 0x20, 0x77), 150)
        assertEquals(0x77, (shaded.toArgb() ushr 24) and 0xFF)
    }

    @Test
    fun uniformIntStaysInRangeAndIsDeterministic() {
        val a = MinstdRand0(42)
        val b = MinstdRand0(42)
        repeat(500) {
            val x = a.uniformInt(85, 115)
            assertTrue(x in 85..115)
            assertEquals(x, b.uniformInt(85, 115))
        }
    }

    @Test
    fun variedColorsAreDeterministicPerMatchedText() {
        val fore = Color.Black
        val back = Color.White
        val first = kloggVariedColors(fore, back, 15, "session-42")
        assertEquals(first, kloggVariedColors(fore, back, 15, "session-42"))
        val shades = (1..30).map { kloggVariedColors(fore, back, 15, "match-$it").second }.toSet()
        assertTrue(shades.size > 1, "different matches should get different shades")
        // factor is in [85, 115]: white's value lands between 100/115 and 100/85 of full (clamped to 255)
        shades.forEach { assertTrue(rgb(it).first in 221..255) }
    }

    @Test
    fun zeroVarianceLeavesTheColoursAlone() {
        assertEquals(Color.Black to Color.White, kloggVariedColors(Color.Black, Color.White, 0, "x"))
    }

    @Test
    fun qtColorStringsParse() {
        assertEquals(Color(0xFFCC0000.toInt()), parseQtColor("#ffcc0000"))
        assertEquals(Color(0x11, 0x22, 0x33, 0x80), parseQtColor("#80112233"))
        assertEquals(Color(0x11, 0x22, 0x33), parseQtColor("#112233"))
        assertEquals(Color(0, 0, 0xFF), parseQtColor("#00f"))
        assertEquals(Color(255, 255, 0), parseQtColor("Yellow"))
        assertEquals(Color(0x80, 0x80, 0x80), parseQtColor("grey"))
        assertEquals(Color(0, 0, 0, 0), parseQtColor("transparent"))
        assertNull(parseQtColor("#12345"))
        assertNull(parseQtColor("notacolour"))
        assertNull(parseQtColor(""))
        assertNotEquals(parseQtColor("red"), parseQtColor("blue"))
    }
}
