package com.indagium.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val WIDEST_DP = 1400

/** The pure rules that decide where the Notes tile's action buttons are, and how they look, at a given card width. */
class NotesHeaderLayoutTest {
    private fun layoutAt(width: Float, hasLocate: Boolean = false, hasRecent: Boolean = false, icons: Int = 3) =
        notesHeaderLayout(width, hasLocate, hasRecent, icons)

    private fun layout(summary: Boolean, inHeader: Boolean, look: NotesButtonLook = NotesButtonLook.LABELS) =
        NotesHeaderLayout(summary, inHeader, look)

    // The card widths (dp) at which each layout starts, walking down: the first element holds from its width up, each next one from its own width.
    private fun startsOf(hasLocate: Boolean = false, hasRecent: Boolean = false, icons: Int = 3): List<Pair<Int, NotesHeaderLayout>> {
        val result = mutableListOf<Pair<Int, NotesHeaderLayout>>()
        for (w in WIDEST_DP downTo 0) {
            val l = layoutAt(w.toFloat(), hasLocate, hasRecent, icons)
            if (result.lastOrNull()?.second != l) result += w to l
        }
        return result
    }

    @Test
    fun aWideHeaderKeepsTheButtonsAsTextAndTheSummary() {
        assertEquals(NotesHeaderLayout.Full, layoutAt(1000f))
        assertEquals(layout(summary = true, inHeader = true), layoutAt(1000f))
    }

    @Test
    fun theSummaryGivesWayFirstThenTheButtonsLeaveTheHeaderForTheBarWhichGoesCompactAndLosesTheSummaryLast() {
        val l = NotesButtonLook.LABELS
        val c = NotesButtonLook.COMPACT
        // Each pair: the widest card (dp) at which the layout is drawn, and the layout (then it holds down to the next pair's width + 1).
        assertEquals(
            listOf(
                WIDEST_DP to layout(summary = true, inHeader = true),
                566 to layout(summary = false, inHeader = true),
                508 to layout(summary = true, inHeader = false, l),
                323 to layout(summary = true, inHeader = false, c),
                254 to layout(summary = false, inHeader = false, c),
            ),
            startsOf(),
        )
    }

    @Test
    fun theButtonsAreInTheHeaderOrInTheBarAtEveryWidthNeverIcons() {
        listOf(false, true).forEach { hasLocate ->
            listOf(false, true).forEach { hasRecent ->
                var leftHeader = false
                var wentCompact = false
                // Walking down from the widest card: the buttons leave the header once and never come back, and the bar's labels give way to compact
                // ones once and never come back: no width flaps between two looks.
                for (w in WIDEST_DP downTo 0) {
                    val layout = layoutAt(w.toFloat(), hasLocate, hasRecent)
                    if (layout.actionsInHeader) {
                        assertFalse(leftHeader, "$w: the buttons came back to the header")
                        assertEquals(NotesButtonLook.LABELS, layout.look, "$w: header buttons are labelled")
                    } else {
                        leftHeader = true
                        if (layout.look == NotesButtonLook.COMPACT) wentCompact = true else assertFalse(wentCompact, "$w: labels came back")
                    }
                }
                assertTrue(leftHeader && wentCompact)
            }
        }
    }

    @Test
    fun theSummaryIsTheFirstThingToGoAndTheButtonsStayInTheHeaderWhenItIsGone() {
        val stages = startsOf().map { it.second }
        assertTrue(stages[0].showSummary && stages[0].actionsInHeader)
        assertFalse(stages[1].showSummary)
        assertTrue(stages[1].actionsInHeader, "dropping the summary is enough to keep the buttons in the header")
        assertFalse(stages[2].actionsInHeader)
        assertTrue(stages[2].showSummary, "with the buttons out of the header there is room for the summary again")
    }

    @Test
    fun theDefaultRightColumnPutsTheLabelledButtonsInTheBarUnderTheHeader() {
        // The default right column is 350 to 360 dp; its card is a few dp less.
        for (w in 340..360) {
            val layout = layoutAt(w.toFloat())
            assertEquals(layout(summary = true, inHeader = false, NotesButtonLook.LABELS), layout, "$w dp")
        }
    }

    @Test
    fun theBarLooksFollowTheWidthTheButtonsNeedOnOneLine() {
        assertEquals(NotesButtonLook.LABELS, notesBarLook(324f, hasLocate = false, hasRecent = false))
        assertEquals(NotesButtonLook.COMPACT, notesBarLook(323f, hasLocate = false, hasRecent = false))
        assertEquals(NotesButtonLook.COMPACT, notesBarLook(0f, hasLocate = true, hasRecent = true), "a bar too narrow even for compact buttons wraps")
        // Locate log… and Open's arrow make the buttons wider.
        val plain = (0..WIDEST_DP).first { notesBarLook(it.toFloat(), false, false) == NotesButtonLook.LABELS }
        assertTrue((0..WIDEST_DP).first { notesBarLook(it.toFloat(), true, false) == NotesButtonLook.LABELS } > plain)
        assertTrue((0..WIDEST_DP).first { notesBarLook(it.toFloat(), false, true) == NotesButtonLook.LABELS } > plain)
        // The look in the layout is the bar's.
        for (w in 0..WIDEST_DP) {
            val layout = layoutAt(w.toFloat())
            if (!layout.actionsInHeader) assertEquals(notesBarLook(w.toFloat(), false, false), layout.look, "$w")
        }
    }

    @Test
    fun theThresholdsGrowWithTheExtraButtonsAndWithFewerHeaderIcons() {
        fun firstInHeader(hasLocate: Boolean, hasRecent: Boolean, icons: Int): Int =
            (0..2000).first { layoutAt(it.toFloat(), hasLocate, hasRecent, icons).actionsInHeader }
        val plain = firstInHeader(false, false, 3)
        assertTrue(firstInHeader(true, false, 3) > plain, "Locate log… needs room")
        assertTrue(firstInHeader(false, true, 3) > plain, "Open's arrow needs room")
        assertTrue(firstInHeader(false, false, 2) < plain, "a tile without a collapse button has more room")
    }

    @Test
    fun theHeaderPartsKeepTheIconDownToTheGenericThresholdTheTitleAlwaysAndFollowTheSummary() {
        val withoutSummary = layout(summary = false, inHeader = false, NotesButtonLook.COMPACT)
        val parts = notesHeaderParts(TILE_HEADER_ICON_MIN_DP, withoutSummary)
        assertTrue(parts.showIcon)
        assertTrue(parts.showTitle)
        assertFalse(parts.showSummary)
        assertFalse(notesHeaderParts(TILE_HEADER_ICON_MIN_DP - 1f, withoutSummary).showIcon)
        assertTrue(notesHeaderParts(1000f, NotesHeaderLayout.Full).showSummary)
        for (w in 0..WIDEST_DP) assertTrue(notesHeaderParts(w.toFloat(), layoutAt(w.toFloat())).showTitle, "$w: the title stays")
    }
}
