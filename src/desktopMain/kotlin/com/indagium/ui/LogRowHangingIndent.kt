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

/** The width of [chars] monospace characters of [advancePx] pixels each, in sp at this density and font scale. */
internal fun hangingIndentSp(chars: Int, advancePx: Float, density: Density): TextUnit =
    with(density) { (chars * advancePx).toSp() }

/** The most of a row's width a hanging indent may take; a pane narrower than that gets no indent. */
internal const val MAX_HANGING_INDENT_FRACTION = 0.5f

/**
 * The indent a profile row's wrapped lines hang at: its message column when that [fits], else just
 * after its first column ([fallbackChars]) when that fits, else none. A row whose message starts far to
 * the right (many columns) or that has no message still keeps its continuation lines off the first
 * column, so they never read as a new row; in a pane too narrow even for that, a short fixed step.
 */
internal fun chooseHangingIndentChars(messageChars: Int, fallbackChars: Int, fits: (Int) -> Boolean): Int = when {
    messageChars > 0 && fits(messageChars) -> messageChars
    fallbackChars > 0 && fits(fallbackChars) -> fallbackChars
    // A narrow tile with many columns: a short fixed step still tells a continuation line from a new row.
    (messageChars > 0 || fallbackChars > 0) && fits(MIN_HANGING_INDENT_CHARS) -> MIN_HANGING_INDENT_CHARS
    else -> 0
}

/** The last indent step before none, for a pane too narrow for the column-based ones. */
internal const val MIN_HANGING_INDENT_CHARS = 4

/**
 * Whether an indent of [indentPx] still leaves a wrapped row room to read in a field [fieldWidthPx]
 * wide. A width not measured yet (0) is taken as wide enough.
 */
internal fun hangingIndentFits(indentPx: Float, fieldWidthPx: Int): Boolean =
    fieldWidthPx <= 0 || indentPx <= fieldWidthPx * MAX_HANGING_INDENT_FRACTION

/**
 * The hanging indent for a profile row in the row's own font: [messageChars] when it fits the field,
 * else [fallbackChars] (see [chooseHangingIndentChars]), or null when neither does (the continuation
 * lines then start at the left edge, as without one).
 */
@Composable
internal fun rememberHangingIndent(
    messageChars: Int,
    fallbackChars: Int,
    family: FontFamily,
    fontSize: TextUnit,
    fieldWidthPx: Int,
    forceMessage: Boolean = false,
): TextUnit? {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val advance = remember(measurer, density, family, fontSize) { monoAdvancePx(measurer, family, fontSize) }
    return remember(messageChars, fallbackChars, advance, density, fieldWidthPx, forceMessage) {
        val chars = if (forceMessage) messageChars else chooseHangingIndentChars(messageChars, fallbackChars) { hangingIndentFits(it * advance, fieldWidthPx) }
        if (chars > 0) hangingIndentSp(chars, advance, density) else null
    }
}
