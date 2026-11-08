package com.indagium.ui

import com.indagium.diagram3.Seq3Dialect

/*
 * Indentation rules of the PlantUML / Mermaid source editor, pure and UI-free so they can be unit
 * tested. Nested content is one level (four spaces) deeper than its block opener. Mermaid's body
 * starts one level in, after `sequenceDiagram`; PlantUML's starts at column 0. `else`, `and` and
 * `option` sit at their opener's level and only count inside the block that owns them; `end` sits at
 * its opener's level. Comment and marker lines never open or close anything and take the level of
 * the statement that follows them. Offsets in every result are raw-text offsets.
 *
 * Activations nest the same way. `activate X` opens a level, so the lines up to the matching
 * `deactivate X` are one level deeper, and `deactivate X` sits at the level its `activate X` has.
 * Blocks and activations share one stack of open levels, and every opener records the indent it
 * actually got. That, not the number of levels open, is what the other lines are placed by:
 * - a closer (`end`, `deactivate X`) sits exactly at its own opener's recorded indent, and `else`,
 *   `and` and `option` at their block's recorded indent;
 * - a plain line sits one level deeper than the innermost still-open opener's recorded indent, and
 *   at the base (0 for PlantUML, Mermaid's body level after `sequenceDiagram`) with nothing open.
 *   So an activation opened before a block and closed inside it leaves the block where it is:
 *   closers still line up with their openers, whatever order they close in;
 * - `deactivate X` closes the innermost open `activate X` wherever it sits in the stack, so
 *   interleaved activations (`activate A`, `activate B`, `deactivate A`) are fine: A's level is
 *   removed, B stays open at its own indent and the lines after the `deactivate A` are still
 *   one level deeper than B;
 * - a bare `deactivate`, or one whose name matches no open activation (a name still being typed),
 *   closes the innermost open activation; with none open it is an ordinary line;
 * - `end` closes the innermost open block and `else` / `and` / `option` belong to it; activations
 *   opened inside that block are not closed by it, they stay open after the `end`;
 * - a bare `activate` (no name yet) is an ordinary line until the name is typed;
 * - Mermaid's `->>+B` / `-->>-A` arrow shorthand does NOT count. Those are message lines that would
 *   have to be both a body line and an opener or closer, the shorthand is often left unbalanced, and
 *   the generated source never uses it (it writes explicit `activate` / `deactivate` lines), so such
 *   lines keep the level of their surroundings. PlantUML's `++` / `--` suffix is treated alike.
 *
 * The same walk tells which opener a closer or divider belongs to ([Seq3SourceLineLevel.partner]),
 * which the editor's opener/closer highlighting (Seq3ConstructMark.kt) reads, so the pairs it shows
 * always agree with the indentation.
 */

internal const val SEQ3_SOURCE_INDENT_WIDTH = 4

internal enum class Seq3SourceLineKind { BLANK, COMMENT, HEADER, OPENER, MIDDLE, CLOSER, TEXT }

/**
 * [partner] is the tag (see [Seq3SourceIndentState.feed]) of the opener a closer or divider belongs
 * to, -1 when it has none. [orphan] marks a closer that matched nothing: an `end` outside every
 * block (a CLOSER with no partner) or a `deactivate` with no activation open (which stays a TEXT line).
 */
internal data class Seq3SourceLineLevel(
    val kind: Seq3SourceLineKind,
    val level: Int,
    val partner: Int = -1,
    val orphan: Boolean = false,
)

/** The text of one edit plus where the selection ends up. */
internal data class Seq3SourceEdit(val text: String, val selectionStart: Int, val selectionEnd: Int)

private val SEQ3_BLOCK_OPENERS = setOf("loop", "alt", "opt", "par", "critical", "break", "group")

/** One open level: a block (`word` is `loop`, `alt`, ...) or an activation (`word` is the name).
 *  [indent] is the level its opener actually got; [tag] names the opener line for the caller. */
private class Seq3IndentFrame(val word: String, val activation: Boolean, val indent: Int, val tag: Int)

internal fun seq3SourceIndentString(level: Int): String = " ".repeat(level.coerceAtLeast(0) * SEQ3_SOURCE_INDENT_WIDTH)

/** Walks source lines in order, tracking the open blocks and activations. */
internal class Seq3SourceIndentState(private val dialect: Seq3Dialect) {
    private val frames = ArrayList<Seq3IndentFrame>()
    private var bodyStarted = false

