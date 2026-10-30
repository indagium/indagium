package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Captures real generated content in the wide tiled workspace and narrow preview-first stack. */
class DiagramWorkspaceTilingUiTest {
    private companion object {
        const val GENERATION_TIMEOUT_NANOS = 4_000_000_000L
    }

    @get:Rule
    val rule = createComposeRule()

    private val tempDir = Files.createTempDirectory("diagram-tiles-ui-").toFile()
    private val state = AppState(
        autosaveFile = File(tempDir, "state.cache"),
        restoreOnCreate = false,
        notesDir = File(tempDir, "notes"),
        archiveCacheDir = File(tempDir, "archive-cache"),
        customCommandsDir = File(tempDir, "commands"),
        filterBackupsDir = File(tempDir, "filter-backups"),
        diagramLibraryStore = DiagramLibraryStore(File(tempDir, "library.cache")),
    )
    private var workspaceWidth by mutableIntStateOf(1024)

    @After
    fun tearDown() {
        state.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun generatedWorkspaceHasRealTilesAndProducesWideAndNarrowEvidence() {
        val tab = mkTab("diagram-tiles", "diagram-tiles.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Client", "request start", 7, 11),
            LogEntry(2, "10:00:00.050", LogLevel.I, "Server", "request accepted", 7, 11),
            LogEntry(3, "10:00:00.100", LogLevel.I, "Client", "request complete", 7, 11),
            LogEntry(4, "10:00:00.150", LogLevel.I, "Server", "response sent", 7, 11),
        ))
        state.tabs = listOf(tab)
        state.activateTab(tab.id)
        val sessionId = requireNotNull(state.seq3Sessions.begin(tab.id, setOf(1, 2, 3, 4)))
        awaitGenerated(state, sessionId)
        val session = requireNotNull(state.seq3Sessions.sessions.firstOrNull { it.id == sessionId })
        assertTrue(session.document.messages.isNotEmpty(), "the evidence diagram should contain generated messages")

        setWorkspace(sessionId)
        rule.waitForIdle()

        val messages = rule.onNodeWithTag("diagram-tile-card-messages").getBoundsInRoot()
        val preview = rule.onNodeWithTag("diagram-tile-card-preview").getBoundsInRoot()
        rule.onNodeWithTag("diagram-tile-card-lifelines").assertExists()
        rule.onNodeWithTag("diagram-tile-card-artifacts").assertExists()
        rule.onNodeWithText("Lifelines", useUnmergedTree = true).assertExists()
        assertTrue(messages.right <= preview.left, "authoring tiles should sit to the left of preview in wide layout")
        saveEvidenceScreenshot("diagram-wide-generated.png", rule.onNodeWithTag("diagram-tiling-host").captureToImage())

        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("diagram-tile-card-source").assertExists()
        rule.onNodeWithTag("seq3-source-editor").assertIsDisplayed()
        saveEvidenceScreenshot("seq3-source-tile.png", rule.onNodeWithTag("diagram-tiling-host").captureToImage())
        rule.onNodeWithTag("diagram-tile-hide-source").performMouseInput { click() }
        rule.waitForIdle()

