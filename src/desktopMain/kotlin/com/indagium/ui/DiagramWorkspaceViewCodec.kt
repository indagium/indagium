package com.indagium.ui

import kotlinx.serialization.json.Json

private const val DIAGRAM_TILE_LAYOUT_FIELD = 10
private const val DIAGRAM_HIDDEN_TILES_FIELD = 11
private const val DIAGRAM_COLLAPSED_TILES_FIELD = 12
private const val DIAGRAM_TILES_LOCKED_FIELD = 13
private const val DIAGRAM_LAST_PRESET_FIELD = 14

/**
 * The per-session diagram view token. Fields 0–9 retain the original queue section flags, heights,
 * and panel width; fields 10 onward store the tile tree, hidden/collapsed ids, lock, and last preset.
 * The original flags are written as a projection of the tile state and remain the migration input
 * for caches that predate the tiled workspace.
 */
internal fun AppState.seq3ViewToken(): String =
    seq3Sessions.sessions
        .mapNotNull { session ->
            val libraryItemId = session.libraryItemId ?: return@mapNotNull null
            val view = seq3Sessions.viewState(session.id) ?: return@mapNotNull null
            val tileState = sanitizeDiagramWorkspaceLayoutState(view.diagramTileLayout, view.diagramHiddenTiles, view.diagramCollapsedTiles)
            tokenFields(
                libraryItemId,
                (DiagramWorkspaceSectionId.MESSAGES !in tileState.hidden).toString(),
                (DiagramWorkspaceSectionId.MESSAGES !in tileState.collapsed).toString(),
                (DiagramWorkspaceSectionId.LIFELINES !in tileState.hidden).toString(),
                (DiagramWorkspaceSectionId.LIFELINES !in tileState.collapsed).toString(),
                (DiagramWorkspaceSectionId.ARTIFACTS !in tileState.hidden).toString(),
                (DiagramWorkspaceSectionId.ARTIFACTS !in tileState.collapsed).toString(),
                view.lifelinesSectionHeightDp.toString(),
                view.artifactsSectionHeightDp.toString(),
                view.panelWidthDp.toString(),
                tileLayoutJson(tileState.layout) { it.key }.toString(),
                tileState.hidden.joinToString(",") { it.key },
                tileState.collapsed.joinToString(",") { it.key },
                view.diagramTilesLocked.toString(),
                view.diagramLastPreset.name,
            )
        }
        .joinToString(",") { it.b64() }

/** Restores a view entry after [restoreDiagramTabs] has reopened its matching library sessions. */
internal fun AppState.restoreSeq3ViewToken(token: String) {
    if (token.isBlank()) return
    val sessionIdByLibraryItemId = seq3Sessions.sessions.mapNotNull { s -> s.libraryItemId?.let { id -> id to s.id } }.toMap()
    token.split(",").forEach { entry ->
        val fields = entry.unb64().tokenFields()
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
    view.diagramTileLayout = restored.layout
    view.diagramHiddenTiles = restored.hidden
    view.diagramCollapsedTiles = restored.collapsed
    fields.getOrNull(DIAGRAM_TILES_LOCKED_FIELD)?.toBooleanStrictOrNull()?.let { view.diagramTilesLocked = it }
    fields.getOrNull(DIAGRAM_LAST_PRESET_FIELD)?.let { name ->
        view.diagramLastPreset = DiagramWorkspacePreset.entries.firstOrNull { it.name == name } ?: view.diagramLastPreset
    }
    DiagramWorkspaceSectionId.entries.forEach { id ->
        setLegacyDiagramPaneOpen(view, id, id !in restored.hidden)
        setLegacyDiagramTileExpanded(view, id, id !in restored.collapsed)
    }
}
