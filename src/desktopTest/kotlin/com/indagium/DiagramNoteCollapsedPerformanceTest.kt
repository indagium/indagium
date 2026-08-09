package com.indagium

import com.indagium.diagram.DiagramExportMode
import com.indagium.ui.DiagramNoteParseCache
import com.indagium.ui.DiagramNoteSummaryCache
import com.indagium.ui.DiagramRenderCache
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagramNoteCollapsedPerformanceTest {
    @BeforeTest
    fun resetCaches() {
        DiagramNoteSummaryCache.clearForTest()
        DiagramNoteParseCache.clearForTest()
        DiagramRenderCache.clearForTest()
    }

    @Test
    fun twentyCollapsedLargeNotesUseBoundedMetadataOnlyAndNeverParseOrRender() {
        val largeSnapshotPayload = "{\"f\":0,\"l\":\"payload\"},".repeat(80_000)
        val notes = List(20) { ordinal ->
            // Each note is a distinct String instance and comfortably exceeds the shallow scan
            // cap. Duplicate-looking message fields live exclusively inside snapshot.model and
            // must never be counted or searched by the collapsed-card path.
            """<!-- indagium:diagram v3 {"dialect":"MERMAID","title":"Large $ordinal","participants":[],"range":{"kind":"ids","from":10,"to":20},"mode":"COMPONENT_FLOW","rules":[],"options":{},"sourceFile":"large.log","sourceHash":"x","attachment":{"diagramId":"d-$ordinal","mode":"SNAPSHOT","revision":7,"caption":"kept","exportMode":"IMAGE"},"snapshot":{"source":"sequenceDiagram","sourceHash":"x","model":{"messages":[$largeSnapshotPayload],"caption":"must-not-win","exportMode":"SOURCE"}}} -->
```mermaid
sequenceDiagram
```
"""
        }

        notes.forEachIndexed { ordinal, note ->
            assertTrue(note.length > DiagramNoteSummaryCache.MAX_INSPECTED_CHARS * 20)
            val summary = assertNotNull(DiagramNoteSummaryCache.summary(note))
            assertEquals("Large $ordinal", summary.title)
            assertEquals("kept", summary.caption)
            assertEquals(DiagramExportMode.IMAGE, summary.exportMode)
            assertEquals("Lines 10–20", summary.scope)
            assertEquals(7L, summary.revision)
            assertNull(summary.messageCount, "folded cards must not count snapshot/model messages")
            assertTrue(summary.inspectedChars <= DiagramNoteSummaryCache.MAX_INSPECTED_CHARS)
        }

        assertEquals(0, DiagramNoteParseCache.parseCountForTest(), "folded cards must not run the full codec parser")
        assertEquals(0, DiagramRenderCache.renderMissCountForTest(), "folded cards must not rasterize")
    }
}