    private val base: Int get() = if (dialect == Seq3Dialect.MERMAID && bodyStarted) 1 else 0

    /** The level a plain statement would get on the next line: one deeper than the innermost open
     *  opener's own indent, or the base with none open. */
    val currentLevel: Int get() = frames.lastOrNull()?.let { it.indent + 1 } ?: base

    /** The block (`loop`, `alt`, ...) the next line would sit in, or null outside every block. */
    val innermostBlock: String? get() = frames.lastOrNull { !it.activation }?.word

    fun copy(): Seq3SourceIndentState = Seq3SourceIndentState(dialect).also {
        it.frames.addAll(frames)
        it.bodyStarted = bodyStarted
    }

    /** Feeds one already trimmed line. Blank and comment lines are returned with level -1: their
     *  level depends on the line that follows. [tag] names the line to the caller: a closer or
     *  divider reports the tag of the opener it belongs to. */
    fun feed(text: String, tag: Int = -1): Seq3SourceLineLevel {
        if (text.isEmpty()) return Seq3SourceLineLevel(Seq3SourceLineKind.BLANK, -1)
        if (isComment(text)) return Seq3SourceLineLevel(Seq3SourceLineKind.COMMENT, -1)
        if (isHeader(text)) return feedHeader()
        if (text.equals("end", ignoreCase = true)) return feedEnd()
        return feedStatement(text, tag)
    }

    private fun feedHeader(): Seq3SourceLineLevel {
        if (dialect == Seq3Dialect.MERMAID) bodyStarted = true
        return Seq3SourceLineLevel(Seq3SourceLineKind.HEADER, 0)
    }

    /** An `end` closes the innermost open block; with none open it is an orphan. */
    private fun feedEnd(): Seq3SourceLineLevel {
        val index = frames.indexOfLast { !it.activation }
        if (index < 0) return Seq3SourceLineLevel(Seq3SourceLineKind.CLOSER, base, orphan = true)
        val closed = frames.removeAt(index)
        return Seq3SourceLineLevel(Seq3SourceLineKind.CLOSER, closed.indent, partner = closed.tag)
    }

    /** A line that is not blank, a comment, a header or an `end`: an opener, an activation, a divider or plain text. */
    private fun feedStatement(text: String, tag: Int): Seq3SourceLineLevel {
        val word = text.takeWhile { !it.isWhitespace() }.lowercase()
        if (word in SEQ3_BLOCK_OPENERS || (word == "rect" && dialect == Seq3Dialect.MERMAID)) {
            val level = currentLevel
            frames += Seq3IndentFrame(word, activation = false, indent = level, tag = tag)
            return Seq3SourceLineLevel(Seq3SourceLineKind.OPENER, level)
        }
        // The import reads these two keywords in lower case only.
        val rawWord = text.takeWhile { !it.isWhitespace() }
        if (rawWord == "activate" || rawWord == "deactivate") return feedActivation(text, rawWord == "activate", tag)
        val ownerIndex = frames.indexOfLast { !it.activation }
        if (ownerIndex >= 0 && ownsMiddleWord(frames[ownerIndex].word, word)) {
            return Seq3SourceLineLevel(Seq3SourceLineKind.MIDDLE, frames[ownerIndex].indent, partner = frames[ownerIndex].tag)
        }
        return Seq3SourceLineLevel(Seq3SourceLineKind.TEXT, currentLevel)
    }

    /** `activate X` opens a level, `deactivate X` closes X's (see the rules at the top of the file). */
    private fun feedActivation(text: String, activate: Boolean, tag: Int): Seq3SourceLineLevel {
        val name = text.split(Regex("\\s+")).getOrNull(1).orEmpty()
        if (activate) {
            if (name.isEmpty()) return Seq3SourceLineLevel(Seq3SourceLineKind.TEXT, currentLevel)
            val level = currentLevel
            frames += Seq3IndentFrame(name, activation = true, indent = level, tag = tag)
            return Seq3SourceLineLevel(Seq3SourceLineKind.OPENER, level)
        }
        val index = frames.indexOfLast { it.activation && it.word == name }
            .takeIf { it >= 0 } ?: frames.indexOfLast { it.activation }
        if (index < 0) return Seq3SourceLineLevel(Seq3SourceLineKind.TEXT, currentLevel, orphan = true)
        val closed = frames.removeAt(index)
        return Seq3SourceLineLevel(Seq3SourceLineKind.CLOSER, closed.indent, partner = closed.tag)
    }

