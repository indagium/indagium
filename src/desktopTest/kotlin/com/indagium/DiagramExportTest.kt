package com.indagium

import com.indagium.diagram.DiagramMessage
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.DiagramTheme
import com.indagium.diagram.DiagramAttachmentMetadata
import com.indagium.diagram.DiagramExportMode
import com.indagium.diagram.MessageKind
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.encodeDiagramNote
import com.indagium.diagram.renderSequenceDiagram
import com.indagium.diagram.toPngBytes
import com.indagium.model.AnnBlock
import com.indagium.model.AnnotationLogBlockStyle
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.mkTab
import com.indagium.utils.annotationDiagramFileName
import com.indagium.utils.buildAnnotationsHtml
import com.indagium.utils.buildMd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a diagram note leaves the app: the Markdown/Jira forms buildMd emits, the filename those
 * anchors must agree with, and the inline PNG the rich-text clipboard carries.
 *
 * The recurring risk here is the spec/model header leaking into user-visible output — it is a
 * multi-KB JSON blob, so any path that forgets to strip it produces something unusable rather than
 * something slightly wrong.
 */
class DiagramExportTest {

    private val participants = listOf(
        DiagramParticipant("BT", "BluetoothAdapter", ParticipantKind.TAG, tag = "BT"),
        DiagramParticipant("BMS", "BluetoothManagerService", ParticipantKind.TAG, tag = "BMS"),
    )

    private val model = SeqDiagram(
        spec = SeqDiagramSpec(participants = participants),
        participants = participants,
        messages = listOf(
            DiagramMessage(0, 1, "enable() called", 1001, "10:00:00.000", LogLevel.I, MessageKind.CALL),
            DiagramMessage(1, 0, "STATE_OFF", 1002, "10:00:00.400", LogLevel.E, MessageKind.RETURN),
        ),
    )

    private fun diagramNote(
        id: String = "n1",
        attachment: DiagramAttachmentMetadata? = null,
    ): AnnBlock.Note =
        AnnBlock.Note(id, encodeDiagramNote(model.spec, "sequenceDiagram\n  BT->>BMS: enable() called", model, attachment))

    private fun tabWith(vararg blocks: AnnBlock, frameStamp: String? = null) = mkTab(
        "t1", "bugreport.txt", listOf(LogEntry(1001, "10:00:00.000", LogLevel.I, "BT", "enable() called")),
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
            tabWith(diagramNote(attachment = DiagramAttachmentMetadata(exportMode = DiagramExportMode.SOURCE))),
            AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED),
        )

        assertTrue(md.contains("```mermaid"), "the fence must survive so a Markdown viewer renders it; got:\n$md")
        assertTrue(md.contains("sequenceDiagram"), "the diagram body must survive")
        assertTrue(md.trimEnd().contains("```"), "the fence must be closed")
        assertFalse(md.contains("indagium:diagram"), "the spec header must never reach exported Markdown")
        assertFalse(md.contains("\"messages\""), "the carried model JSON must never reach exported Markdown")
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
        assertFalse(md.contains("indagium:diagram"), "the spec header must never reach a Jira comment")
    }

    @Test
    fun imageModeUsesAMarkdownImageReferenceAndCaptionByDefault() {
        val md = buildMd(
            tabWith(diagramNote(attachment = DiagramAttachmentMetadata(caption = "Bluetooth startup"))),
            AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED),
        )

        assertTrue(md.contains("Bluetooth startup"), "the persisted caption should label the attachment; got:\n$md")
        assertTrue(md.contains("![Bluetooth startup](${annotationDiagramFileName(1)})"), "got:\n$md")
        assertFalse(md.contains("```mermaid"), "default image mode should not emit dialect source")
    }

    @Test
    fun sourceModeUsesJiraCodeWithoutAnImageAnchor() {
        val md = buildMd(
            tabWith(diagramNote(attachment = DiagramAttachmentMetadata(exportMode = DiagramExportMode.SOURCE))),
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
        val png = renderSequenceDiagram(model, DiagramTheme.LIGHT).toPngBytes()

        val html = buildAnnotationsHtml(tabWith(diagramNote()), AppSettings()) { png }

        assertTrue(html.contains("<img src=\"data:image/png;base64,"), "expected an inline PNG; got:\n$html")
        assertFalse(html.contains("indagium:diagram"), "the spec header must never reach the clipboard HTML")
    }

    @Test
    fun theRichClipboardFallsBackToSourceWhenTheDiagramCannotBeRendered() {
        // The default renderer callback returns null (utils has no access to the colour theme), and
        // a note with no carried model can't be drawn at all. Either way the reader should still
        // get the diagram's content rather than an empty block.
        val html = buildAnnotationsHtml(tabWith(diagramNote()), AppSettings())

        assertTrue(html.contains("<pre>"), "expected the source as preformatted text; got:\n$html")
        assertTrue(html.contains("sequenceDiagram"))
        assertFalse(html.contains("indagium:diagram"))
    }
}
