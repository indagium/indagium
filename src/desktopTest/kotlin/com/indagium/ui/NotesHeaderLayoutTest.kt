package com.indagium.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val WIDEST_DP = 1400

/** The pure rules that decide what the Notes tile's one-row header draws at a given card width. */
class NotesHeaderLayoutTest {
    private fun layoutAt(width: Float, hasLocate: Boolean = false, hasRecent: Boolean = false, icons: Int = 3) =
        notesHeaderLayout(width, hasLocate, hasRecent, icons)

    // The distinct layouts met walking the card width down from [from] to 0, widest first.
    private fun stages(from: Int = WIDEST_DP, hasLocate: Boolean = false, hasRecent: Boolean = false): List<NotesHeaderLayout> =
        (from downTo 0).map { layoutAt(it.toFloat(), hasLocate, hasRecent) }.distinct()

    private fun layout(summary: Boolean, title: Boolean, look: NotesButtonLook, vararg folded: NotesAction) =
        NotesHeaderLayout(summary, title, look, folded.toList())

    @Test
    fun aWideHeaderKeepsTheButtonsTheTitleAndTheSummary() {
        assertEquals(NotesHeaderLayout.Full, layoutAt(1000f))
        assertEquals(layout(summary = true, title = true, NotesButtonLook.LABELS), layoutAt(1000f))
    }

    // The card widths (dp) at which each layout starts, walking down: the first element holds from its width up, each next one from its own width.
    private fun startsOf(hasLocate: Boolean = false, hasRecent: Boolean = false): List<Pair<Int, NotesHeaderLayout>> {
        val result = mutableListOf<Pair<Int, NotesHeaderLayout>>()
        for (w in WIDEST_DP downTo 0) {
            val l = layoutAt(w.toFloat(), hasLocate, hasRecent)
            if (result.lastOrNull()?.second != l) result += w to l
        }
        return result
    }

    @Test
    fun theHeaderDropsTheTextThenShrinksTheButtonsThenFoldsTheLeastNeededOnesBeforeAnythingElse() {
        val l = NotesButtonLook.LABELS
        val i = NotesButtonLook.ICONS
        val open = NotesAction.OPEN
        val new = NotesAction.NEW
        val save = NotesAction.SAVE
        assertEquals(
            listOf(
                layout(summary = true, title = true, l),
                layout(summary = false, title = true, l),
                layout(summary = false, title = false, l),
                layout(summary = false, title = false, NotesButtonLook.COMPACT),
                layout(summary = false, title = false, i),
                layout(summary = false, title = false, i, open, new),
                layout(summary = false, title = false, i, open, new, save),
            ),
            stages().take(7),
        )
        // With the recent-notes arrow on Open, folding Open alone already saves room, so it is the first fold.
        assertEquals(listOf(open), stages(hasRecent = true).first { it.overflow }.folded)
    }

    @Test
    fun theStepsHappenAtTheseCardWidths() {
        val l = NotesButtonLook.LABELS
        val i = NotesButtonLook.ICONS
        val open = NotesAction.OPEN
        val new = NotesAction.NEW
        val save = NotesAction.SAVE
        val locate = NotesAction.LOCATE
        // Each pair: the widest card (dp) at which the layout is drawn, and the layout (then it holds down to the next pair's width + 1).
        assertEquals(
            listOf(
                WIDEST_DP to layout(summary = true, title = true, l),
                566 to layout(summary = false, title = true, l),
                508 to layout(summary = false, title = false, l),
                462 to layout(summary = false, title = false, NotesButtonLook.COMPACT),
                425 to layout(summary = false, title = false, i),
                333 to layout(summary = false, title = false, i, open, new),
                300 to layout(summary = false, title = false, i, open, new, save),
            ),
            startsOf().take(7),
        )
        assertEquals(
            listOf(443 to layout(summary = false, title = false, i), 351 to layout(summary = false, title = false, i, open)),
            startsOf(hasRecent = true).filter { it.second.look == NotesButtonLook.ICONS }.take(2),
        )
        assertEquals(
            listOf(533 to layout(summary = false, title = false, i), 384 to layout(summary = false, title = false, i, locate, open)),
            startsOf(hasLocate = true, hasRecent = true).filter { it.second.look == NotesButtonLook.ICONS }.take(2),
        )
    }

