package com.indagium.ui

import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 5 of the diagram editor: the pure rules behind the workspace UX (header fold, Source beside the
 *  preview, the Fit width cap, gutter numbering, status text) and the one-dialect session contract. */
class Seq3WorkspaceUxTest {
    // ── Narrow header ────────────────────────────────────────────────────────────────────────────

    @Test
    fun theHeaderFoldsItsOutputActionsIntoTheMenuWhenTheyDoNotFit() {
        assertEquals(Seq3HeaderOutputVariant.INLINE, seq3HeaderOutputVariant(1440f))
        assertEquals(Seq3HeaderOutputVariant.INLINE, seq3HeaderOutputVariant(SEQ3_HEADER_INLINE_MIN_DP))
        assertEquals(Seq3HeaderOutputVariant.FOLDED, seq3HeaderOutputVariant(SEQ3_HEADER_INLINE_MIN_DP - 1f))
        assertEquals(Seq3HeaderOutputVariant.FOLDED, seq3HeaderOutputVariant(520f))
        assertEquals(Seq3HeaderOutputVariant.FOLDED, seq3HeaderOutputVariant(0f))
    }

    @Test
    fun theFoldHappensAtAWindowWidthUsersActuallyHave() {
        // The ordinary 1024dp window keeps the actions in a row; half of it does not.
        assertEquals(Seq3HeaderOutputVariant.INLINE, seq3HeaderOutputVariant(1024f))
        assertEquals(Seq3HeaderOutputVariant.FOLDED, seq3HeaderOutputVariant(800f))
    }

    @Test
    fun theIdentityBlockKeepsAMinimumAndTakesWhatTheActionsLeave() {
        val folded = seq3HeaderIdentityMaxDp(520f, Seq3HeaderOutputVariant.FOLDED)
        val narrowInline = seq3HeaderIdentityMaxDp(520f, Seq3HeaderOutputVariant.INLINE)
        assertTrue(folded >= 96f, "never below a usable title: $folded")
        assertEquals(folded, narrowInline, "a header too narrow for either variant gives the identity its minimum")
        assertTrue(seq3HeaderIdentityMaxDp(1800f, Seq3HeaderOutputVariant.INLINE) > folded)
        assertTrue(
            seq3HeaderIdentityMaxDp(1800f, Seq3HeaderOutputVariant.FOLDED) > seq3HeaderIdentityMaxDp(1800f, Seq3HeaderOutputVariant.INLINE),
            "the folded actions leave the identity more room",
        )
    }

    @Test
    fun theFoldedMenuNamesTheCopyItemsLikeTheCopyMenuDoes() {
        assertEquals("Copy PNG image", seq3OverflowCopyLabel(Seq3CopyTarget.PNG_IMAGE))
        assertEquals("Download PNG", seq3OverflowCopyLabel(Seq3CopyTarget.DOWNLOAD_PNG))
        assertEquals("Copy PlantUML source", seq3OverflowCopyLabel(Seq3CopyTarget.PLANTUML_SOURCE))
        assertEquals("Copy Mermaid source", seq3OverflowCopyLabel(Seq3CopyTarget.MERMAID_SOURCE))
    }

    // ── Source beside the preview ─────────────────────────────────────────────────────────────────

    private fun siblings(node: DiagramWorkspaceLayout, first: DiagramWorkspaceSectionId, second: DiagramWorkspaceSectionId): TileAxis? {
        if (node !is TileSplit) return null
        val sections = node.children.map { (it as? TileLeaf)?.section }
        val at = sections.indexOf(first)
        if (at >= 0 && sections.getOrNull(at + 1) == second) return node.axis
        return node.children.firstNotNullOfOrNull { siblings(it, first, second) }
    }

