package com.indagium.ui

import com.indagium.model.AnnotationCopyFormat
import com.indagium.model.AnnotationLogBlockStyle
import com.indagium.model.AppSettings
import com.indagium.model.LogTab
import com.indagium.utils.annotationMarkdownToJiraWiki
import com.indagium.utils.buildMd
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

private const val MAX_IMAGE_IMPORT_SOURCE_BYTES = 64 * 1024 * 1024

/**
 * Converts the platform's [DataFlavor.imageFlavor] payload to portable encoded bytes. Clipboard
 * images are commonly backed by native AWT image implementations, so draw into a BufferedImage
 * before encoding rather than assuming the object can be cast or serialized directly.
 */
internal fun imageBytesFromTransferable(transferable: Transferable): ByteArray? = runCatching {
    if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
    val image = transferable.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
    val width = image.getWidth(null)
    val height = image.getHeight(null)
    if (width <= 0 || height <= 0) return null
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = buffered.createGraphics()
    try {
        graphics.drawImage(image, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    ByteArrayOutputStream().use { out ->
        if (!ImageIO.write(buffered, "png", out)) return null
        out.toByteArray()
    }
}.getOrNull()

/**
 * Reads an image file dropped on Notes. The source is bounded before it reaches ImageIO so a
 * mistaken archive or raw capture cannot monopolize the UI heap; the persisted representation is
 * still normalized and hard-capped by AnnotationManager/ImageDownscale afterwards.
 */
internal fun imageBytesFromFile(file: File): ByteArray? = runCatching {
    if (!file.isFile || file.length() !in 1..MAX_IMAGE_IMPORT_SOURCE_BYTES) return null
    val bytes = file.readBytes()
    ImageIO.read(ByteArrayInputStream(bytes))?.let { bytes }
}.getOrNull()

// Two custom Transferables for the "get pictures into Jira" clipboard paths (see AppState.
// copyImageToClipboard / copyRichPreview). Kept as standalone classes — rather than building the
// Transferable inline at the clipboard.setContents() call site — specifically so
// getTransferData()/isDataFlavorSupported() can be unit-tested directly, without touching the
// real (headless-unfriendly) system clipboard.

/** Per-image "Copy image": advertises [DataFlavor.imageFlavor] (a decoded [java.awt.Image], so a
 *  rich editor — a Jira comment box, Slack, an email — uploads it as a real inline image on
 *  paste) *and* [DataFlavor.stringFlavor] as a fallback (the block's provenance string) for
 *  plain-text targets. A multi-flavor Transferable so both editor kinds get something sensible
 *  from the same clipboard write. [bytes] is decoded once, lazily, via ImageIO — if decoding
 *  fails (corrupt/unsupported blob), only the string flavor is offered. */
internal class ImageTransferable(bytes: ByteArray, private val fallbackText: String) : Transferable {
    private val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()

    override fun getTransferDataFlavors(): Array<DataFlavor> =
        if (image != null) arrayOf(DataFlavor.imageFlavor, DataFlavor.stringFlavor) else arrayOf(DataFlavor.stringFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = transferDataFlavors.any { it == flavor }

    override fun getTransferData(flavor: DataFlavor?): Any = when {
        flavor == DataFlavor.imageFlavor && image != null -> image
        flavor == DataFlavor.stringFlavor -> fallbackText
        else -> throw UnsupportedFlavorException(flavor)
    }
}

/** "Copy rich preview": advertises a `text/html` [DataFlavor] carrying [html] (built by
 *  utils/AnnotationHtml.kt's buildAnnotationsHtml — inline `<img>` data URIs and all) *and*
 *  [DataFlavor.stringFlavor] carrying [plainText] (the same masked buildMd() output the plain
 *  "Copy" button writes) as a fallback for editors that don't accept HTML paste. Whether the HTML
 *  flavor actually renders as rich content is up to the paste target — Jira Cloud's editor
 *  generally accepts it, Server/DC may not, hence the fallback. */
internal class HtmlTransferable(private val html: String, private val plainText: String) : Transferable {
    companion object {
        val HTML_FLAVOR: DataFlavor = DataFlavor("text/html;class=java.lang.String")
    }

    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(HTML_FLAVOR, DataFlavor.stringFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = transferDataFlavors.any { it == flavor }

    override fun getTransferData(flavor: DataFlavor?): Any = when (flavor) {
        HTML_FLAVOR -> html
        DataFlavor.stringFlavor -> plainText
        else -> throw UnsupportedFlavorException(flavor)
    }
}

/** Build the clipboard object for one explicit menu choice. The preference is read-only here so
 * choosing a one-off format can never mutate the saved default. [html] is required for Cloud's
 * rich flavor and the HTML-source choice; callers may omit it for plain-text formats. */
internal fun annotationClipboardTransferable(
    tab: LogTab,
    settings: AppSettings,
    format: AnnotationCopyFormat,
    html: String = "",
): Transferable {
    fun markdown(style: AnnotationLogBlockStyle): String = buildMd(tab, settings.copy(annotationLogBlockStyle = style))
    return when (format) {
        AnnotationCopyFormat.JIRA_CLOUD -> HtmlTransferable(
            html = html,
            plainText = maskWordForCopy(markdown(AnnotationLogBlockStyle.JIRA_CLOUD), settings),
        )
        AnnotationCopyFormat.JIRA_WIKI -> StringSelection(
            maskWordForCopy(
                annotationMarkdownToJiraWiki(markdown(AnnotationLogBlockStyle.JIRA_JAVA)),
                settings,
            ),
        )
        AnnotationCopyFormat.MARKDOWN -> StringSelection(
            maskWordForCopy(markdown(AnnotationLogBlockStyle.INDENTED), settings),
        )
        AnnotationCopyFormat.HTML -> StringSelection(html)
    }
}
