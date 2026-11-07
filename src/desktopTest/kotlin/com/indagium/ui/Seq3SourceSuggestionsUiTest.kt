package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
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

class Seq3SourceSuggestionsUiTest {
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
    fun slashSuggestionsFilterNavigateInsertAsOneUndoableDraftEdit() {
        val sessionId = startWorkspace("source-suggestions")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()

        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextReplacement("@startuml\n/")
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed()
        editor.performTextInput("pa")
        rule.waitForIdle()

        rule.onNodeWithText("/participant", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("/actor", useUnmergedTree = true).assertDoesNotExist()
        editor.performKeyInput { pressKey(Key.DirectionDown) }
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        assertEquals("@startuml\npar condition\n    From -> To: message label\nend", view.sourceDraft)
        assertTrue(view.sourceDraftDirty)
        assertEquals(Seq3Dialect.PLANTUML, view.sourceEditorDialect)
        assertTrue(view.textFieldFocused)
        editor.assertIsFocused()

        editor.performKeyInput { withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) } }
        rule.waitForIdle()
        assertEquals("@startuml\n/pa", view.sourceDraft, "one draft undo removes the whole suggestion insertion")
        assertTrue(view.sourceDraftDirty)
        assertFalse(view.sourceDraftStale)
        assertEquals(0, currentSession(sessionId).undoStack.size, "draft text undo does not change diagram history")
    }

    @Test
    fun escapeDismissesSuggestionsWithoutChangingDraftOrMovingFocus() {
        val sessionId = startWorkspace("source-suggestions-escape")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextReplacement("@startuml\n/par")
        rule.waitForIdle()
        val before = view.sourceDraft
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed()
        editor.assertIsFocused()
        assertTrue(view.textFieldFocused, "the editor should retain focus while the suggestion list is open")

        editor.performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()

        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(before, view.sourceDraft)
        editor.assertIsFocused()
        assertTrue(view.textFieldFocused, "Escape dismisses suggestions without giving up editor focus")
    }

    @Test
    fun slashSuggestionListIsCompactScrollableAndAnchoredToTheCaretLine() {
        val sessionId = startWorkspace("source-suggestions-scroll")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextReplacement("@startuml\n/")
        rule.waitForIdle()

        val editorBounds = editor.getBoundsInRoot()
        val suggestions = rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed()
        val suggestionsBounds = suggestions.getBoundsInRoot()
        assertTrue(suggestionsBounds.right.value - suggestionsBounds.left.value <= 330f, "suggestions should have a compact width: $suggestionsBounds")
        assertTrue(suggestionsBounds.bottom.value - suggestionsBounds.top.value <= 215f, "suggestions should show a short scrollable list: $suggestionsBounds")
        assertTrue(suggestionsBounds.left.value >= editorBounds.left.value &&
            suggestionsBounds.left.value - editorBounds.left.value <= 20f,
            "suggestions should line up with the typed slash: editor=$editorBounds suggestions=$suggestionsBounds")
        // The slash is on line 2: the list starts right under that line (the layout trims the first
        // line's leading, so it ends at 31dp, not 36dp), never at the editor's top.
        val belowFirstLine = editorBounds.top.value + 18f
        assertTrue(suggestionsBounds.top.value >= belowFirstLine && suggestionsBounds.top.value <= editorBounds.top.value + 40f,
            "suggestions should open just below the caret line: editor=$editorBounds suggestions=$suggestionsBounds")
        rule.onNodeWithText("/group", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun suggestionListOpensAboveTheCaretLineWhenTheViewportEndsBelowIt() {
        val sessionId = startWorkspace("source-suggestions-above")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        val lines = (1..80).joinToString("\n") { "participant P$it" }
        editor.performTextReplacement("@startuml\n$lines\n/")
        rule.waitForIdle()

        val editorBounds = editor.getBoundsInRoot()
        val suggestionsBounds = rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        // The caret is on the last line, scrolled to the bottom of the viewport: the list ends above that line.
        assertTrue(suggestionsBounds.top.value >= editorBounds.top.value - 0.5f && suggestionsBounds.bottom.value <= editorBounds.bottom.value - 18f + 1f,
            "suggestions should open above the caret line near the bottom: editor=$editorBounds suggestions=$suggestionsBounds")
    }

    @Test
    fun enterAfterAKeywordTypedInFullWithNothingToExpandIsANewline() {
        val sessionId = startWorkspace("source-suggestions-end-enter")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextReplacement("@startuml\nalt ok\n    A -> B: x\nend")
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertDoesNotExist()

        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        assertEquals("@startuml\nalt ok\n    A -> B: x\nend\n", view.sourceDraft)
    }

    @Test
    fun enterAfterASingleLetterIsANewline() {
        val sessionId = startWorkspace("source-suggestions-a-enter")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextReplacement("@startuml\na")
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertDoesNotExist()

        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        assertEquals("@startuml\na\n", view.sourceDraft)
    }

    @Test
    fun dividerIsSuggestedOnlyInsideTheBlockThatOwnsIt() {
        val sessionId = startWorkspace("source-suggestions-divider")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")

        editor.performTextReplacement("@startuml\nalt ok\n    /el")
        rule.waitForIdle()
        rule.onNodeWithText("/else", useUnmergedTree = true).assertIsDisplayed()

        editor.performTextReplacement("@startuml\nloop x\n    /el")
        rule.waitForIdle()
        rule.onNodeWithText("/else", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun everyRowHasAPictogram() {
        val sessionId = startWorkspace("source-suggestions-glyphs")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextReplacement("@startuml\n/")
        rule.waitForIdle()

        listOf("participant", "actor", "title", "note", "activate", "call", "return", "async", "loop").forEach { keyword ->
            rule.onNodeWithTag("seq3-source-suggestion-glyph-$keyword", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun livePreviewKeepsFollowingTheDraftWhileTheSuggestionListIsOpen() {
        val sessionId = startWorkspace("source-suggestions-live")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")

        // The slash word is typed above `@enduml`, so the draft is valid apart from that word.
        val first = "@startuml\nparticipant \"Alpha\" as A\n/pa"
        editor.performTextReplacement("$first\n@enduml")
        editor.performTextInputSelection(TextRange(first.length))
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed()
        rule.waitUntil(5_000) { currentSession(sessionId).document.lifelines.any { it.name == "Alpha" } }
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(view.sourceDiagnostics.isEmpty(), "the half-typed word is not part of the preview: ${view.sourceDiagnostics}")

        val second = "@startuml\nparticipant \"Alpha\" as A\nparticipant \"Beta\" as B\n/pa"
        editor.performTextReplacement("$second\n@enduml")
        editor.performTextInputSelection(TextRange(second.length))
        rule.waitUntil(5_000) { currentSession(sessionId).document.lifelines.any { it.name == "Beta" } }
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(view.sourceDiagnostics.isEmpty(), "${view.sourceDiagnostics}")
    }

    @Test
    fun editorBoundsStayFixedAcrossSuggestionAndDiagnosticTransitions() {
        val sessionId = startWorkspace("source-status-stability")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        val initialBounds = editor.getBoundsInRoot()

        editor.performTextReplacement("@startuml\n/")
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed()
        rule.waitForIdle()
        assertEquals(initialBounds, editor.getBoundsInRoot(), "opening slash suggestions must not move or resize the editor")
        Thread.sleep(500)
        rule.waitForIdle()
        assertTrue(view.sourceDiagnostics.isEmpty(), "an active slash keyword suggestion is not a syntax error")

        val lastValidDocument = currentSession(sessionId).document
        editor.performTextReplacement("@startuml\nthis is not a source statement\n@enduml")
        rule.waitUntil(5_000) { view.sourceDiagnostics.isNotEmpty() }
        assertEquals(lastValidDocument, currentSession(sessionId).document, "invalid source keeps the last valid preview")
        assertEquals(initialBounds, editor.getBoundsInRoot(), "diagnostics must not move or resize the editor")

        editor.performTextReplacement("@startuml\nparticipant \"Stable\" as S\n@enduml")
        rule.waitUntil(5_000) { currentSession(sessionId).document.lifelines.any { it.name == "Stable" } }
        assertTrue(view.sourceDiagnostics.isEmpty())
        assertEquals(initialBounds, editor.getBoundsInRoot(), "valid source must keep the same editor bounds")
    }

    private fun startWorkspace(tabId: String): String {
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
        return requireNotNull(state.seq3Sessions.beginEmpty(tab.id)).also { state.seq3Sessions.setDialect(it, Seq3Dialect.PLANTUML) }
    }

    private fun currentSession(sessionId: String) =
        requireNotNull(state.seq3Sessions.sessions.singleOrNull { it.id == sessionId })

    private fun showWorkspace(sessionId: String) {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(1280.dp, 900.dp)) {
                    Seq3Workspace(state, sessionId)
                }
            }
        }
        rule.waitForIdle()
    }
}
