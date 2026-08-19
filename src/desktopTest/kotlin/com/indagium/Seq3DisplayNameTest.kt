package com.indagium

import com.indagium.diagram3.Seq3FontRole
import com.indagium.diagram3.Seq3TextMetrics
import com.indagium.diagram3.seq3DisplayName
import com.indagium.diagram3.seq3WrapDisplayName
import kotlin.test.Test
import kotlin.test.assertEquals

class Seq3DisplayNameTest {
    // Same 7px-per-char stub scheme as Seq3RasterTest — deterministic and easy to hand-compute
    // wrap points from.
    private class StubMetrics : Seq3TextMetrics {
        override fun width(role: Seq3FontRole, text: String): Double = text.length * 7.0

        override fun lineHeight(role: Seq3FontRole): Double = 16.0
    }

    private val dotted = "com.mycompany.myapp.Example1"

    @Test
    fun resolvesEachSegmentCountAgainstADottedName() {
        assertEquals(dotted, seq3DisplayName(dotted, segments = 0, documentDefault = 0))
        assertEquals("Example1", seq3DisplayName(dotted, segments = 1, documentDefault = 0))
        assertEquals("myapp.Example1", seq3DisplayName(dotted, segments = 2, documentDefault = 0))
        assertEquals("mycompany.myapp.Example1", seq3DisplayName(dotted, segments = 3, documentDefault = 0))
        // segments >= the number of dot-separated parts is the same as "full name".
        assertEquals(dotted, seq3DisplayName(dotted, segments = 4, documentDefault = 0))
        assertEquals(dotted, seq3DisplayName(dotted, segments = 99, documentDefault = 0))
    }

    @Test
    fun nullSegmentsInheritsTheDocumentDefault() {
        assertEquals(dotted, seq3DisplayName(dotted, segments = null, documentDefault = 0))
        assertEquals("myapp.Example1", seq3DisplayName(dotted, segments = null, documentDefault = 2))
        // An explicit per-lifeline override always wins over the document default.
        assertEquals("Example1", seq3DisplayName(dotted, segments = 1, documentDefault = 2))
    }

    @Test
    fun aDotlessNameIsReturnedAsIsForAnySegmentCount() {
        assertEquals("Example1", seq3DisplayName("Example1", segments = 1, documentDefault = 0))
        assertEquals("Example1", seq3DisplayName("Example1", segments = 5, documentDefault = 0))
        assertEquals("Example1", seq3DisplayName("Example1", segments = 0, documentDefault = 0))
        assertEquals("Example1", seq3DisplayName("Example1", segments = null, documentDefault = 3))
    }

    @Test
    fun anEmptyNameRoundTripsWithoutThrowing() {
        assertEquals("", seq3DisplayName("", segments = 0, documentDefault = 0))
        assertEquals("", seq3DisplayName("", segments = 1, documentDefault = 0))
        assertEquals("", seq3DisplayName("", segments = null, documentDefault = 4))
    }

    @Test
    fun aTrailingDotDoesNotThrowAndDegradesPredictably() {
        // "com.example." splits into ["com", "example", ""] — keeping the last 1 segment is the
        // trailing empty segment itself. Documented degenerate behaviour, not a crash.
        assertEquals("", seq3DisplayName("com.example.", segments = 1, documentDefault = 0))
        assertEquals("example.", seq3DisplayName("com.example.", segments = 2, documentDefault = 0))
        // Full name (0/absent) is always returned byte-for-byte, trailing dot included.
        assertEquals("com.example.", seq3DisplayName("com.example.", segments = 0, documentDefault = 0))
    }

    @Test
    fun wrapsAfterDotsBeforeFallingBackToAnyOtherBreak() {
        // Chunk widths (7px/char): "com."=28, "mycompany."=70, "myapp."=42, "Example1"=56.
        // At maxWidth=100: "com."+"mycompany."=98 fits, adding "myapp." (140) doesn't -> line
        // break lands right after a dot, never mid-word.
        val lines = seq3WrapDisplayName(dotted, maxWidth = 100.0, tm = StubMetrics())
        assertEquals(listOf("com.mycompany.", "myapp.Example1"), lines)
    }

    @Test
    fun capsAtThreeLinesAndEllipsizesTheLastOne() {
        // Five single-char dot segments, each exactly filling a 14px (2-char) line on its own but
        // never two together -> needs 5 lines; capped at 3, with the 3rd ellipsized to signal the
        // dropped "d." and "e" segments rather than silently discarding them.
        val lines = seq3WrapDisplayName("a.b.c.d.e", maxWidth = 14.0, tm = StubMetrics())
        assertEquals(3, lines.size)
        assertEquals(listOf("a.", "b."), lines.take(2))
        assertEquals("c…", lines[2])
    }

    @Test
    fun aSingleDotlessSegmentWiderThanMaxWidthHardSplitsInsteadOfOverflowing() {
        // No dots at all, so the dot-boundary break can never help - only case this function
        // hard-splits mid-token (see its own doc).
        val lines = seq3WrapDisplayName("Supercalifragilistic", maxWidth = 21.0, tm = StubMetrics())
        // 21px / 7px-per-char = 3 chars fit per line.
        assertEquals(listOf("Sup", "erc", "al…"), lines)
        lines.forEach { line -> assert(line.length <= 3) { "line \"$line\" exceeds the 3-char budget" } }
    }

    @Test
    fun aNameThatFitsEntirelyOnOneLineIsNotWrappedOrEllipsized() {
        val lines = seq3WrapDisplayName("Short", maxWidth = 1000.0, tm = StubMetrics())
        assertEquals(listOf("Short"), lines)
    }

    @Test
    fun anEmptyDisplayNameWrapsToASingleEmptyLine() {
        assertEquals(listOf(""), seq3WrapDisplayName("", maxWidth = 100.0, tm = StubMetrics()))
    }
}
