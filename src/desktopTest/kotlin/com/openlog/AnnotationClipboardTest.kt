package com.openlog

import com.openlog.model.AnnBlock
import com.openlog.model.Annotations
import com.openlog.model.AppSettings
import com.openlog.model.CopyMaskRule
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.ui.HtmlTransferable
import com.openlog.ui.ImageTransferable
import com.openlog.ui.maskWordForCopy
import com.openlog.ui.mkTab
import com.openlog.utils.buildAnnotationsHtml
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
        assertTrue(html.contains("from bugreport.zip/screen.mp4"))
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
