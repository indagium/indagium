package com.indagium.ui

import com.indagium.diagram3.Seq3Dialect

/*
 * Find and replace of the PlantUML / Mermaid source editor, pure and UI-free so it can be unit
 * tested. Matching ignores case and never overlaps. While the identity markers are hidden a match
 * that touches a hidden marker run is skipped: the user cannot see it, so it must not be counted,
 * selected or replaced. Offsets are raw-text offsets.
 */

/** Every match of [query] in [source], in order. A blank [query] has none. */
internal fun seq3SourceFindMatches(source: String, query: String, dialect: Seq3Dialect, showMarkers: Boolean): List<Seq3SourceRange> {
    if (query.isEmpty()) return emptyList()
    val hidden = if (showMarkers) emptyList() else seq3SourceMarkerDeletionRanges(source, dialect)
    val matches = ArrayList<Seq3SourceRange>()
    var hiddenIndex = 0
    var from = 0
    while (true) {
        val at = source.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        val end = at + query.length
        // Both lists are in order, so the hidden runs already passed are never looked at again.
        while (hiddenIndex < hidden.size && hidden[hiddenIndex].endExclusive <= at) hiddenIndex++
        val inHidden = hiddenIndex < hidden.size && hidden[hiddenIndex].start < end
        if (inHidden) {
            // Resume where the hidden run ends: nothing inside it is a match.
            from = maxOf(at + 1, hidden[hiddenIndex].endExclusive)
        } else {
            matches += Seq3SourceRange(at, end)
            from = end
        }
    }
    return matches
}

/** The index of the first match at or after [caret], wrapping to the first; -1 when there is none. */
internal fun seq3SourceFindIndexFrom(matches: List<Seq3SourceRange>, caret: Int): Int =
    if (matches.isEmpty()) -1 else matches.indexOfFirst { it.start >= caret }.takeIf { it >= 0 } ?: 0

/** The match after [index], or before it when [forward] is false; wraps at both ends. */
internal fun seq3SourceFindStep(matches: List<Seq3SourceRange>, index: Int, forward: Boolean): Int = when {
    matches.isEmpty() -> -1
    index !in matches.indices -> if (forward) 0 else matches.lastIndex
    forward -> (index + 1) % matches.size
    else -> (index - 1 + matches.size) % matches.size
}

/** [match] replaced by [replacement], the caret placed after it. */
internal fun seq3SourceReplaceMatch(source: String, match: Seq3SourceRange, replacement: String): Seq3SourceEdit {
    val text = source.replaceRange(match.start, match.endExclusive, replacement)
    val caret = match.start + replacement.length
    return Seq3SourceEdit(text, caret, caret)
}

/** Every one of [matches] replaced by [replacement]; the selection keeps its place in the text. */
internal fun seq3SourceReplaceAll(
    source: String,
    matches: List<Seq3SourceRange>,
    replacement: String,
    selectionStart: Int,
    selectionEnd: Int,
): Seq3SourceEdit {
    if (matches.isEmpty()) return Seq3SourceEdit(source, selectionStart, selectionEnd)
    val text = buildString(source.length) {
        var copied = 0
        matches.forEach { match ->
            append(source, copied, match.start).append(replacement)
            copied = match.endExclusive
        }
        append(source, copied, source.length)
    }

    // A position after a match moves by what the matches before it changed; one inside a match
    // lands at the end of that match's replacement.
    fun map(offset: Int): Int {
        var shifted = offset
        for (match in matches) {
            if (match.start >= offset) break
            val grown = replacement.length - (match.endExclusive - match.start)
            shifted += if (match.endExclusive <= offset) grown else match.start + replacement.length - offset
        }
        return shifted
    }
    return Seq3SourceEdit(text, map(selectionStart), map(selectionEnd))
}
