package com.indagium.ui

import androidx.compose.ui.text.TextRange
import com.indagium.diagram3.SEQ3_SOURCE_EDIT_LABEL
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3SourceDiagnostic
import com.indagium.diagram3.Seq3SourceFix

/*
 * Edit rules of the PlantUML / Mermaid source editor that are neither indentation nor autocomplete,
 * pure and UI-free so they can be unit tested: Backspace and Delete next to the hidden identity
 * markers, how a typing run is grouped into undo steps, how a burst of live source edits shares one
 * diagram undo entry, where the caret goes when the text is replaced from outside the editor, and how
 * the issues list maps a diagnostic's raw source line to the text the user sees and edits.
 */

/** Typing breaks into a new editor undo step after this long without a keystroke. */
internal const val SEQ3_SOURCE_TYPING_PAUSE_MS = 1000L

/** Live source edits applied within this long of each other belong to one typing burst. */
internal const val SEQ3_SOURCE_UNDO_BURST_MS = 1000L

/**
 * Backspace (or, with [forward], Delete) next to the hidden marker run of a statement. The default
 * edit would delete a character of a marker: Backspace at the start of a line removes the newline
 * that ends the marker line above it, and Delete at the end of a line removes the newline in front
 * of the markers below it. Instead the blank line beside the markers is removed; when there is no
 * blank line to remove the key does nothing, since the only join left would consume markers.
 *
 * Returns null when the default edit is the right one: a selection, shown markers, or a caret that
 * is not at the line break next to a marker line. A returned edit may equal [source] (key consumed
 * without a change).
 */
internal fun seq3SourceDeleteNextToMarkers(
    source: String,
    selectionStart: Int,
    selectionEnd: Int,
    dialect: Seq3Dialect,
    forward: Boolean,
    showMarkers: Boolean = false,
): Seq3SourceEdit? {
    if (showMarkers || selectionStart != selectionEnd || !source.contains("indagium-seq3:v1")) return null
    val caret = selectionStart.coerceIn(0, source.length)
    val lines = seq3SourceLines(source)
    val index = lines.indexOfLast { it.start <= caret }
    val line = lines[index]
    fun isMarker(i: Int) = isSeq3SourceMarkerLine(lines[i].text, dialect)
    fun unchanged() = Seq3SourceEdit(source, caret, caret)
    fun remove(target: Seq3SourceLine, caretAfter: Int): Seq3SourceEdit {
        val end = if (target.nextStart > target.end) target.nextStart else target.end
        return Seq3SourceEdit(source.removeRange(target.start, end), caretAfter, caretAfter)
    }

    if (!forward) {
        if (caret != line.start || index == 0 || !isMarker(index - 1)) return null
        if (line.text.isBlank()) return remove(line, line.start)
        var above = index - 1
        while (above >= 0 && isMarker(above)) above--
        val blank = lines.getOrNull(above)?.takeIf { it.text.isBlank() } ?: return unchanged()
        return remove(blank, caret - (blank.nextStart - blank.start))
    }
    if (caret != line.end || index == lines.lastIndex || !isMarker(index + 1)) return null
    var below = index + 1
    while (below <= lines.lastIndex && isMarker(below)) below++
    val blank = lines.getOrNull(below)?.takeIf { it.text.isBlank() } ?: return unchanged()
    return remove(blank, caret)
}

/** A run of single typed characters that the editor history keeps as one undo step. [caret] is
 *  where the next character of the run lands and [atMs] the time of the last keystroke. */
internal data class Seq3SourceTypingRun(val caret: Int, val atMs: Long)

/** What the editor history does with one text change: push the text before it as a new undo step
 *  (or leave the current step to absorb it), and the typing run that is open afterwards. */
internal data class Seq3SourceHistoryDecision(val pushStep: Boolean, val run: Seq3SourceTypingRun?)

/**
 * Groups typing into undo steps. A single typed character that continues an open [run] (the caret
 * has not jumped, no pause over [SEQ3_SOURCE_TYPING_PAUSE_MS]) joins the current step, unless it is
 * whitespace: whitespace starts a new step, which the word after it then continues. Everything else
 * (newline, deletion, paste, replacement, a selection) is its own step and closes the run.
 */
internal fun seq3SourceHistoryDecision(
    oldText: String,
    newText: String,
    newSelection: TextRange,
    run: Seq3SourceTypingRun?,
    nowMs: Long,
): Seq3SourceHistoryDecision {
    val at = newSelection.start - 1
    val typedChar = if (newSelection.collapsed && at >= 0 && newText.length == oldText.length + 1 &&
        newText.removeRange(at, at + 1) == oldText
    ) newText[at] else null
    if (typedChar == null || typedChar == '\n' || typedChar == '\r') return Seq3SourceHistoryDecision(true, null)
    val continues = run != null && run.caret == at && nowMs - run.atMs <= SEQ3_SOURCE_TYPING_PAUSE_MS && !typedChar.isWhitespace()
    return Seq3SourceHistoryDecision(!continues, Seq3SourceTypingRun(at + 1, nowMs))
}

/** Whether a live source edit applied at [nowMs] joins the top diagram undo entry, labelled
 *  [topLabel], instead of adding another one: both belong to one typing burst, and nothing else
 *  was done to the diagram in between. [lastBurstEditAtMs] is null when no burst is open. */