    private fun isComment(text: String): Boolean =
        if (dialect == Seq3Dialect.MERMAID) text.startsWith("%%") else text.startsWith("'")

    private fun isHeader(text: String): Boolean =
        if (dialect == Seq3Dialect.MERMAID) text == "sequenceDiagram"
        else text.startsWith("@startuml") || text.startsWith("@enduml")

    // PlantUML writes every operand divider as `else`, whatever the block; Mermaid has its own
    // words for par (`and`) and critical (`option`).
    private fun ownsMiddleWord(block: String, word: String): Boolean = when (word) {
        "else" -> block == "alt" || (dialect == Seq3Dialect.PLANTUML && (block == "par" || block == "critical" || block == "group"))
        "and" -> block == "par"
        "option" -> block == "critical"
        else -> false
    }
}

/** The state after every line of [source] has been fed (a trailing partial line counts). */
internal fun seq3SourceIndentStateAfter(source: String, dialect: Seq3Dialect): Seq3SourceIndentState =
    Seq3SourceIndentState(dialect).also { state -> source.split('\n').forEach { state.feed(it.trim()) } }

/** Kind and level of each raw line of [source] (split on newlines), plus the level a plain
 *  statement would get after the last line. */
internal fun seq3SourceLineLevels(source: String, dialect: Seq3Dialect): List<Seq3SourceLineLevel> =
    seq3SourceAnalyse(source, dialect).first

private fun seq3SourceAnalyse(source: String, dialect: Seq3Dialect): Pair<List<Seq3SourceLineLevel>, Int> {
    val state = Seq3SourceIndentState(dialect)
    val fed = source.split('\n').map { state.feed(it.trim()) }
    val resolved = fed.toMutableList()
    var next = state.currentLevel
    for (index in resolved.indices.reversed()) {
        val line = resolved[index]
        when (line.kind) {
            Seq3SourceLineKind.BLANK, Seq3SourceLineKind.COMMENT -> resolved[index] = line.copy(level = next)
            else -> next = line.level
        }
    }
    return resolved to state.currentLevel
}

/** Re-indents a whole source by the rules above. Idempotent; blank lines lose their spaces, line
 *  endings and the trailing newline are kept. */
internal fun seq3IndentSource(source: String, dialect: Seq3Dialect): String {
    if (source.isEmpty()) return source
    val levels = seq3SourceLineLevels(source, dialect)
    return buildString(source.length + 64) {
        source.split('\n').forEachIndexed { index, line ->
            if (index > 0) append('\n')
            if (line.isBlank()) append(line.trimEnd(' ', '\t'))
            else append(seq3SourceIndentString(levels[index].level)).append(line.trimStart(' ', '\t'))
        }
    }
}

/** A snippet re-indented for its insertion point, with a map from offsets in the original snippet
 *  to offsets in [text]. */
internal class Seq3SourceReindented(val text: String, private val offsets: IntArray) {
    fun map(offset: Int): Int = offsets[offset.coerceIn(0, offsets.lastIndex)]
}

/** Re-indents [content] line by line as if it were typed where [context] stands. A whitespace-only
 *  line is a body line (the caret placeholder inside a block snippet). [context] is not changed. */
internal fun seq3SourceReindentSnippet(content: String, context: Seq3SourceIndentState): Seq3SourceReindented {
    val state = context.copy()
    val offsets = IntArray(content.length + 1)
    val out = StringBuilder(content.length + 32)
    var oldStart = 0
    content.split('\n').forEachIndexed { index, line ->
        val trimmed = line.trimStart(' ', '\t')
        val leading = line.length - trimmed.length
        val fed = state.feed(trimmed.trim())
        val level = if (fed.level >= 0) fed.level else state.currentLevel
        val indent = seq3SourceIndentString(level)
        if (index > 0) out.append('\n')
        val newStart = out.length
        out.append(indent).append(trimmed)
        for (column in 0..line.length) {
            offsets[oldStart + column] = newStart + if (column >= leading) indent.length + (column - leading) else minOf(column, indent.length)
        }
        oldStart += line.length + 1
    }
    return Seq3SourceReindented(out.toString(), offsets)
}

/** [content] with every line put after the fixed [indent] and otherwise left as it is: the insertion
 *  with Auto-indent off. A line keeps its own leading blanks, so a block snippet keeps its shape. */
