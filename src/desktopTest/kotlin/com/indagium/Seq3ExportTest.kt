package com.indagium

import com.indagium.diagram3.DiagramExportMode
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3RasterTheme
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.model.AnnBlock
import com.indagium.model.AnnotationLogBlockStyle
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.Seq3RenderCache
import com.indagium.ui.mkTab
import com.indagium.utils.annotationDiagramFileName
import com.indagium.utils.buildAnnotationsHtml
import com.indagium.utils.buildMd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v3 port of the deleted `DiagramExportTest`: how a diagram note leaves the app — the Markdown/
 * Jira forms `buildMd` emits, the filename those anchors must agree with, and the inline PNG the
 * rich-text clipboard carries. Ported onto `com.indagium.diagram3.Seq3Codec`'s header instead of
 * the deleted v1/v2 `DiagramSpecCodec` — the recurring risk this guards is unchanged: the header
 * (now the WHOLE [Seq3Document] as JSON) leaking into user-visible output.
 */
class Seq3ExportTest {
    private companion object {
        const val EXAMPLE_ENTRY_ID = 1001
    }

    private val document = Seq3Document(
        lifelines = listOf(
            Seq3Lifeline("BT", "BluetoothAdapter", setOf("BT"), 0),
            Seq3Lifeline("BMS", "BluetoothManagerService", setOf("BMS"), 1),
        ),
        messages = listOf(
            Seq3Message(
                id = "msg-1",
                match = Seq3Match("BT", "enable() called"),
                fromLifelineId = "BT",
                toLifelineId = "BMS",
                labelTemplate = "enable() called",
                kind = Seq3Kind.CALL,
                occurrences = listOf(
                    Seq3Occurrence(EXAMPLE_ENTRY_ID, 0L, "10:00:00.000", 0, 0, 'I', "enable() called"),
                ),
            ),
            Seq3Message(
                id = "msg-2",
                match = Seq3Match("BMS", "STATE_OFF"),
                fromLifelineId = "BMS",
                toLifelineId = "BT",
                labelTemplate = "STATE_OFF",
                kind = Seq3Kind.RETURN,
                occurrences = listOf(
                    Seq3Occurrence(1002, 400L, "10:00:00.400", 0, 0, 'E', "STATE_OFF"),
                ),
            ),
        ),
    )

    private fun diagramNote(
        id: String = "n1",
        caption: String = "",
        exportMode: DiagramExportMode = DiagramExportMode.IMAGE,
    ): AnnBlock.Note = AnnBlock.Note(id, encodeSeq3Note(document, caption = caption, exportMode = exportMode))

    private fun tabWith(vararg blocks: AnnBlock, frameStamp: String? = null) = mkTab(
        "t1", "bugreport.txt", listOf(
            LogEntry(EXAMPLE_ENTRY_ID, "10:00:00.000", LogLevel.I, "BT", "enable() called"),
        ),
    ).let { it.copy(annotations = Annotations(blocks = blocks.toList(), frameStamp = frameStamp)) }

    // ── Filenames ────────────────────────────────────────────────────────────────────────────

    @Test
    fun diagramFileNamesAreZeroPaddedPngAndCarryTheFrameStampWhenThereIsOne() {
        assertEquals("diagram-01.png", annotationDiagramFileName(1))
        assertEquals("diagram-12.png", annotationDiagramFileName(12))
        assertEquals("diagram-20260807-231500-01.png", annotationDiagramFileName(1, "20260807-231500"))
    }

    @Test
    fun diagramsAndScreenshotsUseSeparateOrdinalSequences() {
        // A document with two of each must produce frame-01/02 AND diagram-01/02 — not a shared
        // 1..4 run, which would make the anchors reference files that were never written.
        assertEquals("diagram-01.png", annotationDiagramFileName(1))
        assertEquals("frame-01.jpg", com.indagium.utils.annotationImageFileName(1, "jpeg"))
    }

    // ── Markdown form ────────────────────────────────────────────────────────────────────────

    @Test
    fun sourceModeEmitsTheFenceVerbatimSoGithubRendersTheDiagram() {
        val md = buildMd(
            tabWith(diagramNote(exportMode = DiagramExportMode.SOURCE)),
            AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED),
        )

