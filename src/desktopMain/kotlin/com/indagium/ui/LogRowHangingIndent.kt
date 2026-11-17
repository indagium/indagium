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
private const val MAX_INDENT_WIDTH_FRACTION = 0.5f

/**
 * Whether an indent of [indentPx] still leaves a wrapped row room to read in a field [fieldWidthPx]
 * wide. A width not measured yet (0) is taken as wide enough.
 */
internal fun hangingIndentFits(indentPx: Float, fieldWidthPx: Int): Boolean =
    fieldWidthPx <= 0 || indentPx <= fieldWidthPx * MAX_INDENT_WIDTH_FRACTION

/**
 * The hanging indent for a profile row: [chars] characters of the row's own font, or null when the
 * field is too narrow for it (the continuation lines then start at the left edge, as without one).
 */
@Composable
internal fun rememberHangingIndent(chars: Int, family: FontFamily, fontSize: TextUnit, fieldWidthPx: Int): TextUnit? {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val advance = remember(measurer, density, family, fontSize) { monoAdvancePx(measurer, family, fontSize) }
    return remember(chars, advance, density, fieldWidthPx) {
        hangingIndentSp(chars, advance, density).takeIf { hangingIndentFits(chars * advance, fieldWidthPx) }
    }
}
