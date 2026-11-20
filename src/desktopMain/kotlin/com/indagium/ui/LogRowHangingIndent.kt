package com.indagium.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private const val ADVANCE_SAMPLE_CHARS = 64

/**
 * The text with one paragraph over all of it whose continuation lines (after a soft wrap and after a
 * wrap-limit line break alike) start [indent] from the left, while the first line starts at 0. The
 * characters are untouched, so selection offsets and copied text stay what they were.
 */
internal fun AnnotatedString.withHangingIndent(indent: TextUnit): AnnotatedString {
    if (length == 0 || indent.value <= 0f) return this
    val whole = this
    return buildAnnotatedString {
        append(whole)
        addStyle(ParagraphStyle(textIndent = TextIndent(firstLine = 0.sp, restLine = indent)), 0, whole.length)
    }
}

/** Horizontal advance, in pixels, of one character of the monospace [family] at [fontSize]. */
internal fun monoAdvancePx(measurer: TextMeasurer, family: FontFamily, fontSize: TextUnit): Float {
    val layout = measurer.measure(
        AnnotatedString("M".repeat(ADVANCE_SAMPLE_CHARS)),
        TextStyle(fontFamily = family, fontSize = fontSize),
    )
    return layout.getHorizontalPosition(ADVANCE_SAMPLE_CHARS, usePrimaryDirection = true) / ADVANCE_SAMPLE_CHARS
}

/** [monoAdvancePx] for the current composition, measured again only when the font or size changes. */
@Composable
internal fun rememberMonoAdvancePx(family: FontFamily, fontSize: TextUnit): Float {
    val measurer = rememberTextMeasurer(cacheSize = 1)
    return remember(measurer, family, fontSize) { monoAdvancePx(measurer, family, fontSize) }
}

/** The width of [chars] monospace characters of [advancePx] pixels each, in sp at this density and font scale. */
internal fun hangingIndentSp(chars: Int, advancePx: Float, density: Density): TextUnit =
    with(density) { (chars * advancePx).toSp() }

/** The most of a row's width the last-resort hanging step ([MIN_HANGING_INDENT_CHARS]) may take. */
internal const val MAX_HANGING_INDENT_FRACTION = 0.5f

/** How many characters a wrapped line must have left after the message or fallback column's indent for that indent to be used. */
internal const val MIN_HANGING_ROOM_CHARS = 30

/** The last indent step before none, for a pane too narrow for the column-based ones. */
internal const val MIN_HANGING_INDENT_CHARS = 4

/** How many monospace characters of [advancePx] pixels fit a field [fieldWidthPx] wide; 0 while it is not measured yet. */
internal fun fieldCharsFor(fieldWidthPx: Int, advancePx: Float): Float =
    if (fieldWidthPx <= 0 || advancePx <= 0f) 0f else fieldWidthPx / advancePx

/**
 * Whether an indent of [indentChars] still leaves [MIN_HANGING_ROOM_CHARS] characters to read after it in a
 * field of [fieldChars]. A field not measured yet (0) is taken as wide enough.
 */
internal fun hangingIndentLeavesRoom(indentChars: Int, fieldChars: Float): Boolean =
    fieldChars <= 0f || fieldChars - indentChars >= MIN_HANGING_ROOM_CHARS

/** The last-resort rule: a short step is used while it is at most [MAX_HANGING_INDENT_FRACTION] of the field. */
private fun hangingStepFits(indentChars: Int, fieldChars: Float): Boolean =
    fieldChars <= 0f || indentChars <= fieldChars * MAX_HANGING_INDENT_FRACTION

/**
 * The indent a profile row's wrapped lines hang at, by the characters left after it in a field of
 * [fieldChars]: its message column when [MIN_HANGING_ROOM_CHARS] remain after it, else just after its
 * first column ([fallbackChars]) on the same rule, else a short step of [MIN_HANGING_INDENT_CHARS] while
 * that is at most half the field, else none. A row whose message starts far to the right (many columns)
 * or that has no message still keeps its continuation lines off the first column, so they never read as a
 * new row.
 */