        assertTrue(md.contains("```mermaid"), "the fence must survive so a Markdown viewer renders it; got:\n$md")
        assertTrue(md.contains("sequenceDiagram"), "the diagram body must survive")
        assertTrue(md.trimEnd().contains("```"), "the fence must be closed")
        assertFalse(md.contains("indagium:diagram3"), "the header must never reach exported Markdown")
        assertFalse(md.contains("\"messages\""), "the carried document JSON must never reach exported Markdown")
    }

    // ── Jira form ────────────────────────────────────────────────────────────────────────────

    @Test
    fun imageModeEmitsAnAttachmentReferenceMatchingTheFileTheExporterWrites() {
        // Jira renders neither Mermaid nor ``` fences, so the picture has to arrive as an
        // attachment. The anchor and the written filename come from one shared helper precisely so
        // this can't drift — assert they agree.
        val md = buildMd(tabWith(diagramNote()), AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA))

        assertTrue(md.contains("!${annotationDiagramFileName(1)}!"), "expected a Jira attachment anchor; got:\n$md")
        assertFalse(md.contains("{code}"), "image mode must not also emit the source")
        assertFalse(md.contains("sequenceDiagram"))
        assertFalse(md.contains("indagium:diagram3"), "the header must never reach a Jira comment")
    }

    @Test
    fun imageModeUsesAMarkdownImageReferenceAndCaptionByDefault() {
        val md = buildMd(
            tabWith(diagramNote(caption = "Bluetooth startup")),
            AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED),
        )

        assertTrue(md.contains("Bluetooth startup"), "the persisted caption should label the attachment; got:\n$md")
        assertTrue(md.contains("![Bluetooth startup](${annotationDiagramFileName(1)})"), "got:\n$md")
        assertFalse(md.contains("```mermaid"), "default image mode should not emit dialect source")
    }

    @Test
    fun sourceModeUsesJiraCodeWithoutAnImageAnchor() {
        val md = buildMd(
            tabWith(diagramNote(exportMode = DiagramExportMode.SOURCE)),
            AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA),
        )

        assertTrue(md.contains("{code}"))
        assertTrue(md.contains("sequenceDiagram"))
        assertFalse(md.contains("!${annotationDiagramFileName(1)}!"))
    }

    @Test
    fun theJiraAnchorPicksUpTheAnalysisFrameStamp() {
        val md = buildMd(
            tabWith(diagramNote(), frameStamp = "20260807-231500"),
            AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA),
        )

        assertTrue(md.contains("!diagram-20260807-231500-01.png!"), "got:\n$md")
    }

    @Test
    fun eachDiagramNoteGetsItsOwnAscendingOrdinal() {
        val md = buildMd(
            tabWith(diagramNote("n1"), diagramNote("n2")),
            AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA),
        )

        assertTrue(md.contains("!diagram-01.png!"), "got:\n$md")
        assertTrue(md.contains("!diagram-02.png!"), "got:\n$md")
    }

    @Test
    fun anOrdinaryTextNoteIsStillExportedAsPlainTextAlongsideDiagrams() {
        val md = buildMd(
            tabWith(AnnBlock.Note("n0", "Root cause below."), diagramNote("n1")),
            AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA),
        )

        assertTrue(md.contains("Root cause below."), "prose notes must be untouched by the diagram branch; got:\n$md")
        assertTrue(md.contains("!diagram-01.png!"))
    }

    // ── Rich-text clipboard ──────────────────────────────────────────────────────────────────

    @Test
    fun theRichClipboardEmbedsTheDiagramAsAnInlinePngDataUri() {
        // This is the one path that puts a real picture into a Jira/Confluence comment without the
        // user hand-attaching a file, so it matters that it carries image bytes and not source.
        val png = Seq3RenderCache.pngBytes(Seq3RenderCache.layout(document), Seq3RasterTheme.DEFAULT_LIGHT)

        val html = buildAnnotationsHtml(tabWith(diagramNote()), AppSettings()) { png }

        assertTrue(html.contains("<img src=\"data:image/png;base64,"), "expected an inline PNG; got:\n$html")
        assertFalse(html.contains("indagium:diagram3"), "the header must never reach the clipboard HTML")
    }

    @Test
    fun theRichClipboardFallsBackToSourceWhenTheDiagramCannotBeRendered() {
        // The default renderer callback returns null (utils has no access to the colour theme).
        // The reader should still get the diagram's content rather than an empty block.
        val html = buildAnnotationsHtml(tabWith(diagramNote()), AppSettings())

        assertTrue(html.contains("<pre>"), "expected the source as preformatted text; got:\n$html")
        assertTrue(html.contains("sequenceDiagram"))
        assertFalse(html.contains("indagium:diagram3"))
    }
}
