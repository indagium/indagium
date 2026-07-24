package com.openlog.utils

import com.openlog.model.*
import java.util.Base64

// HTML counterpart to buildMd() (Filter.kt) for the "Copy rich preview" clipboard path
// (ui/AppState.copyRichPreview). Mirrors the same block order/logic as buildMd() and
// AnnotationPanel's RenderedMarkdownPreview — prefix, numbered blocks, suffix — but emits real
// markup instead of plain text: escaped paragraphs for Note/LogRef content, and each AnnBlock.
// Image as an inline <img src="data:image/…;base64,…"> so a paste into a rich-text target (e.g.
// Jira Cloud's comment editor) shows the actual picture, not just a provenance marker. Unlike
// copyAnn's plain-text path, this output is NOT run through maskWordForCopy — masking a word
// inside an escaped fragment is safe, but the risk of a mask rule's word-boundary regex matching
// inside a base64 image payload (however unlikely) isn't worth it for a feature whose plain-text
// fallback is already masked.
fun buildAnnotationsHtml(tab: LogTab, settings: AppSettings = AppSettings()): String = buildString {
    append("<div>")
    if (tab.annotations.prefix.isNotBlank()) {
        append("<p>").append(escapeHtmlMultiline(tab.annotations.prefix)).append("</p>")
    }
    var blockNumber = 1
    for (block in tab.annotations.blocks) {
        blockNumber = when (block) {
            is AnnBlock.Note -> appendNoteHtml(block, settings, blockNumber)
            is AnnBlock.LogRef -> appendLogRefHtml(tab, block, settings, blockNumber)
            is AnnBlock.Image -> appendImageHtml(block, settings, blockNumber)
        }
    }
    if (tab.annotations.suffix.isNotBlank()) {
        append("<hr><p>").append(escapeHtmlMultiline(tab.annotations.suffix)).append("</p>")
    }
    append("</div>")
}

// Mirrors buildMd()'s AnnBlock.Note branch: only emitted (and only advances the numbering) when
// the note has non-blank text.
private fun StringBuilder.appendNoteHtml(block: AnnBlock.Note, settings: AppSettings, blockNumber: Int): Int {
    if (block.text.isBlank()) return blockNumber
    val prefix = if (settings.numberAnnotationBlocks) "$blockNumber. " else ""
    append("<p>").append(escapeHtmlMultiline(prefix + block.text)).append("</p>")
    return if (settings.numberAnnotationBlocks) blockNumber + 1 else blockNumber
}

// Mirrors RenderedMarkdownPreview's AnnBlock.LogRef branch (caption heading shown whenever it's
// non-blank OR numbering is on, "From <file>" sub-line for compare-mode sources, then the raw log
// rows) rather than buildMd()'s Jira-flavored {code:java} fencing — this output is real HTML, so
// a <pre> block is the equivalent of that fence.
private fun StringBuilder.appendLogRefHtml(tab: LogTab, block: AnnBlock.LogRef, settings: AppSettings, blockNumber: Int): Int {
    var num = blockNumber
    if (block.caption.isNotBlank() || settings.numberAnnotationBlocks) {
        val prefix = if (settings.numberAnnotationBlocks) "${num++}. " else ""
        append("<p><b>").append(escapeHtmlMultiline(prefix + block.caption)).append("</b></p>")
    }
    if (block.sourceFilename != null) {
        append("<p><i>").append(escapeHtml("From ${block.sourceFilename}")).append("</i></p>")
    }
    val rows = block.sourceEntries ?: block.logIds.mapNotNull { tab.rmap[it] }
    append("<pre>")
    rows.forEach { row -> appendLine(escapeHtml("${row.ts}  ${row.level.key}/${row.tag}  ${row.msg}")) }
    append("</pre>")
    return num
}

// Mirrors RenderedMarkdownPreview's AnnBlock.Image branch, but where that composable decodes
// block.bytes into an ImageBitmap for on-screen display, this emits the same bytes as a base64
// data URI — the whole point of "Copy rich preview" is that the picture itself lands on the
// clipboard, not just a reference to it. Raw Base64 encoder (not the .b64()/unb64() helpers used
// elsewhere in this codebase for token persistence) since those round-trip through UTF-8 text and
// would corrupt binary JPEG bytes.
private fun StringBuilder.appendImageHtml(block: AnnBlock.Image, settings: AppSettings, blockNumber: Int): Int {
    var num = blockNumber
    if (block.caption.isNotBlank() || settings.numberAnnotationBlocks) {
        val prefix = if (settings.numberAnnotationBlocks) "${num++}. " else ""
        append("<p><b>").append(escapeHtmlMultiline(prefix + block.caption)).append("</b></p>")
    }
    append("<p><i>").append(escapeHtml(block.videoFrame?.provenanceLabel ?: block.provenance)).append("</i></p>")
    val encoded = Base64.getEncoder().encodeToString(block.bytes)
    append("<p><img src=\"data:image/${block.format};base64,").append(encoded).append("\" style=\"max-width:100%\"></p>")
    return num
}

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

// For text that may contain user-typed line breaks (notes, prefix/suffix) — HTML collapses raw
// newlines, so they need an explicit <br> once the surrounding text has been entity-escaped.
private fun escapeHtmlMultiline(text: String): String = escapeHtml(text).replace("\n", "<br>")
