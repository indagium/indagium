package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Lifeline
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A Diagram tab with no log renders its canvas, source editor, Messages and Lifelines, and offers none
 *  of the controls that need a log row to act on. */
class LogFreeDiagramWorkspaceUiTest {
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
    fun logFreeWorkspaceRendersWithoutRegenerateAndWithTheLogAttachDisabled() {
        root = createTempDirectory("log-free-workspace-ui").toFile()
        state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        )
        val id = state.seq3Sessions.beginStandalone()!!
        state.seq3Sessions.applyCommand(id, Seq3Command.AddLifeline(Seq3Lifeline("a", "Client", emptySet(), 0)))
        assertEquals(0, state.tabs.size, "no log tab exists at all")

        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(1280.dp, 900.dp)) { Seq3Workspace(state, id) }
            }
        }
        rule.waitForIdle()

        // It renders: the title, the manual subtitle, the Messages and Lifelines tiles.
        rule.onNodeWithText("Untitled diagram", useUnmergedTree = true)
        rule.onNodeWithText("Manual diagram", useUnmergedTree = true)
        rule.onNode(hasText("+ message") or hasContentDescription("Add message"), useUnmergedTree = true)
        rule.onNode(hasText("+ lifeline") or hasContentDescription("Add lifeline"), useUnmergedTree = true)
        // Nothing offers to rebuild from a log.
        rule.onAllNodesWithText("Regenerate…", useUnmergedTree = true).assertCountIsZero()
        // Attaching to notes needs the log, so it is disabled and says why.
        rule.onNode(hasText("Attach snapshot")).assertExists()
        rule.onNode(hasText("Attach snapshot") and hasClickAction()).assertDoesNotExist()
        // The "+ message" menu offers only the custom message.
        rule.onNode(hasText("+ message") or hasContentDescription("Add message"), useUnmergedTree = true).performClick()
        rule.onNodeWithText("Custom message…", useUnmergedTree = true)
        rule.onAllNodesWithText("Enter row numbers…", useUnmergedTree = true).assertCountIsZero()
        rule.onAllNodesWithText("Open log view to select rows", useUnmergedTree = true).assertCountIsZero()
        assertTrue(state.seq3Sessions.sessions.single().logFree)
    }

    @Test
    fun aLogBackedDiagramStillOffersRegenerateAndAttach() {
        root = createTempDirectory("log-backed-workspace-ui").toFile()
        val tab = mkTab("log", "app.log", emptyList())
        state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also {
            it.tabs = listOf(tab)
            it.activateTab(tab.id)
        }
        val id = state.seq3Sessions.beginEmpty(tab.id)!!
        state.seq3Sessions.applyCommand(id, Seq3Command.AddLifeline(Seq3Lifeline("a", "Client", emptySet(), 0)))

        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(1280.dp, 900.dp)) { Seq3Workspace(state, id) }
            }
        }
        rule.waitForIdle()

        rule.onNode(hasText("Regenerate…")).assertExists()
        rule.onNode(hasText("Attach snapshot") and hasClickAction()).assertExists()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountIsZero() =
        assertCountEquals(0)
}
