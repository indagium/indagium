package com.indagium.ui

// How the Notes tile's header degrades as its card narrows. The header is one row: grip, icon, "Notes", "3 notes", the action buttons (Preview,
// Copy, Save, New, Locate log…, Open), then the header icons (collapse, maximize, hide). Pure, like LogToolbarLayout.kt: the width goes in, what to
// draw comes out, so the thresholds are unit-tested (NotesHeaderLayoutTest) and the composables only render what they are told.
//
// The text gives way before any button does. In order as the card narrows: the "3 notes" summary, then the "Notes" title text (the icon stays), then
// the buttons get smaller (compact labels, with less padding; then icons with tooltips, the log toolbar's own narrow form), and only when even that
// does not fit do buttons move into one "⋯" menu, the least important first (Locate log…, Open, New, Save). Preview and Copy stay on the row as long as
// they, the menu button and the header icons fit; below that everything is in the menu, which has room for the title and the summary again.

/** An action of the Notes header, in button order; [id] names its test tag and menu entry. */
internal enum class NotesAction(val id: String) {
    PREVIEW("preview"),
    COPY("copy"),
    SAVE("save"),
    NEW("new"),
    LOCATE("locate"),
    OPEN("open"),
}

/** How the buttons are drawn: [LABELS] (the log toolbar's buttons), [COMPACT] (the same with less padding) or [ICONS] (icon buttons with tooltips). */
internal enum class NotesButtonLook { LABELS, COMPACT, ICONS }

/**
 * What the Notes header draws: the [showSummary] and [showTitle] texts, the buttons' [look], and the [folded] actions that live in the "⋯" menu
 * instead of on the row (none while everything fits).
 */
internal data class NotesHeaderLayout(
    val showSummary: Boolean,
    val showTitle: Boolean,
    val look: NotesButtonLook = NotesButtonLook.LABELS,
    val folded: List<NotesAction> = emptyList(),
) {
    /** True while some action is in the "⋯" menu. */
    val overflow: Boolean get() = folded.isNotEmpty()

    companion object {
        /** Everything as it is when there is room (and in the panel's own bar). */
        val Full = NotesHeaderLayout(showSummary = true, showTitle = true)
    }
}

// Estimated widths in dp (default font size); like the log toolbar's they only have to be close, and a little high: a card a few dp narrower than
// estimated simply takes the next variant. Labels: the log toolbar's 10 dp of padding; compact: 6 dp; icons: a 15 dp icon and 6 dp of padding.
private val LABEL_DP = mapOf(
    NotesAction.PREVIEW to 67f, NotesAction.COPY to 64f, NotesAction.SAVE to 50f, NotesAction.NEW to 47f, NotesAction.LOCATE to 92f, NotesAction.OPEN to 54f,
)
private val COMPACT_DP = mapOf(
    NotesAction.PREVIEW to 59f, NotesAction.COPY to 59f, NotesAction.SAVE to 42f, NotesAction.NEW to 39f, NotesAction.LOCATE to 84f, NotesAction.OPEN to 46f,
)
private const val ICON_DP = 27f
private const val ARROW_DP = 18f
private const val OVERFLOW_DP = ICON_DP
private const val TITLE_DP = 40f
private const val SUMMARY_DP = 52f

// Padding, grip, icon, their gaps and the card's border; the header icons are added by count.
private const val FIXED_DP = 8f + 8f + 22f + TILE_HEADER_GAP_DP + 15f + TILE_HEADER_GAP_DP + 2f
private const val HEADER_ICON_DP = 22f + TILE_HEADER_GAP_DP

private fun actionDp(action: NotesAction, look: NotesButtonLook, hasRecent: Boolean): Float {
    val base = when (look) {
        NotesButtonLook.LABELS -> LABEL_DP.getValue(action)
        NotesButtonLook.COMPACT -> COMPACT_DP.getValue(action)
        NotesButtonLook.ICONS -> ICON_DP + if (action == NotesAction.COPY) ARROW_DP else 0f
    }
    return base + if (action == NotesAction.OPEN && hasRecent) ARROW_DP else 0f
}

