package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.AnnotationCopyFormat
import com.indagium.model.AnnotationLogBlockStyle
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.model.CopyMaskRule
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.VideoFrameReference
import com.indagium.model.VideoSource
import com.indagium.ui.HtmlTransferable
import com.indagium.ui.ImageTransferable
import com.indagium.ui.annotationClipboardTransferable
import com.indagium.ui.imageBytesFromTransferable
import com.indagium.ui.maskWordForCopy
import com.indagium.ui.mkTab
import com.indagium.utils.buildAnnotationsHtml
import com.indagium.utils.buildMd
import com.indagium.utils.annotationMarkdownToJiraWiki
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
        val markdownSource = markdown.indexOf("From screen.mp4 @ ")
        val markdownImage = markdown.indexOf("[screenshot]")
        assertTrue(markdownCaption >= 0 && markdownSource >= 0 && markdownImage >= 0)
        assertTrue(markdownCaption < markdownSource)
        assertTrue(markdownSource < markdownImage)

        val htmlCaption = html.indexOf("Crash dialog")
        val htmlSource = html.indexOf("From screen.mp4 @ ")
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

        assertTrue(html.contains("<ol><li>Evidence</li></ol>"))
        assertTrue(html.contains("boot complete"))
    }

    @Test
    fun buildAnnotationsHtmlPreservesCaptionMarkdownWithoutBoldingPlainText() {
        val tab = mkTab("log", "LOGCAT_example.log", emptyList()).copy(
            annotations = Annotations(
                blocks = listOf(
                    AnnBlock.LogRef("r1", emptyList(), "**Important** evidence with *emphasis*"),
                    AnnBlock.Image("i1", "**Screenshot** evidence with *emphasis*", "pasted", "jpeg", byteArrayOf(1)),
                ),
            ),
        )

        val html = buildAnnotationsHtml(tab)

        val renderedCaption = "<p><strong>Important</strong> evidence with <em>emphasis</em></p>"
        assertTrue(html.contains(renderedCaption))
        assertTrue(html.contains("<p><strong>Screenshot</strong> evidence with <em>emphasis</em></p>"))
        assertFalse(html.contains("<p><strong><strong>Important"))
        assertFalse(html.contains("<p><strong><strong>Screenshot"))
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

    @Test
    fun copyFormatsExposeExpectedFlavorsAndCloudMarkdownFallbackWithoutChangingDefault() {
        val tab = mkTab("log", "LOGCAT_example.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Car_SDK", "ready"),
        )).copy(
            annotations = Annotations(
                blocks = listOf(
                    AnnBlock.Note("n1", "**MSG_DONE**"),
                    AnnBlock.LogRef("l1", listOf(1), "Captured logs"),
                    AnnBlock.Image("i1", "Screenshot", "pasted", "jpeg", byteArrayOf(1, 2)),
                ),
            ),
        )
        val settings = AppSettings(annotationCopyFormat = AnnotationCopyFormat.JIRA_CLOUD)
        val html = buildAnnotationsHtml(tab, settings)

        val cloud = annotationClipboardTransferable(tab, settings, AnnotationCopyFormat.JIRA_CLOUD, html)
        assertTrue(cloud.isDataFlavorSupported(HtmlTransferable.HTML_FLAVOR))
        assertTrue(cloud.isDataFlavorSupported(DataFlavor.stringFlavor))
        val richHtml = cloud.getTransferData(HtmlTransferable.HTML_FLAVOR) as String
        assertTrue(richHtml.contains("<strong>MSG_DONE</strong>"))
        assertTrue(richHtml.contains("<p>Captured logs</p>"))
        assertFalse(richHtml.contains("<p><strong>Captured logs</strong></p>"))
        assertTrue(richHtml.contains("<pre><code>"), "log refs must remain preformatted in the text/html clipboard flavor")
        val fallback = cloud.getTransferData(DataFlavor.stringFlavor) as String
        assertTrue(fallback.contains("```java"))
        assertTrue(fallback.contains("Car_SDK"))
        assertTrue(fallback.contains("ready"))
        assertTrue(fallback.contains("![Screenshot]("))

        val wiki = annotationClipboardTransferable(tab, settings, AnnotationCopyFormat.JIRA_WIKI)
        val wikiText = wiki.getTransferData(DataFlavor.stringFlavor) as String
        assertTrue(wikiText.contains("*MSG_DONE*"))
        assertTrue(wikiText.contains("{code:java}"))

        val markdown = annotationClipboardTransferable(tab, settings, AnnotationCopyFormat.MARKDOWN)
        val markdownText = markdown.getTransferData(DataFlavor.stringFlavor) as String
        assertTrue(markdownText.contains("**MSG_DONE**"))
        assertFalse(markdownText.contains("{code:java}"))

        val htmlSource = annotationClipboardTransferable(tab, settings, AnnotationCopyFormat.HTML, html)
        assertEquals(html, htmlSource.getTransferData(DataFlavor.stringFlavor))
        assertFalse(htmlSource.isDataFlavorSupported(HtmlTransferable.HTML_FLAVOR))
        assertEquals(AnnotationCopyFormat.JIRA_CLOUD, settings.annotationCopyFormat)
    }

    @Test
    fun richClipboardHtmlMatchesPreviewProseAndKeepsMarkdownAndLogsAsCodeBlocks() {
        val tab = mkTab("log", "LOGCAT_example.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Car_SDK", "ready <now>"),
        )).copy(
            annotations = Annotations(
                prefix = "Context **ready**",
                blocks = listOf(
                    AnnBlock.Note("n1", "Before\n\n```kotlin\nif (ready) {\n  finish()\n}\n```"),
                    AnnBlock.LogRef("l1", listOf(1), "Routine **bold** caption"),
                ),
                suffix = "Next *steps*",
            ),
        )
        val html = buildAnnotationsHtml(tab)
        val cloud = annotationClipboardTransferable(
            tab,
            AppSettings(annotationCopyFormat = AnnotationCopyFormat.JIRA_CLOUD),
            AnnotationCopyFormat.JIRA_CLOUD,
            html,
        )

        assertTrue(html.contains("<p>Context <strong>ready</strong></p>"))
        assertTrue(html.contains("<p>Routine <strong>bold</strong> caption</p>"))
        assertFalse(html.contains("<strong>Routine"))
        assertTrue(html.contains("<pre><code class=\"language-kotlin\">if (ready) {\n  finish()\n}\n</code></pre>"))
        assertTrue(html.contains("<pre><code>"))
        assertTrue(html.contains("ready &lt;now&gt;"))
        assertTrue(html.contains("<p>Next <em>steps</em></p>"))
        assertEquals(html, cloud.getTransferData(HtmlTransferable.HTML_FLAVOR))
        val fallback = cloud.getTransferData(DataFlavor.stringFlavor) as String
        assertTrue(fallback.contains("```kotlin"), "plain-text fallback should retain the note code fence")
        assertTrue(fallback.contains("```java"), "Cloud fallback should fence log rows")
    }

    @Test
    fun markdownHtmlFormatsInlineMarkupEscapesContentAndRejectsUnsafeUrls() {
        val tab = mkTab("log", "notes.log", emptyList()).copy(
            annotations = Annotations(
                blocks = listOf(
                    AnnBlock.Note(
                        "n1",
                        "**MSG_DONE** and `**literal** <code>` <script> & [unsafe](javascript:alert(1)) " +
                            "![bad](data:text/html;base64,PHNj) ![ok](data:image/png;base64,YQ==)",
                    ),
                    AnnBlock.Image("i1", "app image", "pasted", "jpeg", byteArrayOf(1, 2)),
                ),
            ),
        )

        val html = buildAnnotationsHtml(tab)

        assertTrue(html.contains("<strong>MSG_DONE</strong>"))
        assertTrue(html.contains("<code>**literal** &lt;code&gt;</code>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("javascript:"))
        assertFalse(html.contains("data:text/html"))
        assertTrue(html.contains("data:image/png;base64,YQ=="))
        assertTrue(html.contains("data:image/jpeg;base64,AQI="))

        val malformedImageType = buildAnnotationsHtml(
            tab.copy(annotations = Annotations(blocks = listOf(AnnBlock.Image("bad", "", "", "jpeg\" onerror=\"alert(1)", byteArrayOf(1))))),
        )
        assertTrue(malformedImageType.contains("data:image/jpeg;base64,AQ=="))
        assertFalse(malformedImageType.contains("onerror"))

        val codeHtml = buildAnnotationsHtml(
            tab.copy(annotations = Annotations(blocks = listOf(AnnBlock.Note("code", "```text\n  **literal**\n\n```")))),
        )
        assertTrue(codeHtml.contains("<code class=\"language-text\">  **literal**\n\n</code>"))
    }

    @Test
    fun wikiProseKeepsInlineCodeLiteralAndConvertsCloudBold() {
        val wiki = annotationMarkdownToJiraWiki("`**literal**` **MSG_DONE**\n```kotlin\n**fenced literal**\n```\n")

        assertTrue(wiki.startsWith("{{**literal**}} *MSG_DONE*"))
        assertTrue(wiki.contains("{code:kotlin}\n**fenced literal**\n{code}"))
    }
}
