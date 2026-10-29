package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3CustomMessageSpec
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.toPlantUml
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Seq3SourceAndQueueInteractionsUiTest {
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
    fun invalidPlantUmlShowsDiagnosticsAndReloadDiscardsOnlyTheDraft() {
        val sessionId = startEmptyWorkspace("source-invalid")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val original = currentSession(sessionId).document
        showWorkspace(sessionId)

        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        assertTrue(sourceVisible(view))
        assertTrue(view.sourceDraftInitialized)
        assertTrue(view.textFieldFocused, "the source draft should own keyboard focus when it opens")
        rule.onNodeWithTag("seq3-source-editor").performTextReplacement("not PlantUML")
        rule.onNodeWithTag("seq3-source-apply").performClick()
        rule.waitForIdle()

        assertEquals(original, currentSession(sessionId).document, "invalid source must leave the diagram untouched")
        assertTrue(sourceVisible(view), "validation errors stay in the source tile for correction")
        assertTrue(view.textFieldFocused, "diagnostics keep Ctrl/Cmd+Z routed to the source draft")
        val firstLineDiagnostic = requireNotNull(view.sourceDiagnostics.firstOrNull { it.line == 1 }) {
            "the parser should locate the malformed first source line"
        }
        rule.onNodeWithText("Line 1: ${firstLineDiagnostic.message}", useUnmergedTree = true).assertIsDisplayed()

        rule.onNodeWithTag("seq3-source-reload").performClick()
        rule.waitForIdle()
        assertTrue(sourceVisible(view), "resetting a draft keeps the tile in the workspace")
        assertEquals(original.toPlantUml(), view.sourceDraft, "Reload restores canonical source from the unchanged diagram")
        assertFalse(view.sourceDraftDirty)
        assertFalse(view.sourceDraftStale)
        rule.onNodeWithTag("seq3-source-editor").assertIsFocused()
        assertEquals(original, currentSession(sessionId).document, "reloading source does not mutate the diagram")
    }

    @Test
    fun validPlantUmlApplyIsOneDiagramUndoAndDraftUndoStaysInsideTheEditor() {
        val sessionId = startEmptyWorkspace("source-apply")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val originalTitle = currentSession(sessionId).document.title
        showWorkspace(sessionId)

        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        val source = currentSession(sessionId).document.toPlantUml()
        val edited = source.replaceFirst(Regex("(?m)^title .+$"), "title Authored in source")
        editor.performTextReplacement(edited)
        rule.onNodeWithTag("seq3-source-apply").performClick()
        rule.waitForIdle()

        assertEquals("Authored in source", currentSession(sessionId).document.title)
        assertEquals(1, currentSession(sessionId).undoStack.size, "the whole source import creates one diagram undo entry")
        assertTrue(sourceVisible(view), "successful Apply keeps the source tile available")
        assertTrue(view.textFieldFocused, "successful Apply restores focus to the source draft")
        val canonical = currentSession(sessionId).document.toPlantUml()
        assertEquals(canonical, view.sourceDraft, "Apply refreshes the draft from canonical source")
        assertFalse(view.sourceDraftDirty)
        assertFalse(view.sourceDraftStale)
        rule.onNodeWithTag("seq3-source-apply").performClick()
        rule.waitForIdle()
        assertEquals(1, currentSession(sessionId).undoStack.size, "reapplying canonical source is a no-op in diagram history")

        editor.performTextReplacement("$canonical\n' draft-only change")
        editor.performKeyInput {
            withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) }
        }
        rule.waitForIdle()
        assertEquals(canonical, view.sourceDraft, "source Ctrl/Cmd+Z undoes only the text draft")
        assertEquals("Authored in source", currentSession(sessionId).document.title)
        assertEquals(1, currentSession(sessionId).undoStack.size, "text undo does not append or consume diagram undo history")

        assertEquals("Authored in source", currentSession(sessionId).document.title)
        assertTrue(currentSession(sessionId).document.title != originalTitle)

        seedTwoLifelines(sessionId)
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", "Visual follow-up")))
        rule.waitForIdle()
        assertEquals(currentSession(sessionId).document.toPlantUml(), view.sourceDraft)
        assertTrue(view.sourceDraft.contains("Visual follow-up"))
        assertFalse(view.sourceDraftStale, "clean source follows a visual document change")
    }

    @Test
    fun dirtySourceSurvivesQueueEditAndMustReloadBeforeApply() {
        val sessionId = startEmptyWorkspace("source-stale")
        seedTwoLifelines(sessionId)
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", "First")))
        val firstId = currentSession(sessionId).document.messages.single().id
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId)

        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        val sourceBefore = view.sourceDraft
        val titleLine = Regex("(?m)^title .+$")
        val draft = if (titleLine.containsMatchIn(sourceBefore)) {
            sourceBefore.replaceFirst(titleLine, "title Source draft")
        } else {
            sourceBefore.replace("@startuml", "@startuml\ntitle Source draft")
        }
        rule.onNodeWithTag("seq3-source-editor").performTextReplacement(draft)
        rule.waitForIdle()
        assertTrue(view.sourceDraftDirty)

        rule.onNodeWithTag("seq3-queue-row-$firstId", useUnmergedTree = true)
            .performMouseInput { rightClick() }
        rule.onNodeWithTag("seq3-queue-context-add-after", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Custom message…", useUnmergedTree = true).performClick()
        val fields = rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()), useUnmergedTree = true)
        fields[1].performClick().performTextReplacement("Visual insert")
        rule.onNode(hasText("Add message") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()

        assertEquals("Visual insert", currentSession(sessionId).document.messages.last().labelTemplate)
        assertEquals(draft, view.sourceDraft, "a queue edit preserves the uncommitted source text")
        assertTrue(view.sourceDraftStale)
        rule.onNodeWithTag("seq3-source-stale").assertIsDisplayed()
        rule.onNodeWithTag("seq3-source-apply").assertIsNotEnabled()

        rule.onNodeWithTag("seq3-source-reload").performClick()
        rule.waitForIdle()
        assertEquals(currentSession(sessionId).document.toPlantUml(), view.sourceDraft)
        assertFalse(view.sourceDraftStale)
        assertFalse(view.sourceDraftDirty)
        assertTrue(view.sourceDraft.contains("Visual insert"))
    }

    @Test
    fun rightClickQueueActionOpensTheAnchoredAddFlowAndAddsAfterThatParent() {
        val sessionId = startEmptyWorkspace("queue-add-after")
        seedTwoLifelines(sessionId)
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", "First")))
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", "Second")))
        val firstId = currentSession(sessionId).document.messages.first().id
        showWorkspace(sessionId)

        rule.onNodeWithTag("seq3-queue-row-$firstId", useUnmergedTree = true)
            .performMouseInput { rightClick() }
        rule.onNodeWithText("Add message after this", useUnmergedTree = true).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Add message after this row", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Custom message…", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("After clicked row", useUnmergedTree = true).assertIsDisplayed()

        val fields = rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()), useUnmergedTree = true)
        fields[1].performClick().performTextReplacement("Inserted")
        rule.onNode(hasText("Add message") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()

        assertEquals(listOf("First", "Inserted", "Second"), currentSession(sessionId).document.messages.map { it.labelTemplate })
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        assertEquals(null, view.pendingInsertRequest, "the shared queue/canvas insertion request is consumed on completion")
    }

    @Test
    fun queueDragPreviewsUntilReleaseThenCommitsOneParentMove() {
        val sessionId = startEmptyWorkspace("queue-reorder")
        seedTwoLifelines(sessionId)
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", "First")))
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", "Second")))
        val firstId = currentSession(sessionId).document.messages.first().id
        val secondId = currentSession(sessionId).document.messages.last().id
        showWorkspace(sessionId)

        val handle = rule.onNodeWithTag("seq3-queue-drag-$firstId", useUnmergedTree = true)
        handle.performTouchInput {
            down(center)
            moveBy(Offset(0f, 220f * rule.density.density))
        }
        rule.waitForIdle()
        assertEquals(listOf(firstId, secondId), currentSession(sessionId).document.messages.map { it.id }, "drag preview does not mutate the document")
        assertEquals(firstId, requireNotNull(state.seq3Sessions.viewState(sessionId)).queueReorderDraggedId)

        rule.onNodeWithTag("seq3-workspace-root").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf(secondId, firstId), currentSession(sessionId).document.messages.map { it.id })
        assertEquals(5, currentSession(sessionId).undoStack.size, "two lifelines and two inserts precede one reorder undo entry")
        assertEquals(null, requireNotNull(state.seq3Sessions.viewState(sessionId)).queueReorderDraggedId)
    }

    @Test
    fun escapeCancelsAQueueReorderPreviewWithoutChangingUndoHistory() {
        val sessionId = startEmptyWorkspace("queue-reorder-cancel")
        seedTwoLifelines(sessionId)
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", "First")))
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", "Second")))
        val before = currentSession(sessionId)
        val firstId = before.document.messages.first().id
        val beforeIds = before.document.messages.map { it.id }
        val beforeUndoCount = before.undoStack.size
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId)

        rule.onNodeWithTag("seq3-queue-drag-$firstId", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, 220f * rule.density.density))
        }
        rule.waitForIdle()
        assertEquals(firstId, view.queueReorderDraggedId)
        rule.onNodeWithTag("seq3-workspace-root").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertEquals(null, view.queueReorderDraggedId)

        rule.onNodeWithTag("seq3-workspace-root").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(beforeIds, currentSession(sessionId).document.messages.map { it.id })
        assertEquals(beforeUndoCount, currentSession(sessionId).undoStack.size)
    }

    @Test
    fun globalUndoWorksAfterPopupDismissAndBothPlatformChordsRespectInlineFocus() {
        val sessionId = startEmptyWorkspace("undo-routing")
        seedTwoLifelines(sessionId)
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", "First")))
        val messageId = currentSession(sessionId).document.messages.single().id
        val initialTitle = currentSession(sessionId).document.title
        state.seq3Sessions.updateTitle(sessionId, "Keyboard one")
        state.seq3Sessions.updateTitle(sessionId, "Keyboard two")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))

        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(
                    Modifier.size(1280.dp, 900.dp).onPreviewKeyEvent { event ->
                        handleDiagramUndoKey(event, state)
                    },
                ) {
                    Seq3Workspace(state, sessionId)
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("seq3-queue-row-$messageId", useUnmergedTree = true)
            .performMouseInput { rightClick() }
        rule.onNodeWithTag("seq3-queue-context-add-after", useUnmergedTree = true).assertIsDisplayed()
            .performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onNodeWithText("Add message after this", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("seq3-workspace-root").assertIsFocused()

        rule.onNodeWithTag("seq3-workspace-root").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) }
        }
        assertEquals("Keyboard one", currentSession(sessionId).document.title)
        rule.onNodeWithTag("seq3-workspace-root").performKeyInput {
            withKeyDown(Key.MetaLeft) { pressKey(Key.Z) }
        }
        assertEquals(initialTitle, currentSession(sessionId).document.title)

        state.seq3Sessions.updateTitle(sessionId, "Focused title")
        val undoCount = currentSession(sessionId).undoStack.size
        rule.onNodeWithTag("seq3-title-edit").performClick()
        rule.waitForIdle()
        assertTrue(view.textFieldFocused, "the inline title editor should mark text focus for global undo routing")
        val titleEditor = rule.onNodeWithTag("seq3-title-editor")
        titleEditor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        titleEditor.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Z) } }
        rule.waitForIdle()

        assertEquals("Focused title", currentSession(sessionId).document.title)
        assertEquals(undoCount, currentSession(sessionId).undoStack.size, "text focus keeps both platform chords out of diagram history")
    }

    private fun startEmptyWorkspace(tabId: String): String {
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
        return requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
    }

    private fun seedTwoLifelines(sessionId: String) {
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddLifeline(Seq3Lifeline("a", "A", setOf("A"), 0)))
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddLifeline(Seq3Lifeline("b", "B", setOf("B"), 1)))
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

    private fun sourceVisible(view: Seq3ViewState): Boolean =
        DiagramWorkspaceSectionId.SOURCE in diagramWorkspaceVisibleTiles(view)
}
