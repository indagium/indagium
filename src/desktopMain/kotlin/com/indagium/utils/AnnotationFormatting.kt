package com.indagium.utils

private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
private val LIST_ITEM_REGEX = Regex("^(?:([-+*])\\s+|(\\d+)\\.\\s+)(.*)$")
private val QUOTE_REGEX = Regex("^>\\s?(.*)$")
private val HORIZONTAL_RULE_REGEX = Regex("(?:-{3,}|_{3,}|\\*{3,})")
private const val MAX_LIST_START = 1_000_000

/** Block-level Markdown -> HTML conversion state for [annotationMarkdownToHtml]. Broken out of
 *  that function (rather than left as one big loop body) purely to keep each line type's handling
 *  in its own low-complexity function — see the per-line `handle*` functions below, tried in
 *  Markdown precedence order from [processLine]. */
private class MarkdownBlockState(private val out: StringBuilder) {
    private val paragraph = mutableListOf<String>()
    private var fenceMarker: String? = null
    private var fenceLanguage = ""
    private val code = StringBuilder()
    private var listTag: String? = null

    private fun flushParagraph() {
        if (paragraph.isEmpty()) return
        out.append("<p>")
        out.append(paragraph.joinToString("<br>") { annotationInlineMarkdownToHtml(it) })
        out.append("</p>")
        paragraph.clear()
    }

    private fun closeList() {
        listTag?.let { out.append("</$it>") }
        listTag = null
    }

    private fun flushCode() {
        val languageClass = fenceLanguage.takeIf { it.isNotBlank() }
            ?.let { " class=\"language-${escapeHtmlAttribute(it)}\"" }.orEmpty()
        out.append("<pre><code$languageClass>")
        out.append(escapeHtmlText(code.toString()))
        out.append("</code></pre>")
        code.setLength(0)
        fenceLanguage = ""
    }

    /** Inside, or opening, a fenced code block. */
    private fun handleFenceLine(line: String, trimmed: String, marker: String): Boolean {
        val currentFence = fenceMarker
        if (currentFence != null) {
            if (marker.length >= 3 && marker.first() == currentFence.first()) {
                flushCode()
                fenceMarker = null
            } else {
                code.append(line).append('\n')
            }
            return true
        }
        if (marker.length < 3) return false
        flushParagraph()
        closeList()
        fenceMarker = marker
        fenceLanguage = trimmed.drop(marker.length).trim()
        return true
    }

    private fun handleBlankLine(trimmed: String): Boolean {
        if (trimmed.isNotEmpty()) return false
        flushParagraph()
        closeList()
        return true
    }

    private fun handleHeading(trimmed: String): Boolean {
        val heading = HEADING_REGEX.matchEntire(trimmed) ?: return false
        flushParagraph()
        closeList()
        val level = heading.groupValues[1].length
        out.append("<h$level>").append(annotationInlineMarkdownToHtml(heading.groupValues[2])).append("</h$level>")
        return true
    }

    private fun handleListItem(trimmed: String): Boolean {
        val list = LIST_ITEM_REGEX.matchEntire(trimmed) ?: return false
        flushParagraph()
        val tag = if (list.groupValues[2].isEmpty()) "ul" else "ol"
        if (listTag != tag) {
            closeList()
            listTag = tag
            // A list that opens above 1 (user-authored, or a copied block's "2. " number prefix)
            // needs an explicit start, or HTML renumbers it from 1.
            val start = list.groupValues[2].toIntOrNull()?.takeIf { it in 2..MAX_LIST_START }
            out.append(if (start != null) "<$tag start=\"$start\">" else "<$tag>")
        }
        out.append("<li>").append(annotationInlineMarkdownToHtml(list.groupValues[3])).append("</li>")
        return true
    }

    private fun handleQuote(trimmed: String): Boolean {
        val quote = QUOTE_REGEX.matchEntire(trimmed) ?: return false
        flushParagraph()
        closeList()
        out.append("<blockquote><p>").append(annotationInlineMarkdownToHtml(quote.groupValues[1])).append("</p></blockquote>")
        return true
    }

    private fun handleHorizontalRule(trimmed: String): Boolean {
        if (!trimmed.matches(HORIZONTAL_RULE_REGEX)) return false
        flushParagraph()
        closeList()
        out.append("<hr>")
        return true
    }

    fun processLine(line: String) {
        val trimmed = line.trim()
        val marker = trimmed.takeWhile { it == '`' || it == '~' }
        if (handleFenceLine(line, trimmed, marker)) return
        if (handleBlankLine(trimmed)) return
        if (handleHeading(trimmed)) return
        if (handleListItem(trimmed)) return
        if (handleQuote(trimmed)) return
        if (handleHorizontalRule(trimmed)) return
        closeList()
        paragraph += line
    }

