package com.indagium.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure rules that decide how much of the log toolbar fits a tile: labels to icons, count to a tooltip, then the "⋯" menu. */
class LogToolbarLayoutTest {
    private fun variantAt(availableDp: Float, hasFilterBar: Boolean = false, originalOpen: Boolean = false) =
        logToolbarVariant(availableDp, hasFilterBar, originalOpen)

    // The distinct variants met walking the available width down from [from] to 0, widest first.
    private fun stages(hasFilterBar: Boolean, originalOpen: Boolean = false, from: Int = 1200): List<LogToolbarVariant> =
        (from downTo 0).map { variantAt(it.toFloat(), hasFilterBar, originalOpen) }.distinct()

    @Test
    fun aWideToolbarIsTheFullOneWithEveryLabelAndTheCount() {
        assertEquals(LogToolbarVariant.FULL, variantAt(2000f))
        assertEquals(LogToolbarVariant.FULL, variantAt(2000f, hasFilterBar = true, originalOpen = true))
    }

    @Test
    fun theToolbarDegradesInTheOrderLabelsCountOverflow() {
        val all = LogToolbarItem.entries
        val overflowFirst = listOf(LogToolbarItem.EXPAND_ALL, LogToolbarItem.COLLAPSE_ALL, LogToolbarItem.UNFILTERED)
        assertEquals(
            listOf(
                LogToolbarVariant(iconLabels = false, showCount = true),
                LogToolbarVariant(iconLabels = true, showCount = true),
                LogToolbarVariant(iconLabels = true, showCount = false),
                LogToolbarVariant(iconLabels = true, showCount = false, overflow = overflowFirst),
                LogToolbarVariant(
                    iconLabels = true, showCount = false,
                    overflow = listOf(LogToolbarItem.TIME_DELTA) + overflowFirst,
                ),
            ),
            stages(hasFilterBar = false),
        )
        assertEquals(
            LogToolbarVariant(
                iconLabels = true, showCount = false,
                overflow = listOf(LogToolbarItem.FILTER_BAR, LogToolbarItem.TIME_DELTA) + overflowFirst,
            ),
            stages(hasFilterBar = true).last(),
        )
        assertEquals(5, stages(hasFilterBar = true).size)
        assertTrue(all.containsAll(stages(hasFilterBar = true).last().overflow))
    }

    @Test
    fun exportAndSearchNeverLeaveTheBar() {
        stages(hasFilterBar = true).forEach { variant ->
            assertTrue(LogToolbarItem.EXPORT !in variant.overflow, "Export stays: $variant")
            assertTrue(LogToolbarItem.SEARCH !in variant.overflow, "Search stays: $variant")
        }
    }

    @Test
    fun overflowOnlyNamesTheFilterBarWhenTheHostHasOne() {
        assertTrue(stages(hasFilterBar = false).none { LogToolbarItem.FILTER_BAR in it.overflow })
        assertTrue(stages(hasFilterBar = true).any { LogToolbarItem.FILTER_BAR in it.overflow })
    }

    @Test
    fun theChosenVariantFitsWheneverAnyDoesAndNarrowerWidthsNeverGetWider() {
        for (hasFilterBar in listOf(false, true)) {
            for (originalOpen in listOf(false, true)) {
                var lastWidth = Float.MAX_VALUE
                for (available in 1200 downTo 0) {
                    val variant = variantAt(available.toFloat(), hasFilterBar, originalOpen)
                    val needed = logToolbarContentDp(variant, hasFilterBar, originalOpen)
                    val narrowest = stages(hasFilterBar, originalOpen).last()
                    if (variant != narrowest) assertTrue(needed <= available, "$variant needs $needed in $available")
                    assertTrue(needed <= lastWidth, "the toolbar grew while the room shrank at $available")
                    lastWidth = needed
                }
            }
        }
    }

    @Test
    fun anOpenOriginalNeedsALongerLabelSoItIconsEarlier() {
        val closed = (1200 downTo 0).first { variantAt(it.toFloat()) != LogToolbarVariant.FULL }
        val open = (1200 downTo 0).first { variantAt(it.toFloat(), originalOpen = true) != LogToolbarVariant.FULL }
        assertTrue(open > closed, "Hide original is wider than Unfiltered: it goes to an icon at $open, not $closed")
    }

    @Test
    fun theAvailableWidthIsTheTileMinusGripMaximizeAndCompareBadges() {
        assertEquals(1000f - 62f, logToolbarAvailableDp(1000f, compareSide = false))
        assertEquals(1000f - 62f - 74f - SIDE_NAME_MIN_DP, logToolbarAvailableDp(1000f, compareSide = true))
    }

    // The tile widths (single view, no filter bar) behind each variant, so a threshold change is a deliberate edit of this test.
    @Test
    fun tileWidthThresholdsOfTheSingleView() {
        fun variantOfTile(tileDp: Int) = logToolbarVariant(logToolbarAvailableDp(tileDp.toFloat(), false), hasFilterBar = false, originalOpen = false)
        assertEquals(LogToolbarVariant.FULL, variantOfTile(700))
        assertEquals(LogToolbarVariant(iconLabels = true, showCount = true), variantOfTile(500))
        assertEquals(LogToolbarVariant(iconLabels = true, showCount = false), variantOfTile(400))
        assertEquals(
            listOf(LogToolbarItem.EXPAND_ALL, LogToolbarItem.COLLAPSE_ALL, LogToolbarItem.UNFILTERED),
            variantOfTile(300).overflow,
        )
        assertEquals(LogToolbarItem.TIME_DELTA, variantOfTile(250).overflow.first())
    }

    @Test
    fun theCompareFileNameShrinksBeforeAnyItemOverflows() {
        // Whenever a variant fits the room left after the badges and the shortest name, the name gets at least that shortest width.
        for (tile in 200..1200) {
            val available = logToolbarAvailableDp(tile.toFloat(), compareSide = true)
            val variant = logToolbarVariant(available, hasFilterBar = false, originalOpen = false)
            if (logToolbarContentDp(variant, false, false) <= available) {
                val room = tile - 62f - 74f - logToolbarContentDp(variant, false, false)
                assertTrue(room >= SIDE_NAME_MIN_DP, "tile $tile leaves the name only $room dp")
            }
            val nameMax = logToolbarNameMaxDp(tile.toFloat(), variant, false, false)
            assertTrue(nameMax in SIDE_NAME_MIN_DP..SIDE_NAME_MAX_DP)
        }
        // A wide compare tile lets the name use its full width; a narrow one keeps only the minimum.
        assertEquals(SIDE_NAME_MAX_DP, logToolbarNameMaxDp(2000f, LogToolbarVariant.FULL, false, false))
        assertEquals(SIDE_NAME_MIN_DP, logToolbarNameMaxDp(150f, stages(false).last(), false, false))
    }

    @Test
    fun aCompareHeaderTakesTheNextVariantEarlierThanASingleOneToKeepTheName() {
        val firstNonFullSingle = (1200 downTo 0).first { logToolbarVariant(logToolbarAvailableDp(it.toFloat(), false), false, false) != LogToolbarVariant.FULL }
        val firstNonFullCompare = (1200 downTo 0).first { logToolbarVariant(logToolbarAvailableDp(it.toFloat(), true), false, false) != LogToolbarVariant.FULL }
        assertTrue(firstNonFullCompare > firstNonFullSingle)
    }
}
