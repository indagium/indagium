package com.indagium.utils

import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatAttribute
import com.indagium.model.ProfileEpochUnit
import com.indagium.model.ProfileTimestampKind
import java.util.Locale

/*
 * Builds a regex log format from one sample line and a choice per column, so a user never has to
 * write the regex. The sample is cut into pieces; each piece is given a meaning; the result is the
 * record regex with one capture group per meaningful column and the capture numbers per field.
 */

private const val MAX_SAMPLE_PIECES = 40
private const val MAX_PIECE_PREVIEW_CHARS = 2_000
private const val MAX_CUSTOM_SEPARATOR_CHARS = 8
private const val EPOCH_SECONDS_DIGITS = 10
private const val EPOCH_MILLIS_DIGITS = 13
private const val EPOCH_MICROS_DIGITS = 16
private const val EPOCH_NANOS_DIGITS = 19

/** What separates the columns of a line. */
enum class ColumnSeparator { WHITESPACE, TAB, CUSTOM }

/** The meaning a user gives to one piece of the sample line. */
enum class ColumnKind(val label: String, val role: LogFieldRole?) {
    TIMESTAMP("Timestamp", LogFieldRole.TIMESTAMP),
    LEVEL("Level", LogFieldRole.SEVERITY),
    TAG("Tag", LogFieldRole.COMPONENT),
    PID("Process ID", LogFieldRole.PROCESS_ID),
    TID("Thread ID", LogFieldRole.THREAD_ID),
    MESSAGE("Message", LogFieldRole.MESSAGE),
    EXTRA("Extra field", null),
    SKIP("Skip", null),
}

/** How a piece was wrapped in the sample; the wrapping is matched but not captured. */
enum class PieceWrap(val open: String, val close: String, val inner: String) {
    NONE("", "", ""),
    SQUARE("\\[", "\\]", "[^\\]]*"),
    ROUND("\\(", "\\)", "[^)]*"),
}

/** One piece of the sample line: its text without brackets or a trailing colon. */
data class SamplePiece(
    val text: String,
    val wrap: PieceWrap = PieceWrap.NONE,
    val trailingColon: Boolean = false,
    /** Set when this piece was glued to the one before it by this character, as `SDK` is in `D/SDK`. */
    val joinBefore: Char? = null,
)

/** The generated parts of a profile. Capture numbers are 1-based, as the regex engine expects. */
data class ColumnFormat(
    val regexPattern: String,
    val fieldMappings: Map<LogFieldRole, String>,
    val attributes: List<LogFormatAttribute>,
    val visibleColumns: List<LogFieldRole>,
    val timestampKind: ProfileTimestampKind,
    val epochUnit: ProfileEpochUnit?,
)

/** Cuts [sample]'s first non-blank line into pieces. An unusable separator gives no pieces. */
fun splitSampleColumns(
    sample: String,
    separator: ColumnSeparator,
    customSeparator: String = "",
    splitChars: String = "",
): List<SamplePiece> {
    val line = sample.lineSequence().firstOrNull(String::isNotBlank)?.take(MAX_PIECE_PREVIEW_CHARS) ?: return emptyList()
    val pieces = when (separator) {
        ColumnSeparator.WHITESPACE -> splitOnWhitespace(line).flatMap { splitGluedWord(it, splitChars) }
        ColumnSeparator.TAB -> line.split('\t').map { SamplePiece(it.trim()) }
        ColumnSeparator.CUSTOM -> {
            val literal = customSeparator.trim()
            if (literal.isEmpty() || literal.length > MAX_CUSTOM_SEPARATOR_CHARS) return emptyList()
            line.split(literal).map { SamplePiece(it.trim()) }
        }
    }
    return pieces.take(MAX_SAMPLE_PIECES)
}

/** Words, except that `[...]` and `(...)` stay one piece even with spaces inside. */
private fun splitOnWhitespace(line: String): List<SamplePiece> {
    val pieces = mutableListOf<SamplePiece>()
    var index = 0
    while (index < line.length) {
        if (line[index].isWhitespace()) {
            index++
            continue
        }
        val (piece, next) = wrappedPieceAt(line, index) ?: wordAt(line, index)
        pieces += piece
        index = next
    }
    return pieces
}

