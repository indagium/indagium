package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

/** The Layout button's warning dot on the Tests surface and a diagram: it shows only when the hidden tiles differ from the surface default. */
class LayoutMenuDotUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun setup() {
        directory = createTempDirectory("layout-menu-dot-ui").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
    }

    private fun setButton() = rule.setContent {
        CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
            Box(Modifier.size(300.dp, 60.dp)) { LayoutMenuButton(state, onReclaimFocus = {}, wrap = true, menuBeside = false) }
        }
    }

    @Test
    fun theTestsSurfaceShowsTheDotOnlyWhileATileIsHidden() {
        setup()
        rule.runOnIdle { state.openTestsTab() }
        setButton()
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-button").assertExists()
        rule.onNodeWithTag("layout-menu-modified-dot").assertDoesNotExist()
        val hide = testsWorkspaceVisibleTiles(state.settings.currentTestsWorkspaceLayout()).first { it != TestsWorkspaceTileId.WORKSPACE }
        rule.runOnIdle {
            state.updateSettings { it.withTestsWorkspaceLayout(it.currentTestsWorkspaceLayout().withTileVisible(hide, false)) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-modified-dot").assertExists()
        rule.onNodeWithTag("layout-menu-badge").assertDoesNotExist()
        rule.runOnIdle { state.updateSettings { it.withTestsWorkspaceLayout(testsWorkspaceDefaultState()) } }
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-modified-dot").assertDoesNotExist()
    }

    @Test
    fun aNewDiagramShowsNoDotHidingAnotherTileShowsItAndRestoringClearsIt() {
        setup()
        val tab = mkTab("dot-diagram", "dot.log", emptyList())
        state.tabs = listOf(tab)
        state.activateTab(tab.id)
        val sessionId = requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        assertEquals(listOf(DiagramWorkspaceSectionId.SOURCE), view.diagramHiddenTiles, "a new diagram hides Source by default")
        setButton()
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-modified-dot").assertDoesNotExist()

        rule.runOnIdle { setDiagramWorkspaceTileVisible(view, DiagramWorkspaceSectionId.MESSAGES, false) }
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-modified-dot").assertExists()
        rule.onNodeWithTag("layout-menu-badge").assertDoesNotExist()

        rule.runOnIdle { setDiagramWorkspaceTileVisible(view, DiagramWorkspaceSectionId.MESSAGES, true) }
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-modified-dot").assertDoesNotExist()

        // Showing the Source tile the default hides is also a change from the default.
        rule.runOnIdle { setDiagramWorkspaceTileVisible(view, DiagramWorkspaceSectionId.SOURCE, true) }
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-modified-dot").assertExists()
        rule.runOnIdle { setDiagramWorkspaceTileVisible(view, DiagramWorkspaceSectionId.SOURCE, false) }
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-modified-dot").assertDoesNotExist()
    }
}
