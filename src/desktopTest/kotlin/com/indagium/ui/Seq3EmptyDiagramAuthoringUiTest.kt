package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Seq3EmptyDiagramAuthoringUiTest {
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
    fun blankWorkspaceCanAuthorLifelinesAndACustomMessageFromItsTiles() {
        root = createTempDirectory("seq3-empty-authoring-ui").toFile()
        val tab = mkTab("empty-authoring", "empty.log", emptyList())
        state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also {
            it.tabs = listOf(tab)
            it.activateTab(tab.id)
        }
        val sessionId = state.seq3Sessions.beginEmpty(tab.id)!!
        assertTrue(state.seq3Sessions.activeSession!!.document.lifelines.isEmpty())
        assertTrue(state.seq3Sessions.activeSession!!.document.messages.isEmpty())

        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(1280.dp, 900.dp)) {
                    Seq3Workspace(state, sessionId)
                }
            }
        }
        rule.waitForIdle()

        addLifeline("Client")
        addLifeline("Server")

        rule.onNodeWithText("+ message", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Custom message…", useUnmergedTree = true).performClick()
        val textInputs = rule.onAllNodes(
            hasSetTextAction() and hasAnyAncestor(isDialog()),
            useUnmergedTree = true,
        )
        textInputs.assertCountEquals(2)
        textInputs[1].performClick().performTextInput("request")
        rule.onNode(
            hasText("Add message") and hasClickAction() and hasAnyAncestor(isDialog()),
        ).performClick()
        rule.waitForIdle()

        val session = assertNotNull(state.seq3Sessions.sessions.singleOrNull { it.id == sessionId })
        assertEquals(listOf("Client", "Server"), session.document.lifelines.map { it.name })
        val message = session.document.messages.single()
        assertEquals("request", message.labelTemplate)
        assertEquals("Client", session.document.lifelines.first { it.id == message.fromLifelineId }.name)
        assertEquals("Server", session.document.lifelines.first { it.id == message.toLifelineId }.name)
        assertTrue(message.occurrences.isEmpty(), "the custom arrow has no fabricated log evidence")
    }

    private fun addLifeline(name: String) {
        rule.onNode(hasText("+ lifeline") or hasContentDescription("Add lifeline"), useUnmergedTree = true).performClick()
        val textInputs = rule.onAllNodes(
            hasSetTextAction() and hasAnyAncestor(isDialog()),
            useUnmergedTree = true,
        )
        textInputs.assertCountEquals(1)
        textInputs[0].performClick().performTextInput(name)
        rule.onNode(
            hasText("Add lifeline") and hasClickAction() and hasAnyAncestor(isDialog()),
        ).performClick()
        rule.waitForIdle()
    }
}
