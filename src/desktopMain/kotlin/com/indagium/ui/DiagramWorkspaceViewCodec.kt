package com.indagium.ui

import kotlinx.serialization.json.Json

private const val DIAGRAM_TILE_LAYOUT_FIELD = 10
private const val DIAGRAM_HIDDEN_TILES_FIELD = 11
private const val DIAGRAM_COLLAPSED_TILES_FIELD = 12
private const val DIAGRAM_TILES_LOCKED_FIELD = 13
private const val DIAGRAM_LAST_PRESET_FIELD = 14

/**
 * The diagram view token: one entry per library item, joined by `,` (each [b64]). Fields 0–9 retain the original queue
 * section flags, heights, and panel width; fields 10 onward store the tile tree, hidden/collapsed ids, lock, and last preset.
 * The original flags are written as a projection of the tile state and remain the migration input
 * for caches that predate the tiled workspace.
 *
 * Entries are written for the diagrams open now (their live layout), then for the layouts remembered for items whose tab is
 * closed ([DiagramLayoutMemory.entries], at most [MAX_DIAGRAM_LIBRARY_ITEMS] entries in all), then, last, one entry with a
 * blank item id holding the layout the user set most recently on any diagram. An older build skips an entry whose item is
 * not open, and one with a blank id, so it reads the open diagrams' entries exactly as before.
 */
internal fun AppState.seq3ViewToken(): String {
    val memory = seq3Sessions.layoutMemory
    val open = seq3Sessions.sessions.mapNotNull { session ->
        val libraryItemId = session.libraryItemId ?: return@mapNotNull null
        val view = seq3Sessions.viewState(session.id) ?: return@mapNotNull null
        libraryItemId to layoutTokenFields(
            libraryItemId,
            view.savedLayout(),
            view.lifelinesSectionHeightDp,
            view.artifactsSectionHeightDp,
        )
    }
    val openIds = open.mapTo(HashSet()) { it.first }
    val defaults = Seq3ViewState()
    val remembered = memory.entries().filter { it.first !in openIds }.map { (id, layout) ->
        id to layoutTokenFields(id, layout, defaults.lifelinesSectionHeightDp, defaults.artifactsSectionHeightDp)
    }
    val lastSet = memory.lastSet()?.let {
        layoutTokenFields("", it, defaults.lifelinesSectionHeightDp, defaults.artifactsSectionHeightDp)
    }
    return ((remembered + open).takeLast(MAX_DIAGRAM_LIBRARY_ITEMS).map { it.second } + listOfNotNull(lastSet))
        .joinToString(",") { it.b64() }
}

private fun layoutTokenFields(libraryItemId: String, saved: DiagramSavedLayout, lifelinesHeightDp: Float, artifactsHeightDp: Float): String =
    tokenFields(
        libraryItemId,
        (DiagramWorkspaceSectionId.MESSAGES !in saved.hidden).toString(),
        (DiagramWorkspaceSectionId.MESSAGES !in saved.collapsed).toString(),
        (DiagramWorkspaceSectionId.LIFELINES !in saved.hidden).toString(),
        (DiagramWorkspaceSectionId.LIFELINES !in saved.collapsed).toString(),
        (DiagramWorkspaceSectionId.ARTIFACTS !in saved.hidden).toString(),
        (DiagramWorkspaceSectionId.ARTIFACTS !in saved.collapsed).toString(),
        lifelinesHeightDp.toString(),
        artifactsHeightDp.toString(),
        saved.panelWidthDp.toString(),
        tileLayoutJson(saved.layout) { it.key }.toString(),
        saved.hidden.joinToString(",") { it.key },
        saved.collapsed.joinToString(",") { it.key },
        saved.locked.toString(),
        saved.lastPreset.name,
    )

/**
 * Restores the token: the remembered layouts and the last-set layout first, then each diagram that is open now takes its own
 * entry, after [restoreDiagramTabs] has reopened its matching library sessions.
 */
internal fun AppState.restoreSeq3ViewToken(token: String) {
    if (token.isBlank()) return
    val entries = token.split(",").map { it.unb64().tokenFields() }
    restoreDiagramLayoutMemory(entries)
    val sessionIdByLibraryItemId = seq3Sessions.sessions.mapNotNull { s -> s.libraryItemId?.let { id -> id to s.id } }.toMap()
    entries.forEach { fields ->
        val libraryItemId = fields.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@forEach
        val sessionId = sessionIdByLibraryItemId[libraryItemId] ?: return@forEach
        val view = seq3Sessions.viewState(sessionId) ?: return@forEach
        fields.getOrNull(1)?.toBooleanStrictOrNull()?.let { view.messagesSectionOpen = it }
        fields.getOrNull(2)?.toBooleanStrictOrNull()?.let { view.messagesExpanded = it }
        fields.getOrNull(3)?.toBooleanStrictOrNull()?.let { view.lifelinesSectionOpen = it }
        fields.getOrNull(4)?.toBooleanStrictOrNull()?.let { view.lifelinesExpanded = it }
        fields.getOrNull(5)?.toBooleanStrictOrNull()?.let { view.artifactsSectionOpen = it }
        fields.getOrNull(6)?.toBooleanStrictOrNull()?.let { view.artifactsExpanded = it }
        fields.getOrNull(7)?.toFloatOrNull()?.let { view.lifelinesSectionHeightDp = seq3ClampLifelinesHeight(it, 0f) }
        fields.getOrNull(8)?.toFloatOrNull()?.let { view.artifactsSectionHeightDp = seq3ClampArtifactsHeight(it, 0f) }
        fields.getOrNull(9)?.toFloatOrNull()?.let { view.panelWidthDp = it.coerceIn(PANEL_WIDTH_MIN_DP, PANEL_WIDTH_MAX_DP) }
        restoreDiagramWorkspaceViewState(view, fields)
    }
}