/** A `[...]` or `(...)` piece starting at [index] with its end, or null when the bracket is just part of a word. */
private fun wrappedPieceAt(line: String, index: Int): Pair<SamplePiece, Int>? {
    val wrap = when (line[index]) {
        '[' -> PieceWrap.SQUARE
        '(' -> PieceWrap.ROUND
        else -> return null
    }
    val closing = line.indexOf(if (wrap == PieceWrap.SQUARE) ']' else ')', index + 1)
    if (closing < 0) return null
    val after = line.getOrNull(closing + 1)
    if (after != null && !after.isWhitespace() && after != ':') return null
    val colon = after == ':' && line.getOrNull(closing + 2)?.isWhitespace() != false
    return SamplePiece(line.substring(index + 1, closing), wrap, colon) to closing + if (colon) 2 else 1
}

private fun wordAt(line: String, index: Int): Pair<SamplePiece, Int> {
    var end = index
    while (end < line.length && !line[end].isWhitespace()) end++
    val word = line.substring(index, end)
    val colon = word.length > 1 && word.endsWith(':')
    return SamplePiece(if (colon) word.dropLast(1) else word, PieceWrap.NONE, colon) to end
}

private val LEVEL_SLASH_TAG = Regex("^([A-Za-z]+)/(\\S+)$")
private const val MAX_SPLIT_CHARS = 4

/**
 * Cuts one word into the columns glued inside it. `D/SDK` (level, slash, tag) is recognised on its
 * own; [splitChars] are characters the user asked to cut at as well. A word that would leave an
 * empty part is kept whole.
 */
private fun splitGluedWord(piece: SamplePiece, splitChars: String): List<SamplePiece> {
    if (piece.wrap != PieceWrap.NONE) return listOf(piece)
    val cutAt = splitChars.filterNot { it.isWhitespace() || it.isLetterOrDigit() }.take(MAX_SPLIT_CHARS).toSet()
    val parts = mutableListOf<SamplePiece>()
    if (cutAt.isEmpty()) {
        val match = LEVEL_SLASH_TAG.matchEntire(piece.text)?.takeIf { looksLikeLevel(it.groupValues[1]) } ?: return listOf(piece)
        parts += SamplePiece(match.groupValues[1])
        parts += SamplePiece(match.groupValues[2], joinBefore = '/')
    } else {
        var start = 0
        var join: Char? = null
        for (index in 0..piece.text.length) {
            val atEnd = index == piece.text.length
            if (!atEnd && piece.text[index] !in cutAt) continue
            parts += SamplePiece(piece.text.substring(start, index), joinBefore = join)
            if (!atEnd) join = piece.text[index]
            start = index + 1
        }
    }
    if (parts.size < 2 || parts.any { it.text.isEmpty() }) return listOf(piece)
    parts[parts.lastIndex] = parts.last().copy(trailingColon = piece.trailingColon)
    return parts
}

private val DATE_PIECE = Regex("^\\d{4}[-/.]\\d{2}[-/.]\\d{2}$|^\\d{2}[-/]\\d{2}$")
private val TIME_PIECE = Regex("^\\d{1,2}:\\d{2}:\\d{2}(?:[.,]\\d+)?$")
private val ISO_PIECE = Regex("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}.*$")
private val EPOCH_PIECE = Regex("^\\d{10}(?:\\d{3}){0,3}$|^\\d{10}\\.\\d+$")
private val RELATIVE_PIECE = Regex("^\\d{1,9}\\.\\d+$")
private val NUMBER_PIECE = Regex("^\\d{1,9}$")
private val LEVEL_WORDS = setOf(
    "trace", "verbose", "debug", "info", "notice", "warn", "warning", "error", "err", "fatal", "critical", "crit",
)
private val LEVEL_LETTERS = setOf("V", "D", "I", "W", "E", "A", "F")