    fun finish() {
        flushParagraph()
        closeList()
        if (fenceMarker != null) flushCode()
    }
}

/** Render the Markdown used in annotation prose as safe clipboard HTML. */
internal fun annotationMarkdownToHtml(markdown: String): String = buildString {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val state = MarkdownBlockState(this)
    for (line in lines) state.processLine(line)
    state.finish()
}

/** Jira-wiki conversion state for [annotationMarkdownToJiraWiki] — same "one function per line
 *  type" shape as [MarkdownBlockState] above, and for the same reason (keeps each branch's own
 *  complexity low and the loop itself free of `continue`). */
private class JiraWikiState(private val out: StringBuilder) {
    private var markdownFence: Char? = null
    private var wikiCode = false

    private fun handleFenceLine(line: String, fence: String): Boolean {
        val currentFence = markdownFence ?: return false
        if (fence.length >= 3 && fence.first() == currentFence) {
            out.appendLine("{code}")
            markdownFence = null
        } else {
            out.appendLine(line)
        }
        return true
    }

    private fun handleWikiCodeLine(line: String, trimmed: String): Boolean {
        if (!wikiCode) return false
        out.appendLine(line)
        if (trimmed == "{code}") wikiCode = false
        return true
    }

    private fun handleFenceOpen(trimmed: String, fence: String): Boolean {
        if (fence.length < 3) return false
        val language = trimmed.drop(fence.length).trim()
        out.appendLine(if (language.isBlank()) "{code}" else "{code:$language}")
        markdownFence = fence.first()
        return true
    }

    private fun handleWikiCodeOpen(line: String, trimmed: String): Boolean {
        if (!trimmed.startsWith("{code")) return false
        wikiCode = true
        out.appendLine(line)
        return true
    }

    fun processLine(line: String) {
        val trimmed = line.trimStart()
        val fence = trimmed.takeWhile { it == '`' || it == '~' }
        if (handleFenceLine(line, fence)) return
        if (handleWikiCodeLine(line, trimmed)) return
        if (handleFenceOpen(trimmed, fence)) return
        if (handleWikiCodeOpen(line, trimmed)) return
        out.appendLine(convertWikiProseLine(line))
    }
}

/** Convert Markdown prose into Jira wiki syntax without altering fenced/code-block contents. */
internal fun annotationMarkdownToJiraWiki(markdown: String): String {
    val output = StringBuilder()
    val state = JiraWikiState(output)
    for (line in markdown.replace("\r\n", "\n").split('\n')) state.processLine(line)
    return output.toString().trimEnd('\n')
}

private fun convertWikiProseLine(line: String): String {
    var result = line
    val protectedCode = mutableListOf<String>()
    result = Regex("`([^`]+)`").replace(result) { match ->
        val token = "\u0000INLINE_CODE_${protectedCode.size}\u0000"
        protectedCode += "{{${match.groupValues[1]}}}"
        token
    }
    result = Regex("!\\[([^]]*)]\\(([^)]+)\\)").replace(result) { "!${it.groupValues[2]}!" }
    result = Regex("\\[([^]]+)]\\(([^)]+)\\)").replace(result) { "[${it.groupValues[1]}|${it.groupValues[2]}]" }
    result = Regex("\\*\\*(.+?)\\*\\*").replace(result) { "*${it.groupValues[1]}*" }
    result = Regex("__(.+?)__").replace(result) { "*${it.groupValues[1]}*" }
    result = Regex("~~(.+?)~~").replace(result) { "-${it.groupValues[1]}-" }
    protectedCode.forEachIndexed { index, replacement -> result = result.replace("\u0000INLINE_CODE_${index}\u0000", replacement) }
    val heading = Regex("^(#{1,6})\\s+(.*)$").matchEntire(result)
    if (heading != null) return "h${heading.groupValues[1].length}. ${heading.groupValues[2]}"
    val quote = Regex("^>\\s?(.*)$").matchEntire(result)
    if (quote != null) return "bq. ${quote.groupValues[1]}"
    if (result.matches(Regex("(?:-{3,}|_{3,}|\\*{3,})"))) return "----"
    result = Regex("^(?:[-+*])\\s+(.*)$").replace(result, "* $1")
    result = Regex("^\\d+\\.\\s+(.*)$").replace(result, "# $1")
    return result
}

private val INLINE_EMPHASIS_DELIMITERS = listOf("**", "__", "~~", "`", "*", "_")
private const val INLINE_ESCAPABLE_CHARS = "\\`*_{}[]()#+-.!>~"

