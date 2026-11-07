package com.indagium.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.parseSeq3SourceArrowEndpoints

/*
 * Token colouring of the PlantUML / Mermaid source editor, pure and UI-free so the roles can be unit
 * tested. A line is split into tokens, each with one role; text without a role keeps the editor's
 * plain colour. Offsets are offsets into the text that was tokenized (the visible text: hidden
 * marker lines are already gone from it). Colour only, never a weight or a style: a different
 * glyph width would put the caret off its character.
 */

internal enum class Seq3SourceRole {
    /** `participant`, `actor`, `title`, `note`, `activate`, the `as` of a declaration, headers. */
    KEYWORD,

    /** `loop`, `alt`, `else`, `end` and the other block words: one role, so `end` is never dimmer. */
    BLOCK,

    /** A participant name, alias or note scope. */
    NAME,

    /** An arrow token, with the Mermaid `+`/`-` activation sign next to it. */
    ARROW,

    /** A quoted participant name. */
    QUOTED,

    /** The text after a message's colon, a block condition or a title. */
    LABEL,

    COMMENT,
}

internal data class Seq3SourceToken(val start: Int, val end: Int, val role: Seq3SourceRole)

private val BLOCK_WORDS = setOf(
    "loop", "alt", "opt", "par", "critical", "break", "group", "rect", "else", "and", "option", "end",
)

/** Statements whose first word is a keyword and whose rest needs no further structure. */
private val GENERIC_KEYWORDS = setOf(
    "autonumber", "return", "box", "skinparam", "hide", "show", "header", "footer", "legend", "newpage",
    "endlegend", "link", "links", "properties", "details",
)

private val PARTICIPANT_WORDS = setOf("participant", "actor")
private val LIFECYCLE_WORDS = setOf("activate", "deactivate", "destroy")

/** The arrow tokens of both dialects, longest first so `-->>` is not read as `-->`. */
private val ARROW_TOKEN = Regex("""\[o->|->o]|-->>|->>|-\)|-->|->""")
private val WORD = Regex("""\S+""")
private val NAME_PIECE = Regex("""[^\s,]+""")
private val DECLARATION_PIECE = Regex(""""(?:[^"\\]|\\.)*"?|\S+""")
private val NOTE_SIDE = Regex("""(?i)^(?:(?:left|right|top|bottom)\s+of|over)\b""")

/** The role of every coloured stretch of [source], in order. */
internal fun tokenizeSeq3Source(source: String, dialect: Seq3Dialect): List<Seq3SourceToken> {
    val tokens = ArrayList<Seq3SourceToken>()
    var offset = 0
    source.split('\n').forEach { line ->
        tokenizeLine(line, offset, dialect, tokens)
        offset += line.length + 1
    }
    return tokens
}

private fun tokenizeLine(line: String, base: Int, dialect: Seq3Dialect, out: MutableList<Seq3SourceToken>) {
    val trimmed = line.trimEnd()
    val leading = trimmed.length - trimmed.trimStart().length
    val text = trimmed.substring(leading)
    if (text.isEmpty()) return
    val start = base + leading
    fun add(from: Int, to: Int, role: Seq3SourceRole) {
        if (from < to) out += Seq3SourceToken(start + from, start + to, role)
    }
    val comment = if (dialect == Seq3Dialect.MERMAID) text.startsWith("%%") else text.startsWith("'")
    if (comment) return add(0, text.length, Seq3SourceRole.COMMENT)

    val word = text.takeWhile { !it.isWhitespace() }
    val lower = word.lowercase()
    val header = if (dialect == Seq3Dialect.MERMAID) word == "sequenceDiagram" else word.startsWith("@startuml") || word.startsWith("@enduml")
    if (header) return add(0, word.length, Seq3SourceRole.KEYWORD)

    // The importer's own reading decides what a message line is, so `Loop -> B: x` stays a message.
    if (parseSeq3SourceArrowEndpoints(text, dialect) != null) return tokenizeMessage(text, dialect, ::add)

    when {
        lower in BLOCK_WORDS -> {
            add(0, word.length, Seq3SourceRole.BLOCK)
            addLabel(text, word.length, ::add)
        }
        lower in PARTICIPANT_WORDS -> {
            add(0, word.length, Seq3SourceRole.KEYWORD)
            tokenizeDeclaration(text, word.length, ::add)
        }
        lower == "create" -> {
            add(0, word.length, Seq3SourceRole.KEYWORD)
            val next = WORD.find(text, word.length)
            if (next != null && next.value in PARTICIPANT_WORDS) {
                add(next.range.first, next.range.last + 1, Seq3SourceRole.KEYWORD)
                tokenizeDeclaration(text, next.range.last + 1, ::add)
            } else addNames(text, word.length, text.length, ::add)
        }
        lower in LIFECYCLE_WORDS -> {
            add(0, word.length, Seq3SourceRole.KEYWORD)
            addNames(text, word.length, text.length, ::add)
        }
        lower == "title" -> {
            add(0, word.length, Seq3SourceRole.KEYWORD)
            addLabel(text, word.length, ::add)
        }
        lower == "note" || lower == "ref" -> tokenizeNote(text, word.length, ::add)
        lower in GENERIC_KEYWORDS -> add(0, word.length, Seq3SourceRole.KEYWORD)
    }
}