    @Test
    fun editOpensSourceRightOfThePreviewOnAWideHostFromTheDefaultLayout() {
        val layout = diagramWorkspaceDefaultLayout()
        assertEquals(
            TileAxis.COLUMN,
            siblings(layout, DiagramWorkspaceSectionId.PREVIEW, DiagramWorkspaceSectionId.SOURCE),
            "the default spot is stacked under the preview",
        )
        val beside = assertNotNull(diagramWorkspaceSourceBesidePreview(layout, DIAGRAM_SOURCE_BESIDE_MIN_DP))
        assertEquals(TileAxis.ROW, siblings(beside, DiagramWorkspaceSectionId.PREVIEW, DiagramWorkspaceSectionId.SOURCE))
        assertEquals(
            tileLeafSections(layout).toSet(), tileLeafSections(beside).toSet(),
            "every tile stays exactly once",
        )
        assertEquals(1, tileLeafSections(beside).count { it == DiagramWorkspaceSectionId.SOURCE })
    }

    @Test
    fun editLeavesSourceStackedOnANarrowHost() {
        assertNull(diagramWorkspaceSourceBesidePreview(diagramWorkspaceDefaultLayout(), DIAGRAM_SOURCE_BESIDE_MIN_DP - 1f))
        assertNull(diagramWorkspaceSourceBesidePreview(diagramWorkspaceDefaultLayout(), 0f))
    }

    @Test
    fun editLeavesALayoutTheUserArrangedAlone() {
        // The user dragged Messages to the right of the preview: not the default arrangement.
        val arranged = dropTile(
            diagramWorkspaceDefaultLayout(),
            DiagramWorkspaceSectionId.MESSAGES,
            TileDropTarget.OnSection(DiagramWorkspaceSectionId.PREVIEW, TileEdge.RIGHT),
        )
        assertNull(diagramWorkspaceSourceBesidePreview(arranged, 1600f))
        // Source already beside the preview, the way a previous Edit left it: still the user's.
        val beside = assertNotNull(diagramWorkspaceSourceBesidePreview(diagramWorkspaceDefaultLayout(), 1600f))
        assertNull(diagramWorkspaceSourceBesidePreview(beside, 1600f))
    }

    @Test
    fun resizingTheTilesIsNotArrangingThem() {
        // Editor + diagram already has Source beside Preview: Edit has nothing to move there.
        for (preset in DiagramWorkspacePreset.entries.filter { it != DiagramWorkspacePreset.EDITOR_AND_DIAGRAM }) {
            assertNotNull(diagramWorkspaceSourceBesidePreview(preset.layout, 1400f), "${preset.label} keeps the default arrangement")
        }
        assertNull(diagramWorkspaceSourceBesidePreview(DiagramWorkspacePreset.EDITOR_AND_DIAGRAM.layout, 1400f))
        val resized: TileNode<DiagramWorkspaceSectionId> = (diagramWorkspaceDefaultLayout() as TileSplit).let { root ->
            root.copy(children = root.children.map { it.withTileWeight(it.weight * 1.5f) })
        }
        assertNotNull(diagramWorkspaceSourceBesidePreview(resized, 1400f))
    }

