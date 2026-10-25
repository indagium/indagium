package com.indagium.ui

// How the log toolbar degrades as its tile gets narrow. Pure: the width goes in, the variant comes out, so the thresholds are unit-tested
// (LogToolbarLayoutTest) and the composables in LogToolbarControls.kt only render what they are told.
//
// The untiled (legacy) toolbar is always [LogToolbarVariant.FULL]. Tiled, the toolbar is the Log tile's header and loses things in this
// order as the tile narrows: the Expand all / Collapse all / Unfiltered labels become icons, then the entries count becomes a tooltip,
// then Unfiltered, Collapse all and Expand all move into a "⋯" menu, then Δt and the filter bar toggle follow. Export and Search stay.

/** The actions of the log toolbar, in keyboard order (the order of `toolbarActions` in `LogViewer`); [id] is that action's id there. */
internal enum class LogToolbarItem(val id: String) {
    EXPORT("export"),
    FILTER_BAR("filter-bar"),
    TIME_DELTA("time-delta"),
    SEARCH("search"),
    EXPAND_ALL("expand-all"),
    COLLAPSE_ALL("collapse-all"),
    UNFILTERED("unfiltered"),
}

/**
 * One way to draw the toolbar. [iconLabels]: Expand all / Collapse all / Unfiltered are icon buttons with tooltips. [showCount]: the
 * "N / M entries" text is drawn (otherwise it is the tooltip of the empty space and the first line of the overflow menu). [overflow]: the
 * actions that live in the "⋯" menu instead of the bar, in toolbar order.
 */
internal data class LogToolbarVariant(
    val iconLabels: Boolean,
    val showCount: Boolean,
    val overflow: List<LogToolbarItem> = emptyList(),
) {
    val hasOverflow: Boolean get() = overflow.isNotEmpty()

    companion object {
        /** Every label, the count and no overflow: the legacy toolbar, and the tiled one in a wide tile. */
        val FULL = LogToolbarVariant(iconLabels = false, showCount = true)
    }
}

// Estimated widths in dp of what the toolbar draws (default font size). They only have to be close, and a little high: a tile that is a
// few dp narrower than estimated simply takes the next variant.
private const val EXPORT_DP = 74f
private const val FILTER_BAR_DP = 40f
private const val TIME_DELTA_DP = 36f
private const val SEARCH_DP = 36f
private const val COUNT_MIN_DP = 112f
private const val EXPAND_ALL_DP = 86f
private const val COLLAPSE_ALL_DP = 94f
private const val UNFILTERED_DP = 80f
private const val HIDE_ORIGINAL_DP = 102f
private const val ICON_BUTTON_DP = 36f
private const val LEFT_GAP_DP = 8f
private const val RIGHT_GAP_DP = 4f

/** The Log tile's move grip and the gap after it, the maximize button with its two gaps, and the card's own border. */
private const val TILED_FIXED_DP = 22f + 4f + 22f + 4f + 8f + 2f

/** A compare side's A / B badge, kind pill and their gaps (the file name after them shrinks first, down to [SIDE_NAME_MIN_DP]). */
private const val COMPARE_SIDE_FIXED_DP = 18f + 4f + 48f + 4f

/** The least the compare header's file name keeps before toolbar items move to the overflow menu. */
internal const val SIDE_NAME_MIN_DP = 40f

/** The most it takes when the tile is wide. */
internal const val SIDE_NAME_MAX_DP = 180f

private val OVERFLOW_STAGES: List<List<LogToolbarItem>> = listOf(
    listOf(LogToolbarItem.EXPAND_ALL, LogToolbarItem.COLLAPSE_ALL, LogToolbarItem.UNFILTERED),
    listOf(LogToolbarItem.FILTER_BAR, LogToolbarItem.TIME_DELTA, LogToolbarItem.EXPAND_ALL, LogToolbarItem.COLLAPSE_ALL, LogToolbarItem.UNFILTERED),
)

// Which of the items stay on the bar are the right-hand group (separated by 4 dp) rather than the left-hand one (8 dp).
private val RIGHT_GROUP = setOf(LogToolbarItem.EXPAND_ALL, LogToolbarItem.COLLAPSE_ALL, LogToolbarItem.UNFILTERED)

