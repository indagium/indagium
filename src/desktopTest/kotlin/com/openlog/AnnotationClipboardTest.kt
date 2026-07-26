package com.openlog

import com.openlog.model.AnnBlock
import com.openlog.model.AnnotationLogBlockStyle
import com.openlog.model.Annotations
import com.openlog.model.AppSettings
import com.openlog.model.CopyMaskRule
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.VideoFrameReference
import com.openlog.model.VideoSource
import com.openlog.ui.HtmlTransferable
import com.openlog.ui.ImageTransferable
import com.openlog.ui.imageBytesFromTransferable
import com.openlog.ui.maskWordForCopy
import com.openlog.ui.mkTab
import com.openlog.utils.buildAnnotationsHtml
import com.openlog.utils.buildMd
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Task D of the video-sync plan: per-image "Copy image" and whole-annotation "Copy rich preview"
// clipboard paths. Covers the pure/testable pieces — buildAnnotationsHtml (utils/AnnotationHtml.kt),
// the maskWordForCopy [screenshot: ...] skip, and the Transferable classes' flavor/getTransferData
// logic — without touching the real (headless-unfriendly) system clipboard.
class AnnotationClipboardTest {
    private fun jpegBytes(): ByteArray {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    // ── buildAnnotationsHtml ─────────────────────────────────────────────

    @Test
    fun buildAnnotationsHtmlEmbedsImageBlockAsBase64DataUri() {
        val tab = mkTab("log", "LOGCAT_example.log", emptyList()).copy(
            annotations = Annotations(
                blocks = listOf(
                    AnnBlock.Image(
                        id = "i1",
                        caption = "Crash dialog",
                        provenance = "from bugreport.zip/screen.mp4",
                        format = "jpeg",
                        bytes = byteArrayOf(1, 2, 3),
                    ),
                ),
            ),
        )

        val html = buildAnnotationsHtml(tab, AppSettings())

        assertTrue(html.contains("data:image/jpeg;base64,"))
        assertTrue(html.contains("Crash dialog"))
        // No videoFrame ⇒ no "From …" line. provenance survives on the model as the plain-text
        // clipboard fallback, but it never reaches an export (AnnBlock.Image.displayProvenance).
        assertFalse(html.contains("from bugreport.zip/screen.mp4"))
    }

    @Test
    fun imageEvidenceMarkdownAndRichPreviewKeepCaptionSourceThenImageOrder() {
        val tab = mkTab("log", "LOGCAT_example.log", emptyList()).copy(
            annotations = Annotations(
                blocks = listOf(
                    AnnBlock.Image(
                        id = "i1",
                        caption = "Crash dialog",
                        provenance = "From bugreport.zip/screen.mp4",
                        format = "jpeg",
                        bytes = byteArrayOf(1, 2, 3),
                        videoFrame = VideoFrameReference(
                            source = VideoSource.LocalFile("/videos/screen.mp4"),
                            sourceLabel = "bugreport.zip/screen.mp4",
                            positionMs = 1_234L,
                        ),
                    ),
                ),
            ),
        )

        // INDENTED explicitly: this test is about caption/source/image ordering, not about C2's
        // JIRA_JAVA-gated `!frame-0N.jpg!` anchors — buildMd()'s default AppSettings() is JIRA_JAVA,
        // which would emit an anchor here instead of the plain "[screenshot]" marker this asserts.
        val markdown = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED))
        val html = buildAnnotationsHtml(tab)

        val markdownCaption = markdown.indexOf("Crash dialog")
        val markdownSource = markdown.indexOf("From bugreport.zip/screen.mp4")
        val markdownImage = markdown.indexOf("[screenshot]")
        assertTrue(markdownCaption >= 0 && markdownSource >= 0 && markdownImage >= 0)
        assertTrue(markdownCaption < markdownSource)
        assertTrue(markdownSource < markdownImage)

