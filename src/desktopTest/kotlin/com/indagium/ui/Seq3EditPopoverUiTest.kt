package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.undoSeq3Command
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3EditPopoverUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var tempRoot: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) {
            state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
            state.close()
        }
        if (::tempRoot.isInitialized) tempRoot.deleteRecursively()
    }

    @Test
    fun popoverHasTheSameSizeAtHalfAndOneAndAHalfZoom() {
        val (sessionId, view) = open(EDIT_TARGET_LIFELINE)
        view.zoomMode = Seq3ZoomMode.MANUAL
        val sizes = listOf(0.5f, 1.5f).map { zoom ->
            view.zoom = zoom
            rule.waitForIdle()
            val popover = bounds("seq3-edit-popover")
            val field = bounds("seq3-edit-popover-field")
            Triple(popover.width, popover.height, field.height)
        }
        assertEquals(sizes[0].first, sizes[1].first, 1f, "popover width does not follow the zoom")
        assertEquals(sizes[0].second, sizes[1].second, 1f, "popover height does not follow the zoom")
        assertEquals(sizes[0].third, sizes[1].third, 1f, "the field is not scaled with the zoom")
        val minWidthPx = minOf(
            SEQ3_EDIT_POPOVER_MIN_WIDTH.value * rule.density.density,
            bounds("seq3-canvas-viewport").width - 12f * rule.density.density,
        )
        assertTrue(sizes[0].first >= minWidthPx - 1f, "at least 280dp (or the viewport) wide: ${sizes[0].first}px")
        assertEquals("Client", fieldText(), "the lifeline editor is seeded with the lifeline name")
        assertTrue(currentSession(sessionId).document.lifelines.isNotEmpty())
    }

    @Test
    fun popoverFollowsTheElementAcrossZoomButKeepsItsFieldSize() {
        val (_, view) = open(EDIT_TARGET_OTHER)
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 0.5f
        rule.waitForIdle()
        val atHalf = bounds("seq3-edit-popover")
        view.zoom = 1.5f
        rule.waitForIdle()
        val atOneAndAHalf = bounds("seq3-edit-popover")
        assertEquals(atHalf.width, atOneAndAHalf.width, 1f)
        assertTrue(atHalf.top != atOneAndAHalf.top, "the anchor moved with the zoom, the popover followed it")
    }

    @Test
    fun enterSavesTheLabelAsOneUndoStepAndEscapeCancelsWithoutChange() {
        val (sessionId, view) = open(EDIT_TARGET_OTHER)
        val undoBefore = currentSession(sessionId).undoStack.size
        field().performTextReplacement("discarded")
        field().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("seq3-edit-popover"), useUnmergedTree = true).assertCountEquals(0)
        assertEquals("reply()", currentSession(sessionId).document.messages.single { it.id == "other" }.labelTemplate)
        assertEquals(undoBefore, currentSession(sessionId).undoStack.size)
        assertFalse(view.textFieldFocused, "cancelling returns keyboard focus to the workspace")

        seq3ActivateCanvasEditor(view, EDIT_TARGET_OTHER)
        rule.waitForIdle()
        assertEquals("reply()", fieldText(), "a cancelled draft is not kept")
        field().performTextReplacement("answer()")
        field().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("answer()", currentSession(sessionId).document.messages.single { it.id == "other" }.labelTemplate)
        assertEquals(undoBefore + 1, currentSession(sessionId).undoStack.size)
        rule.onAllNodes(hasTestTag("seq3-edit-popover"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun checkAndCrossButtonsSaveAndCancel() {
        val (sessionId, view) = open(EDIT_TARGET_OTHER)
        field().performTextReplacement("via check")
        rule.onNodeWithTag("seq3-edit-popover-commit", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("via check", currentSession(sessionId).document.messages.single { it.id == "other" }.labelTemplate)

        seq3ActivateCanvasEditor(view, EDIT_TARGET_OTHER)
        rule.waitForIdle()
        field().performTextReplacement("never saved")
        rule.onNodeWithTag("seq3-edit-popover-cancel", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("via check", currentSession(sessionId).document.messages.single { it.id == "other" }.labelTemplate)
    }

    @Test
    fun aSharedMessageLabelNamesHowManyArrowsItRenamesAndAnUnsharedOneDoesNot() {
        val (_, view) = open(EDIT_TARGET_REPEAT_FIRST)
        assertEquals("request()", fieldText(), "seeded from the template shared by every arrow")
        rule.onNodeWithTag("seq3-edit-popover-note", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(
            "Renames all 2 arrows of this message",
            rule.onNodeWithTag("seq3-edit-popover-note", useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsProperties.Text].single().text,
        )
        rule.onNodeWithTag("seq3-edit-popover-rename-only", useUnmergedTree = true).assertIsDisplayed()

        seq3ActivateCanvasEditor(view, EDIT_TARGET_OTHER)
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("seq3-edit-popover-note"), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(hasTestTag("seq3-edit-popover-rename-only"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun renameOnlyThisArrowSplitsItOutAndLeavesItsSiblingsAsOneUndoStep() {
        val (sessionId, _) = open(EDIT_TARGET_REPEAT_SECOND)
        val before = currentSession(sessionId)
        field().performTextReplacement("retry()")
        rule.onNodeWithTag("seq3-edit-popover-rename-only", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        val after = currentSession(sessionId)
        assertEquals(before.undoStack.size + 1, after.undoStack.size, "the split and the rename are one undo step")
        val group = after.document.messages.single { it.id == "repeat" }
        assertEquals("request()", group.labelTemplate, "the sibling arrows keep the shared label")
        assertEquals(listOf(FIRST_ENTRY), group.occurrences.map { it.entryId })
        val standalone = after.document.messages.single { it.movedOutFromMessageId == "repeat" }
        assertEquals("retry()", standalone.labelTemplate)
        assertEquals(listOf(SECOND_ENTRY), standalone.occurrences.map { it.entryId })
        rule.onAllNodes(hasTestTag("seq3-edit-popover"), useUnmergedTree = true).assertCountEquals(0)

        assertTrue(state.seq3Sessions.undo(sessionId))
        assertEquals(before.document, currentSession(sessionId).document, "one undo restores the diagram before the split")
        assertNull(currentSession(sessionId).document.messages.firstOrNull { it.movedOutFromMessageId != null })
    }

    @Test
    fun theUndoEntryOfRenameOnlyThisArrowIsTheWholeDocumentBeforeTheSplit() {
        val document = document()
        val applied = com.indagium.diagram3.applySeq3Command(
            document,
            Seq3Command.MoveOccurrenceOutAndRename("repeat", SECOND_ENTRY, "retry()"),
        )
        assertTrue(applied.applied)
        assertEquals(document, undoSeq3Command(requireNotNull(applied.undo)))
    }

    private fun open(target: Seq3CanvasEditorTarget): Pair<String, Seq3ViewState> {
        tempRoot = createTempDirectory("seq3-edit-popover-ui").toFile()
        val tab = mkTab("edit-popover", "edit-popover.log", emptyList())
        state = AppState(
            File(tempRoot, "state.cache"),
            notesDir = File(tempRoot, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(tempRoot, "library.cache")),
        ).also {
            it.tabs = listOf(tab)
            it.activateTab(tab.id)
        }
        val sessionId = requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
        assertTrue(state.seq3Sessions.applyCommand(sessionId, Seq3Command.ReplaceDocument(document())))
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(1280.dp, 900.dp)) { Seq3Workspace(state, sessionId) }
            }
        }
        rule.waitForIdle()
        seq3ActivateCanvasEditor(view, target)
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-edit-popover", useUnmergedTree = true).assertIsDisplayed()
        return sessionId to view
    }

    private fun field() = rule.onNodeWithTag("seq3-edit-popover-field", useUnmergedTree = true)

    private fun fieldText(): String = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun currentSession(sessionId: String) =
        requireNotNull(state.seq3Sessions.sessions.singleOrNull { it.id == sessionId })

    private fun occurrence(entryId: Int, timestamp: Long) = Seq3Occurrence(
        entryId = entryId,
        timestampMillis = timestamp,
        rawTimestamp = "00:00:00.${timestamp.toString().padStart(3, '0')}",
        pid = 1,
        tid = 1,
        level = 'I',
        text = "message $entryId",
    )

    private fun document() = Seq3Document(
        lifelines = listOf(
            Seq3Lifeline("a", "Client", setOf("Client"), 0, headerWidthDp = 140.0),
            Seq3Lifeline("b", "Server", setOf("Server"), 1, headerWidthDp = 140.0),
        ),
        messages = listOf(
            Seq3Message(
                id = "repeat",
                match = Seq3Match("Client", "request()"),
                fromLifelineId = "a",
                toLifelineId = "b",
                labelTemplate = "request()",
                repeat = Seq3Repeat.EVERY,
                occurrences = listOf(occurrence(FIRST_ENTRY, FIRST_TS), occurrence(SECOND_ENTRY, SECOND_TS)),
            ),
            Seq3Message(
                id = "other",
                match = Seq3Match("Server", "reply()"),
                fromLifelineId = "b",
                toLifelineId = "a",
                labelTemplate = "reply()",
                occurrences = listOf(occurrence(OTHER_ENTRY, OTHER_TS)),
            ),
        ),
    )

    private companion object {
        const val FIRST_ENTRY = 101
        const val SECOND_ENTRY = 102
        const val OTHER_ENTRY = 103
        const val FIRST_TS = 1_000L
        const val SECOND_TS = 3_000L
        const val OTHER_TS = 2_000L
        val EDIT_TARGET_LIFELINE = Seq3CanvasEditorTarget(Seq3CanvasEditorKind.LIFELINE_NAME, "a")
        val EDIT_TARGET_OTHER = Seq3CanvasEditorTarget(Seq3CanvasEditorKind.MESSAGE_LABEL, "other", OTHER_ENTRY)
        val EDIT_TARGET_REPEAT_FIRST = Seq3CanvasEditorTarget(Seq3CanvasEditorKind.MESSAGE_LABEL, "repeat", FIRST_ENTRY)
        val EDIT_TARGET_REPEAT_SECOND = Seq3CanvasEditorTarget(Seq3CanvasEditorKind.MESSAGE_LABEL, "repeat", SECOND_ENTRY)
    }
}