internal fun seq3SourceFlatSnippet(content: String, indent: String): Seq3SourceReindented {
    val offsets = IntArray(content.length + 1)
    val out = StringBuilder(content.length + 32)
    var oldStart = 0
    content.split('\n').forEachIndexed { index, line ->
        if (index > 0) out.append('\n')
        val newStart = out.length
        out.append(indent).append(line)
        for (column in 0..line.length) offsets[oldStart + column] = newStart + indent.length + column
        oldStart += line.length + 1
    }
    return Seq3SourceReindented(out.toString(), offsets)
}

private fun lineStartOf(source: String, offset: Int): Int = source.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let {
    if (offset <= 0) 0 else it + 1
}

private fun lineEndOf(source: String, offset: Int): Int = source.indexOf('\n', offset).let { if (it < 0) source.length else it }

private fun leadingWhitespace(line: String): String = line.substring(0, line.length - line.trimStart(' ', '\t').length)

/**
 * Enter: a newline plus the indent the next line needs. Text after the caret moves to the new line
 * and loses its old leading spaces. Returns null when the default newline is the better edit (the
 * caret splits a hidden marker line). With [autoIndent] off the new line only keeps the leading
 * whitespace of the line it was typed on.
 */
internal fun seq3SourceEnter(
    source: String,
    selectionStart: Int,
    selectionEnd: Int,
    dialect: Seq3Dialect,
    autoIndent: Boolean = true,
): Seq3SourceEdit? {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, source.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(start, source.length)
    val lineStart = lineStartOf(source, start)
    val head = source.substring(0, start)
    val beforeCaret = source.substring(lineStart, start)
    val tail = source.substring(end)
    if (beforeCaret.isBlank()) {
        // Caret in the leading whitespace: the line moves down whole, keeping its own indent.
        val text = source.substring(0, lineStart) + "\n" + beforeCaret + tail
        val caret = lineStart + 1 + beforeCaret.length
        return Seq3SourceEdit(text, caret, caret)
    }
    val lineEnd = lineEndOf(source, end)
    if (isSeq3SourceMarkerLine(source.substring(lineStart, lineEnd), dialect)) return null
    if (!autoIndent) {
        // Auto-indent off: the new line only repeats the leading blanks of the line it came from.
        val indent = leadingWhitespace(beforeCaret)
        val caret = start + 1 + indent.length
        return Seq3SourceEdit(head + "\n" + indent + tail.trimStart(' ', '\t'), caret, caret)
    }
    val (levels, afterLevel) = seq3SourceAnalyse(head, dialect)
    val last = levels.last()
    // An `end` or divider typed at the wrong level is moved first; every kind that opens a body
    // (and the header line) sends the new line to the level after it.
    val keptLine = if (last.kind == Seq3SourceLineKind.CLOSER || last.kind == Seq3SourceLineKind.MIDDLE) {
        seq3SourceIndentString(last.level) + beforeCaret.trimStart(' ', '\t')
    } else {
        beforeCaret
    }
    val indent = when (last.kind) {
        Seq3SourceLineKind.CLOSER, Seq3SourceLineKind.MIDDLE, Seq3SourceLineKind.OPENER, Seq3SourceLineKind.HEADER ->
            seq3SourceIndentString(afterLevel)
        else -> leadingWhitespace(beforeCaret)
    }
    val text = source.substring(0, lineStart) + keptLine + "\n" + indent + tail.trimStart(' ', '\t')
    val caret = lineStart + keptLine.length + 1 + indent.length
    return Seq3SourceEdit(text, caret, caret)
}

/**
 * Moves the caret line to its opener's level when it is exactly `end`, starts with `else`, `and` or
 * `option` inside the block that owns that word, or is a `deactivate` of an open activation.
 * Returns null when nothing needs to move. The caret stays on the same character.
 */
internal fun seq3SourceAutoOutdent(source: String, caret: Int, dialect: Seq3Dialect): Seq3SourceEdit? {
    val position = caret.coerceIn(0, source.length)
    val lineStart = lineStartOf(source, position)
    val lineEnd = lineEndOf(source, position)
    val line = source.substring(lineStart, lineEnd)
    val last = seq3SourceLineLevels(source.substring(0, lineEnd), dialect).last()
    if (last.kind != Seq3SourceLineKind.CLOSER && last.kind != Seq3SourceLineKind.MIDDLE) return null
    val oldLeading = line.length - line.trimStart(' ', '\t').length
    val indent = seq3SourceIndentString(last.level)
    if (line.startsWith(indent) && oldLeading == indent.length) return null
    val text = source.substring(0, lineStart) + indent + line.trimStart(' ', '\t') + source.substring(lineEnd)
    val offsetInLine = position - lineStart
    val newCaret = lineStart + if (offsetInLine >= oldLeading) indent.length + (offsetInLine - oldLeading) else minOf(offsetInLine, indent.length)
    return Seq3SourceEdit(text, newCaret, newCaret)
}

