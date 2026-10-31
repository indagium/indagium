package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.FilterMode
import com.indagium.model.LogTab
import com.indagium.model.LogViewPresetId
import com.indagium.model.LogViewScope
import com.indagium.model.LogViewSectionId
import com.indagium.model.LogViewTileGroup
import com.indagium.model.ProfileLayoutRef
import com.indagium.model.TileAxis
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import com.indagium.model.ToolbarPlacement

// The single log view's tiling state as AppState extensions (AppState.kt is far too big to grow): the legacy sizes and tile
// context the pure LogViewLayout.kt functions read, and the commit / hide / show / preset / reset / lock / collapse / reveal
// operations the tiling host, the Layout menu, the shortcuts and the tab-strip buttons call.
//
// The group flags (filterVisible, annotationVisible, aiPanelVisible, videoPanelVisible) stay the source of truth for the tab-strip
// toggles, workspace profiles and shortcuts; a tile is shown when its group is on AND it is not in AppSettings.logViewHidden.

/** The pre-tiling panel sizes, which seed Classic (and every preset) until the user has changed the layout. */
internal fun AppState.legacyLogViewSizes(): LogViewLegacySizes =
    LogViewLegacySizes(
        filterPanelWidth = filterPanelWidth,
        annotationPanelWidth = annotationPanelWidth,
        rightSidebarSplit = rightSidebarSplit,
        compareSplit = compareSplit,
    )

/** True when [tab] has something for the Video tile: an attached video, a capture session, or a capture timeline worth showing alone. */
internal fun AppState.hasVideoTileContent(tab: LogTab): Boolean =
    tab.attachedVideo != null || tab.captureSessionId != null || hasTimelineOnlyVideoPanel(this, tab)

/** What decides which tiles of the single log view apply to [tab] right now. */
internal fun AppState.logViewTileContext(tab: LogTab): LogViewTileContext =
    LogViewTileContext(
        compare = false,
        showUnfiltered = tab.showUnfiltered,
        leftShowUnfiltered = tab.showUnfiltered,
        rightShowUnfiltered = false,
        filterBarVisible = filterBarVisible,
        filterModeTags = tab.filter.mode == FilterMode.TAGS,
        hasVideoPanel = hasVideoTileContent(tab),
        toolbarInLayout = settings.toolbarPlacement == ToolbarPlacement.IN_LAYOUT && logViewMaximized == null,
        groupOn = ::logViewGroupOn,
    )

/** The tab-strip toggle behind [group]. */
internal fun AppState.logViewGroupOn(group: LogViewTileGroup): Boolean = when (group) {
    LogViewTileGroup.NONE -> true
    LogViewTileGroup.FILTERS -> filterVisible
    LogViewTileGroup.NOTES -> annotationVisible
    LogViewTileGroup.AI -> aiPanelVisible
    LogViewTileGroup.VIDEO -> videoPanelVisible
}

private fun AppState.setLogViewGroup(group: LogViewTileGroup, on: Boolean) {
    when (group) {
        LogViewTileGroup.NONE -> Unit
        LogViewTileGroup.FILTERS -> updateFilterVisible(on)
        LogViewTileGroup.NOTES -> updateAnnotationVisible(on)
        LogViewTileGroup.AI -> updateAiPanelVisible(on)
        LogViewTileGroup.VIDEO -> updateVideoPanelVisible(on)
    }
}

/** The single log view's layout as drawn: the stored one, else Classic derived from the legacy sizes. */
internal fun AppState.currentLogViewLayout(): TileNode<LogViewSectionId> = effectiveLogViewLayout(settings, legacyLogViewSizes())

/** Stores [tree] as the single log view's layout (a release-only edit: the host calls this once per gesture). */
internal fun AppState.commitLogViewLayout(tree: TileNode<LogViewSectionId>) {
    updateSettings { it.withLogViewLayout(tree) }
}

/** The × on a tile: puts [id] in the hidden list. The tab-strip group flag is left alone, so the badge counts it. */
internal fun AppState.hideLogViewTile(id: LogViewSectionId) {
    if (logViewMaximized == id) logViewMaximized = null
    updateSettings { it.withActiveViewTileHidden(id, true, compareMode) }
}

/** Shows [id] again and switches its group on, so the tile is really visible whichever of the two had hidden it. */
internal fun AppState.showLogViewTile(id: LogViewSectionId) {
    updateSettings { it.withActiveViewTileHidden(id, false, compareMode) }
    setLogViewGroup(id.group, true)
}

