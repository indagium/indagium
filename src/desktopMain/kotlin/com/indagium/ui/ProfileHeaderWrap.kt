package com.indagium.ui

import com.indagium.utils.ProfileHeaderCell
import kotlin.math.floor

/**
 * Where a wrapping profile header breaks, so that each label stays over its own values. It simulates the widest row instead of
 * letting the text wrap on its own: a row breaks before a column only when that column's VALUE does not fit the line (the padding
 * after the previous column may hang past the edge), while a header would break when the LABEL does not fit, and labels are
 * often longer than values.
 *
 * Walks [cells] with the position of the next column on the line: the first line starts at 0, a continuation at [indentChars].
 * A column after the first on its line starts a new one when its value would end past [fieldChars]. The trailing message
 * column starts a new line only when [messageOnOwnLine], never otherwise. Returns the header offsets to break before; empty
 * for a field not measured yet ([fieldChars] 0).
 */
internal fun profileHeaderBreakOffsets(
    cells: List<ProfileHeaderCell>,
    fieldChars: Float,
    indentChars: Int,
    messageOnOwnLine: Boolean,
): List<Int> {
    if (fieldChars <= 0f) return emptyList()
    val breaks = mutableListOf<Int>()
    var position = 0
    var firstOnLine = true
    for (cell in cells) {
        val breaksBefore = !firstOnLine && if (cell.isMessage) messageOnOwnLine else position + cell.valueChars > fieldChars
        if (breaksBefore) {
            breaks += cell.labelStart
            position = indentChars
        }
        position += cell.advanceChars
        firstOnLine = false
    }
    return breaks
}

/**
 * The wrapping header as drawn: [header] with the words of each label joined by no-break spaces, a '\n' before each of
 * [breakOffsets], trailing spaces off every line, and a line that is still longer than [fieldChars] (a label wider than the
 * values under it, last on its line) cut short with an ellipsis. Continuation lines are [indentChars] further right. A line
 * may reach [overhangChars] past [fieldChars] (the end padding the text is drawn into) before it is cut.
 */
internal fun profileWrappingHeaderText(
    header: String,
    breakOffsets: List<Int>,
    fieldChars: Float,
    indentChars: Int,
    overhangChars: Float = 0f,
): String {
    val joined = profileHeaderForWrapping(header)
    val bounds = listOf(0) + breakOffsets.filter { it in 1 until joined.length } + joined.length
    return bounds.zipWithNext().mapIndexed { index, (from, to) ->
        val line = joined.substring(from, to).trimEnd(' ')
        val room = floor(fieldChars + overhangChars).toInt() - if (index > 0) indentChars else 0
        if (fieldChars > 0f && line.length > room && room >= 2) line.take(room - 1) + "…" else line
    }.joinToString("\n")
}
