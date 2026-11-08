package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
private const val LINE_CLICK_X_PX = 600f

/** Every participant-name choice in the source editor is the one list under the caret line. */
class Seq3SourceEndpointListUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var root: File
    private lateinit var state: AppState
    private lateinit var view: Seq3ViewState

    @After fun tearDown() {
        if (::state.isInitialized) state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    private val pumlHead = "@startuml\nparticipant Alias\nparticipant Alias_2\n"
    private val mmdHead = "sequenceDiagram\nparticipant Alias\nparticipant Alias_2\n"

    private fun start(dialect: Seq3Dialect = Seq3Dialect.PLANTUML, autocomplete: Boolean = true): SemanticsNodeInteraction {
        root = createTempDirectory("endpoint-list-ui").toFile()
        val tab = mkTab("endpoint", "endpoint.log", emptyList())
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

    private fun rowShown(alias: String) =
        rule.onAllNodesWithTag("seq3-source-suggestion-row-name-$alias", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Clicks the middle of the character at [offset]; a click elsewhere in the line first, so the click is a caret move. */
    private fun SemanticsNodeInteraction.clickAt(offset: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val box = layouts.single().getBoundingBox(offset)
        performMouseInput { click(Offset(LINE_CLICK_X_PX, box.center.y)) }
        rule.waitForIdle()
        performMouseInput { click(box.center) }
        rule.waitForIdle()
    }

    @Test fun typingInsideAnEndpointOpensTheListUnderTheCaretLineNotInTheCorner() {
        val editor = start()
        // The message is on line 2; the declarations after it still count.
        val text = "@startuml\nAlias -> Ali: hi\nparticipant Alias\nparticipant Alias_2"
        editor.setText(text, "Alias -> Ali")
        assertTrue(listShown())
        assertTrue(rowShown("Alias") && rowShown("Alias_2"))
        rule.onNodeWithTag("seq3-source-endpoint-choices", useUnmergedTree = true).assertDoesNotExist()
        val editorBounds = editor.getBoundsInRoot()
        val list = rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        // Line 2 ends at about 31dp; the corner list of the old design started at 6dp and 8dp from the left.
        assertTrue(list.top.value >= editorBounds.top.value + 18f && list.top.value <= editorBounds.top.value + 40f,
            "right under the caret line: editor=$editorBounds list=$list")
        assertTrue(list.left.value >= editorBounds.left.value + 40f, "at the caret column, not the left edge: editor=$editorBounds list=$list")
        rule.onNodeWithTag("seq3-source-suggestion-glyph-name-Alias", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun downThenEnterPicksTheSecondVariant() {
        val editor = start()
        editor.setText(pumlHead + "participant Alias_3\nAlias -> Ali: hi", "Alias -> Ali")
        assertTrue(rowShown("Alias") && rowShown("Alias_2") && rowShown("Alias_3"))
        editor.performKeyInput { pressKey(Key.DirectionDown) }
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(pumlHead + "participant Alias_3\nAlias -> Alias_2: hi", view.sourceDraft)
        assertFalse(listShown(), "the accepted name stays closed")
    }

    @Test fun enterReplacesTheWholeNameWhenTheCaretIsInsideIt() {
        val editor = start()
        val text = pumlHead + "participant Alias_2xy\nAlias -> Alias_2x: hi"
        editor.setText(text, "Alias -> Alias_")
        assertTrue(rowShown("Alias_2xy"))
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(pumlHead + "participant Alias_2xy\nAlias -> Alias_2xy: hi", view.sourceDraft, "not Alias_2xyx")
    }

    @Test fun upStopsAtTheFirstVariant() {
        val editor = start()
        editor.setText(pumlHead + "Alias -> Ali: hi", "Alias -> Ali")
        editor.performKeyInput { pressKey(Key.DirectionDown) }
        editor.performKeyInput { pressKey(Key.DirectionUp) }
        editor.performKeyInput { pressKey(Key.DirectionUp) }
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(pumlHead + "Alias -> Alias: hi", view.sourceDraft)
    }

    @Test fun mermaidArrowTargetsAndActivationMarksUseTheSameList() {
        val editor = start(Seq3Dialect.MERMAID)
        for (prefix in listOf("Alias->>", "Alias-->>", "Alias-)", "Alias->>+", "Alias-->>-")) {
            editor.setText(mmdHead + "$prefix" + "Ali: hi", "${prefix}Ali")
            assertTrue(listShown(), prefix)
            editor.performKeyInput { pressKey(Key.DirectionDown) }
            editor.performKeyInput { pressKey(Key.Enter) }
            rule.waitForIdle()
            assertEquals(mmdHead + prefix + "Alias_2: hi", view.sourceDraft, prefix)
        }
    }

    @Test fun clickingAnEndpointOpensEveryParticipantWithTheCurrentOneActive() {
        val editor = start()
        val text = pumlHead + "Alias -> Alias_2: hi"
        editor.setText(text)
        editor.clickAt(text.indexOf("Alias_2:") + 6)
        assertTrue(listShown())
        assertTrue(rowShown("Alias") && rowShown("Alias_2"))
        rule.onNodeWithTag("seq3-source-endpoint-choices", useUnmergedTree = true).assertDoesNotExist()
        // The current name is the active row: Enter keeps it.
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(text, view.sourceDraft)
        assertFalse(listShown())
    }

    @Test fun clickingAnEndpointAndChoosingTheOtherNameReplacesTheWholeToken() {
        val editor = start()
        val text = pumlHead + "Alias -> Alias_2: hi"
        editor.setText(text)
        editor.clickAt(text.indexOf("Alias_2:") + 6)
        editor.performKeyInput { pressKey(Key.DirectionUp) }
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(pumlHead + "Alias -> Alias: hi", view.sourceDraft, "the caret was inside the name; all of it is replaced")
        assertFalse(listShown(), "the accepted name stays closed although Alias_2 starts with it")
    }

    @Test fun clickingARowOfTheEndpointListChoosesIt() {
        val editor = start()
        val text = pumlHead + "Alias -> Alias_2: hi"
        editor.setText(text)
        editor.clickAt(text.indexOf("Alias_2:") + 2)
        rule.onNodeWithTag("seq3-source-suggestion-row-name-Alias", useUnmergedTree = true).performMouseInput { click() }
        rule.waitForIdle()
        assertEquals(pumlHead + "Alias -> Alias: hi", view.sourceDraft)
        assertFalse(listShown(), "the click's release does not reopen the list")
    }

    @Test fun mermaidEndpointClickKeepsTheActivationMark() {
        val editor = start(Seq3Dialect.MERMAID)
        val text = mmdHead + "Alias->>+Alias_2: hi"
        editor.setText(text)
        editor.clickAt(text.indexOf("Alias_2:") + 3)
        assertTrue(listShown())
        editor.performKeyInput { pressKey(Key.DirectionUp) }
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(mmdHead + "Alias->>+Alias: hi", view.sourceDraft)
    }

    @Test fun escapeClosesTheListUntilAnotherCharacterIsTyped() {
        val editor = start()
        editor.setText(pumlHead + "Alias -> Ali: hi", "Alias -> Ali")
        assertTrue(listShown())
        editor.performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertFalse(listShown())
        editor.performTextInput("a")
        rule.waitForIdle()
        assertTrue(listShown(), "the next typed character reopens it")
    }

    @Test fun escapeClosesTheClickListToo() {
        val editor = start()
        val text = pumlHead + "Alias -> Alias_2: hi"
        editor.setText(text)
        editor.clickAt(text.indexOf("Alias_2:") + 2)
        assertTrue(listShown())
        editor.performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertFalse(listShown())
        assertEquals(text, view.sourceDraft)
    }

    @Test fun anAcceptedNameDoesNotReopenUntilAnotherCharacterIsTyped() {
        val editor = start()
        editor.setText(pumlHead + "Alias -> Ali: hi", "Alias -> Ali")
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(pumlHead + "Alias -> Alias: hi", view.sourceDraft)
        assertFalse(listShown())
        editor.performKeyInput { pressKey(Key.DirectionLeft) }
        editor.performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertFalse(listShown())
        editor.performTextInput("_")
        rule.waitForIdle()
        assertTrue(listShown() && rowShown("Alias_2"))
    }

    @Test fun autocompleteOffShowsNoListTypingOrClicking() {
        val editor = start(autocomplete = false)
        val text = pumlHead + "Alias -> Ali: hi"
        editor.setText(text, "Alias -> Ali")
        assertFalse(listShown())
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertFalse(view.sourceDraft.contains("-> Alias: hi"), "Enter is a newline, not a completion: ${view.sourceDraft}")
        editor.setText(pumlHead + "Alias -> Alias_2: hi")
        editor.clickAt((pumlHead + "Alias -> Alias_2: hi").indexOf("Alias_2:") + 2)
        assertFalse(listShown(), "no endpoint list on a click either")
        rule.onNodeWithTag("seq3-source-endpoint-choices", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun snippetEndpointFieldsAreCompletedWithTabMovingOn() {
        val editor = start()
        editor.setText("@startuml\nparticipant \"Alpha\" as A\nparticipant \"Beta\" as B\n")
        rule.onNodeWithTag("seq3-source-message-menu").performMouseInput { click() }
        rule.onNodeWithText("Call", useUnmergedTree = true).performMouseInput { click() }
        editor.performTextInput("Al")
        rule.waitForIdle()
        assertTrue(listShown() && rowShown("A"), "typing in the From field lists the match")
        rule.onNodeWithTag("seq3-source-endpoint-choices", useUnmergedTree = true).assertDoesNotExist()
        editor.performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        assertTrue(view.sourceDraft.contains("A -> To: message label"), view.sourceDraft)
        editor.performTextInput("Be")
        rule.waitForIdle()
        assertTrue(listShown() && rowShown("B"))
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue(view.sourceDraft.contains("A -> B: message label"), view.sourceDraft)
        // Tab moves on to the label field, which is typed over.
        editor.performKeyInput { pressKey(Key.Tab) }
        editor.performTextInput("go")
        rule.waitForIdle()
        assertTrue(view.sourceDraft.contains("A -> B: go"), view.sourceDraft)
    }

    @Test fun enteringASnippetEndpointFieldShowsNoListUntilTyping() {
        val editor = start()
        editor.setText("@startuml\nparticipant \"Alpha\" as A\n")
        rule.onNodeWithTag("seq3-source-message-menu").performMouseInput { click() }
        rule.onNodeWithText("Call", useUnmergedTree = true).performMouseInput { click() }
        assertFalse(listShown(), "the selected placeholder is not a typed word")
        editor.performKeyInput { pressKey(Key.Tab) }
        editor.performTextInput("T")
        rule.waitForIdle()
        assertFalse(listShown(), "nothing declared starts with T")
    }
}