/** Fills [DiagramLayoutMemory] from the tiled [entries]: a blank item id is the last-set layout; entries of items the library no longer holds are dropped. */
private fun AppState.restoreDiagramLayoutMemory(entries: List<List<String>>) {
    val memory = seq3Sessions.layoutMemory
    val base = Seq3ViewState().savedLayout()
    entries.forEach { fields ->
        if (fields.size <= DIAGRAM_TILE_LAYOUT_FIELD) return@forEach
        val libraryItemId = fields.getOrNull(0).orEmpty()
        val saved = decodeDiagramSavedLayout(fields, base)
        if (libraryItemId.isBlank()) memory.setLast(saved) else memory.remember(libraryItemId, saved)
    }
    seq3Sessions.dropLayoutsOfMissingItems()
}

/** Fields 10 onward extend the queue token without changing the meaning of any older field. */
private fun restoreDiagramWorkspaceViewState(view: Seq3ViewState, fields: List<String>) {
    if (fields.size <= DIAGRAM_TILE_LAYOUT_FIELD) {
        restoreLegacyDiagramWorkspaceViewState(view)
    } else {
        restoreTiledDiagramWorkspaceViewState(view, fields)
    }
}

private fun restoreLegacyDiagramWorkspaceViewState(view: Seq3ViewState) {
    val hidden = buildList {
        if (!view.messagesSectionOpen) add(DiagramWorkspaceSectionId.MESSAGES)
        if (!view.lifelinesSectionOpen) add(DiagramWorkspaceSectionId.LIFELINES)
        if (!view.artifactsSectionOpen) add(DiagramWorkspaceSectionId.ARTIFACTS)
        add(DiagramWorkspaceSectionId.SOURCE)
    }
    val collapsed = buildList {
        if (!view.messagesExpanded) add(DiagramWorkspaceSectionId.MESSAGES)
        if (!view.lifelinesExpanded) add(DiagramWorkspaceSectionId.LIFELINES)
        if (!view.artifactsExpanded) add(DiagramWorkspaceSectionId.ARTIFACTS)
    }
    val migrated = sanitizeDiagramWorkspaceLayoutState(view.diagramTileLayout, hidden, collapsed)
    view.diagramTileLayout = migrated.layout
    view.diagramHiddenTiles = migrated.hidden
    view.diagramCollapsedTiles = migrated.collapsed
}

private fun restoreTiledDiagramWorkspaceViewState(view: Seq3ViewState, fields: List<String>) {
    view.applySavedLayout(decodeDiagramSavedLayout(fields, view.savedLayout()))
}

/**
 * The layout a tiled entry holds; [base] supplies whatever the entry lacks (a field that is absent or unreadable).
 */
private fun decodeDiagramSavedLayout(fields: List<String>, base: DiagramSavedLayout): DiagramSavedLayout {
    val decodedLayout = fields.getOrNull(DIAGRAM_TILE_LAYOUT_FIELD)?.takeIf { it.isNotBlank() }?.let { raw ->
        runCatching {
            tileLayoutNodeFromJson(
                Json.parseToJsonElement(raw),
                { key -> DiagramWorkspaceSectionId.fromKey(key) },
                0,
                TILE_LAYOUT_MAX_DEPTH,
            )
        }.getOrNull()
    }
    val storedHidden = fields.getOrNull(DIAGRAM_HIDDEN_TILES_FIELD).orEmpty().split(',')
        .mapNotNull(DiagramWorkspaceSectionId::fromKey)
    // Earlier tiled tokens contain four sections. Add the new source tile to the saved tree but
    // keep it hidden so opening an older workspace preserves its previous canvas geometry.
    val hidden = if (decodedLayout?.let { DiagramWorkspaceSectionId.SOURCE in tileLeafSections(it) } == true) {
        storedHidden
    } else {
        (storedHidden + DiagramWorkspaceSectionId.SOURCE).distinct()
    }
    val collapsed = fields.getOrNull(DIAGRAM_COLLAPSED_TILES_FIELD).orEmpty().split(',')
        .mapNotNull(DiagramWorkspaceSectionId::fromKey)
    val restored = sanitizeDiagramWorkspaceLayoutState(decodedLayout, hidden, collapsed)
    return DiagramSavedLayout(
        layout = restored.layout,
        hidden = restored.hidden,
        collapsed = restored.collapsed,
        locked = fields.getOrNull(DIAGRAM_TILES_LOCKED_FIELD)?.toBooleanStrictOrNull() ?: base.locked,
        lastPreset = fields.getOrNull(DIAGRAM_LAST_PRESET_FIELD)
            ?.let { name -> DiagramWorkspacePreset.entries.firstOrNull { it.name == name } } ?: base.lastPreset,
        panelWidthDp = fields.getOrNull(9)?.toFloatOrNull()?.coerceIn(PANEL_WIDTH_MIN_DP, PANEL_WIDTH_MAX_DP) ?: base.panelWidthDp,
    )
}
