package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3CustomMessageSpec
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.testing.setTestsViewport
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 5 of the diagram editor, in a composition: the narrow header, the one dialect control, Edit
 *  opening beside the preview, the Fit width cap, gutter numbering, and the smaller workspace items. */
class Seq3WorkspaceUxUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var root: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    // ── Narrow header ────────────────────────────────────────────────────────────────────────────

    @Test
    fun aWideHeaderShowsEditCopyAndAttachInARow() {
        val sessionId = startEmptyWorkspace("ux-wide-header")
        showWorkspace(sessionId, 1280, 900)
        rule.onNodeWithTag("seq3-source-edit").assertIsDisplayed()
        rule.onNodeWithTag("seq3-header-more").assertDoesNotExist()
        rule.onNodeWithText("Attach snapshot", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aNarrowHeaderFoldsEditCopyAndAttachIntoAMenuAt520dp() {
        val sessionId = startEmptyWorkspace("ux-narrow-header")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId, 520, 900)

        rule.onNodeWithTag("seq3-source-edit").assertDoesNotExist()
        rule.onNodeWithText("Copy", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Attach snapshot", useUnmergedTree = true).assertDoesNotExist()
        val more = rule.onNodeWithTag("seq3-header-more")
        more.assertIsDisplayed()
        assertTrue(more.getBoundsInRoot().right.value <= 520f, "the menu button is inside the header: ${more.getBoundsInRoot()}")

        more.performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Edit source", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Copy PNG image", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Copy PlantUML source", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Attach snapshot", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Attach live link", useUnmergedTree = true).assertIsDisplayed()

        assertFalse(sourceVisible(view))
        rule.onNodeWithText("Edit source", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(sourceVisible(view), "the folded Edit opens the source editor")
        rule.onNodeWithTag("seq3-source-editor").assertIsDisplayed()
    }

    // ── One dialect ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun theHeaderToggleAndTheSourceDropdownChangeTheSameDialect() {
        val sessionId = startEmptyWorkspace("ux-one-dialect")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId, 1280, 900)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        assertEquals(Seq3Dialect.PLANTUML, currentSession(sessionId).dialect, "a new diagram starts in PlantUML")
        assertEquals(Seq3Dialect.PLANTUML, view.sourceEditorDialect, "the editor opens in the diagram's dialect")
        assertTrue(view.sourceDraft.startsWith("@startuml"), view.sourceDraft)

        rule.onNode(hasText("Mermaid") and hasAnyAncestor(hasTestTag("seq3-header-dialect")), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(Seq3Dialect.MERMAID, currentSession(sessionId).dialect)
        assertEquals(Seq3Dialect.MERMAID, view.sourceEditorDialect)
        assertTrue(view.sourceDraft.startsWith("sequenceDiagram"), view.sourceDraft)

        rule.onNodeWithTag("seq3-source-dialect").performClick()
        rule.onNode(hasText("PlantUML") and hasAnyAncestor(hasTestTag("seq3-source-dialect-options")), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(Seq3Dialect.PLANTUML, currentSession(sessionId).dialect, "the dropdown moves the header's dialect too")
        assertEquals(Seq3Dialect.PLANTUML, view.sourceEditorDialect)
        assertTrue(view.sourceDraft.startsWith("@startuml"), view.sourceDraft)
    }

    @Test
    fun theHeaderToggleAsksBeforeDiscardingAnUnappliedDraft() {
        val sessionId = startEmptyWorkspace("ux-dialect-confirm")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId, 1280, 900)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        view.sourceLiveEnabled = false
        rule.onNodeWithTag("seq3-source-editor").performTextReplacement("@startuml\nA -> B: hello\n@enduml")
        rule.waitForIdle()
        assertTrue(view.sourceHasUnappliedDraft())

        rule.onNode(hasText("Mermaid") and hasAnyAncestor(hasTestTag("seq3-header-dialect")), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(Seq3Dialect.MERMAID, view.sourceDialectChangePending)
        assertEquals(Seq3Dialect.PLANTUML, currentSession(sessionId).dialect, "nothing changes until the question is answered")
        assertEquals("@startuml\nA -> B: hello\n@enduml", view.sourceDraft)

        rule.onNodeWithTag("seq3-source-confirm-dialect-switch").performClick()
        rule.waitForIdle()
        assertEquals(Seq3Dialect.MERMAID, currentSession(sessionId).dialect)
        assertEquals(Seq3Dialect.MERMAID, view.sourceEditorDialect)
        assertTrue(view.sourceDraft.startsWith("sequenceDiagram"), view.sourceDraft)
    }

    // ── Source beside the preview ────────────────────────────────────────────────────────────────

    @Test
    fun editOpensSourceBesideThePreviewOnAWideWindow() {
        val sessionId = startEmptyWorkspace("ux-beside")
        showWorkspace(sessionId, 1400, 900)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        val preview = rule.onNodeWithTag("diagram-tile-card-preview").getBoundsInRoot()
        val source = rule.onNodeWithTag("diagram-tile-card-source").getBoundsInRoot()
        assertTrue(source.left >= preview.right, "source $source is right of preview $preview")
        assertTrue(kotlin.math.abs(source.top.value - preview.top.value) < 2f, "the two share a row: $source / $preview")
        rule.onNodeWithTag("seq3-source-editor").assertIsDisplayed()
    }

    @Test
    fun editKeepsSourceUnderThePreviewOnAnOrdinaryWindow() {
        val sessionId = startEmptyWorkspace("ux-stacked")
        showWorkspace(sessionId, 1024, 900)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        val preview = rule.onNodeWithTag("diagram-tile-card-preview").getBoundsInRoot()
        val source = rule.onNodeWithTag("diagram-tile-card-source").getBoundsInRoot()
        assertTrue(source.top >= preview.bottom, "source $source is under preview $preview")
    }

    @Test
    fun editLeavesALayoutTheUserArrangedAlone() {
        val sessionId = startEmptyWorkspace("ux-arranged")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        // The user swapped two side tiles: not the default arrangement, so Edit must not move anything.
        val arranged = dropTile(
            view.diagramTileLayout,
            DiagramWorkspaceSectionId.LIFELINES,
            TileDropTarget.OnSection(DiagramWorkspaceSectionId.MESSAGES, TileEdge.TOP),
        )
        view.diagramTileLayout = arranged
        showWorkspace(sessionId, 1400, 900)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        assertEquals(arranged, view.diagramTileLayout)
        assertTrue(sourceVisible(view))
        val preview = rule.onNodeWithTag("diagram-tile-card-preview").getBoundsInRoot()
        val source = rule.onNodeWithTag("diagram-tile-card-source").getBoundsInRoot()
        assertTrue(source.top >= preview.bottom, "source stays under the preview: $source / $preview")
    }

    // ── Fit width cap ────────────────────────────────────────────────────────────────────────────

    @Test
    fun fitWidthIsCappedAt125PercentWhileSourceIsOpen() {
        val sessionId = startEmptyWorkspace("ux-fit-cap")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        seedTwoLifelines(sessionId)
        // Keep the editor stacked under the preview so the preview stays wide enough to want > 125%.
        view.diagramTileLayout = dropTile(
            view.diagramTileLayout,
            DiagramWorkspaceSectionId.LIFELINES,
            TileDropTarget.OnSection(DiagramWorkspaceSectionId.MESSAGES, TileEdge.TOP),
        )
        showWorkspace(sessionId, 1700, 900)
        assertEquals(Seq3ZoomMode.FIT_WIDTH, view.zoomMode)
        rule.waitForIdle()
        assertTrue(view.zoom > 1.25f, "a small diagram in a wide preview fills it: ${view.zoom}")

        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        assertTrue(sourceVisible(view))
        assertEquals(1.25f, view.zoom, 0.0001f, "Fit width stops at 125% while the editor is open")
    }

    // ── Gutter numbering and diagnostics ─────────────────────────────────────────────────────────

    @Test
    fun theGutterCountsVisibleLinesAndDiagnosticsUseTheSameNumbers() {
        val sessionId = startEmptyWorkspace("ux-gutter")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        seedTwoLifelines(sessionId)
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", "First")))
        state.seq3Sessions.setDialect(sessionId, Seq3Dialect.PLANTUML)
        showWorkspace(sessionId, 1280, 900)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        val canonical = view.sourceDraft
        assertTrue(canonical.contains("indagium-seq3:v1"), canonical)
        val alias = requireNotNull(Regex("(?m)^participant .* as (\\w+)").find(canonical)) { canonical }.groupValues[1]
        rule.onNodeWithTag("seq3-source-editor").performTextReplacement(canonical.replace("@enduml", "$alias -> Pay: second\n@enduml"))
        rule.waitUntil(5_000) { view.sourceDiagnostics.isNotEmpty() }

        val visibleLines = editorText().lines()
        val payNumber = visibleLines.indexOfFirst { it.contains("Pay") } + 1
        assertTrue(payNumber > 0, visibleLines.toString())
        val diagnostic = view.sourceDiagnostics.first { it.fix != null }
        assertTrue(diagnostic.line > payNumber, "the raw line ${diagnostic.line} is past the shown number $payNumber")

        // The gutter shows 1..n for the shown lines, whatever their raw numbers are.
        assertEquals((1..visibleLines.size).map(Int::toString), gutterNumbers().take(visibleLines.size))
        assertEquals(payNumber.toString(), gutterNumberOfRawLine(diagnostic.line))

        rule.onNodeWithTag("seq3-source-diagnostics-details").performClick()
        assertEquals("Line $payNumber", text("seq3-source-issue-${view.sourceDiagnostics.indexOf(diagnostic)}", child = true))
        rule.onNodeWithTag("seq3-source-issue-${view.sourceDiagnostics.indexOf(diagnostic)}").performClick()
        rule.waitForIdle()
        assertEquals("Line $payNumber: ${diagnostic.message}", text("seq3-source-caret-message"))
    }

    // ── Source toolbar ───────────────────────────────────────────────────────────────────────────

    @Test
    fun showMarkersIsOfferedOnlyWhenTheSourceHasMarkers() {
        val sessionId = startEmptyWorkspace("ux-markers-menu")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        state.seq3Sessions.setDialect(sessionId, Seq3Dialect.PLANTUML)
        showWorkspace(sessionId, 1280, 900)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        assertFalse(view.sourceDraft.contains("indagium-seq3:v1"), view.sourceDraft)

        rule.onNodeWithTag("seq3-source-more").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-new-diagram").assertIsDisplayed()
        rule.onNodeWithTag("seq3-source-toggle-markers").assertDoesNotExist()
    }

    @Test
    fun theMarkersItemAppearsOnceTheSourceHasMarkers() {
        val sessionId = startEmptyWorkspace("ux-markers-present")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        seedTwoLifelines(sessionId)
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", "First")))
        state.seq3Sessions.setDialect(sessionId, Seq3Dialect.PLANTUML)
        showWorkspace(sessionId, 1280, 900)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        assertTrue(view.sourceDraft.contains("indagium-seq3:v1"), view.sourceDraft)

        rule.onNodeWithTag("seq3-source-more").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-toggle-markers").assertIsDisplayed()
        rule.onNodeWithTag("seq3-source-toggle-markers").performClick()
        rule.waitForIdle()
        assertTrue(editorText().contains("indagium-seq3:v1"), "Show markers shows them")
    }

    @Test
    fun theInsertButtonsStayInOneGroupApartFromTheMenu() {
        val sessionId = startEmptyWorkspace("ux-insert-group")
        showWorkspace(sessionId, 1280, 900)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        val group = rule.onNodeWithTag("seq3-source-insert-group").getBoundsInRoot()
        for (tag in listOf("seq3-source-insert-participant", "seq3-source-insert-actor", "seq3-source-message-menu", "seq3-source-insert-note",
            "seq3-source-fragment-menu", "seq3-source-activation-menu")) {
            val bounds = rule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue(bounds.left >= group.left && bounds.right <= group.right, "$tag $bounds is in the group $group")
        }
        val more = rule.onNodeWithTag("seq3-source-more").getBoundsInRoot()
        assertTrue(more.left >= group.right, "the menu sits after the group: $more / $group")
    }

    // ── Titles ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun newDiagramKeepsARealTitleInTheHeader() {
        val sessionId = startEmptyWorkspace("ux-new-title")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        state.seq3Sessions.setDialect(sessionId, Seq3Dialect.PLANTUML)
        showWorkspace(sessionId, 1280, 900)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-more").performClick()
        rule.onNodeWithTag("seq3-source-new-diagram").performClick()
        rule.waitForIdle()
        val title = currentSession(sessionId).document.title
        assertEquals("Untitled diagram", title)
        assertTrue(view.sourceDraft.contains("title $title"), view.sourceDraft)
        rule.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Sequence diagram v3", useUnmergedTree = true).assertDoesNotExist()
    }

    // ── Canvas ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theStatusBarLeavesOutScannedForAManualDiagram() {
        val sessionId = startEmptyWorkspace("ux-scanned")
        showWorkspace(sessionId, 1280, 900)
        rule.onNodeWithText("0 lifelines", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(" scanned", substring = true, useUnmergedTree = true).assertDoesNotExist()

        state.seq3Sessions.applyCommand(
            sessionId,
            Seq3Command.ReplaceDocument(currentSession(sessionId).document.copy(sourceFile = "ux-scanned.log")),
        )
        rule.waitForIdle()
        rule.onNodeWithText(" scanned", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun theEmptyCanvasOffersAPlusParticipantThatOpensTheEditorWithOne() {
        val sessionId = startEmptyWorkspace("ux-empty-add")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        state.seq3Sessions.setDialect(sessionId, Seq3Dialect.PLANTUML)
        showWorkspace(sessionId, 1280, 900)
        rule.onNodeWithText("No lifelines to diagram", useUnmergedTree = true).assertIsDisplayed()
        assertFalse(sourceVisible(view))

        rule.onNodeWithTag("seq3-canvas-empty-add-participant").performClick()
        rule.waitForIdle()
        assertTrue(sourceVisible(view), "the editor opens")
        assertFalse(view.sourceInsertParticipantRequest, "the request is consumed once")
        assertTrue(view.sourceDraft.contains("participant") && view.sourceDraft.contains("Alias"), view.sourceDraft)
        rule.onNodeWithTag("seq3-source-editor").assertIsDisplayed()
    }

    // ── Messages tile ────────────────────────────────────────────────────────────────────────────

    @Test
    fun theMessagesTileTitleIsNotTruncatedAt1440By900() {
        val sessionId = startEmptyWorkspace("ux-messages-title")
        showWorkspace(sessionId, 1440, 900)
        val titles = rule.onAllNodesWithText("Messages", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { node ->
                generateSequence(node.parent) { it.parent }
                    .any { it.config.getOrNull(SemanticsProperties.TestTag) == "diagram-tile-header-messages" }
            }
        assertEquals(1, titles.size, "the tile header holds the title once")
        val layouts = mutableListOf<TextLayoutResult>()
        titles.single().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        assertFalse(layouts.single().hasVisualOverflow, "the title reads in full, not as \"Me…\"")
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun editorText(): String =
        rule.onNodeWithTag("seq3-source-editor").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun text(tag: String, child: Boolean = false): String {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val source = if (child) node.children.first() else node
        return source.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    }

    private val gutterRow = SemanticsMatcher("a gutter row") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("seq3-source-gutter-line-") == true
    }

    /** The numbers the gutter shows, top to bottom. */
    private fun gutterNumbers(): List<String> =
        rule.onAllNodes(gutterRow, useUnmergedTree = true).fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .map { node -> node.children.first().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty() }

    /** The number shown in the gutter row that stands for raw source line [raw]. */
    private fun gutterNumberOfRawLine(raw: Int): String = text("seq3-source-gutter-line-$raw", child = true)

    private fun startEmptyWorkspace(tabId: String): String {
        root = createTempDirectory("$tabId-ui").toFile()
        val tab = mkTab(tabId, "$tabId.log", emptyList())
        state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also {
            it.tabs = listOf(tab)
            it.activateTab(tab.id)
        }
        return requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
    }

    private fun seedTwoLifelines(sessionId: String) {
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddLifeline(Seq3Lifeline("a", "A", setOf("A"), 0)))
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddLifeline(Seq3Lifeline("b", "B", setOf("B"), 1)))
    }

    private fun currentSession(sessionId: String) =
        requireNotNull(state.seq3Sessions.sessions.singleOrNull { it.id == sessionId })

    /** The compose test window is 1024 x 768 unless it is resized, so every test sets the size it means. */
    private fun showWorkspace(sessionId: String, width: Int, height: Int) {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.fillMaxSize()) {
                    Seq3Workspace(state, sessionId)
                }
            }
        }
        rule.setTestsViewport(width, height)
        rule.waitForIdle()
    }

    private fun sourceVisible(view: Seq3ViewState): Boolean =
        DiagramWorkspaceSectionId.SOURCE in diagramWorkspaceVisibleTiles(view)
}