/** The outdent that follows a small typed change, or null for anything else (paste, replacement,
 *  newline, selection) and always null with [autoIndent] off. */
internal fun seq3SourceTypedOutdent(
    oldText: String,
    newText: String,
    caret: Int,
    collapsed: Boolean,
    dialect: Seq3Dialect,
    autoIndent: Boolean = true,
): Seq3SourceEdit? {
    if (!autoIndent || !collapsed || oldText == newText) return null
    var prefix = 0
    val shortest = minOf(oldText.length, newText.length)
    while (prefix < shortest && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (suffix < shortest - prefix && oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
    val removed = oldText.length - prefix - suffix
    val added = newText.length - prefix - suffix
    if (removed > 8 || added > 8) return null
    if (newText.substring(prefix, prefix + added).contains('\n') || oldText.substring(prefix, prefix + removed).contains('\n')) return null
    return seq3SourceAutoOutdent(newText, caret, dialect)
}

/** The start offsets of the lines the selection [first, last] touches; a selection ending at a line start does not touch that line. */
private fun seq3SourceTouchedLineStarts(source: String, first: Int, last: Int): List<Int> {
    val starts = mutableListOf<Int>()
    var cursor = lineStartOf(source, first)
    val lastTouched = if (last > first && source[last - 1] == '\n') last - 1 else last
    while (true) {
        starts += cursor
        val next = source.indexOf('\n', cursor)
        if (next < 0 || next >= lastTouched) break
        cursor = next + 1
    }
    return starts
}

/** What Tab (or Shift+Tab with [shift]) does to one [line] starting at [lineStart]: null for none, else the line start paired with the
 *  characters added there or, when negative, removed. */
private fun seq3SourceTabChange(line: String, lineStart: Int, shift: Boolean, unit: String): Pair<Int, Int>? {
    if (!shift) return if (line.trim('\r').isEmpty()) null else lineStart to unit.length
    val removable = when {
        line.startsWith("\t") -> 1
        else -> line.takeWhile { it == ' ' }.length.coerceAtMost(SEQ3_SOURCE_INDENT_WIDTH)
    }
    return if (removable == 0) null else lineStart to -removable
}

/**
 * Tab / Shift+Tab. A collapsed Tab inserts one indent of spaces. Otherwise every touched line moves
 * one level in (Tab) or up to one level out (Shift+Tab, four spaces or one leading tab); a selection
 * that ends at the start of a line does not touch that line. The selection keeps covering the same
 * text.
 */
internal fun seq3SourceTab(source: String, selectionStart: Int, selectionEnd: Int, shift: Boolean): Seq3SourceEdit {
    val first = minOf(selectionStart, selectionEnd).coerceIn(0, source.length)
    val last = maxOf(selectionStart, selectionEnd).coerceIn(first, source.length)
    val unit = " ".repeat(SEQ3_SOURCE_INDENT_WIDTH)
    if (first == last && !shift) {
        val text = source.substring(0, first) + unit + source.substring(first)
        val caret = first + unit.length
        return Seq3SourceEdit(text, caret, caret)
    }
    // (line start, characters added at it or, when negative, removed from it)
    val changes = seq3SourceTouchedLineStarts(source, first, last).mapNotNull { lineStart ->
        seq3SourceTabChange(source.substring(lineStart, lineEndOf(source, lineStart)), lineStart, shift, unit)
    }
    if (changes.isEmpty()) return Seq3SourceEdit(source, selectionStart, selectionEnd)
    val text = buildString(source.length + changes.size * unit.length) {
        var copied = 0
        changes.forEach { (lineStart, delta) ->
            append(source, copied, lineStart)
            if (delta > 0) {
                append(unit)
                copied = lineStart
            } else {
                copied = lineStart - delta
            }
        }
        append(source, copied, source.length)
    }

    fun map(offset: Int): Int = offset + changes.sumOf { (lineStart, delta) ->
        if (delta > 0) (if (offset >= lineStart) delta else 0)
        else -(offset - lineStart).coerceIn(0, -delta)
    }
    return Seq3SourceEdit(text, map(selectionStart), map(selectionEnd))
}