private fun looksLikeTimestamp(text: String): Boolean =
    ISO_PIECE.matches(text) || TIME_PIECE.matches(text) || EPOCH_PIECE.matches(text)

private fun looksLikeLevel(text: String): Boolean =
    text.lowercase(Locale.ROOT) in LEVEL_WORDS || text in LEVEL_LETTERS

/** A first guess of each piece's meaning; the user corrects it. Never fails: unknown pieces are skipped. */
fun guessColumnKinds(pieces: List<SamplePiece>): List<ColumnKind> {
    val kinds = arrayOfNulls<ColumnKind>(pieces.size)
    markTimestamp(pieces, kinds)
    pieces.indices.firstOrNull { kinds[it] == null && looksLikeLevel(pieces[it].text) }?.let { level ->
        kinds[level] = ColumnKind.LEVEL
        // "D/SDK": what is glued to the level with a slash is the tag.
        if (pieces.getOrNull(level + 1)?.joinBefore == '/' && kinds[level + 1] == null) kinds[level + 1] = ColumnKind.TAG
    }
    val messageRange = guessMessageRange(kinds)
    if (messageRange != null) {
        // "Tag: text" — a word with a colon right before the text names the component.
        val first = messageRange.first
        val tagged = pieces[first].trailingColon && first < messageRange.last && ColumnKind.TAG !in kinds
        for (index in messageRange) kinds[index] = if (tagged && index == first) ColumnKind.TAG else ColumnKind.MESSAGE
    }
    // Bare numbers that are left are usually the process and thread ids, in that order.
    val numbers = pieces.indices.filter { kinds[it] == null && NUMBER_PIECE.matches(pieces[it].text) }
    numbers.getOrNull(0)?.let { kinds[it] = ColumnKind.PID }
    numbers.getOrNull(1)?.let { kinds[it] = ColumnKind.TID }
    return kinds.map { it ?: ColumnKind.SKIP }
}

private fun markTimestamp(pieces: List<SamplePiece>, kinds: Array<ColumnKind?>) {
    for (index in pieces.indices) {
        val text = pieces[index].text
        val next = pieces.getOrNull(index + 1)
        if (DATE_PIECE.matches(text) && next != null && TIME_PIECE.matches(next.text)) {
            kinds[index] = ColumnKind.TIMESTAMP
            kinds[index + 1] = ColumnKind.TIMESTAMP
            return
        }
        if (looksLikeTimestamp(text) || (index == 0 && RELATIVE_PIECE.matches(text))) {
            kinds[index] = ColumnKind.TIMESTAMP
            return
        }
    }
}

/** The text is what follows the last recognised column; failing that, the longest unrecognised run. */
private fun guessMessageRange(kinds: Array<ColumnKind?>): IntRange? {
    val lastKnown = kinds.indexOfLast { it != null }
    if (lastKnown < kinds.lastIndex) return (lastKnown + 1)..kinds.lastIndex
    var best: IntRange? = null
    var start = -1
    for (index in 0..kinds.size) {
        val free = index < kinds.size && kinds[index] == null
        if (free && start < 0) start = index
        if (!free && start >= 0) {
            val run = start until index
            if (best == null || run.count() > best.count()) best = run
            start = -1
        }
    }
    return best
}

/** How the sample's timestamp text should be read; AUTO when it is not one of the clear cases. */
fun guessTimestampSettings(text: String): Pair<ProfileTimestampKind, ProfileEpochUnit?> = when {
    EPOCH_PIECE.matches(text) -> ProfileTimestampKind.EPOCH to when (text.substringBefore('.').length) {
        EPOCH_SECONDS_DIGITS -> ProfileEpochUnit.SECONDS
        EPOCH_MILLIS_DIGITS -> ProfileEpochUnit.MILLIS
        EPOCH_MICROS_DIGITS -> ProfileEpochUnit.MICROS
        EPOCH_NANOS_DIGITS -> ProfileEpochUnit.NANOS
        else -> ProfileEpochUnit.SECONDS
    }
    RELATIVE_PIECE.matches(text) -> ProfileTimestampKind.RELATIVE to ProfileEpochUnit.SECONDS
    TIME_PIECE.matches(text) -> ProfileTimestampKind.TIME_OF_DAY to null
    text.contains('T') && ISO_PIECE.matches(text) -> ProfileTimestampKind.ISO_8601 to null
    else -> ProfileTimestampKind.AUTO to null
}

