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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
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
        assertTrue(view.diagramHiddenTiles.isEmpty(), "Reset should restore all preset tiles")
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