    @Test
    fun noButtonIsFoldedWhileSmallerButtonsStillFit() {
        for (w in 0..WIDEST_DP) {
            val layout = layoutAt(w.toFloat())
            if (layout.look != NotesButtonLook.ICONS) assertFalse(layout.overflow, "$w: folded before the buttons were as small as they get")
            if (layout.overflow) assertTrue(layout.look == NotesButtonLook.ICONS, "$w: a folded button next to labelled ones")
        }
    }

    @Test
    fun theTextGivesWayBeforeAnyButtonChangesAndNothingIsFoldedUntilTheyAreIcons() {
        // Walking down, the first button change comes after both texts are gone.
        val textless = stages().first { !it.showSummary && !it.showTitle }
        val firstButtonChange = stages().first { it.look != NotesButtonLook.LABELS || it.overflow }
        assertEquals(NotesButtonLook.LABELS, textless.look)
        assertFalse(firstButtonChange.showSummary || firstButtonChange.showTitle)
    }

    @Test
    fun theFoldOrderIsLocateOpenNewSaveAndPreviewAndCopyStayOnTheRowLongest() {
        val order = listOf(NotesAction.LOCATE, NotesAction.OPEN, NotesAction.NEW, NotesAction.SAVE)
        stages(hasLocate = true, hasRecent = true).map { it.folded }.filter { it.isNotEmpty() && it.size < 5 }.forEach { folded ->
            assertEquals(order.take(folded.size), folded, "folded in the order of importance")
        }
        // Preview and Copy are on the row at every width down to the narrowest one that still leaves room for them, the menu and the header icons.
        val narrowest = (0..WIDEST_DP).first { layoutAt(it.toFloat()).let { l -> NotesAction.PREVIEW !in l.folded && NotesAction.COPY !in l.folded } }
        assertTrue(narrowest <= 268, "Preview and Copy stay visible down to a ${narrowest}dp card")
    }

    @Test
    fun theDefaultRightColumnShowsAllFiveButtons() {
        // The default right column is 350 to 360 dp; its card is a few dp less. All of Preview, Copy, Save, New and Open are visible there.
        for (w in 340..360) {
            val layout = layoutAt(w.toFloat())
            assertTrue(layout.folded.isEmpty(), "$w dp: nothing folded, got ${layout.folded}")
            assertFalse(layout.showSummary || layout.showTitle, "$w dp: the text is gone, the icon stays")
        }
    }

    @Test
    fun withLocateLogAndRecentNotesTheDefaultWidthFoldsAtWorstOpen() {
        for (w in 350..360) {
            val layout = layoutAt(w.toFloat(), hasLocate = true, hasRecent = true)
            assertTrue(layout.folded.all { it == NotesAction.LOCATE || it == NotesAction.OPEN }, "$w dp: folded ${layout.folded}")
            for (kept in listOf(NotesAction.PREVIEW, NotesAction.COPY, NotesAction.SAVE, NotesAction.NEW)) assertFalse(kept in layout.folded)
        }
    }

    @Test
    fun theThresholdsGrowWithTheExtraButtonsAndWithFewerHeaderIcons() {
        fun firstFullWidth(hasLocate: Boolean, hasRecent: Boolean, icons: Int): Int =
            (0..2000).first { layoutAt(it.toFloat(), hasLocate, hasRecent, icons) == NotesHeaderLayout.Full }
        val plain = firstFullWidth(false, false, 3)
        assertTrue(firstFullWidth(true, false, 3) > plain, "Locate log… needs room")
        assertTrue(firstFullWidth(false, true, 3) > plain, "Open's arrow needs room")
        assertTrue(firstFullWidth(false, false, 2) < plain, "a tile without a collapse button has more room")
    }

    @Test
    fun theTitleOutlivesTheSummaryAtEveryWidth() {
        for (w in 0..WIDEST_DP) {
            val layout = layoutAt(w.toFloat())
            if (layout.showSummary) assertTrue(layout.showTitle, "$w: a summary without its title")
        }
    }

    @Test
    fun theHeaderPartsKeepTheIconDownToTheGenericThresholdAndFollowTheLayout() {
        val layout = layout(summary = false, title = false, NotesButtonLook.ICONS, NotesAction.OPEN)
        val parts = notesHeaderParts(TILE_HEADER_ICON_MIN_DP, layout)
        assertTrue(parts.showIcon)
        assertFalse(parts.showTitle)
        assertFalse(parts.showSummary)
        assertFalse(notesHeaderParts(TILE_HEADER_ICON_MIN_DP - 1f, layout).showIcon)
    }
}