/** The actions the header has: Locate log… only while the tab has no log. */
private fun presentActions(hasLocate: Boolean): List<NotesAction> = NotesAction.entries.filter { it != NotesAction.LOCATE || hasLocate }

// The order buttons move into the menu: the rarest or least needed first. Preview and Copy go only when nothing else is left.
private val FOLD_ORDER = listOf(NotesAction.LOCATE, NotesAction.OPEN, NotesAction.NEW, NotesAction.SAVE, NotesAction.COPY, NotesAction.PREVIEW)

/** The estimated width (dp) of the buttons [look] draws, less the [folded] ones, plus the menu button when something is folded. */
internal fun notesActionsDp(look: NotesButtonLook, folded: List<NotesAction>, hasLocate: Boolean, hasRecent: Boolean): Float {
    val shown = presentActions(hasLocate).filter { it !in folded }.map { actionDp(it, look, hasRecent) }
    val parts = shown + if (folded.isEmpty()) emptyList() else listOf(OVERFLOW_DP)
    return parts.sum() + TILE_HEADER_GAP_DP * (parts.size - 1).coerceAtLeast(0)
}

/**
 * What the header of a Notes card [widthDp] wide draws, with [headerIcons] buttons (collapse, maximize, hide) at its right. The widest variant
 * that fits wins; the narrowest (the menu alone) when none does.
 */
internal fun notesHeaderLayout(widthDp: Float, hasLocate: Boolean, hasRecent: Boolean, headerIcons: Int = 3): NotesHeaderLayout {
    val room = widthDp - FIXED_DP - headerIcons * HEADER_ICON_DP
    val title = TITLE_DP + TILE_HEADER_GAP_DP
    val summary = SUMMARY_DP + TILE_HEADER_GAP_DP
    val present = presentActions(hasLocate)

    fun candidate(showSummary: Boolean, showTitle: Boolean, look: NotesButtonLook, folded: List<NotesAction>): Pair<NotesHeaderLayout, Float> {
        val actions = notesActionsDp(look, folded, hasLocate, hasRecent) + TILE_HEADER_GAP_DP
        val text = (if (showTitle) title else 0f) + (if (showSummary) summary else 0f)
        return NotesHeaderLayout(showSummary, showTitle, look, folded) to actions + text
    }
    val foldStages = FOLD_ORDER.indices.map { FOLD_ORDER.take(it + 1).filter { action -> action in present } }
        .filter { it.isNotEmpty() && it.size < present.size - 1 }.distinct()
    val candidates = listOf(
        candidate(showSummary = true, showTitle = true, NotesButtonLook.LABELS, emptyList()),
        candidate(showSummary = false, showTitle = true, NotesButtonLook.LABELS, emptyList()),
        candidate(showSummary = false, showTitle = false, NotesButtonLook.LABELS, emptyList()),
        candidate(showSummary = false, showTitle = false, NotesButtonLook.COMPACT, emptyList()),
        candidate(showSummary = false, showTitle = false, NotesButtonLook.ICONS, emptyList()),
    ) + foldStages.map { candidate(showSummary = false, showTitle = false, NotesButtonLook.ICONS, it) } + listOf(
        candidate(showSummary = true, showTitle = true, NotesButtonLook.ICONS, present),
        candidate(showSummary = false, showTitle = true, NotesButtonLook.ICONS, present),
        candidate(showSummary = false, showTitle = false, NotesButtonLook.ICONS, present),
    )
    return candidates.firstOrNull { it.second <= room }?.first ?: candidates.last().first
}

/** The tile header parts for [layout]: the icon stays down to the generic icon threshold, the summary and title follow [layout]. */
internal fun notesHeaderParts(widthDp: Float, layout: NotesHeaderLayout): TileHeaderParts =
    TileHeaderParts(showIcon = widthDp >= TILE_HEADER_ICON_MIN_DP, showSummary = layout.showSummary, showTitle = layout.showTitle)