/** `A -> B: label`: names, the arrow (and a Mermaid activation sign), then the label after the colon. */
private fun tokenizeMessage(text: String, dialect: Seq3Dialect, add: (Int, Int, Seq3SourceRole) -> Unit) {
    val arrow = ARROW_TOKEN.find(text) ?: return
    var arrowEnd = arrow.range.last + 1
    // Only Mermaid has the `+`/`-` sign; a PlantUML name may start with either character.
    val signAt = if (dialect == Seq3Dialect.MERMAID) (arrowEnd until text.length).firstOrNull { !text[it].isWhitespace() } else null
    if (signAt != null && (text[signAt] == '+' || text[signAt] == '-') && signAt + 1 < text.length && !text[signAt + 1].isWhitespace() &&
        text[signAt + 1] != ':'
    ) arrowEnd = signAt + 1
    val colon = text.indexOf(':', arrowEnd).takeIf { it >= 0 } ?: text.length
    addNames(text, 0, arrow.range.first, add)
    add(arrow.range.first, arrow.range.last + 1, Seq3SourceRole.ARROW)
    if (arrowEnd > arrow.range.last + 1) add(arrow.range.last + 1, arrowEnd, Seq3SourceRole.ARROW)
    addNames(text, arrowEnd, colon, add)
    if (colon < text.length) addLabel(text, colon + 1, add)
}

/** A participant / actor declaration after its keyword: names, quoted names and the `as` between them. */
private fun tokenizeDeclaration(text: String, from: Int, add: (Int, Int, Seq3SourceRole) -> Unit) {
    var seenAs = false
    DECLARATION_PIECE.findAll(text, from).forEach { piece ->
        val role = when {
            piece.value.startsWith('"') -> Seq3SourceRole.QUOTED
            piece.value == "as" && !seenAs -> Seq3SourceRole.KEYWORD.also { seenAs = true }
            else -> Seq3SourceRole.NAME
        }
        add(piece.range.first, piece.range.last + 1, role)
    }
}

/** `note over A,B: text`, `note right of A: text`, `ref over A: text`. */
private fun tokenizeNote(text: String, wordEnd: Int, add: (Int, Int, Seq3SourceRole) -> Unit) {
    add(0, wordEnd, Seq3SourceRole.KEYWORD)
    var scopeStart = wordEnd
    while (scopeStart < text.length && text[scopeStart].isWhitespace()) scopeStart++
    val side = NOTE_SIDE.find(text.substring(scopeStart))
    if (side != null) {
        add(scopeStart, scopeStart + side.value.length, Seq3SourceRole.KEYWORD)
        scopeStart += side.value.length
    }
    val colon = text.indexOf(':', scopeStart).takeIf { it >= 0 } ?: text.length
    addNames(text, scopeStart, colon, add)
    if (colon < text.length) addLabel(text, colon + 1, add)
}

private fun addNames(text: String, from: Int, to: Int, add: (Int, Int, Seq3SourceRole) -> Unit) {
    if (from >= to) return
    NAME_PIECE.findAll(text.substring(from, to)).forEach { piece ->
        add(from + piece.range.first, from + piece.range.last + 1, Seq3SourceRole.NAME)
    }
}

/** The label from [from] on, without the whitespace that opens it. */
private fun addLabel(text: String, from: Int, add: (Int, Int, Seq3SourceRole) -> Unit) {
    var labelStart = from
    while (labelStart < text.length && text[labelStart].isWhitespace()) labelStart++
    add(labelStart, text.length, Seq3SourceRole.LABEL)
}

/** The editor's colours per role. */
internal data class Seq3SourcePalette(
    val text: Color,
    val keyword: Color,
    val block: Color,
    val name: Color,
    val arrow: Color,
    val quoted: Color,
    val label: Color,
    val comment: Color,
) {
    fun colorOf(role: Seq3SourceRole): Color = when (role) {
        Seq3SourceRole.KEYWORD -> keyword
        Seq3SourceRole.BLOCK -> block
        Seq3SourceRole.NAME -> name
        Seq3SourceRole.ARROW -> arrow
        Seq3SourceRole.QUOTED -> quoted
        Seq3SourceRole.LABEL -> label
        Seq3SourceRole.COMMENT -> comment
    }
}

/**
 * Every role comes from a [ThemeColors] colour that is readable on the editor's panel in all
 * presets (`tx`, `ts`, `ac`, `success`), or from the two sequence colours pulled toward `tx` so a
 * pale one cannot vanish on a light theme or a dark one on a dark theme. `Seq3SourceTokensTest`
 * holds each role to 3:1 against `p2` on every preset.
 */
internal fun seq3SourcePalette(colors: ThemeColors): Seq3SourcePalette = Seq3SourcePalette(
    text = colors.tx,
    keyword = colors.ac,
    block = colors.success,
    name = lerp(colors.seq1, colors.tx, 0.5f),
    arrow = colors.tx,
    quoted = lerp(colors.seq2, colors.tx, 0.5f),
    label = lerp(colors.tx, colors.ts, 0.35f),
    comment = colors.ts,
)

internal fun highlightSeq3Source(source: String, dialect: Seq3Dialect, palette: Seq3SourcePalette): AnnotatedString =
    buildAnnotatedString {
        append(source)
        tokenizeSeq3Source(source, dialect).forEach { token ->
            addStyle(SpanStyle(color = palette.colorOf(token.role)), token.start, token.end)
        }
    }