/**
 * Applies [preset] (single-view presets only): its layout from the legacy sizes, its hidden tiles, and remembers it.
 *
 * Log focus hides all eight filter tiles. Rather than eight entries in the hidden list (which would count 8 on the Layout button's
 * badge and leave the tab strip's Filter button lit while nothing shows), the filter GROUP is switched off instead and the tiles
 * stay un-hidden; every preset that shows filters switches the group back on. The layout menu's "active preset" check folds the
 * group flag back into the hidden list (see [logViewPresetActive]).
 */
internal fun AppState.applyLogViewPreset(preset: LogViewLayoutPreset) {
    if (preset.scope != LogViewScope.SINGLE) return
    val filtersOff = LOG_VIEW_FILTER_TILES.all { it in preset.hidden }
    val sizes = legacyLogViewSizes()
    updateSettings { settings ->
        val applied = settings.withLogViewPreset(preset, sizes)
        if (filtersOff) applied.withFiltersKeptAtTheLeft(sizes) else applied
    }
    logViewMaximized = null
    updateFilterVisible(!filtersOff)
    updateFilterBarVisible(preset.showsFilterBar)
    preset.panels?.let {
        updateAnnotationVisible(it.notes)
        updateVideoPanelVisible(it.video)
        updateAiPanelVisible(it.ai)
    }
}

/** Captures the single-log layout and its associated panel/collapse state as one named snapshot. */
internal fun AppState.captureCustomLogViewLayout(name: String): com.indagium.model.CustomLogViewLayout {
    val tree = currentLogViewLayout()
    return com.indagium.model.CustomLogViewLayout(
        id = newCustomLogViewLayoutId(),
        name = name,
        tree = tree,
        filterPanelWidth = filterPanelWidth,
        annotationPanelWidth = annotationPanelWidth,
        rightSidebarSplit = rightSidebarSplit,
        hidden = settings.logViewHidden,
        collapsed = tileLeafSections(tree).filter { !it.neverHidden && isLogViewTileCollapsed(it) }.distinct(),
        tilesLocked = settings.logViewTilesLocked,
        filterVisible = filterVisible,
        annotationVisible = annotationVisible,
        aiPanelVisible = aiPanelVisible,
        videoPanelVisible = videoPanelVisible,
        filterBarVisible = filterBarVisible,
    )
}

/** True when the current single-log view exactly matches a saved snapshot. */
internal fun AppState.matchesCustomLogViewLayout(layout: com.indagium.model.CustomLogViewLayout): Boolean {
    if (compareMode) return false
    val toolbarInLayout = settings.toolbarPlacement == ToolbarPlacement.IN_LAYOUT
    val savedState = sanitizeLogViewLayoutState(
        layout.tree,
        layout.hidden,
        LogViewScope.SINGLE,
        toolbarInLayout,
    )
    val currentState = sanitizeLogViewLayoutState(
        currentLogViewLayout(),
        settings.logViewHidden,
        LogViewScope.SINGLE,
        toolbarInLayout,
    )
    val singleTileSet = LogViewSectionId.entries.filter { it.scope != LogViewScope.COMPARE }.toSet()
    val current = captureCustomLogViewLayout(layout.name)
    return savedState.first == currentState.first &&
        layout.filterPanelWidth.coerceIn(LOG_VIEW_PIN_MIN_DP, LOG_VIEW_PIN_MAX_DP) == current.filterPanelWidth &&
        layout.annotationPanelWidth.coerceIn(LOG_VIEW_PIN_MIN_DP, LOG_VIEW_PIN_MAX_DP) == current.annotationPanelWidth &&
        layout.rightSidebarSplit.coerceIn(RIGHT_SIDEBAR_SPLIT_MIN, RIGHT_SIDEBAR_SPLIT_MAX) == current.rightSidebarSplit &&
        savedState.second.toSet() == currentState.second.toSet() &&
        layout.collapsed.filter { it in singleTileSet }.toSet() == current.collapsed.toSet() &&
        layout.tilesLocked == current.tilesLocked &&
        layout.filterVisible == current.filterVisible &&
        layout.annotationVisible == current.annotationVisible &&
        layout.aiPanelVisible == current.aiPanelVisible &&
        layout.videoPanelVisible == current.videoPanelVisible &&
        (layout.filterBarVisible == null || layout.filterBarVisible == current.filterBarVisible)
}

