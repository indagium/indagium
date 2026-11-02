package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertTrue

class Seq3SourceSnippetUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var root: File
    private lateinit var state: AppState

    @After fun tearDown() {
        if (::state.isInitialized) state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun start(): Seq3ViewState {
        root = createTempDirectory("source-snippet-ui").toFile()
        val tab = mkTab("snippet", "snippet.log", emptyList())
        state = AppState(File(root, "state.cache"), notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")))
        state.tabs = listOf(tab)
        state.activateTab(tab.id)
        val id = requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
        rule.setContent { CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
            Box(Modifier.size(1280.dp, 900.dp)) { Seq3Workspace(state, id) }
        } }
        rule.onNodeWithTag("seq3-source-edit").performMouseInput { click() }
        return requireNotNull(state.seq3Sessions.viewState(id))
    }

    @Test fun participantSnippetAcceptsTypingAndTabMovesToAlias() {
        val view = start()
        rule.onNodeWithTag("seq3-source-insert-participant").performMouseInput { click() }
        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextInput("Client app")
        editor.performKeyInput { pressKey(Key.Tab) }
        editor.performTextInput("Client")
        rule.waitForIdle()
        assertTrue(view.sourceDraft.contains("participant \"Client app\" as Client"), view.sourceDraft)
    }

    @Test fun endpointPointerOpensParticipantChoicesAndExactAliasDoesNotShowKeywords() {
        val view = start()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextReplacement("@startuml\nparticipant \"Alpha\" as A\nparticipant \"Beta\" as B\n@enduml")
        rule.onNodeWithTag("seq3-source-message-menu").performMouseInput { click() }
        rule.onNodeWithText("Call", useUnmergedTree = true).performMouseInput { click() }
        editor.assertIsFocused()
        val layouts = mutableListOf<TextLayoutResult>()
        editor.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val box = layouts.single().getBoundingBox(view.sourceDraft.indexOf("From") + 1)
        editor.performMouseInput { click(Offset(200f, box.center.y)) }
        editor.performMouseInput { click(box.center) }
        rule.onNodeWithTag("seq3-source-endpoint-choices", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Alpha", useUnmergedTree = true).performMouseInput { click() }
        rule.waitForIdle()
        assertTrue(view.sourceDraft.contains("A -> To: message label"), view.sourceDraft)
        rule.onNodeWithTag("seq3-source-endpoint-choices", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun messageMenuClosesOnSecondPointerClickAndInsertsEditableSnippet() {
        val view = start()
        val opener = rule.onNodeWithTag("seq3-source-message-menu")
        opener.performMouseInput { click() }
        rule.onNodeWithTag("seq3-source-message-menu-options", useUnmergedTree = true).assertIsDisplayed()
        opener.performMouseInput { click() }
        rule.onNodeWithTag("seq3-source-message-menu-options", useUnmergedTree = true).assertDoesNotExist()
        opener.performMouseInput { click() }
        rule.onNodeWithText("Call", useUnmergedTree = true).performMouseInput { click() }
        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.assertIsFocused()
        editor.performTextInput("Client")
        editor.performKeyInput { pressKey(Key.Tab) }
        editor.performTextInput("Server")
        editor.performKeyInput { pressKey(Key.Tab) }
        editor.performTextInput("Request")
        rule.waitForIdle()
        assertTrue(view.sourceDraft.contains("Client -> Server: Request"), view.sourceDraft)
    }
}