private data class ColumnGroup(val kind: ColumnKind, val pieces: List<SamplePiece>, val firstIndex: Int)

/**
 * Turns the chosen [kinds] (one per piece) into a regex and field numbers. Fails with a message
 * meant for the user when the choice cannot work, for example a field chosen for two columns.
 */
fun buildColumnFormat(
    pieces: List<SamplePiece>,
    kinds: List<ColumnKind>,
    separator: ColumnSeparator,
    customSeparator: String = "",
    extraNames: Map<Int, String> = emptyMap(),
): Result<ColumnFormat> = runCatching {
    require(pieces.isNotEmpty()) { "Paste a sample line first." }
    require(pieces.size == kinds.size) { "Choose a meaning for every column." }
    val groups = groupColumns(pieces, kinds, separator)
    listOf(ColumnKind.TIMESTAMP, ColumnKind.LEVEL, ColumnKind.TAG, ColumnKind.PID, ColumnKind.TID, ColumnKind.MESSAGE).forEach { kind ->
        require(groups.count { it.kind == kind } <= 1) {
            "${kind.label} is chosen for columns that are not next to each other. Keep one, or set the others to Skip."
        }
    }
    require(groups.any { it.kind == ColumnKind.MESSAGE }) { "Choose which column is the message." }

    val syntax = SeparatorSyntax.of(separator, customSeparator)
    val mappings = linkedMapOf<LogFieldRole, String>()
    val attributes = mutableListOf<LogFormatAttribute>()
    val usedIds = mutableSetOf<String>()
    var capture = 0
    val pattern = StringBuilder("^\\s*")
    groups.forEachIndexed { groupIndex, group ->
        if (group.kind != ColumnKind.SKIP) capture++
        val role = group.kind.role
        if (role != null) mappings[role] = capture.toString()
        if (group.kind == ColumnKind.EXTRA) {
            val label = extraNames[group.firstIndex]?.trim().orEmpty().ifEmpty { "Field ${attributes.size + 1}" }
            attributes += LogFormatAttribute(extraAttributeId(label, usedIds), label, capture.toString(), visibleByDefault = true)
        }
        val join = group.pieces.first().joinBefore
        if (groupIndex > 0) pattern.append(if (join != null) escapeLiteral(join) else syntax.between)
        pattern.append(syntax.pattern(group, groups.getOrNull(groupIndex + 1)?.pieces?.first()?.joinBefore))
    }
    pattern.append("\\s*$")
    val timestampText = groups.firstOrNull { it.kind == ColumnKind.TIMESTAMP }?.pieces?.joinToString(" ") { it.text }
    val (timestampKind, epochUnit) = timestampText?.let(::guessTimestampSettings) ?: (ProfileTimestampKind.AUTO to null)
    ColumnFormat(
        regexPattern = pattern.toString(),
        fieldMappings = mappings,
        attributes = attributes,
        visibleColumns = groups.mapNotNull { it.kind.role },
        timestampKind = timestampKind,
        epochUnit = epochUnit,
    ).also { validateSafeProfileRegex(it.regexPattern) }
}

/**
 * Neighbouring pieces become one column where that is what the user means: a date and a time are
 * one timestamp, and every word of the text is one message. That only works between plain words.
 */
