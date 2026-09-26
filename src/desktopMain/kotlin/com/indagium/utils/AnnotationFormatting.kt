package com.indagium.utils

/** Render the Markdown used in annotation prose as safe clipboard HTML. */
internal fun annotationMarkdownToHtml(markdown: String): String = buildString {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val paragraph = mutableListOf<String>()
    var fenceMarker: String? = null
    var fenceLanguage = ""
    val code = StringBuilder()
    var listTag: String? = null

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            append("<p>")
            append(paragraph.joinToString("<br>") { annotationInlineMarkdownToHtml(it) })
            append("</p>")
            paragraph.clear()
        }
    }

    fun closeList() {
        listTag?.let { append("</$it>") }
        listTag = null
    }

    fun flushCode() {
        val languageClass = fenceLanguage.takeIf { it.isNotBlank() }
            ?.let { " class=\"language-${escapeHtmlAttribute(it)}\"" }.orEmpty()
        append("<pre><code$languageClass>")
        append(escapeHtmlText(code.toString()))
        append("</code></pre>")
        code.setLength(0)
        fenceLanguage = ""
    }

    for (line in lines) {
        val trimmed = line.trim()
        val marker = trimmed.takeWhile { it == '`' || it == '~' }
        if (fenceMarker != null) {
            if (marker.length >= 3 && marker.first() == fenceMarker.first()) {
                flushCode()
                fenceMarker = null
            } else {
                code.append(line).append('\n')
            }
            continue
        }
        if (marker.length >= 3) {
            flushParagraph()
            closeList()
            fenceMarker = marker
            fenceLanguage = trimmed.drop(marker.length).trim()
            continue
        }
        if (trimmed.isEmpty()) {
            flushParagraph()
            closeList()
            continue
        }
        val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)
        if (heading != null) {
            flushParagraph()
            closeList()
            val level = heading.groupValues[1].length
            append("<h$level>").append(annotationInlineMarkdownToHtml(heading.groupValues[2])).append("</h$level>")
            continue
        }
        val list = Regex("^(?:([-+*])\\s+|(\\d+)\\.\\s+)(.*)$").matchEntire(trimmed)
        if (list != null) {
            flushParagraph()
            val tag = if (list.groupValues[2].isEmpty()) "ul" else "ol"
            if (listTag != tag) {
                closeList()
                listTag = tag
                append("<$tag>")
            }
            append("<li>").append(annotationInlineMarkdownToHtml(list.groupValues[3])).append("</li>")
            continue
        }
        val quote = Regex("^>\\s?(.*)$").matchEntire(trimmed)
        if (quote != null) {
            flushParagraph()
            closeList()
            append("<blockquote><p>").append(annotationInlineMarkdownToHtml(quote.groupValues[1])).append("</p></blockquote>")
            continue
        }
        if (trimmed.matches(Regex("(?:-{3,}|_{3,}|\\*{3,})"))) {
            flushParagraph()
            closeList()
            append("<hr>")
            continue
        }
        closeList()
        paragraph += line
    }
    flushParagraph()
    closeList()
    if (fenceMarker != null) flushCode()
}

/** Convert Markdown prose into Jira wiki syntax without altering fenced/code-block contents. */
internal fun annotationMarkdownToJiraWiki(markdown: String): String {
    val output = StringBuilder()
    var markdownFence: Char? = null
    var wikiCode = false
    for (line in markdown.replace("\r\n", "\n").split('\n')) {
        val trimmed = line.trimStart()
        val fence = trimmed.takeWhile { it == '`' || it == '~' }
        if (markdownFence != null) {
            if (fence.length >= 3 && fence.first() == markdownFence) {
                output.appendLine("{code}")
                markdownFence = null
            } else {
                output.appendLine(line)
            }
            continue
        }
        if (wikiCode) {
            output.appendLine(line)
            if (trimmed == "{code}") wikiCode = false
            continue
        }
        if (fence.length >= 3) {
            val language = trimmed.drop(fence.length).trim()
            output.appendLine(if (language.isBlank()) "{code}" else "{code:$language}")
            markdownFence = fence.first()
            continue
        }
        if (trimmed.startsWith("{code")) {
            wikiCode = true
            output.appendLine(line)
            continue
        }
        output.appendLine(convertWikiProseLine(line))
    }
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

internal fun annotationInlineMarkdownToHtml(source: String): String = buildString {
    var i = 0
    while (i < source.length) {
        val image = source[i] == '!' && source.getOrNull(i + 1) == '['
        val linkStart = if (image) i + 1 else i
        if (source.getOrNull(linkStart) == '[') {
            val labelEnd = source.indexOf(']', linkStart + 1)
            val urlStart = if (labelEnd >= 0 && source.getOrNull(labelEnd + 1) == '(') labelEnd + 2 else -1
            val urlEnd = if (urlStart >= 0) source.indexOf(')', urlStart) else -1
            if (labelEnd >= 0 && urlEnd > urlStart) {
                val label = source.substring(linkStart + 1, labelEnd)
                val url = source.substring(urlStart, urlEnd)
                val safeUrl = safeMarkdownUrl(url, image)
                if (safeUrl == null) {
                    append(annotationInlineMarkdownToHtml(label))
                } else if (image) {
                    append("<img src=\"").append(escapeHtmlAttribute(safeUrl)).append("\" alt=\"")
                        .append(escapeHtmlAttribute(label)).append("\">")
                } else {
                    append("<a href=\"").append(escapeHtmlAttribute(safeUrl)).append("\">")
                        .append(annotationInlineMarkdownToHtml(label)).append("</a>")
                }
                i = urlEnd + 1
                continue
            }
        }
        val delimiter = listOf("**", "__", "~~", "`", "*", "_").firstOrNull { source.startsWith(it, i) }
        if (delimiter != null && (i == 0 || source[i - 1] != '\\')) {
            val end = source.indexOf(delimiter, i + delimiter.length)
            if (end > i + delimiter.length) {
                val content = source.substring(i + delimiter.length, end)
                when (delimiter) {
                    "**", "__" -> append("<strong>").append(annotationInlineMarkdownToHtml(content)).append("</strong>")
                    "~~" -> append("<del>").append(annotationInlineMarkdownToHtml(content)).append("</del>")
                    "`" -> append("<code>").append(escapeHtmlText(content)).append("</code>")
                    else -> append("<em>").append(annotationInlineMarkdownToHtml(content)).append("</em>")
                }
                i = end + delimiter.length
                continue
            }
        }
        when (source[i]) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            '\\' -> if (i + 1 < source.length && source[i + 1] in "\\`*_{}[]()#+-.!>~") {
                append(escapeHtmlText(source[i + 1].toString()))
                i++
            } else append('\\')
            else -> append(source[i])
        }
        i++
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