        rule.runOnIdle { workspaceWidth = 620 }
        rule.waitForIdle()
        val narrowPreview = rule.onNodeWithTag("diagram-tile-card-preview").getBoundsInRoot()
        val narrowMessages = rule.onNodeWithTag("diagram-tile-card-messages").getBoundsInRoot()
        assertTrue(narrowPreview.top < narrowMessages.top, "preview should be first in the narrow scroll stack")
        saveEvidenceScreenshot("diagram-narrow-generated.png", rule.onNodeWithTag("diagram-tiling-host").captureToImage())
    }

    @Test
    fun diagramTileControlsResizeHideRestoreResetAndMaximizeThroughEscape() {
        val tab = mkTab("diagram-tile-controls", "controls.log", emptyList())
        state.tabs = listOf(tab)
        state.activateTab(tab.id)
        val sessionId = requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val initialLayout = view.diagramTileLayout
        setWorkspace(sessionId)
        rule.waitForIdle()

        val splitter = rule.onNodeWithTag("diagram-tile-splitter-0")
        splitter.performSemanticsAction(SemanticsActions.RequestFocus)
        splitter.performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        assertNotEquals(initialLayout, view.diagramTileLayout, "the diagram splitter should commit a resize")

        rule.onNodeWithTag("diagram-tile-maximize-preview").performClick()
        rule.waitForIdle()
        assertEquals(DiagramWorkspaceSectionId.PREVIEW, view.maximizedDiagramTile)
        rule.onNodeWithTag("diagram-tile-card-preview").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertNull(view.maximizedDiagramTile, "Escape should restore the tiled preview")

        rule.onNodeWithTag("diagram-tile-hide-messages").performClick()
        rule.waitForIdle()
        assertTrue(DiagramWorkspaceSectionId.MESSAGES in view.diagramHiddenTiles)
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.onNodeWithTag("diagram-layout-menu-panel-messages").performClick()
        rule.waitForIdle()
        assertTrue(DiagramWorkspaceSectionId.MESSAGES !in view.diagramHiddenTiles)
        rule.onNodeWithTag("diagram-tile-card-messages").assertExists()

        rule.onNodeWithTag("diagram-layout-menu-preset-CANVAS_FOCUS").performClick()
        rule.waitForIdle()
        assertEquals(DiagramWorkspacePreset.CANVAS_FOCUS, view.diagramLastPreset)
        rule.onNodeWithTag("diagram-layout-menu-panel-artifacts").performClick()
        rule.waitForIdle()
        assertTrue(DiagramWorkspaceSectionId.ARTIFACTS in view.diagramHiddenTiles)
        rule.onNodeWithTag("diagram-layout-menu-reset").performClick()
        rule.waitForIdle()
        assertEquals(DiagramWorkspacePreset.CANVAS_FOCUS.layout, view.diagramTileLayout)
        assertEquals(listOf(DiagramWorkspaceSectionId.SOURCE), view.diagramHiddenTiles, "presets keep source hidden until requested")
    }

    @Test
    fun sourceTileCanBeShownCollapsedHiddenAndItsVisibilityPersists() {
        val tab = mkTab("diagram-source-tile", "source-tile.log", emptyList())
        state.tabs = listOf(tab)
        state.activateTab(tab.id)
        val sessionId = requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        assertTrue(DiagramWorkspaceSectionId.SOURCE in view.diagramHiddenTiles)
        assertFalse(DiagramWorkspaceSectionId.SOURCE in view.diagramCollapsedTiles, "new source tile starts expanded")
        setWorkspace(sessionId)
        rule.waitForIdle()

        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.onNodeWithTag("diagram-layout-menu-panel-source").performClick()
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.waitForIdle()
        assertTrue(DiagramWorkspaceSectionId.SOURCE !in view.diagramHiddenTiles)
        rule.onNodeWithTag("diagram-tile-card-source").assertExists()
        rule.onNodeWithTag("seq3-source-editor").assertExists()
        val sourceEditor = rule.onNodeWithTag("seq3-source-editor")
        val sourceDraft = "${view.sourceDraft}\n' visibility draft"
        sourceEditor.performTextReplacement(sourceDraft)
        rule.waitForIdle()
        assertTrue(view.sourceDraftDirty)
        assertTrue(view.textFieldFocused)
        assertFalse(DiagramWorkspaceSectionId.SOURCE in view.diagramCollapsedTiles, "source tile starts expanded after Show")

        val collapseControl = rule.onNodeWithTag("diagram-tile-collapse-source").assertIsDisplayed()
        val collapseBounds = collapseControl.getBoundsInRoot()
        val editorBounds = rule.onNodeWithTag("seq3-source-editor").getBoundsInRoot()
        val headerBounds = rule.onNodeWithTag("diagram-tile-header-source").getBoundsInRoot()
        val sourceCardBounds = rule.onNodeWithTag("diagram-tile-card-source").getBoundsInRoot()
        val previewBounds = rule.onNodeWithTag("diagram-tile-card-preview").getBoundsInRoot()
        val collapseCenterX = (collapseBounds.left.value + collapseBounds.right.value) / 2
        val collapseCenterY = (collapseBounds.top.value + collapseBounds.bottom.value) / 2
        assertFalse(
            collapseCenterX in editorBounds.left.value..editorBounds.right.value &&
                collapseCenterY in editorBounds.top.value..editorBounds.bottom.value,
            "source editor bounds $editorBounds must not cover the collapse control at ($collapseCenterX, $collapseCenterY)",
        )
        saveEvidenceScreenshot("source-tile-collapse-before.png", rule.onNodeWithTag("diagram-tiling-host").captureToImage())
        collapseControl.performMouseInput { click() }
        rule.waitForIdle()
        assertTrue(
            DiagramWorkspaceSectionId.SOURCE in view.diagramCollapsedTiles,
            "physical collapse click did not update state; control=$collapseBounds header=$headerBounds " +
                "card=$sourceCardBounds preview=$previewBounds editor=$editorBounds",
        )
        assertFalse(view.textFieldFocused, "collapsing the focused source tile returns focus to the workspace")
        assertEquals(sourceDraft, view.sourceDraft, "collapse preserves the detached source draft")
        rule.onNodeWithTag("seq3-workspace-root").assertIsFocused()
        rule.onNodeWithTag("diagram-tile-collapse-source").performClick()
        rule.waitForIdle()
        assertTrue(DiagramWorkspaceSectionId.SOURCE !in view.diagramCollapsedTiles)

        rule.onNodeWithTag("diagram-tile-hide-source").performMouseInput { click() }
        rule.waitForIdle()
        assertTrue(DiagramWorkspaceSectionId.SOURCE in view.diagramHiddenTiles)
        assertFalse(view.textFieldFocused)
        assertEquals(sourceDraft, view.sourceDraft, "hiding preserves the draft for a later show")

        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.onNodeWithTag("diagram-layout-menu-panel-source").performClick()
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.waitForIdle()
        assertEquals(sourceDraft, view.sourceDraft)
        assertTrue(view.sourceDraftDirty)
        val tokenWithSourceVisible = state.seq3ViewToken()
        rule.onNodeWithTag("diagram-tile-hide-source").performClick()
        rule.waitForIdle()
        state.restoreSeq3ViewToken(tokenWithSourceVisible)
        rule.waitForIdle()
        assertTrue(DiagramWorkspaceSectionId.SOURCE !in view.diagramHiddenTiles, "saved visibility restores the explicit Show choice")
        rule.onNodeWithTag("diagram-tile-card-source").assertExists()
    }

    private fun setWorkspace(sessionId: String) {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.width(workspaceWidth.dp).height(900.dp)) {
                    Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.End) {
                        LayoutMenuButton(
                            state = state,
                            onReclaimFocus = {
                                state.seq3Sessions.viewState(sessionId)?.let { runCatching { it.focusRequester.requestFocus() } }
                            },
                            wrap = true,
                            menuBeside = false,
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) { Seq3Workspace(state, sessionId) }
                }
            }
        }
    }

    private fun awaitGenerated(state: AppState, id: String) {
        val deadline = System.nanoTime() + GENERATION_TIMEOUT_NANOS
        while (state.seq3Sessions.sessions.firstOrNull { it.id == id }?.generating != false && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        check(state.seq3Sessions.sessions.firstOrNull { it.id == id }?.generating == false) {
            "diagram generation did not finish before visual QA"
        }
    }

    private fun saveEvidenceScreenshot(name: String, image: ImageBitmap) {
        val directory = File("build/diagram-tiles-qa").apply { mkdirs() }
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        check(ImageIO.write(rendered, "png", File(directory, name)))
    }
}
