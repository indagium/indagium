package com.indagium

import com.indagium.diagram3.DiagramExportMode
import com.indagium.ui.Seq3NoteParseCache
import com.indagium.ui.Seq3NoteSummaryCache
import com.indagium.ui.Seq3RenderCache
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v3 port of the deleted `DiagramNoteCollapsedPerformanceTest` — same property, ported onto the
 * v3 note header (`<!-- indagium:diagram3 v1 {json} -->`, `Seq3Codec.kt`): a collapsed diagram
 * note in the Notes column must never fully parse or render, no matter how large its carried
 * lifelines/messages arrays are. Unlike v1/v2's separate optional "snapshot"/"model" payload keys,
 * v3's header carries lifelines/messages directly under "document" — but `title`/`caption`/
 * `exportMode`/`range` are all written before those two arrays (see `Seq3Codec.documentToMap`'s
 * field order), so [Seq3NoteSummaryCache]'s bounded scan still never has to reach them.
 */
class Seq3NoteCollapsedPerformanceTest {
    @BeforeTest
    fun resetCaches() {
        Seq3NoteSummaryCache.clearForTest()
        Seq3NoteParseCache.clearForTest()
        Seq3RenderCache.clearForTest()
    }

    @Test
    fun twentyCollapsedLargeNotesUseBoundedMetadataOnlyAndNeverParseOrRender() {
        val largeLifelinesPayload = "{\"id\":\"x\",\"name\":\"y\",\"tagIds\":[\"z\"],\"ordinal\":0},".repeat(80_000)
        val notes = List(20) { ordinal ->
            // Each note is a distinct String instance and comfortably exceeds the shallow scan
            // cap. The huge lifelines array lives strictly AFTER title/caption/exportMode/range in
            // Seq3Codec's own field order, so it must never be reached or counted by the collapsed-
            // card path.
            """<!-- indagium:diagram3 v1 {"dialect":"mermaid","sourceHash":"x","caption":"kept","exportMode":"IMAGE","document":{"title":"Large $ordinal","sourceFile":"large.log","range":{"type":"ids","from":10,"to":20},"lifelines":[$largeLifelinesPayload],"messages":[],"fragments":[],"notes":[],"defaultRepeat":"COLLAPSE_ABOVE"}} -->
```mermaid
sequenceDiagram
```
"""
        }

        notes.forEachIndexed { ordinal, note ->
            assertTrue(note.length > Seq3NoteSummaryCache.MAX_INSPECTED_CHARS * 20)
            val summary = assertNotNull(Seq3NoteSummaryCache.summary(note))
            assertEquals("Large $ordinal", summary.title)
            assertEquals("kept", summary.caption)
            assertEquals(DiagramExportMode.IMAGE, summary.exportMode)
            assertEquals("Lines 10–20", summary.scope)
            assertNull(summary.messageCount, "folded cards must not count document messages")
            assertTrue(summary.inspectedChars <= Seq3NoteSummaryCache.MAX_INSPECTED_CHARS)
        }

        assertEquals(0, Seq3NoteParseCache.parseCountForTest(), "folded cards must not run the full codec parser")
        assertEquals(0, Seq3RenderCache.renderMissCountForTest(), "folded cards must not rasterize")
    }
}
