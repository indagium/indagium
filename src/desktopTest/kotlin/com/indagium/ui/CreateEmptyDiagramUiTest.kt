package com.indagium.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CreateEmptyDiagramUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var root: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun emptyDiagramOptionIsAvailableAndWorksWithoutSelectionOrLogRows() {
        root = createTempDirectory("create-empty-diagram-ui").toFile()
        val tab = mkTab("empty-log", "empty.log", emptyList())
        state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also {
            it.tabs = listOf(tab)
            it.activateTab(tab.id)
        }
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Row { DiagramLibraryHeaderAction(state, tab) }
            }
        }

        rule.onNodeWithText("+ diagram", useUnmergedTree = true).performClick()
        rule.onNodeWithText("From selection", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("whole visible view", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("From notes", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("no log lines in notes", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Empty diagram", useUnmergedTree = true).assertIsDisplayed().performClick()

        val session = assertNotNull(
            state.seq3Sessions.activeSession,
            "the blank option remains actionable with no selected, noted, or available log lines",
        )
        assertEquals(0, session.document.lifelines.size)
        assertEquals(0, session.document.messages.size)
        assertEquals(0L, state.seq3Sessions.generateRunCount.get(), "the menu action must not generate from the empty log")
        assertEquals(1, state.seq3Sessions.libraryForTab(tab).size, "the new blank diagram is saved as a library draft")
    }
}