/** Attempts to consume a `[label](url)`/`![alt](url)` construct starting at [i]. Returns the index
 *  to resume scanning from, or null if [i] doesn't start a well-formed link/image. */
private fun StringBuilder.tryConsumeLinkOrImage(source: String, i: Int): Int? {
    val image = source[i] == '!' && source.getOrNull(i + 1) == '['
    val linkStart = if (image) i + 1 else i
    if (source.getOrNull(linkStart) != '[') return null
    val labelEnd = source.indexOf(']', linkStart + 1)
    val urlStart = if (labelEnd >= 0 && source.getOrNull(labelEnd + 1) == '(') labelEnd + 2 else -1
    val urlEnd = if (urlStart >= 0) source.indexOf(')', urlStart) else -1
    if (labelEnd < 0 || urlEnd <= urlStart) return null
    val label = source.substring(linkStart + 1, labelEnd)
    val url = source.substring(urlStart, urlEnd)
    val safeUrl = safeMarkdownUrl(url, image)
    when {
        safeUrl == null -> append(annotationInlineMarkdownToHtml(label))
        image -> append("<img src=\"").append(escapeHtmlAttribute(safeUrl)).append("\" alt=\"")
            .append(escapeHtmlAttribute(label)).append("\">")
        else -> append("<a href=\"").append(escapeHtmlAttribute(safeUrl)).append("\">")
            .append(annotationInlineMarkdownToHtml(label)).append("</a>")
    }
    return urlEnd + 1
}

/** Attempts to consume a `**bold**`/`__bold__`/`~~strike~~`/`` `code` ``/`*em*`/`_em_` span
 *  starting at [i]. Returns the index to resume scanning from, or null if none applies. */
private fun StringBuilder.tryConsumeEmphasis(source: String, i: Int): Int? {
    val delimiter = INLINE_EMPHASIS_DELIMITERS.firstOrNull { source.startsWith(it, i) } ?: return null
    if (i != 0 && source[i - 1] == '\\') return null
    val end = source.indexOf(delimiter, i + delimiter.length)
    if (end <= i + delimiter.length) return null
    val content = source.substring(i + delimiter.length, end)
    when (delimiter) {
        "**", "__" -> append("<strong>").append(annotationInlineMarkdownToHtml(content)).append("</strong>")
        "~~" -> append("<del>").append(annotationInlineMarkdownToHtml(content)).append("</del>")
        "`" -> append("<code>").append(escapeHtmlText(content)).append("</code>")
        else -> append("<em>").append(annotationInlineMarkdownToHtml(content)).append("</em>")
    }
    return end + delimiter.length
}

/** Appends the plain/escaped char at [i] (or, for a recognized `\x` escape, just `x`). Returns the
 *  index to resume scanning from. */
private fun StringBuilder.appendEscapedChar(source: String, i: Int): Int {
    val c = source[i]
    if (c == '\\') {
        val next = source.getOrNull(i + 1)
        return if (next != null && next in INLINE_ESCAPABLE_CHARS) {
            append(escapeHtmlText(next.toString()))
            i + 2
        } else {
            append('\\')
            i + 1
        }
    }
    when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(c)
    }
    return i + 1
}

internal fun annotationInlineMarkdownToHtml(source: String): String = buildString {
    var i = 0
    while (i < source.length) {
        i = tryConsumeLinkOrImage(source, i) ?: tryConsumeEmphasis(source, i) ?: appendEscapedChar(source, i)
    }
}

/** Markdown is user-authored and this HTML is handed to rich-text editors. Keep relative URLs
 * usable, allow ordinary web/mail links, and allow only raster image data URIs for Markdown images.
 * App-generated pasted image data URIs bypass this function and are emitted by buildAnnotationsHtml. */
private fun safeMarkdownUrl(raw: String, image: Boolean): String? {
    val value = raw.trim()
    if (value.isEmpty() || value.any { it.code < 0x20 || it.code == 0x7f }) return null
    val schemeProbe = value.filterNot { it.isWhitespace() || it.code < 0x20 }
    val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(schemeProbe)?.groupValues?.get(1)?.lowercase()
        ?: return value
    return when (scheme) {
        "http", "https" -> value
        "mailto" -> if (image) null else value
        "data" -> if (image && Regex("^data:image/(?:png|gif|jpe?g|webp);base64,[A-Za-z0-9+/=]+$", RegexOption.IGNORE_CASE).matches(value)) value else null
        else -> null
    }
}

private fun escapeHtmlText(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private fun escapeHtmlAttribute(text: String): String = escapeHtmlText(text)