internal fun chooseHangingIndentChars(messageChars: Int, fallbackChars: Int, fieldChars: Float): Int = when {
    messageChars > 0 && hangingIndentLeavesRoom(messageChars, fieldChars) -> messageChars
    fallbackChars > 0 && hangingIndentLeavesRoom(fallbackChars, fieldChars) -> fallbackChars
    // A narrow tile with many columns: a short fixed step still tells a continuation line from a new row.
    (messageChars > 0 || fallbackChars > 0) && hangingStepFits(MIN_HANGING_INDENT_CHARS, fieldChars) -> MIN_HANGING_INDENT_CHARS
    else -> 0
}

/**
 * Whether a message starting [messageChars] characters into a first line of [firstLineChars] goes onto a
 * line of its own: the column is squeezed (too little room left after it, by [hangingIndentLeavesRoom])
 * and the line is longer than the field anyway, so it would wrap. A field not measured yet never breaks.
 */
internal fun messageBreaksOntoOwnLine(messageChars: Int, firstLineChars: Int, fieldChars: Float): Boolean =
    messageChars > 0 && fieldChars > 0f && !hangingIndentLeavesRoom(messageChars, fieldChars) && firstLineChars > fieldChars

/**
 * The text with a visual line break inserted before [offset]. Manual wrap mode inserts the same kind of
 * '\n' (see visualLogLineForWrapLimit) and copying strips it (stripVisualWrapBreaks), so the characters
 * of the row, and its spans, are otherwise untouched. [offset] outside the text leaves it as it was.
 */
internal fun AnnotatedString.withBreakBefore(offset: Int): AnnotatedString {
    if (offset <= 0 || offset >= length) return this
    val whole = this
    return buildAnnotatedString {
        append(whole.subSequence(0, offset))
        append('\n')
        append(whole.subSequence(offset, whole.length))
    }
}

/** How a profile row or header is laid out for its field: its hanging indent and whether its message starts a new line. */
internal class HangingPlan(val indent: TextUnit?, val messageOnOwnLine: Boolean, val indentChars: Int = 0)

/** [this] with [plan] applied: the message on its own line (at [messageOffset]) when the plan says so, then the hanging indent. */
internal fun AnnotatedString.withHangingPlan(plan: HangingPlan, messageOffset: Int): AnnotatedString {
    val broken = if (plan.messageOnOwnLine) withBreakBefore(messageOffset) else this
    return plan.indent?.let { broken.withHangingIndent(it) } ?: broken
}

/**
 * The hanging plan for a profile row or header in its own font: the indent of [chooseHangingIndentChars]
 * (or exactly [messageChars] when [forceMessage]), none when no step fits, and the message on its own
 * line when [firstLineChars] is given (> 0) and [messageBreaksOntoOwnLine] holds. The plan depends only on
 * the field's width, never on the text it lays out, so adding the break cannot change it.
 */
@Composable
internal fun rememberHangingIndent(
    messageChars: Int,
    fallbackChars: Int,
    family: FontFamily,
    fontSize: TextUnit,
    fieldWidthPx: Int,
    forceMessage: Boolean = false,
    firstLineChars: Int = 0,
): HangingPlan {
    val density = LocalDensity.current
    val advance = rememberMonoAdvancePx(family, fontSize)
    return remember(messageChars, fallbackChars, advance, density, fieldWidthPx, forceMessage, firstLineChars) {
        val fieldChars = fieldCharsFor(fieldWidthPx, advance)
        val chars = if (forceMessage) messageChars else chooseHangingIndentChars(messageChars, fallbackChars, fieldChars)
        val breaks = !forceMessage && firstLineChars > 0 && messageBreaksOntoOwnLine(messageChars, firstLineChars, fieldChars)
        HangingPlan(if (chars > 0) hangingIndentSp(chars, advance, density) else null, breaks, chars)
    }
}
