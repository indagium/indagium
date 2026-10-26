package com.indagium.ui

// Where the Notes tile's action buttons (Preview, Copy, Save, New, Locate log…, Open) go as its card narrows. The header is one row: grip, icon,
// "Notes", then the header icons (collapse, maximize, hide); the buttons sit in the free room between the title and those icons, centred in it.
// Pure, like LogToolbarLayout.kt: the width goes in, what to draw comes out, so the thresholds are unit-tested
// (NotesHeaderLayoutTest) and the composables only render what they are told.
//
// The buttons are always text, never icons, and the "Notes" title is never traded away for them. As the card narrows: when the labelled buttons do
// not fit next to the title they leave the header for the bar the panel draws directly under it (AnnotationPanel), where they are centred as well:
// with their full labels while those fit on one line, then with compact labels (less padding), and when even those do not fit the bar wraps onto a
// second centred line. The header then holds the title and the icons.
// The tile's header (LocalTileHeaderWidthDp) and its body (TileBodyInfo.widthDp) are the same card width and ask this one function, so the buttons are
// in exactly one of the two places at every width.

/** An action of the Notes bar, in button order; [id] names its test tag. */
internal enum class NotesAction(val id: String) {
    PREVIEW("preview"),
    COPY("copy"),
    SAVE("save"),
    NEW("new"),
    LOCATE("locate"),
    OPEN("open"),
}

/** How the buttons are drawn: [LABELS] (the log toolbar's buttons) or [COMPACT] (the same with less padding). */
internal enum class NotesButtonLook { LABELS, COMPACT }

/**
 * What the Notes tile draws: whether the buttons are in the header row ([actionsInHeader]) or in the bar under it, and their [look] (always
 * [NotesButtonLook.LABELS] in the header; in the bar labels, or compact ones when those do not fit on one line).
 */
internal data class NotesHeaderLayout(
    val actionsInHeader: Boolean = true,
    val look: NotesButtonLook = NotesButtonLook.LABELS,
) {
    companion object {
        /** Everything as it is when there is room: the labelled buttons in the header. */
        val Full = NotesHeaderLayout()
    }
}

// Estimated widths in dp (default font size); like the log toolbar's they only have to be close, and a little high: a card a few dp narrower than
// estimated simply takes the next variant. Labels: the log toolbar's 10 dp of padding; compact: 6 dp.
private val LABEL_DP = mapOf(
    NotesAction.PREVIEW to 67f, NotesAction.COPY to 64f, NotesAction.SAVE to 50f, NotesAction.NEW to 47f, NotesAction.LOCATE to 92f, NotesAction.OPEN to 54f,
)
private val COMPACT_DP = mapOf(
    NotesAction.PREVIEW to 59f, NotesAction.COPY to 59f, NotesAction.SAVE to 42f, NotesAction.NEW to 39f, NotesAction.LOCATE to 84f, NotesAction.OPEN to 46f,
)
private const val ARROW_DP = 18f
private const val TITLE_DP = 40f

// Padding, grip, icon, their gaps and the card's border; the header icons are added by count.
private const val FIXED_DP = 8f + 8f + 22f + TILE_HEADER_GAP_DP + 15f + TILE_HEADER_GAP_DP + 2f
private const val HEADER_ICON_DP = 22f + TILE_HEADER_GAP_DP

// The bar's side padding and the card's border: what the buttons do not get of the card's width.
private const val BAR_FIXED_DP = 2f * TILE_TOOLBAR_PADDING_DP + 2f

private fun actionDp(action: NotesAction, look: NotesButtonLook, hasRecent: Boolean): Float {
    val base = if (look == NotesButtonLook.LABELS) LABEL_DP.getValue(action) else COMPACT_DP.getValue(action)
    return base + if (action == NotesAction.OPEN && hasRecent) ARROW_DP else 0f
}

/** The actions there are: Locate log… only while the tab has no log. */
private fun presentActions(hasLocate: Boolean): List<NotesAction> = NotesAction.entries.filter { it != NotesAction.LOCATE || hasLocate }

/** The estimated width (dp) of all the buttons in [look] on one line. */
internal fun notesActionsDp(look: NotesButtonLook, hasLocate: Boolean, hasRecent: Boolean): Float {
    val parts = presentActions(hasLocate).map { actionDp(it, look, hasRecent) }
    return parts.sum() + TILE_HEADER_GAP_DP * (parts.size - 1).coerceAtLeast(0)
}

/**
 * The look of the buttons in the bar under a card [widthDp] wide: their labels while they all fit on one line, otherwise compact ones (which wrap
 * onto a second line when they do not fit either). Also what the panel uses on its own, without a tile.
 */
internal fun notesBarLook(widthDp: Float, hasLocate: Boolean, hasRecent: Boolean): NotesButtonLook =
    if (notesActionsDp(NotesButtonLook.LABELS, hasLocate, hasRecent) <= widthDp - BAR_FIXED_DP) NotesButtonLook.LABELS else NotesButtonLook.COMPACT

/**
 * What a Notes card [widthDp] wide draws, with [headerIcons] buttons (collapse, maximize, hide) at the right of its header. The buttons in the
 * header need room next to the title; the bar is the one that always fits.
 */
internal fun notesHeaderLayout(widthDp: Float, hasLocate: Boolean, hasRecent: Boolean, headerIcons: Int = 3): NotesHeaderLayout {
    val room = widthDp - FIXED_DP - headerIcons * HEADER_ICON_DP
    val title = TITLE_DP + TILE_HEADER_GAP_DP
    val buttons = notesActionsDp(NotesButtonLook.LABELS, hasLocate, hasRecent) + TILE_HEADER_GAP_DP
    if (room >= title + buttons) return NotesHeaderLayout.Full
    return NotesHeaderLayout(actionsInHeader = false, look = notesBarLook(widthDp, hasLocate, hasRecent))
}

/** The tile header parts of the Notes card: the icon stays down to the generic icon threshold, the title always stays, there is no summary. */
internal fun notesHeaderParts(widthDp: Float): TileHeaderParts =
    TileHeaderParts(showIcon = widthDp >= TILE_HEADER_ICON_MIN_DP, showSummary = false)