internal fun seq3SourceUndoMerges(topLabel: String?, lastBurstEditAtMs: Long?, nowMs: Long): Boolean =
    topLabel == SEQ3_SOURCE_EDIT_LABEL && lastBurstEditAtMs != null &&
        nowMs - lastBurstEditAtMs in 0..SEQ3_SOURCE_UNDO_BURST_MS

/** Where [caret] of [old] lands when the text is replaced by [new]: unchanged before the changed
 *  span, shifted after it, and at most at the end of the new span inside it. */
internal fun seq3SourceMapCaret(old: String, new: String, caret: Int): Int {
    val shortest = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < shortest && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < shortest - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    val position = caret.coerceIn(0, old.length)
    val mapped = when {
        position <= prefix -> position
        position >= old.length - suffix -> position + (new.length - old.length)
        else -> minOf(position, new.length - suffix)
    }
    return mapped.coerceIn(0, new.length)
}

/** The 0-based indices of the lines the editor does not show while markers are hidden: marker lines,
 *  and the empty last line after a trailing marker (the same set [seq3SourceLineNumberMap] omits). */
private fun hiddenSourceLineIndices(lines: List<Seq3SourceLine>, source: String, dialect: Seq3Dialect): Set<Int> {
    val hidden = HashSet<Int>()
    lines.forEachIndexed { index, line -> if (isSeq3SourceMarkerLine(line.text, dialect)) hidden += index }
    if (hidden.isNotEmpty() && source.endsWith('\n') && lines.size > 1 && (lines.lastIndex - 1) in hidden) hidden += lines.lastIndex
    if (hidden.size == lines.size) hidden.remove(0)
    return hidden
}

/** The raw 1-based line holding [offset]. */
internal fun seq3SourceRawLineAt(source: String, offset: Int): Int {
    val end = offset.coerceIn(0, source.length)
    var line = 1
    for (i in 0 until end) if (source[i] == '\n') line++
    return line
}

/**
 * Maps raw 1-based source lines (a diagnostic's line numbers) to the raw line the user actually
 * sees: a line out of range is clamped, and a line the editor hides while [showMarkers] is false
 * (an identity marker line) maps to the next visible line, or the previous one when none follows.
 */
internal fun seq3SourceVisibleRawLines(
    source: String,
    rawLines: List<Int>,
    dialect: Seq3Dialect,
    showMarkers: Boolean,
): List<Int> {
    if (rawLines.isEmpty()) return emptyList()
    val lines = seq3SourceLines(source)
    val hidden = if (showMarkers || !source.contains("indagium-seq3:v1")) emptySet() else hiddenSourceLineIndices(lines, source, dialect)
    return rawLines.map { raw ->
        val index = (raw - 1).coerceIn(0, lines.lastIndex)
        var visible = index
        while (visible <= lines.lastIndex && visible in hidden) visible++
        if (visible > lines.lastIndex) {
            visible = index
            while (visible > 0 && visible in hidden) visible--
        }
        visible + 1
    }
}

/** Where the caret goes to show raw source line [rawLine]: the first character after its
 *  indentation (a blank line's end), on the nearest visible line. */
internal fun seq3SourceCaretOffsetForLine(source: String, rawLine: Int, dialect: Seq3Dialect, showMarkers: Boolean): Int {
    val line = seq3SourceVisibleRawLines(source, listOf(rawLine), dialect, showMarkers).first()
    val target = seq3SourceLines(source)[line - 1]
    val indent = target.text.takeWhile { it == ' ' || it == '\t' }.length
    return if (indent >= target.text.length) target.end else target.start + indent
}

/** The diagnostic for the line the caret is on, or null. A diagnostic on a hidden marker line
 *  belongs to the visible line the caret would be shown on. */
internal fun seq3SourceDiagnosticAtCaret(
    source: String,
    caret: Int,
    diagnostics: List<Seq3SourceDiagnostic>,
    dialect: Seq3Dialect,
    showMarkers: Boolean,
): Seq3SourceDiagnostic? {
    if (diagnostics.isEmpty()) return null
    val visible = seq3SourceVisibleRawLines(source, listOf(seq3SourceRawLineAt(source, caret)) + diagnostics.map { it.line }, dialect, showMarkers)
    return diagnostics.withIndex().firstOrNull { (index, _) -> visible[index + 1] == visible[0] }?.value
}

/** A diagnostic's quick fix as one edit: [Seq3SourceFix.text] becomes a new line before raw line
 *  [Seq3SourceFix.insertLine] (appended when that is past the last line). The selection keeps its
 *  place in the text, shifted past the inserted line when it lies at or after it. */
internal fun seq3SourceApplyFix(source: String, fix: Seq3SourceFix, selectionStart: Int, selectionEnd: Int): Seq3SourceEdit {
    val lines = seq3SourceLines(source)
    val index = (fix.insertLine - 1).coerceIn(0, lines.size)
    val at: Int
    val next: String
    if (index < lines.size) {
        at = lines[index].start
        next = source.substring(0, at) + fix.text + "\n" + source.substring(at)
    } else {
        val separator = if (source.isEmpty() || source.endsWith('\n')) "" else "\n"
        at = source.length + separator.length
        next = source + separator + fix.text
    }
    val inserted = next.length - source.length
    fun shift(position: Int) = position.coerceIn(0, source.length).let { if (it >= at) it + inserted else it }
    return Seq3SourceEdit(next, shift(selectionStart), shift(selectionEnd))
}