/** Applies a saved single-log layout, restoring both settings-backed and filter-panel-backed collapse flags. */
internal fun AppState.applyCustomLogViewLayout(layout: com.indagium.model.CustomLogViewLayout) {
    if (compareMode) return
    val sanitized = sanitizeLogViewLayoutState(
        layout.tree,
        layout.hidden,
        LogViewScope.SINGLE,
        LogViewSectionId.TOOLBAR in tileLeafSections(layout.tree),
    )
    val filterTileSet = LOG_VIEW_FILTER_TILES.toSet()
    val singleTileSet = LogViewSectionId.entries.filter { it.scope != LogViewScope.COMPARE }.toSet()
    updateSettings { current ->
        current.copy(
            logViewLayout = sanitized.first,
            logViewHidden = sanitized.second,
            logViewCollapsed = (current.logViewCollapsed - singleTileSet + layout.collapsed.filter { it in singleTileSet }).distinct(),
            logViewTilesLocked = layout.tilesLocked,
            lastCustomLogViewLayoutId = layout.id,
        )
    }
    filterPanelWidth = layout.filterPanelWidth.coerceIn(LOG_VIEW_PIN_MIN_DP, LOG_VIEW_PIN_MAX_DP)
    annotationPanelWidth = layout.annotationPanelWidth.coerceIn(LOG_VIEW_PIN_MIN_DP, LOG_VIEW_PIN_MAX_DP)
    rightSidebarSplit = layout.rightSidebarSplit.coerceIn(RIGHT_SIDEBAR_SPLIT_MIN, RIGHT_SIDEBAR_SPLIT_MAX)
    updateFilterPanelUiState {
        filterTileSet.forEach { setExpandedFlag(it, it !in layout.collapsed) }
    }
    logViewMaximized = null
    updateFilterVisible(layout.filterVisible)
    updateAnnotationVisible(layout.annotationVisible)
    updateAiPanelVisible(layout.aiPanelVisible)
    updateVideoPanelVisible(layout.videoPanelVisible)
    layout.filterBarVisible?.let { updateFilterBarVisible(it) }
}

// A preset that leaves the filter tiles out of its tree gets them back from the sanitizer at the trailing page edge, as a weighted
// column that would then share the row with the log. Since the filters stay un-hidden (the group flag is what switches them off), put
// them where Classic has them instead, in a pinned column at the left, so switching the Filter group on again looks right.
private fun AppSettings.withFiltersKeptAtTheLeft(sizes: LogViewLegacySizes): AppSettings {
    val tree = logViewLayout ?: return copy(logViewHidden = logViewHidden - LOG_VIEW_FILTER_TILES.toSet())
    val rest = LOG_VIEW_FILTER_TILES.fold(tree as TileNode<LogViewSectionId>?) { acc, id -> acc?.let { removeTile(it, id, LOG_VIEW_LONE_COLUMNS) } }
    val filterColumn = (classicLogViewLayout(sizes) as TileSplit<LogViewSectionId>).children.first()
    val rebuilt = rest?.let { normalizeTileLayout(TileSplit(TileAxis.ROW, listOf(filterColumn, it)), LOG_VIEW_LONE_COLUMNS) } ?: tree
    return copy(logViewHidden = logViewHidden - LOG_VIEW_FILTER_TILES.toSet()).withLogViewLayout(rebuilt)
}

/** "Reset to <preset>": re-applies the preset the layout was last set from (Classic when none). */
internal fun AppState.resetLogViewLayout() {
    settings.lastCustomLogViewLayoutId
        ?.let { id -> settings.customLogViewLayouts.firstOrNull { it.id == id } }
        ?.let { custom ->
            applyCustomLogViewLayout(custom)
            return
        }
    if (settings.lastCustomLogViewLayoutId != null) {
        applyLogViewPreset(LogViewLayoutPreset.CLASSIC)
        return
    }
    val preset = resettableSinglePreset(settings.lastLogViewPreset)
    applyLogViewPreset(preset)
}

internal fun AppState.lastLogViewResetLabel(): String =
    settings.lastCustomLogViewLayoutId?.let { id -> settings.customLogViewLayouts.firstOrNull { it.id == id }?.name }
        ?: if (settings.lastCustomLogViewLayoutId != null) LogViewLayoutPreset.CLASSIC.label else lastLogViewPresetOrClassic().label

