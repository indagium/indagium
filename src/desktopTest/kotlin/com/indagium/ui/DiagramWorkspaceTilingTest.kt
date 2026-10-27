package com.indagium.ui

import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileSplit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiagramWorkspaceTilingTest {
    @Test
    fun defaultLayoutKeepsThreeAuthoringTilesInALeftColumnBesidePreview() {
        val layout = diagramWorkspaceDefaultLayout()
        val root = assertIs<TileSplit<DiagramWorkspaceSectionId>>(layout)
        assertEquals(TileAxis.ROW, root.axis)
        val sidebar = assertIs<TileSplit<DiagramWorkspaceSectionId>>(root.children.first())
        assertEquals(TileAxis.COLUMN, sidebar.axis)
        assertEquals(
            listOf(
                DiagramWorkspaceSectionId.MESSAGES,
                DiagramWorkspaceSectionId.LIFELINES,
                DiagramWorkspaceSectionId.ARTIFACTS,
            ),
            tileLeafSections(sidebar),
        )
        assertEquals(listOf(DiagramWorkspaceSectionId.PREVIEW), tileLeafSections(root.children.last()))
    }

    @Test
    fun hiddenTilesKeepTheirPlaceAndSanitizerAlwaysLeavesPreviewVisible() {
        val view = Seq3ViewState()
        assertTrue(setDiagramWorkspaceTileVisible(view, DiagramWorkspaceSectionId.MESSAGES, false))
        assertFalse(DiagramWorkspaceSectionId.MESSAGES in diagramWorkspaceVisibleTiles(view))
        assertTrue(DiagramWorkspaceSectionId.MESSAGES in tileLeafSections(view.diagramTileLayout))
        assertFalse(view.messagesSectionOpen)

        val allHidden = sanitizeDiagramWorkspaceLayoutState(
            view.diagramTileLayout,
            DiagramWorkspaceSectionId.entries,
            DiagramWorkspaceSectionId.entries,
        )
        assertFalse(DiagramWorkspaceSectionId.PREVIEW in allHidden.hidden)
        assertTrue(DiagramWorkspaceSectionId.PREVIEW in tileLeafSections(allHidden.layout).filterNot { it in allHidden.hidden })
    }

    @Test
    fun narrowLayoutStacksPreviewFirst() {
        assertEquals(
            DiagramWorkspaceSectionId.PREVIEW,
            tileLeafSections(diagramWorkspaceNarrowLayout(diagramWorkspaceDefaultLayout())).first(),
        )
    }

    @Test
    fun layoutAndTilePreferencesRoundTripThroughPerDiagramAutosave() = withEmptyDiagram { state, sessionId ->
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val customLayout = TileSplit(
            TileAxis.ROW,
            listOf(
                TileLeaf(DiagramWorkspaceSectionId.PREVIEW, 2.4f),
                TileLeaf(DiagramWorkspaceSectionId.MESSAGES),
                TileSplit(
                    TileAxis.COLUMN,
                    listOf(TileLeaf(DiagramWorkspaceSectionId.LIFELINES), TileLeaf(DiagramWorkspaceSectionId.ARTIFACTS)),
                    0.8f,
                ),
            ),
        )
        view.diagramTileLayout = customLayout
        view.diagramHiddenTiles = listOf(DiagramWorkspaceSectionId.ARTIFACTS)
        view.diagramCollapsedTiles = listOf(DiagramWorkspaceSectionId.MESSAGES)
        view.diagramTilesLocked = true
        view.diagramLastPreset = DiagramWorkspacePreset.CANVAS_FOCUS

        val encoded = state.seq3ViewToken()
        view.diagramTileLayout = diagramWorkspaceDefaultLayout()
        view.diagramHiddenTiles = emptyList()
        view.diagramCollapsedTiles = emptyList()
        view.diagramTilesLocked = false
        view.diagramLastPreset = DiagramWorkspacePreset.STANDARD
        state.restoreSeq3ViewToken(encoded)

        assertEquals(customLayout, view.diagramTileLayout)
        assertEquals(listOf(DiagramWorkspaceSectionId.ARTIFACTS), view.diagramHiddenTiles)
        assertEquals(listOf(DiagramWorkspaceSectionId.MESSAGES), view.diagramCollapsedTiles)
        assertTrue(view.diagramTilesLocked)
        assertEquals(DiagramWorkspacePreset.CANVAS_FOCUS, view.diagramLastPreset)
    }

    @Test
    fun legacySectionFlagsBecomeHiddenAndCollapsedTiles() = withEmptyDiagram { state, sessionId ->
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val libraryItemId = requireNotNull(state.seq3Sessions.sessions.single { it.id == sessionId }.libraryItemId)
        val legacyToken = tokenFields(
            libraryItemId,
            // Messages: visible, collapsed.
            "true", "false",
            // Lifelines: hidden, expanded.
            "false", "true",
            // Artifacts: visible, expanded.
            "true", "true",
            "220", "200", "392",
        ).b64()

        state.restoreSeq3ViewToken(legacyToken)

        assertEquals(listOf(DiagramWorkspaceSectionId.LIFELINES), view.diagramHiddenTiles)
        assertEquals(listOf(DiagramWorkspaceSectionId.MESSAGES), view.diagramCollapsedTiles)
        assertFalse(view.messagesExpanded)
        assertFalse(view.lifelinesSectionOpen)
    }

    @Test
    fun escapeLetsFocusedEditorHandleKeyBeforeRestoringMaximizedTile() = withEmptyDiagram { state, sessionId ->
        val session = requireNotNull(state.seq3Sessions.sessions.firstOrNull { it.id == sessionId })
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.maximizedDiagramTile = DiagramWorkspaceSectionId.PREVIEW
        view.textFieldFocused = true

        assertFalse(applySeq3Escape(state, session, view))
        assertFalse(view.textFieldFocused)
        assertEquals(DiagramWorkspaceSectionId.PREVIEW, view.maximizedDiagramTile)
        assertTrue(applySeq3Escape(state, session, view))
        assertEquals(null, view.maximizedDiagramTile)
    }

    private fun withEmptyDiagram(block: (AppState, String) -> Unit) {
        val root = Files.createTempDirectory("diagram-tiling-state-").toFile()
        val state = AppState(
            autosaveFile = File(root, "state.cache"),
            restoreOnCreate = false,
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        )
        try {
            val tab = mkTab("diagram-tiling-state", "diagram-tiling-state.log", emptyList())
            state.tabs = listOf(tab)
            state.activateTab(tab.id)
            block(state, requireNotNull(state.seq3Sessions.beginEmpty(tab.id)))
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }
}
