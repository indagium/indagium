package com.indagium.ui

import androidx.compose.ui.text.TextRange
import com.indagium.diagram3.SEQ3_SOURCE_EDIT_LABEL
import com.indagium.diagram3.Seq3Dialect

/*
 * Edit rules of the PlantUML / Mermaid source editor that are neither indentation nor autocomplete,
 * pure and UI-free so they can be unit tested: Backspace and Delete next to the hidden identity
 * markers, how a typing run is grouped into undo steps, how a burst of live source edits shares one
 * diagram undo entry, and where the caret goes when the text is replaced from outside the editor.
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
