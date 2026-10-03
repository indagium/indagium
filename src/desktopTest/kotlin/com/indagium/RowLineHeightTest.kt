package com.indagium

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.indagium.ui.rowLineHeightFor
import kotlin.test.Test
import kotlin.test.assertEquals

class RowLineHeightTest {
    @Test
    fun plainRowKeepsTheFixedLineBox() {
        assertEquals(16.sp, rowLineHeightFor(AnnotatedString("plain row"), 12.sp))
        assertEquals(18.sp, rowLineHeightFor(AnnotatedString("plain row"), 14.sp))
    }

    @Test
    fun colourAndWeightSpansDoNotChangeTheLineBox() {
        val text = buildAnnotatedString {
            append("a ")
            withStyle(SpanStyle(background = Color.Yellow, fontWeight = FontWeight.SemiBold)) { append("match") }
        }
        assertEquals(16.sp, rowLineHeightFor(text, 12.sp))
    }

    @Test
    fun fontFamilyOverrideUsesNaturalMetrics() {
        val text = buildAnnotatedString {
            append("a ")
            withStyle(SpanStyle(background = Color.Yellow, fontFamily = FontFamily.Serif)) { append("match") }
        }
        assertEquals(TextUnit.Unspecified, rowLineHeightFor(text, 12.sp))
    }
}