private fun itemDp(item: LogToolbarItem, variant: LogToolbarVariant, originalOpen: Boolean): Float = when (item) {
    LogToolbarItem.EXPORT -> EXPORT_DP
    LogToolbarItem.FILTER_BAR -> FILTER_BAR_DP
    LogToolbarItem.TIME_DELTA -> TIME_DELTA_DP
    LogToolbarItem.SEARCH -> SEARCH_DP
    LogToolbarItem.EXPAND_ALL -> if (variant.iconLabels) ICON_BUTTON_DP else EXPAND_ALL_DP
    LogToolbarItem.COLLAPSE_ALL -> if (variant.iconLabels) ICON_BUTTON_DP else COLLAPSE_ALL_DP
    LogToolbarItem.UNFILTERED -> if (variant.iconLabels) ICON_BUTTON_DP else if (originalOpen) HIDE_ORIGINAL_DP else UNFILTERED_DP
}

/** The estimated width of the toolbar's buttons (and the count when shown) for [variant]; [hasFilterBar] adds the filter bar toggle. */
internal fun logToolbarContentDp(variant: LogToolbarVariant, hasFilterBar: Boolean, originalOpen: Boolean): Float {
    var total = 0f
    LogToolbarItem.entries.forEach { item ->
        if (item == LogToolbarItem.FILTER_BAR && !hasFilterBar) return@forEach
        if (item in variant.overflow) return@forEach
        total += itemDp(item, variant, originalOpen) + if (item in RIGHT_GROUP) RIGHT_GAP_DP else LEFT_GAP_DP
    }
    if (variant.showCount) total += COUNT_MIN_DP + LEFT_GAP_DP
    if (variant.hasOverflow) total += ICON_BUTTON_DP + RIGHT_GAP_DP
    return total
}

/**
 * The width a toolbar's controls may use in a Log tile [tileWidthDp] wide: the card minus the grip and maximize button, and, in compare
 * ([compareSide]), minus the badge, kind pill and the shortest file name.
 */
internal fun logToolbarAvailableDp(tileWidthDp: Float, compareSide: Boolean): Float =
    tileWidthDp - TILED_FIXED_DP - if (compareSide) COMPARE_SIDE_FIXED_DP + SIDE_NAME_MIN_DP else 0f

/**
 * The widest variant whose controls fit [availableDp] (see [logToolbarAvailableDp]); the narrowest one when none does. The order, from
 * wide to narrow: [LogToolbarVariant.FULL], icons instead of labels, no count, then the overflow stages.
 */
internal fun logToolbarVariant(availableDp: Float, hasFilterBar: Boolean, originalOpen: Boolean): LogToolbarVariant {
    val candidates = listOf(
        LogToolbarVariant.FULL,
        LogToolbarVariant(iconLabels = true, showCount = true),
        LogToolbarVariant(iconLabels = true, showCount = false),
    ) + OVERFLOW_STAGES.map { stage ->
        LogToolbarVariant(iconLabels = true, showCount = false, overflow = stage.filter { hasFilterBar || it != LogToolbarItem.FILTER_BAR })
    }
    return candidates.firstOrNull { logToolbarContentDp(it, hasFilterBar, originalOpen) <= availableDp } ?: candidates.last()
}

/**
 * The most a compare header's file name may take in a Log tile [tileWidthDp] wide drawing [variant]: what the controls leave over,
 * between [SIDE_NAME_MIN_DP] (where it ellipsizes before toolbar items overflow) and [SIDE_NAME_MAX_DP].
 */
internal fun logToolbarNameMaxDp(tileWidthDp: Float, variant: LogToolbarVariant, hasFilterBar: Boolean, originalOpen: Boolean): Float {
    val left = tileWidthDp - TILED_FIXED_DP - COMPARE_SIDE_FIXED_DP - logToolbarContentDp(variant, hasFilterBar, originalOpen)
    return left.coerceIn(SIDE_NAME_MIN_DP, SIDE_NAME_MAX_DP)
}