        val htmlCaption = html.indexOf("Crash dialog")
        val htmlSource = html.indexOf("From bugreport.zip/screen.mp4")
        val htmlImage = html.indexOf("<img ")
        assertTrue(htmlCaption >= 0 && htmlSource >= 0 && htmlImage >= 0)
        assertTrue(htmlCaption < htmlSource)
        assertTrue(htmlSource < htmlImage)
    }

    @Test
    fun buildAnnotationsHtmlEscapesHtmlSpecialCharsInNoteText() {
        val tab = mkTab("log", "LOGCAT_example.log", emptyList()).copy(
            annotations = Annotations(blocks = listOf(AnnBlock.Note("n1", "<script>alert(1)</script> & \"quoted\""))),
        )

        val html = buildAnnotationsHtml(tab, AppSettings())

        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("&quot;quoted&quot;"))
    }

    @Test
    fun buildAnnotationsHtmlIncludesPrefixAndSuffix() {
        val tab = mkTab("log", "LOGCAT_example.log", emptyList()).copy(
            annotations = Annotations(prefix = "Summary line", suffix = "Next action", blocks = emptyList()),
        )

        val html = buildAnnotationsHtml(tab, AppSettings())

        assertTrue(html.contains("Summary line"))
        assertTrue(html.contains("Next action"))
    }

    @Test
    fun buildAnnotationsHtmlRendersLogRefRowsAndNumbering() {
        val tab = mkTab(
            "log",
            "LOGCAT_example.log",
            listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "boot complete")),
        ).copy(
            annotations = Annotations(blocks = listOf(AnnBlock.LogRef("r1", listOf(1), "Evidence"))),
        )

        val html = buildAnnotationsHtml(tab, AppSettings(numberAnnotationBlocks = true))

        assertTrue(html.contains("1. Evidence"))
        assertTrue(html.contains("boot complete"))
    }

    // ── maskWordForCopy: [screenshot: ...] marker skip ──────────────────

    @Test
    fun maskWordForCopyPreservesScreenshotMarkers() {
        val settings = AppSettings(maskWordOnCopy = true, copyMaskRules = listOf(CopyMaskRule("java", "j*ava")))
        val text = "Repro steps\n[screenshot: from bugreport.zip/java-crash.mp4]\nSaw java crash"

        val result = maskWordForCopy(text, settings)

        assertTrue(result.contains("[screenshot: from bugreport.zip/java-crash.mp4]"))
        assertTrue(result.contains("Saw j*ava crash"))
    }

    // ── ImageTransferable ─────────────────────────────────────────────────

    @Test
    fun imageTransferableOffersImageAndStringFlavorsForValidBytes() {
        val t = ImageTransferable(jpegBytes(), "from bugreport.zip/screen.mp4")

        assertTrue(t.isDataFlavorSupported(DataFlavor.imageFlavor))
        assertTrue(t.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertTrue(t.getTransferData(DataFlavor.imageFlavor) is Image)
        assertEquals("from bugreport.zip/screen.mp4", t.getTransferData(DataFlavor.stringFlavor))
    }

    @Test
    fun imageTransferableFallsBackToStringOnlyForUndecodableBytes() {
        val t = ImageTransferable(byteArrayOf(1, 2, 3), "from bugreport.zip/screen.mp4")

        assertFalse(t.isDataFlavorSupported(DataFlavor.imageFlavor))
        assertTrue(t.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertEquals("from bugreport.zip/screen.mp4", t.getTransferData(DataFlavor.stringFlavor))
        assertFailsWith<UnsupportedFlavorException> { t.getTransferData(DataFlavor.imageFlavor) }
    }

    @Test
    fun clipboardImageFlavorCanBeConvertedIntoPortableImageBytes() {
        val encoded = imageBytesFromTransferable(ImageTransferable(jpegBytes(), "clipboard image"))

        requireNotNull(encoded)
        assertTrue(ImageIO.read(encoded.inputStream()) != null)
    }

    // ── HtmlTransferable ─────────────────────────────────────────────────

    @Test
    fun htmlTransferableOffersHtmlAndStringFlavors() {
        val t = HtmlTransferable("<p>hi</p>", "hi")

        assertTrue(t.isDataFlavorSupported(HtmlTransferable.HTML_FLAVOR))
        assertTrue(t.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertEquals("<p>hi</p>", t.getTransferData(HtmlTransferable.HTML_FLAVOR))
        assertEquals("hi", t.getTransferData(DataFlavor.stringFlavor))
        assertFailsWith<UnsupportedFlavorException> { t.getTransferData(DataFlavor.imageFlavor) }
    }
}
