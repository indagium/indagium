package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.ui.HL_COLORS
import com.indagium.ui.SEQ_COLORS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaletteTest {
    @Test
    fun palettesOfferMoreThanTheInitialSmallSet() {
        assertTrue(SEQ_COLORS.size >= 20)
        assertEquals(100, HL_COLORS.size)
        assertEquals(100, HL_COLORS.map { it.value }.distinct().size)
        assertEquals(
            listOf(Color(0xFFfacc15), Color(0xFFf97316), Color(0xFFec4899), Color(0xFF22c55e), Color(0xFF06b6d4)),
            HL_COLORS.take(5),
        )
        assertFalse(HL_COLORS.drop(25).all { it.red > .8f && it.green < .25f && it.blue < .25f })
    }
}
