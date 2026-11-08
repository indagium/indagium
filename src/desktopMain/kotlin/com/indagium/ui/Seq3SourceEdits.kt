package com.indagium.ui

import androidx.compose.ui.text.TextRange
import com.indagium.diagram3.SEQ3_SOURCE_EDIT_LABEL
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3SourceDiagnostic
import com.indagium.diagram3.Seq3SourceFix
import com.indagium.diagram3.SourceEmissionAnchor

/*
 * Edit rules of the PlantUML / Mermaid source editor that are neither indentation nor autocomplete,
 * pure and UI-free so they can be unit tested: Backspace and Delete next to the hidden identity
 * markers, how a typing run is grouped into undo steps, how a burst of live source edits shares one
 * diagram undo entry, where the caret goes when the text is replaced from outside the editor, and how
 * the issues list maps a diagnostic's raw source line to the text the user sees and edits, how a
 * caret line and a diagram message find each other, and the comment toggle.
 */

/** Typing breaks into a new editor undo step after this long without a keystroke. */
internal const val SEQ3_SOURCE_TYPING_PAUSE_MS = 1000L

/** Live source edits applied within this long of each other belong to one typing burst. */
internal const val SEQ3_SOURCE_UNDO_BURST_MS = 1000L

/**
 * Where a visible caret is when the field reports the raw selection [selectionStart]..[selectionEnd]
 * and it lies entirely inside a hidden marker run: the field maps a collapsed visible point at a
 * hole to the whole hole. That is the start of the statement after the run, or the start of the run
 * when nothing is shown after it. Null for a collapsed selection and for a real selection, one that
 * reaches outside a hidden run, which keeps its meaning.
 */
internal fun seq3SourceShownCaretAtHiddenRun(source: String, selectionStart: Int, selectionEnd: Int, dialect: Seq3Dialect): Int? {
    if (selectionStart == selectionEnd || !source.contains("indagium-seq3:v1")) return null
    val low = minOf(selectionStart, selectionEnd)
    val high = maxOf(selectionStart, selectionEnd)
    val run = seq3SourceMarkerDeletionRanges(source, dialect).firstOrNull { low >= it.start && high <= it.endExclusive } ?: return null
    return if (run.endExclusive < source.length) run.endExclusive else run.start
}

/**
 * Backspace (or, with [forward], Delete) next to the hidden marker run of a statement. The default
 * edit would delete a character of a marker, or a whole marker line with it: Backspace at the start
 * of a line removes the newline that ends the marker line above it, and Delete at the end of a line
 * removes the newline in front of the markers below it. Instead the blank line beside the markers is
 * removed; when there is no blank line to remove the key does nothing, since the only join left
 * would consume markers.
 *
 * The field maps a visible position at a hidden run to the whole run: a caret the user sees at the
 * start of the statement after the markers (Home, Cmd+Left, a click) is a raw SELECTION covering
 * the marker lines, and a raw caret on a marker line is likewise first moved to where it is shown:
 * the start of the statement after the run, or the end of the line before a trailing run. The
 * remaining edit is then the plain one-character delete there.
 *
 * Returns null when the default edit is the right one: a selection, shown markers, or a caret that
 * is not at the line break next to a marker line. A returned edit may equal [source] (key consumed
 * without a change, the caret possibly moved to its visible place).
 */
internal fun seq3SourceDeleteNextToMarkers(
    source: String,
    selectionStart: Int,
    selectionEnd: Int,
    dialect: Seq3Dialect,
    forward: Boolean,
    showMarkers: Boolean = false,
): Seq3SourceEdit? {
    if (showMarkers || !source.contains("indagium-seq3:v1")) return null
    // A visible caret at a hidden run is a RAW selection covering the run (the field maps the
    // collapsed point to the whole hole), so it is a caret here; any other selection deletes as
    // selected.
    val shownAtRun = seq3SourceShownCaretAtHiddenRun(source, selectionStart, selectionEnd, dialect)
    if (selectionStart != selectionEnd && shownAtRun == null) return null
    val lines = seq3SourceLines(source)
    // The lines the editor hides: marker lines and the empty last line after a trailing marker.
    val hidden = hiddenSourceLineIndices(lines, source, dialect)
    val given = shownAtRun ?: selectionStart.coerceIn(0, source.length)
    val caret = seq3SourceCaretWhereShown(lines, hidden, given)
    val deletion = Seq3MarkerDeletion(source, lines, hidden, caret, forward)
    return deletion.besideMarkerRun()
        ?: if (caret == given && shownAtRun == null) {
            null
        } else {
            // The caret was moved to its visible place and no marker is beside it now: the plain delete.
            deletion.plainDelete()
        }
}

