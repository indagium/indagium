package com.indagium.proofread

/** Spaces protect technical/Markdown content while keeping every original UTF-16 offset and line break. */
internal object ProofreadingText {
    const val MAX_CHUNK_LENGTH = 4_000

    data class Chunk(val offset: Int, val text: String)

    private val comments = Regex("<!--[\\s\\S]*?(?:-->|$)")
    private val html = Regex("<[^>\\r\\n]+>")
    private val destinations = Regex("!?\\[[^]\\r\\n]*]\\((?:[^()\\r\\n]|\\([^()\\r\\n]*\\))*\\)")
    private val referenceDestination = Regex("(?m)^ {0,3}\\[[^]\\r\\n]+]:[^\\r\\n]*")
    private val urls = Regex("(?i)\\b(?:https?://|ftp://|www\\.)[^\\s<>]+")
    private val paths = Regex("(?<![\\p{L}\\p{N}])(?:[A-Za-z]:[\\\\/]|~?/|\\.\\.?/)[^\\s<>]+")
    private val email = Regex("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[A-Za-z]{2,}")
    private val entities = Regex("&(?:[A-Za-z]+|#\\d+|#x[0-9A-Fa-f]+);")
    private val tokens = Regex("[\\p{L}\\p{N}_][\\p{L}\\p{N}_./\\\\:-]*")
    private val camelCase = Regex("[a-z][A-Z]")
    private val letterDigit = Regex("(?:\\p{L}\\p{N}|\\p{N}\\p{L})")
    private val words = Regex("[\\p{L}\\p{M}]+")
    private val lineSyntax = Regex("(?m)^ {0,3}(?:#{1,6}[ \\t]+|>[ \\t]*|[-+*][ \\t]+|\\d+[.)][ \\t]+)")
    private val fenceOpening = Regex("^ {0,3}(`{3,}|~{3,}).*$")
    private val technicalSeparators = setOf('_', '/', '\\', '.')

    fun chunks(markdown: String): List<Chunk> {
        val prepared = mask(markdown)
        return buildList {
            var start = 0
            while (start < prepared.length) {
                var end = minOf(start + MAX_CHUNK_LENGTH, prepared.length)
                if (end < prepared.length) end = boundary(prepared, start, end)
                val text = prepared.substring(start, end)
                if (text.any(Char::isLetter)) add(Chunk(start, text))
                start = end
            }
        }
    }

    /** A replacement spanning excluded syntax/content could corrupt Markdown even if the checker only saw spaces. */
    fun isProseRange(source: String, chunk: Chunk, start: Int, end: Int): Boolean =
        (start until end).all { index ->
            val original = source[chunk.offset + index]
            original == chunk.text[index] || original.isWhitespace()
        }

    internal fun mask(markdown: String): String {
        val chars = markdown.toCharArray()

        fun hide(start: Int, end: Int) {
            for (index in start until end) if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
        }

        fun hideMatches(regex: Regex) {
            regex.findAll(String(chars)).forEach { hide(it.range.first, it.range.last + 1) }
        }
        hideMatches(comments)
        maskCodeBlocks(markdown, ::hide)
        maskInlineCode(String(chars), ::hide)
        hideMatches(referenceDestination)
        destinations.findAll(String(chars)).forEach { match ->
            val closeLabel = match.value.indexOf("](")
            if (closeLabel >= 0) hide(match.range.first + closeLabel, match.range.last + 1)
        }
        listOf(html, urls, paths, email, entities).forEach(::hideMatches)
        tokens.findAll(String(chars)).forEach { match ->
            if (isTechnicalToken(match.value)) hide(match.range.first, match.range.last + 1)
        }
        words.findAll(String(chars)).forEach { match ->
            if (hasNonLatinLetter(match.value)) hide(match.range.first, match.range.last + 1)
        }
        hideMatches(lineSyntax)
        for (index in chars.indices) if (chars[index] in "*_~[]") chars[index] = ' '
        return String(chars)
    }

    private fun isTechnicalToken(value: String): Boolean {
        val token = value.trimEnd('.', ':', '-')
        val hasTechnicalSeparator = token.any { it in technicalSeparators }
        return hasTechnicalSeparator || camelCase.containsMatchIn(token) || letterDigit.containsMatchIn(token)
    }

    private fun maskCodeBlocks(text: String, hide: (Int, Int) -> Unit) {
        var offset = 0
        var fenceChar: Char? = null
        var fenceLength = 0
        for (line in text.splitToSequence('\n')) {
            val opening = fenceOpening.matchEntire(line.removeSuffix("\r"))
            if (fenceChar != null) {
                hide(offset, offset + line.length)
                val content = line.trimStart().trimEnd('\r', ' ', '\t')
                if (content.length >= fenceLength && content.all { it == fenceChar }) fenceChar = null
            } else if (opening != null) {
                val marker = opening.groupValues[1]
                fenceChar = marker.first()
                fenceLength = marker.length
                hide(offset, offset + line.length)
            } else if (line.startsWith("    ") || line.startsWith('\t')) {
                hide(offset, offset + line.length)
            }
            offset += line.length + 1
        }
    }

    private fun maskInlineCode(text: String, hide: (Int, Int) -> Unit) {
        var index = 0
        while (index < text.length) {
            if (text[index] != '`') {
                index++
                continue
            }
            val start = index
            while (index < text.length && text[index] == '`') index++
            val delimiter = text.substring(start, index)
            var close = text.indexOf(delimiter, index)
            while (close >= 0 && text.getOrNull(close + delimiter.length) == '`') {
                close = text.indexOf(delimiter, close + delimiter.length + 1)
            }
            val end = if (close >= 0) close + delimiter.length else text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length
            hide(start, end)
            index = end
        }
    }

    private fun hasNonLatinLetter(word: String): Boolean = word.codePoints().anyMatch { point ->
        Character.isLetter(point) && Character.UnicodeScript.of(point) != Character.UnicodeScript.LATIN
    }

    private fun boundary(text: String, start: Int, limit: Int): Int {
        val lower = start + MAX_CHUNK_LENGTH / 2
        for (index in limit - 1 downTo lower) {
            if (text[index] == '\n' || (text[index].isWhitespace() && text.getOrNull(index - 1) in listOf('.', '!', '?'))) {
                return index + 1
            }
        }
        for (index in limit - 1 downTo lower) if (text[index].isWhitespace()) return index + 1
        return if (text[limit - 1].isHighSurrogate()) limit - 1 else limit
    }
}