private fun groupColumns(pieces: List<SamplePiece>, kinds: List<ColumnKind>, separator: ColumnSeparator): List<ColumnGroup> {
    val groups = mutableListOf<ColumnGroup>()
    pieces.forEachIndexed { index, piece ->
        val kind = kinds[index]
        val last = groups.lastOrNull()
        val joins = last != null && last.kind == kind &&
            (kind == ColumnKind.MESSAGE || (kind == ColumnKind.TIMESTAMP && separator == ColumnSeparator.WHITESPACE)) &&
            (kind == ColumnKind.MESSAGE || (piece.plainWord() && last.pieces.all { it.plainWord() }))
        if (joins) {
            groups[groups.lastIndex] = last!!.copy(pieces = last.pieces + piece)
        } else {
            groups += ColumnGroup(kind, listOf(piece), index)
        }
    }
    return groups
}

private fun SamplePiece.plainWord(): Boolean = wrap == PieceWrap.NONE && joinBefore == null

private val RESERVED_ATTRIBUTE_IDS = LogFieldRole.entries.map { it.name.lowercase(Locale.ROOT) }.toSet() +
    setOf("row_number", "time_delta", "pid_name")
private const val MAX_ATTRIBUTE_ID_CHARS = 40

private fun extraAttributeId(label: String, used: MutableSet<String>): String {
    val base = label.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(MAX_ATTRIBUTE_ID_CHARS)
        .ifEmpty { "field" }
    var id = if (base in RESERVED_ATTRIBUTE_IDS) "x_$base" else base
    var suffix = 2
    while (id in used) id = "${base}_${suffix++}"
    used += id
    return id
}

private fun escapeLiteral(char: Char): String = if (char.isLetterOrDigit()) char.toString() else "\\$char"

/** The regex fragments one separator needs: a captured column, a skipped one, and what sits between. */
private class SeparatorSyntax(val between: String, private val cell: String, private val whitespace: Boolean) {
    /** [nextJoin] is the character that glues the following column to this one, when there is one. */
    fun pattern(group: ColumnGroup, nextJoin: Char? = null): String {
        val captured = group.kind != ColumnKind.SKIP
        if (group.kind == ColumnKind.MESSAGE && (group.pieces.size > 1 || group.pieces.single().wrap == PieceWrap.NONE)) {
            // The text takes whatever the fixed columns around it leave.
            return "(.*?)"
        }
        if (!whitespace) return if (captured) "($cell)" else cell
        if (group.pieces.size > 1) return "(" + group.pieces.joinToString("\\s+") { "\\S+" } + ")"
        val piece = group.pieces.single()
        val colon = if (piece.trailingColon) ":" else ""
        return when {
            piece.wrap != PieceWrap.NONE -> {
                val inner = if (captured) "(${piece.wrap.inner})" else piece.wrap.inner
                piece.wrap.open + inner + piece.wrap.close + colon
            }
            piece.trailingColon -> if (captured) "(\\S+?):" else "\\S+?:"
            // A glued column ends at the glue character, not at the next space.
            nextJoin != null -> "[^\\s${escapeLiteral(nextJoin)}]+".let { if (captured) "($it)" else it }
            else -> if (captured) "(\\S+)" else "\\S+"
        }
    }

    companion object {
        fun of(separator: ColumnSeparator, customSeparator: String): SeparatorSyntax = when (separator) {
            ColumnSeparator.WHITESPACE -> SeparatorSyntax("\\s+", "\\S+", whitespace = true)
            ColumnSeparator.TAB -> SeparatorSyntax("\\t", "[^\\t]*", whitespace = false)
            ColumnSeparator.CUSTOM -> {
                val literal = customSeparator.trim()
                require(literal.isNotEmpty()) { "Type the separator between columns." }
                require(literal.length <= MAX_CUSTOM_SEPARATOR_CHARS) { "The separator can be at most $MAX_CUSTOM_SEPARATOR_CHARS characters." }
                val escaped = literal.map { if (it.isLetterOrDigit()) it.toString() else "\\$it" }.joinToString("")
                // A one-character separator cannot occur inside a fixed column; a longer one can only be matched lazily.
                val cell = if (literal.length == 1) "[^${if (literal[0].isLetterOrDigit()) literal else "\\$literal"}]*?" else ".*?"
                SeparatorSyntax("\\s*$escaped\\s*", cell, whitespace = false)
            }
        }
    }
}
