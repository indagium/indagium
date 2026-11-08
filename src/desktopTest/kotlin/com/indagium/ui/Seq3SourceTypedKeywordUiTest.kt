package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3Dialect
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where a click lands on the line before the click on the character, to its right. */
private const val TYPED_KEYWORD_LINE_CLICK_X_PX = 600f

/** A keyword-template list opens for a keyword the user typed, never for one the caret merely sits in. */
class Seq3SourceTypedKeywordUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var root: File
    private lateinit var state: AppState
    private lateinit var view: Seq3ViewState

    @After fun tearDown() {
        if (::state.isInitialized) state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun start(dialect: Seq3Dialect = Seq3Dialect.PLANTUML, autocomplete: Boolean = true): SemanticsNodeInteraction {
        root = createTempDirectory("typed-keyword-ui").toFile()
        val tab = mkTab("typedkw", "typedkw.log", emptyList())
        state = AppState(File(root, "state.cache"), notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")))
        state.tabs = listOf(tab)
        state.activateTab(tab.id)
        val id = requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
        state.seq3Sessions.setDialect(id, dialect)
        if (!autocomplete) toggleSeq3SourceAutocomplete(state)
        rule.setContent { CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
            Box(Modifier.size(1280.dp, 900.dp)) { Seq3Workspace(state, id) }
        } }
        rule.onNodeWithTag("seq3-source-edit").performClick()
        view = requireNotNull(state.seq3Sessions.viewState(id))
        return rule.onNodeWithTag("seq3-source-editor")
    }

    private fun SemanticsNodeInteraction.setText(text: String, caretAt: String? = null) {
        performTextReplacement(text)
        if (caretAt != null) performTextInputSelection(TextRange(text.indexOf(caretAt) + caretAt.length))
        rule.waitForIdle()
    }

    private fun listShown() = rule.onAllNodesWithTag("seq3-source-suggestions", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun rowShown(tag: String) =
        rule.onAllNodesWithTag("seq3-source-suggestion-row-$tag", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Clicks the middle of the character at [offset]; a click elsewhere in the line first, so the click is a caret move. */
    private fun SemanticsNodeInteraction.clickAt(offset: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val box = layouts.single().getBoundingBox(offset)
        performMouseInput { click(Offset(TYPED_KEYWORD_LINE_CLICK_X_PX, box.center.y)) }
        rule.waitForIdle()
        performMouseInput { click(box.center) }
        rule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.press(key: Key, times: Int = 1) {
        repeat(times) { performKeyInput { pressKey(key) } }
        rule.waitForIdle()
    }

    @Test fun clickingIntoAnExistingActorLineOpensNoList() {
        val editor = start()
        val text = "@startuml\nactor Alias as Name\nparticipant Other"
        editor.setText(text)
        editor.clickAt(text.indexOf("actor") + 2)
        assertFalse(listShown(), "the caret sits in a keyword that is already written")
        editor.performTextInputSelection(TextRange(text.indexOf("actor") + 5))
        rule.waitForIdle()
        assertFalse(listShown())
    }

    @Test fun clickingIntoAnExistingActorLineOpensNoListInMermaid() {
        val editor = start(Seq3Dialect.MERMAID)
        val text = "sequenceDiagram\nactor Alias as Name\nparticipant Other"
        editor.setText(text)
        editor.clickAt(text.indexOf("actor") + 2)
        assertFalse(listShown())
    }

    @Test fun arrowKeysIntoAnExistingBlockKeywordOpenNoList() {
        val editor = start()
        val text = "@startuml\nopt condition\n    From -> To: hi\nend"
        editor.setText(text)
        // From the end of `end` (column 3) up two lines: right after `opt`.
        editor.press(Key.DirectionUp, 2)
        assertFalse(listShown(), "the caret was moved into `opt`, not typed")
        editor.press(Key.DirectionLeft)
        assertFalse(listShown())
    }

    @Test fun arrowKeysIntoAnExistingBlockKeywordOpenNoListInMermaid() {
        val editor = start(Seq3Dialect.MERMAID)
        val text = "sequenceDiagram\nopt condition\n    Alice->>Bob: hi\nend"
        editor.setText(text)
        editor.press(Key.DirectionUp, 2)
        assertFalse(listShown())
    }

    @Test fun typingAKeywordOnANewEmptyLineOpensTheListAndEnterInsertsTheBlock() {
        val editor = start()
        editor.setText("@startuml\n")
        editor.performTextInput("op")
        rule.waitForIdle()
        assertTrue(listShown())
        assertTrue(rowShown("opt"))
        editor.press(Key.Enter)
        assertEquals("@startuml\nopt condition\n    From -> To: message label\nend", view.sourceDraft)
    }

    @Test fun typingAKeywordOnANewEmptyLineOpensTheListInMermaid() {
        val editor = start(Seq3Dialect.MERMAID)
        editor.setText("sequenceDiagram\n    ")
        editor.performTextInput("op")
        rule.waitForIdle()
        assertTrue(listShown())
        assertTrue(rowShown("opt"))
    }

    @Test fun typingAKeywordInFrontOfExistingTextOpensNoKeywordList() {
        val editor = start()
        val text = "@startuml\n condition"
        editor.setText(text)
        editor.performTextInputSelection(TextRange("@startuml\n".length))
        rule.waitForIdle()
        editor.performTextInput("opt")
        rule.waitForIdle()
        assertEquals("@startuml\nopt condition", view.sourceDraft)
        assertFalse(listShown())
    }

    @Test fun typingAKeywordAfterOtherTextOnTheLineOpensNoKeywordList() {
        val editor = start()
        editor.setText("@startuml\nfoo ")
        editor.performTextInput("opt")
        rule.waitForIdle()
        assertFalse(listShown())
    }

    @Test fun typingASlashWordOnAnEmptyLineOpensTheList() {
        val editor = start()
        editor.setText("@startuml\n")
        editor.performTextInput("/par")
        rule.waitForIdle()
        assertTrue(listShown())
        assertTrue(rowShown("par"))
    }

    @Test fun typingASlashWordOnAnEmptyLineOpensTheListInMermaid() {
        val editor = start(Seq3Dialect.MERMAID)
        editor.setText("sequenceDiagram\n")
        editor.performTextInput("/par")
        rule.waitForIdle()
        assertTrue(listShown())
    }

    @Test fun backspaceInsideATypedKeywordKeepsTheList() {
        val editor = start()
        editor.setText("@startuml\n")
        editor.performTextInput("opt")
        rule.waitForIdle()
        editor.press(Key.Backspace)
        assertEquals("@startuml\nop", view.sourceDraft)
        assertTrue(listShown(), "deleting inside the typed word keeps the list")
    }

    @Test fun backspaceInsideAnExistingKeywordOpensNoList() {
        val editor = start()
        val text = "@startuml\nopt condition\nend"
        editor.setText(text, "opt")
        editor.press(Key.Backspace)
        assertEquals("@startuml\nop condition\nend", view.sourceDraft)
        assertFalse(listShown())
    }

    @Test fun movingTheCaretAwayClosesTheTypedKeywordList() {
        val editor = start()
        editor.setText("@startuml\nnote over A: hi\n")
        editor.performTextInput("op")
        rule.waitForIdle()
        assertTrue(listShown())
        editor.performTextInputSelection(TextRange(3))
        rule.waitForIdle()
        assertFalse(listShown())
        editor.performTextInputSelection(TextRange(view.sourceDraft.length))
        rule.waitForIdle()
        assertFalse(listShown(), "coming back to the word does not reopen it")
    }

    @Test fun typingInAnEndpointStillOpensTheNameList() {
        val editor = start()
        val text = "@startuml\nparticipant Alias\nparticipant Alias_2\nAlias -> Ali: hi"
        editor.setText(text, "Alias -> Ali")
        assertTrue(listShown())
        assertTrue(rowShown("name-Alias") && rowShown("name-Alias_2"))
    }

    @Test fun clickingAnEndpointStillOpensEveryParticipant() {
        val editor = start()
        val text = "@startuml\nparticipant Alias\nparticipant Alias_2\nAlias -> Alias_2: hi"
        editor.setText(text)
        editor.clickAt(text.indexOf("Alias_2: hi") + 2)
        assertTrue(listShown())
        assertTrue(rowShown("name-Alias") && rowShown("name-Alias_2"))
    }

    @Test fun autocompleteOffShowsNoListWhateverIsTyped() {
        val editor = start(autocomplete = false)
        editor.setText("@startuml\n")
        editor.performTextInput("op")
        rule.waitForIdle()
        assertFalse(listShown())
        editor.performTextInput("/par")
        rule.waitForIdle()
        assertFalse(listShown())
    }
}
