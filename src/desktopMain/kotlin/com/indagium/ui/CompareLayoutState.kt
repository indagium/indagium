package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.FilterMode
import com.indagium.model.LogTab
import com.indagium.model.LogViewScope
import com.indagium.model.LogViewSectionId
import com.indagium.model.TileNode

// The compare view's tiling state as AppState extensions, the counterpart of LogViewLayoutState.kt: the two tabs being compared, the
// tile context the pure LogViewLayout.kt rules read, and the commit / preset / reset / badge operations the compare host and the
// Layout menu call. One layout for the whole app (AppSettings.compareLayout / compareHidden / lastComparePreset), like the single view.

/**
 * The two tabs the compare view shows, (A, B), or null when there is no tab. A is the active tab, B the compare tab; the two are never
 * the same tab, because both log viewers key their scroll state by tab id alone and two panes on one tab would fight over it.
 */
internal fun AppState.compareTabs(): Pair<LogTab, LogTab>? {
    val left = tab(activeTabId) ?: tabs.firstOrNull() ?: return null
    val rawRight = tab(compareTabId) ?: tabs.getOrNull(1) ?: tabs.first()
    val right = if (rawRight.id == left.id) tabs.firstOrNull { it.id != left.id } ?: rawRight else rawRight
    return left to right
}

/**
 * What decides which tiles of the compare view apply. Compare never shows the horizontal filter bar, so the Tags tile always applies;
 * Message rules follow the Tags | Regex mode of the tab whose filter the panel edits ([compareFilterTarget]).
 */
internal fun AppState.compareTileContext(left: LogTab, right: LogTab): LogViewTileContext {
    val edited = if (compareFilterEditTabId(compareFilterTarget, left.id, right.id) == right.id) right else left
    return LogViewTileContext(
        compare = true,
        showUnfiltered = left.showUnfiltered,
        leftShowUnfiltered = left.showUnfiltered,
        rightShowUnfiltered = right.showUnfiltered,
        filterBarVisible = false,
        filterModeTags = edited.filter.mode == FilterMode.TAGS,
        hasVideoPanel = hasVideoTileContent(left),
        groupOn = ::logViewGroupOn,
    )
}

/** The compare view's layout as drawn: the stored one, else Compare grid derived from the legacy sizes (compareSplit and the panel widths). */
internal fun AppState.currentCompareLayout(): TileNode<LogViewSectionId> = effectiveCompareLayout(settings, legacyLogViewSizes())

/** Stores [tree] as the compare layout (a release-only edit: the host calls this once per gesture). */
internal fun AppState.commitCompareLayout(tree: TileNode<LogViewSectionId>) {
    updateSettings { it.withCompareLayout(tree) }
}

/** Applies a compare [preset] (Compare grid / Compare rows) and remembers it. */
internal fun AppState.applyComparePreset(preset: LogViewLayoutPreset) {
    if (preset.scope != LogViewScope.COMPARE) return
    val sizes = legacyLogViewSizes()
    updateSettings { it.withComparePreset(preset, sizes) }
    logViewMaximized = null
}

/** The compare preset "Reset to ..." re-applies: the last applied one (Compare grid when none). */
internal fun AppState.lastComparePresetOrGrid(): LogViewLayoutPreset =
    LogViewLayoutPreset.of(settings.lastComparePreset).takeIf { it.scope == LogViewScope.COMPARE } ?: LogViewLayoutPreset.COMPARE_GRID

internal fun AppState.resetCompareLayout() = applyComparePreset(lastComparePresetOrGrid())

/** True when the compare view currently shows [preset]. */
internal fun AppState.comparePresetActive(preset: LogViewLayoutPreset): Boolean =
    logViewPresetIsActive(preset, settings, legacyLogViewSizes())

/** True when the compare view no longer matches the compare preset it started from (the Layout button's warning dot). */
internal fun AppState.compareLayoutModified(): Boolean = !comparePresetActive(lastComparePresetOrGrid())

/** The F6 focus stops of the compare view in reading order (filter tiles are one stop; each side's Original and Log are one stop). */
internal fun AppState.compareVisiblePanels(left: LogTab, right: LogTab): List<LogViewFocusPanel> {
    val ctx = compareTileContext(left, right)
    val hidden = settings.compareHidden
    val projection = projectTileLayout(currentCompareLayout()) { logViewTileVisible(it, ctx, hidden) } ?: return emptyList()
    return logViewFocusOrder(projection.root)
}

/** [this] with [id] hidden or shown in the layout that is on screen: the compare one in [compare] mode, else the single log view's. */
internal fun AppSettings.withActiveViewTileHidden(id: LogViewSectionId, hidden: Boolean, compare: Boolean): AppSettings =
    if (compare) withCompareTileHidden(id, hidden) else withLogViewTileHidden(id, hidden)