/** The preset [resetLogViewLayout] would apply, for the menu's label. */
internal fun AppState.lastLogViewPresetOrClassic(): LogViewLayoutPreset =
    resettableSinglePreset(settings.lastLogViewPreset)

private fun resettableSinglePreset(id: LogViewPresetId): LogViewLayoutPreset = when (id) {
    LogViewPresetId.REVIEW -> LogViewLayoutPreset.ANALYSIS
    LogViewPresetId.FILTERS_ON_TOP -> LogViewLayoutPreset.FILTERING
    else -> LogViewLayoutPreset.of(id).takeIf { it.scope == LogViewScope.SINGLE } ?: LogViewLayoutPreset.CLASSIC
}

/**
 * True when the view currently shows [preset]: its arrangement and, for a preset that sets panels ([LogViewLayoutPreset.panels]),
 * all five panel flags, so toggling a panel makes the preset read as modified (Revert restores it). A switched-off Filter group counts
 * as the filter tiles being hidden, because that is how Log focus is applied (see [applyLogViewPreset]).
 */
internal fun AppState.logViewPresetActive(preset: LogViewLayoutPreset): Boolean {
    val folded = settings.copy(logViewHidden = (settings.logViewHidden + if (filterVisible) emptyList() else LOG_VIEW_FILTER_TILES).distinct())
    if (!logViewPresetIsActive(preset, folded, legacyLogViewSizes())) return false
    val panels = preset.panels ?: return true
    return panels == LogViewPanels(filterVisible, filterBarVisible, annotationVisible, videoPanelVisible, aiPanelVisible)
}

/**
 * The layout a workspace profile saved right now would point at: the selected saved layout when it matches the view exactly, else
 * the built-in preset that is active, else any saved layout that matches exactly; null when the view is none of them (the profile
 * then keeps whatever layout is on screen when it is applied).
 */
internal fun AppState.activeLogViewLayoutRef(): ProfileLayoutRef? {
    if (compareMode) return null
    val saved = settings.customLogViewLayouts
    saved.firstOrNull { it.id == settings.lastCustomLogViewLayoutId && matchesCustomLogViewLayout(it) }
        ?.let { return ProfileLayoutRef.Saved(it.id) }
    LogViewLayoutPreset.forScope(LogViewScope.SINGLE).firstOrNull(::logViewPresetActive)
        ?.let { return ProfileLayoutRef.Preset(it.id) }
    return saved.firstOrNull(::matchesCustomLogViewLayout)?.let { ProfileLayoutRef.Saved(it.id) }
}

/**
 * Applies the layout a profile points at: a preset through [applyLogViewPreset], a saved layout through [applySavedLogViewLayout].
 * Null, or a saved layout that no longer exists, keeps the current layout, silently.
 */
internal fun AppState.applyProfileLayout(ref: ProfileLayoutRef?) {
    when (ref) {
        null -> Unit
        is ProfileLayoutRef.Preset -> LogViewLayoutPreset.of(ref.id).takeIf { it.scope == LogViewScope.SINGLE }?.let(::applyLogViewPreset)
        is ProfileLayoutRef.Saved -> applySavedLogViewLayout(ref.id)
    }
}

internal fun AppState.toggleLogViewTilesLocked() {
    updateSettings { it.withLogViewTilesLocked(!it.logViewTilesLocked) }
}

// The filter panel's own expanded flags back six of the filter tiles' collapsed state (they predate the tiles, are autosaved with the
// panel's UI state, and the section bodies draw nothing while their flag is off); every other tile uses AppSettings.logViewCollapsed.
private fun FilterPanelUiState.expandedFlagOf(id: LogViewSectionId): Boolean? = when (id) {
    LogViewSectionId.HIGHLIGHTERS -> highlightersExpanded
    LogViewSectionId.LOG_LEVEL -> lvlExpanded
    LogViewSectionId.SEQUENCES -> seqExpanded
    LogViewSectionId.ISSUES -> crashExpanded
    LogViewSectionId.SAVED_FILTERS -> sfExpanded
    LogViewSectionId.LOG_COMPOSITION -> logCompositionExpanded
    else -> null
}

