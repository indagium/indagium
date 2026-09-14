package com.indagium

import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.updateSeq3NoteCaption
import com.indagium.ui.Seq3NoteSummaryCache
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class Seq3NoteSummaryCaptionTest {
    @BeforeTest
    fun resetCache() = Seq3NoteSummaryCache.clearForTest()

    @Test
    fun collapsedSummaryDecodesMultiLineCaptionEscapesExactly() {
        val caption = "1. **first**\n2. second\n\n> quote \"q\"\n```\ncode \\ path\n```\ttab ü"
        val note = encodeSeq3Note(Seq3Document(title = "T"), caption = caption)

        val summary = assertNotNull(Seq3NoteSummaryCache.summary(note))
        assertEquals(caption, summary.caption)
    }

    @Test
    fun editingACaptionThroughTheSummaryDoesNotAccumulateEscapes() {
        val note = encodeSeq3Note(Seq3Document(title = "T"), caption = "a\nb")
        val first = assertNotNull(Seq3NoteSummaryCache.summary(note)).caption
        val rewritten = assertNotNull(updateSeq3NoteCaption(note, first))

        assertEquals("a\nb", assertNotNull(Seq3NoteSummaryCache.summary(rewritten)).caption)
    }

    @Test
    fun unescapeLeavesUnknownAndIncompleteEscapesAsWritten() {
        assertEquals("\\x \\u12", Seq3NoteSummaryCache.unescapeJsonString("\\x \\u12"))
        assertEquals("a\\", Seq3NoteSummaryCache.unescapeJsonString("a\\"))
        assertEquals("A/\"\\", Seq3NoteSummaryCache.unescapeJsonString("\\u0041\\/\\\"\\\\"))
    }
}
