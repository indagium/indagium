package com.indagium.ui

import com.indagium.diagram3.Seq3Dialect

/*
 * Opener / closer matching of the PlantUML / Mermaid source editor, pure and UI-free so it can be unit
 * tested. With the caret on the keyword of a construct, the editor highlights the keyword and its
 * partners, the way an IDE highlights matching brackets:
 * - a block opener (`loop`, `alt`, `opt`, `par`, `critical`, `break`, `group`, Mermaid's `rect`), every
 *   `else` / `and` / `option` divider of that block and its `end` are one group, whichever of them
 *   the caret is on;
 * - `activate X` and the `deactivate X` that closes it are one group; the whole statement counts as
 *   the keyword for the caret (anywhere on the line), and the mark covers the keyword and the name.
 * A group that has no closing partner (a block without `end`, an `activate` never deactivated) and a
 * closer that has no opener (an `end` outside every block, a `deactivate` with nothing activated) are
 * marked as unbalanced instead, so the user sees what is missing.
 *
 * The pairs come from the same walk as the indentation (Seq3SourceIndent.kt), so they always agree
 * with where the editor indents a closer. The marks are raw-text ranges of keyword tokens, which are
 * never inside a hidden identity-marker line: the editor maps them to the shown text with
 * seq3SourceVisibleOffset, the way it does for find matches.
 */

/** One highlighted keyword: a raw range, and whether it is drawn as unbalanced. */
internal data class Seq3ConstructMark(val range: Seq3SourceRange, val unbalanced: Boolean)

private val SEQ3_MATCH_BLOCK_WORDS = setOf("loop", "alt", "opt", "par", "critical", "break", "group", "rect", "end", "else", "and", "option")

/** The marks to draw for a caret at [selectionStart]..[selectionEnd] in [source]; none when text is
 *  selected or the caret is not on the keyword of a construct. */
internal fun seq3SourceConstructMarks(
    source: String,
    selectionStart: Int,
    selectionEnd: Int,
    dialect: Seq3Dialect,
): List<Seq3ConstructMark> {
    if (selectionStart != selectionEnd) return emptyList()
    val caret = selectionStart.coerceIn(0, source.length)
    // Most caret moves are on no keyword: look at the caret's own line before walking the source.
    val caretStart = if (caret == 0) 0 else source.lastIndexOf('\n', caret - 1) + 1
    val caretEnd = source.indexOf('\n', caret).let { if (it < 0) source.length else it }
    val caretLine = Seq3SourceLine(0, source.substring(caretStart, caretEnd), caretStart, caretEnd, caretEnd + 1)
    val caretKeyword = seq3SourceKeywordRange(caretLine) ?: return emptyList()
    if (!seq3SourceCaretOnKeyword(caretLine, caretKeyword, caret)) return emptyList()
    val lines = seq3SourceLines(source)
    val caretIndex = lines.indexOfLast { it.start <= caret }.coerceAtLeast(0)

    // The same walk as the indentation, one tag per line.
    val state = Seq3SourceIndentState(dialect)
    val fed = lines.mapIndexed { index, line -> state.feed(line.text.trim(), index) }
    val line = fed[caretIndex]
    if (line.orphan) return listOf(Seq3ConstructMark(caretKeyword, unbalanced = true))
    val opener = when (line.kind) {
        Seq3SourceLineKind.OPENER -> caretIndex
        Seq3SourceLineKind.CLOSER, Seq3SourceLineKind.MIDDLE -> line.partner
        else -> -1
    }
    if (opener < 0) return emptyList()

    val group = ArrayList<Int>()
    group += opener
    var closed = false
    fed.forEachIndexed { index, other ->
        if (other.partner == opener && (other.kind == Seq3SourceLineKind.CLOSER || other.kind == Seq3SourceLineKind.MIDDLE)) {
            group += index
            if (other.kind == Seq3SourceLineKind.CLOSER) closed = true
        }
    }
    return group.mapNotNull { index ->
        seq3SourceKeywordRange(lines[index])?.let { Seq3ConstructMark(it, unbalanced = !closed) }
    }
}

/**
 * The raw range of the keyword a construct line is marked by: the first word, or, for `activate` /
 * `deactivate`, the word and the name after it. Null when the line starts with no construct word.
 */
private fun seq3SourceKeywordRange(line: Seq3SourceLine): Seq3SourceRange? {
    val text = line.text
    val first = text.indexOfFirst { !it.isWhitespace() }
    if (first < 0) return null
    var wordEnd = first
    while (wordEnd < text.length && !text[wordEnd].isWhitespace()) wordEnd++
    val word = text.substring(first, wordEnd)
    if ((word == "activate" || word == "deactivate")) {
        var nameStart = wordEnd
        while (nameStart < text.length && text[nameStart].isWhitespace()) nameStart++
        var nameEnd = nameStart
        while (nameEnd < text.length && !text[nameEnd].isWhitespace()) nameEnd++
        // A bare keyword (no name yet) stands alone.
        return Seq3SourceRange(line.start + first, line.start + if (nameEnd > nameStart) nameEnd else wordEnd)
    }
    if (word.lowercase() !in SEQ3_MATCH_BLOCK_WORDS) return null
    return Seq3SourceRange(line.start + first, line.start + wordEnd)
}

/** Whether [caret] is inside or at either edge of [keyword]; for `activate` / `deactivate` anywhere on the line. */
private fun seq3SourceCaretOnKeyword(line: Seq3SourceLine, keyword: Seq3SourceRange, caret: Int): Boolean {
    val word = line.text.trim().takeWhile { !it.isWhitespace() }
    if (word == "activate" || word == "deactivate") return caret in line.start..line.end
    return caret in keyword.start..keyword.endExclusive
}