private fun FilterPanelUiState.setExpandedFlag(id: LogViewSectionId, expanded: Boolean) {
    when (id) {
        LogViewSectionId.HIGHLIGHTERS -> highlightersExpanded = expanded
        LogViewSectionId.LOG_LEVEL -> lvlExpanded = expanded
        LogViewSectionId.SEQUENCES -> seqExpanded = expanded
        LogViewSectionId.ISSUES -> crashExpanded = expanded
        LogViewSectionId.SAVED_FILTERS -> sfExpanded = expanded
        LogViewSectionId.LOG_COMPOSITION -> logCompositionExpanded = expanded
        else -> Unit
    }
}

/** True when [id]'s tile is collapsed to its header. */
internal fun AppState.isLogViewTileCollapsed(id: LogViewSectionId): Boolean =
    fpState.expandedFlagOf(id)?.not() ?: (id in settings.logViewCollapsed)

/** The collapse button: flips the filter panel's flag (autosaved with its UI state) or the settings' collapsed list. */
internal fun AppState.toggleLogViewTileCollapsed(id: LogViewSectionId) {
    if (fpState.expandedFlagOf(id) != null) {
        updateFilterPanelUiState { setExpandedFlag(id, !(expandedFlagOf(id) ?: true)) }
    } else {
        updateSettings { it.withLogViewTileCollapsed(id, id !in it.logViewCollapsed) }
    }
}

internal fun FilterPanelSection.logViewTile(): LogViewSectionId = when (this) {
    FilterPanelSection.HIGHLIGHTERS -> LogViewSectionId.HIGHLIGHTERS
    FilterPanelSection.LOG_LEVEL -> LogViewSectionId.LOG_LEVEL
}

/**
 * Makes the filter tiles reachable: the Filter group on, [sections] un-hidden, expanded and un-collapsed, a maximized tile restored
 * (it covers the filters), and, when no filter tile would be visible even then, all of them un-hidden. The Ctrl+F / Cmd+1 shortcuts
 * and the filter bar's residual chip call this where they used to call updateFilterVisible(true).
 */
internal fun AppState.revealFilterTiles(sections: Collection<LogViewSectionId> = emptyList()) {
    logViewMaximized = null
    if (sections.isNotEmpty()) {
        updateFilterPanelUiState { sections.forEach { setExpandedFlag(it, true) } }
    }
    val compare = compareMode
    updateSettings { settings ->
        val unhidden = sections.fold(settings) { acc, id -> acc.withActiveViewTileHidden(id, false, compare).withLogViewTileCollapsed(id, false) }
        if (anyFilterTileVisible(unhidden)) unhidden else LOG_VIEW_FILTER_TILES.fold(unhidden) { acc, id -> acc.withActiveViewTileHidden(id, false, compare) }
    }
    updateFilterVisible(true)
}

// With the group on: does any filter tile apply to the active tab and sit outside the hidden list?
private fun AppState.anyFilterTileVisible(settings: AppSettings): Boolean {
    val tab = activeTab() ?: return true
    val compare = compareTabs()?.takeIf { compareMode }
    val ctx = (if (compare != null) compareTileContext(compare.first, compare.second) else logViewTileContext(tab)).copy(groupOn = { true })
    val hidden = if (compare != null) settings.compareHidden else settings.logViewHidden
    return LOG_VIEW_FILTER_TILES.any { logViewTileVisible(it, ctx, hidden) }
}

/** The F6 focus stops of [tab]'s log view in reading order (filter tiles are one stop); empty when no tile is showing. */
internal fun AppState.logViewVisiblePanels(tab: LogTab): List<LogViewFocusPanel> {
    val ctx = logViewTileContext(tab)
    val hidden = settings.logViewHidden
    val projection = projectTileLayout(currentLogViewLayout()) { logViewTileVisible(it, ctx, hidden) } ?: return emptyList()
    return logViewFocusOrder(projection.root)
}

/**
 * The Layout button's badge: the applicable tiles hidden beyond what the last-applied preset hides on purpose (Classic keeps the
 * Diagram library hidden, which is no news to anyone). Hidden through the menu or a tile's hide button, they are what the badge counts.
 */
internal fun AppState.logViewBadgeCount(ctx: LogViewTileContext): Int =
    logViewHiddenBadgeCount(ctx, settings.logViewHidden - lastLogViewPresetOrClassic().hidden, LogViewScope.SINGLE)
