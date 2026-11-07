package com.indagium.ui

// How the diagram workspace's header degrades as the window gets narrow. Pure: the width goes in, the
// variant comes out, so the threshold is unit-tested (Seq3HeaderLayoutTest) and Seq3TitleBar only draws
// what it is told. Same shape as LogToolbarLayout.kt.
//
// Wide, the header's right cluster shows the output actions in a row: Edit, Copy and Attach. When the
// header cannot hold them next to the identity block and the presentation group they fold into one "⋯" menu.

/** How the header draws its output actions (Edit, Copy ▾, Attach ▾). */
internal enum class Seq3HeaderOutputVariant {
    /** The three controls side by side. */
    INLINE,

    /** One "⋯" menu holding all three. */
    FOLDED,
}

// Estimated widths in dp of what the header draws (default font size). They only have to be close, and a
// little high: a header a few dp narrower than estimated simply takes the folded variant.
private const val HEADER_PADDING_DP = 24f
private const val HEADER_GAP_DP = 8f
private const val IDENTITY_MIN_DP = 150f
private const val PRESENTATION_GROUP_DP = 450f
private const val GROUP_SEPARATION_DP = 12f + 1f + 12f
private const val EDIT_BUTTON_DP = 50f
private const val COPY_DROPDOWN_DP = 64f
private const val ATTACH_SPLIT_DP = 126f
private const val OVERFLOW_BUTTON_DP = 28f

private const val INLINE_OUTPUT_DP = EDIT_BUTTON_DP + COPY_DROPDOWN_DP + ATTACH_SPLIT_DP + 2 * HEADER_GAP_DP

/** The width the header needs to draw the output actions inline beside the rest of it. */
internal const val SEQ3_HEADER_INLINE_MIN_DP: Float =
    HEADER_PADDING_DP + IDENTITY_MIN_DP + HEADER_GAP_DP + PRESENTATION_GROUP_DP + GROUP_SEPARATION_DP + INLINE_OUTPUT_DP

/** The widest variant that fits a header [widthDp] wide; [Seq3HeaderOutputVariant.FOLDED] when the inline row does not. */
internal fun seq3HeaderOutputVariant(widthDp: Float): Seq3HeaderOutputVariant =
    if (widthDp >= SEQ3_HEADER_INLINE_MIN_DP) Seq3HeaderOutputVariant.INLINE else Seq3HeaderOutputVariant.FOLDED

/**
 * The most the identity block (title and subtitle) may take in a header [widthDp] wide drawing [variant]:
 * what the presentation group and the output actions leave over, never less than [IDENTITY_MIN_DP].
 */
internal fun seq3HeaderIdentityMaxDp(widthDp: Float, variant: Seq3HeaderOutputVariant): Float {
    val output = if (variant == Seq3HeaderOutputVariant.INLINE) INLINE_OUTPUT_DP else OVERFLOW_BUTTON_DP
    val left = widthDp - HEADER_PADDING_DP - 2 * HEADER_GAP_DP - PRESENTATION_GROUP_DP - GROUP_SEPARATION_DP - output
    return left.coerceAtLeast(IDENTITY_MIN_DP)
}
