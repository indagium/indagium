package com.indagium.ui

/**
 * The layout preferences of one diagram workspace: the tile tree, the hidden and collapsed tiles, the lock,
 * the preset the Layout menu's Reset returns to, and the panel width. Always in the form
 * [sanitizeDiagramWorkspaceLayoutState] returns, so two of them compare equal when they draw the same.
 */
internal data class DiagramSavedLayout(
    val layout: DiagramWorkspaceLayout,
    val hidden: List<DiagramWorkspaceSectionId>,
    val collapsed: List<DiagramWorkspaceSectionId>,
    val locked: Boolean,
    val lastPreset: DiagramWorkspacePreset,
    val panelWidthDp: Float,
)

/** The layout [this] shows now. */
internal fun Seq3ViewState.savedLayout(): DiagramSavedLayout {
    val state = sanitizeDiagramWorkspaceLayoutState(diagramTileLayout, diagramHiddenTiles, diagramCollapsedTiles)
    return DiagramSavedLayout(state.layout, state.hidden, state.collapsed, diagramTilesLocked, diagramLastPreset, panelWidthDp)
}

/** Shows [saved] in [this], keeping the older section flags that mirror the hidden and collapsed tiles in step. */
internal fun Seq3ViewState.applySavedLayout(saved: DiagramSavedLayout) {
    val state = sanitizeDiagramWorkspaceLayoutState(saved.layout, saved.hidden, saved.collapsed)
    diagramTileLayout = state.layout
    diagramHiddenTiles = state.hidden
    diagramCollapsedTiles = state.collapsed
    diagramTilesLocked = saved.locked
    diagramLastPreset = saved.lastPreset
    panelWidthDp = saved.panelWidthDp.coerceIn(PANEL_WIDTH_MIN_DP, PANEL_WIDTH_MAX_DP)
    DiagramWorkspaceSectionId.entries.forEach { id ->
        setLegacyDiagramPaneOpen(this, id, id !in state.hidden)
        setLegacyDiagramTileExpanded(this, id, id !in state.collapsed)
    }
}

/**
 * What the diagram workspaces remember of their layouts, for [Seq3Session]:
 *  - [layoutFor]: the layout each library item had when its tab was closed (or when the app last saved it), at most
 *    [maxItems] of them, the least recently remembered dropped first. Reopening the item shows it again.
 *  - [lastSet]: the layout the user most recently set on any diagram. A diagram with no layout of its own starts from it.
 * Both are kept across a restart by the `seq3View` autosave token (DiagramWorkspaceViewCodec.kt). Its own monitor
 * guards it, taken last and never held while calling out, so autosave may read it under any other lock.
 */
internal class DiagramLayoutMemory(private val maxItems: Int = MAX_DIAGRAM_LIBRARY_ITEMS) {
    private val byItem = LinkedHashMap<String, DiagramSavedLayout>()
    private var last: DiagramSavedLayout? = null

    @Synchronized
    fun layoutFor(libraryItemId: String): DiagramSavedLayout? = byItem[libraryItemId]

    /** Remembers [layout] as [libraryItemId]'s, now its most recent entry; drops the oldest ones beyond the bound. */
    @Synchronized
    fun remember(libraryItemId: String, layout: DiagramSavedLayout) {
        byItem.remove(libraryItemId)
        byItem[libraryItemId] = layout
        while (byItem.size > maxItems) byItem.remove(byItem.keys.first())
    }

    /** Drops [libraryItemId]'s entry (its library item is gone). */
    @Synchronized
    fun forget(libraryItemId: String) {
        byItem.remove(libraryItemId)
    }

    /** Keeps only the entries whose item [exists]. */
    @Synchronized
    fun retainWhere(exists: (String) -> Boolean) {
        byItem.keys.retainAll { exists(it) }
    }

    @Synchronized
    fun lastSet(): DiagramSavedLayout? = last

    @Synchronized
    fun setLast(layout: DiagramSavedLayout?) {
        last = layout
    }

    /** The entries from the least to the most recently remembered. */
    @Synchronized
    fun entries(): List<Pair<String, DiagramSavedLayout>> = byItem.map { it.key to it.value }

    @Synchronized
    fun isEmpty(): Boolean = byItem.isEmpty() && last == null
}

/** The user set [view]'s layout (a preset, a tile moved, resized, hidden, collapsed or locked): later diagrams start from it. */
internal fun AppState.diagramLayoutEdited(view: Seq3ViewState) {
    seq3Sessions.layoutMemory.setLast(view.savedLayout())
    autosaveInBackground()
}