    // ── Fit width cap ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun fitWidthEnlargesAtMost125PercentWhileSourceIsVisible() {
        assertEquals(1.25f, seq3FitWidthMaxZoom(sourceVisible = true))
        assertEquals(2.5f, seq3FitWidthMaxZoom(sourceVisible = false))
        // A small diagram in a wide viewport: it would fill four times its width.
        assertEquals(2.5f, seq3FitWidthZoom(100.0, 400.0))
        assertEquals(1.25f, seq3FitWidthZoom(100.0, 400.0, seq3FitWidthMaxZoom(sourceVisible = true)))
        // Below the cap Fit width is exact, and it can still shrink a wide diagram.
        assertEquals(1.1f, seq3FitWidthZoom(100.0, 110.0, seq3FitWidthMaxZoom(sourceVisible = true)), 0.001f)
        assertEquals(0.5f, seq3FitWidthZoom(400.0, 200.0, seq3FitWidthMaxZoom(sourceVisible = true)), 0.001f)
    }

    // ── Gutter numbering ──────────────────────────────────────────────────────────────────────────

    private val markerLine = "' indagium-seq3:v1 m1"
    private val marked = listOf("@startuml", "participant \"A\" as A", markerLine, "participant \"B\" as B", markerLine, "A -> B: hi", markerLine, "@enduml")
        .joinToString("\n")

    @Test
    fun theGutterNumbersTheVisibleLinesOneToNWhileMarkersAreHidden() {
        val puml = Seq3Dialect.PLANTUML
        // Raw lines 1,2,4,6,8 are shown; diagnostics on them are numbered by their place among those.
        assertEquals(listOf(1, 2, 3, 4, 5), seq3SourceDisplayLines(marked, listOf(1, 2, 4, 6, 8), puml, showMarkers = false))
    }

    @Test
    fun aDiagnosticOnAHiddenMarkerLineIsNumberedLikeTheLineShownInItsPlace() {
        val puml = Seq3Dialect.PLANTUML
        // Raw line 3 is a marker: it is shown as the next visible line, raw line 4, the third row.
        assertEquals(listOf(3), seq3SourceDisplayLines(marked, listOf(3), puml, showMarkers = false))
        assertEquals(listOf(5), seq3SourceDisplayLines(marked, listOf(7), puml, showMarkers = false))
    }

    @Test
    fun withMarkersShownTheNumbersAreTheRawOnes() {
        assertEquals(listOf(1, 3, 6, 8), seq3SourceDisplayLines(marked, listOf(1, 3, 6, 8), Seq3Dialect.PLANTUML, showMarkers = true))
    }

    @Test
    fun withoutMarkersTheNumbersAreTheRawOnes() {
        val plain = "@startuml\nA -> B: hi\n@enduml"
        assertEquals(listOf(1, 2, 3), seq3SourceDisplayLines(plain, listOf(1, 2, 3), Seq3Dialect.PLANTUML, showMarkers = false))
        assertEquals(emptyList(), seq3SourceDisplayLines(plain, emptyList(), Seq3Dialect.PLANTUML, showMarkers = false))
    }

    @Test
    fun theMarkersMenuItemIsOfferedOnlyWhenTheSourceHasMarkers() {
        assertTrue(seq3SourceHasMarkers(marked, Seq3Dialect.PLANTUML))
        assertFalse(seq3SourceHasMarkers("@startuml\nA -> B: hi\n@enduml", Seq3Dialect.PLANTUML))
        assertFalse(seq3SourceHasMarkers("", Seq3Dialect.PLANTUML))
        // A marker of the other dialect is an ordinary comment here.
        assertFalse(seq3SourceHasMarkers("sequenceDiagram\n%% indagium-seq3:v1 m1\nA->>B: hi", Seq3Dialect.PLANTUML))
        assertTrue(seq3SourceHasMarkers("sequenceDiagram\n%% indagium-seq3:v1 m1\nA->>B: hi", Seq3Dialect.MERMAID))
    }

    // ── Status text ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun aManualDiagramLeavesOutTheScannedCount() {
        val manual = Seq3Document(title = "Manual")
        assertEquals("0 shown · 0 lifelines · 0 hidden", seq3CanvasStatusSummary(manual, 0, 0, 0))
        val generated = Seq3Document(title = "From a log", sourceFile = "app.log")
        assertEquals("3 shown · 120 scanned · 0 lifelines · 1 hidden", seq3CanvasStatusSummary(generated, 3, 120, 1))
        assertEquals("0 shown · 0 scanned · 0 lifelines · 0 hidden", seq3CanvasStatusSummary(generated, 0, 0, 0), "a log diagram says so, even at zero")
    }

    // ── One dialect (session) ─────────────────────────────────────────────────────────────────────

    @Test
    fun theDiagramsDialectDrivesTheEditorAndPersistsWithTheDiagram() {
        val root = createTempDirectory("dialect-ux").toFile()
        try {
            val tab = mkTab("dialect-ux", "dialect-ux.log", emptyList())
            val libraryFile = File(root, "library.cache")
            val state = AppState(
                File(root, "state.cache"),
                notesDir = File(root, "notes"),
                diagramLibraryStore = DiagramLibraryStore(libraryFile),
            ).also {
                it.tabs = listOf(tab)
                it.activateTab(tab.id)
            }
            val id = requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
            val view = requireNotNull(state.seq3Sessions.viewState(id))

            fun session() = state.seq3Sessions.sessions.single { it.id == id }
            assertEquals(Seq3Dialect.PLANTUML, session().dialect, "a new diagram starts in PlantUML")
            assertEquals(Seq3Dialect.PLANTUML, requireNotNull(DiagramLibraryStore(libraryFile).get(requireNotNull(session().libraryItemId))).parsed?.dialect)

            // The editor opens in the diagram's dialect, not in a default of its own.
            assertTrue(state.seq3Sessions.prepareSourceDraft(id))
            assertEquals(Seq3Dialect.PLANTUML, view.sourceEditorDialect)
            assertTrue(view.sourceDraft.startsWith("@startuml"), view.sourceDraft)

            // Switching the editor changes the diagram's dialect with it, and it is saved with the diagram.
            assertTrue(state.seq3Sessions.switchSourceDraftDialect(id, Seq3Dialect.MERMAID))
            assertEquals(Seq3Dialect.MERMAID, session().dialect)
            assertEquals(Seq3Dialect.MERMAID, view.sourceEditorDialect)
            assertTrue(view.sourceDraft.startsWith("sequenceDiagram"), view.sourceDraft)
            state.seq3Sessions.flush(id)
            val savedItem = DiagramLibraryStore(libraryFile).get(requireNotNull(session().libraryItemId))
            assertEquals(Seq3Dialect.MERMAID, savedItem?.parsed?.dialect)

            // The header control: setDialect alone saves too, and a restored session opens the editor in it.
            state.seq3Sessions.setDialect(id, Seq3Dialect.PLANTUML)
            state.seq3Sessions.flush(id)
            val reopenedItem = requireNotNull(DiagramLibraryStore(libraryFile).get(requireNotNull(session().libraryItemId)))
            assertEquals(Seq3Dialect.PLANTUML, reopenedItem.parsed?.dialect)
            state.seq3Sessions.close(id)
            assertTrue(state.seq3Sessions.openLibraryItem(reopenedItem.id, tab.id))
            val reopened = state.seq3Sessions.sessions.single()
            assertEquals(Seq3Dialect.PLANTUML, reopened.dialect)
            val reopenedView = requireNotNull(state.seq3Sessions.viewState(reopened.id))
            assertTrue(state.seq3Sessions.prepareSourceDraft(reopened.id))
            assertEquals(Seq3Dialect.PLANTUML, reopenedView.sourceEditorDialect)
            state.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aNewDiagramKeepsARealTitle() {
        val root = createTempDirectory("title-ux").toFile()
        try {
            val tab = mkTab("title-ux", "title-ux.log", emptyList())
            val state = AppState(
                File(root, "state.cache"),
                notesDir = File(root, "notes"),
                diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
            ).also {
                it.tabs = listOf(tab)
                it.activateTab(tab.id)
            }
            val id = requireNotNull(state.seq3Sessions.beginEmpty(tab.id))

            fun session() = state.seq3Sessions.sessions.single { it.id == id }
            val title = session().document.title
            assertTrue(title.isNotBlank())

            // A titled diagram keeps its own title through New diagram.
            assertEquals(title, state.seq3Sessions.newSourceDiagramTitle(id))
            val starter = seq3SourceStarter(Seq3Dialect.PLANTUML, state.seq3Sessions.newSourceDiagramTitle(id))
            state.seq3Sessions.startNewSourceDiagram(id, starter, Seq3Dialect.PLANTUML)
            assertEquals(title, session().document.title)

            // One without a title gets the default, never an empty one.
            state.seq3Sessions.applyCommand(id, Seq3Command.ReplaceDocument(session().document.copy(title = "")))
            assertEquals("Untitled diagram", state.seq3Sessions.newSourceDiagramTitle(id))
            state.close()
        } finally {
            root.deleteRecursively()
        }
    }
}