/** [given] moved off a hidden marker line to where it is shown: the start of the statement after the run, or the end of the line
 *  before a trailing run. Any other caret stays. */
private fun seq3SourceCaretWhereShown(lines: List<Seq3SourceLine>, hidden: Set<Int>, given: Int): Int {
    val onMarker = lines.indexOfLast { it.start <= given }
    if (onMarker !in hidden) return given
    var first = onMarker
    while (first > 0 && (first - 1) in hidden) first--
    var last = onMarker
    while (last < lines.lastIndex && (last + 1) in hidden) last++
    return when {
        last < lines.lastIndex -> lines[last + 1].start
        first > 0 -> lines[first - 1].end
        else -> 0
    }
}

/** One Backspace / Delete with the collapsed [caret] (already where it is shown) over the lines of a source whose [hidden]
 *  lines are marker lines. */
private class Seq3MarkerDeletion(
    private val source: String,
    private val lines: List<Seq3SourceLine>,
    private val hidden: Set<Int>,
    private val caret: Int,
    private val forward: Boolean,
) {
    private fun isMarker(i: Int) = i in hidden

    private fun unchanged() = Seq3SourceEdit(source, caret, caret)

    private fun remove(start: Int, end: Int, caretAfter: Int) = Seq3SourceEdit(source.removeRange(start, end), caretAfter, caretAfter)

    private fun removeLine(target: Seq3SourceLine, caretAfter: Int): Seq3SourceEdit {
        val end = if (target.nextStart > target.end) target.nextStart else target.end
        return remove(target.start, end, caretAfter)
    }

    fun plainDelete(): Seq3SourceEdit = if (!forward) {
        if (caret == 0) {
            unchanged()
        } else {
            val width = if (caret >= 2 && source[caret - 1].isLowSurrogate() && source[caret - 2].isHighSurrogate()) 2 else 1
            remove(caret - width, caret, caret - width)
        }
    } else {
        if (caret >= source.length) unchanged()
        else remove(caret, caret + if (source[caret].isHighSurrogate() && caret + 1 < source.length) 2 else 1, caret)
    }

    /** The edit when the caret is at the line break next to a marker line, null when it is not. */
    fun besideMarkerRun(): Seq3SourceEdit? {
        val index = lines.indexOfLast { it.start <= caret }
        val line = lines[index]
        val afterRun = caret == line.start && index > 0 && isMarker(index - 1)
        return when {
            !forward -> if (afterRun) backspaceAfterRun(index, line) else null
            caret == line.end && index < lines.lastIndex && isMarker(index + 1) -> deleteBeforeRun(index)
            // The default Delete here would take the hidden run along with the visible character: the
            // field deletes in the shown text, where the character after the caret follows the run.
            afterRun -> plainDelete()
            else -> null
        }
    }

    private fun backspaceAfterRun(index: Int, line: Seq3SourceLine): Seq3SourceEdit {
        if (line.text.isBlank()) return removeLine(line, line.start)
        var above = index - 1
        while (above >= 0 && isMarker(above)) above--
        val blank = lines.getOrNull(above)?.takeIf { it.text.isBlank() } ?: return unchanged()
        return removeLine(blank, caret - (blank.nextStart - blank.start))
    }

    private fun deleteBeforeRun(index: Int): Seq3SourceEdit {
        var below = index + 1
        while (below <= lines.lastIndex && isMarker(below)) below++
        val blank = lines.getOrNull(below)?.takeIf { it.text.isBlank() } ?: return unchanged()
        return removeLine(blank, caret)
    }
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

/**
 * The line numbers the gutter shows for raw source lines [rawLines] (a diagnostic's lines): while
 * [showMarkers] is false the gutter counts only the visible lines, 1..n, so a raw line is mapped to
 * the visible line that stands in for it (see [seq3SourceVisibleRawLines]) and then to its place
 * among the visible lines. With markers shown the numbers are the raw ones.
 */
internal fun seq3SourceDisplayLines(source: String, rawLines: List<Int>, dialect: Seq3Dialect, showMarkers: Boolean): List<Int> {
    if (rawLines.isEmpty()) return emptyList()
    val numbers = seq3SourceLineNumberMap(source, dialect, showMarkers)
    return seq3SourceVisibleRawLines(source, rawLines, dialect, showMarkers).map { numbers.indexOfLine(it) + 1 }
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

/** The line-comment prefix of [dialect]: `'` for PlantUML, `%%` for Mermaid. */
internal fun seq3SourceCommentPrefix(dialect: Seq3Dialect): String = if (dialect == Seq3Dialect.MERMAID) "%%" else "'"

/**
 * Cmd/Ctrl+/. The lines the selection touches (a selection that ends at the start of a line leaves
 * that line alone) are commented with the dialect's prefix, or uncommented when every one of them
 * already is. Blank lines are skipped, and identity marker lines are never touched, shown or hidden:
 * a marker is itself a comment line and toggling it would orphan its statement. The comment goes at
 * the indentation, so the lines stay aligned. The selection keeps covering the same lines. When no
 * line qualifies the edit equals [source].
 */
internal fun seq3SourceToggleComment(source: String, selectionStart: Int, selectionEnd: Int, dialect: Seq3Dialect): Seq3SourceEdit {
    val first = minOf(selectionStart, selectionEnd).coerceIn(0, source.length)
    val last = maxOf(selectionStart, selectionEnd).coerceIn(first, source.length)
    val lines = seq3SourceLines(source)
    val firstIndex = lines.indexOfLast { it.start <= first }
    var lastIndex = lines.indexOfLast { it.start <= last }
    if (last > first && lastIndex > firstIndex && lines[lastIndex].start == last) lastIndex--
    val touched = (firstIndex..lastIndex).map { lines[it] }
        .filter { it.text.isNotBlank() && !isSeq3SourceMarkerLine(it.text, dialect) }
    if (touched.isEmpty()) return Seq3SourceEdit(source, selectionStart, selectionEnd)
    val prefix = seq3SourceCommentPrefix(dialect)

    fun indentEnd(line: Seq3SourceLine) = line.start + line.text.takeWhile { it == ' ' || it == '\t' }.length
    val uncomment = touched.all { source.startsWith(prefix, indentEnd(it)) }
    // (position in the old text, characters added there or, when negative, removed from there)
    val changes = touched.map { line ->
        val at = indentEnd(line)
        if (uncomment) at to -(prefix.length + if (source.getOrNull(at + prefix.length) == ' ') 1 else 0)
        else at to prefix.length + 1
    }
    val text = buildString(source.length + changes.size * (prefix.length + 1)) {
        var copied = 0
        changes.forEach { (at, delta) ->
            append(source, copied, at)
            if (delta > 0) {
                append(prefix).append(' ')
                copied = at
            } else {
                copied = at - delta
            }
        }
        append(source, copied, source.length)
    }
    val collapsed = selectionStart == selectionEnd

    // A selection start in front of the indentation stays in front of the new prefix, so the
    // selection still begins with the comment; a caret moves along with the text it stood in.
    fun map(offset: Int, isStart: Boolean): Int = offset + changes.sumOf { (at, delta) ->
        when {
            delta > 0 -> if (if (isStart && !collapsed) offset > at else offset >= at) delta else 0
            else -> -(offset - at).coerceIn(0, -delta)
        }
    }
    val selectionIsReversed = selectionStart > selectionEnd
    val newStart = map(selectionStart, isStart = !selectionIsReversed)
    val newEnd = map(selectionEnd, isStart = selectionIsReversed)
    return Seq3SourceEdit(text, newStart, newEnd)
}

/** The raw line of the arrow a message is drawn from, for message [messageId]: the one belonging
 *  to occurrence [occurrenceEntryId] when that is given and marked, else the first. Null when the
 *  source holds no marked statement of that message. */
internal fun seq3SourceLineOfMessage(anchors: List<SourceEmissionAnchor>, messageId: String, occurrenceEntryId: Int? = null): Int? {
    val own = anchors.filter { it.messageId == messageId }
    return (own.firstOrNull { occurrenceEntryId != null && it.occurrenceEntryId == occurrenceEntryId } ?: own.firstOrNull())?.line
}

/** The marked message statement on raw line [rawLine], or null when that line is not one. */
internal fun seq3SourceAnchorAtLine(anchors: List<SourceEmissionAnchor>, rawLine: Int): SourceEmissionAnchor? =
    anchors.firstOrNull { it.line == rawLine }
