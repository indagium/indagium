package com.openlog

import com.openlog.ui.HL_COLORS
import com.openlog.ui.SEQ_COLORS
import kotlin.test.Test
import kotlin.test.assertTrue

class PaletteTest {
    @Test
    fun palettesOfferMoreThanTheInitialSmallSet() {
        assertTrue(SEQ_COLORS.size >= 20)
        assertTrue(HL_COLORS.size >= 20)
    }
}
