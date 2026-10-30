package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3RowRef
import com.indagium.diagram3.SEQ3_DELAY_EDITOR_FIELD_WIDTH_DP
import com.indagium.diagram3.seq3DrawnRowRefs
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3CanvasInteractionsUiTest {
    private companion object {
        const val FIRST_REPEAT_ENTRY_ID = 101
        const val FIRST_REPEAT_TIMESTAMP_MS = 1_000L
        const val SECOND_REPEAT_ENTRY_ID = 102
        const val SECOND_REPEAT_TIMESTAMP_MS = 3_000L
        const val OTHER_ENTRY_ID = 103
        const val OTHER_TIMESTAMP_MS = 2_000L
    }

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
    fun headerDragUsesPhysicalDeltaAtHalfAndDoubleZoomAndAddsOneUndoEntryPerRelease() {
        val sessionId = startWorkspace("header-zoom")
        val baseWidth = 140.0
        replaceDocument(sessionId, headerDocument(baseWidth))
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId)

        listOf(0.5f, 2.0f).forEach { zoom ->
            view.zoomMode = Seq3ZoomMode.MANUAL
            view.zoom = zoom
            rule.waitForIdle()

            val handleTag = "seq3-header-width-handle-a"
            rule.onNodeWithTag(handleTag, useUnmergedTree = true).assertIsDisplayed()
            val handle = bounds(handleTag)
            val start = rootPoint(centerX(handle), centerY(handle))
            val dragPixels = 48f
            val undoBefore = currentSession(sessionId).undoStack.size
            val widthBefore = currentSession(sessionId).document.lifelines.single { it.id == "a" }.headerWidthDp

            rule.onNodeWithTag("seq3-workspace-root").performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(dragPixels, 0f))
                release()
            }
            rule.waitForIdle()

            val widthAfter = currentSession(sessionId).document.lifelines.single { it.id == "a" }.headerWidthDp
            val expectedDelta = dragPixels.toDouble() / (rule.density.density * zoom).toDouble()
            assertEquals(expectedDelta, requireNotNull(widthAfter) - requireNotNull(widthBefore), 1.0)
            assertEquals(undoBefore + 1, currentSession(sessionId).undoStack.size, "one release is one undo step")
        }
    }

    @Test
    fun escapeCancelsHeaderResizePreviewBeforeTheMouseIsReleased() {
        val sessionId = startWorkspace("header-escape")
        replaceDocument(sessionId, headerDocument(140.0))
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        showWorkspace(sessionId)

        val originalWidth = currentSession(sessionId).document.lifelines.single { it.id == "a" }.headerWidthDp
        val undoBefore = currentSession(sessionId).undoStack.size
        val handle = bounds("seq3-header-width-handle-a")
        val start = rootPoint(centerX(handle), centerY(handle))
        val workspace = rule.onNodeWithTag("seq3-workspace-root")
        workspace.performMouseInput {
            moveTo(start)
            press()
            moveTo(start + Offset(42f, 0f))
        }
        rule.waitForIdle()

        assertNotNull(view.headerResizePreview, "dragging shows an uncommitted header-width preview")
        workspace.performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertNull(view.headerResizePreview, "Escape removes the preview while the pointer is still down")
        workspace.performMouseInput { release() }
        rule.waitForIdle()

        assertEquals(originalWidth, currentSession(sessionId).document.lifelines.single { it.id == "a" }.headerWidthDp)
        assertEquals(undoBefore, currentSession(sessionId).undoStack.size)
    }

    @Test
    fun headerResizeEdgeHasHoverOnlyCueAndDoubleClickRestoresAutomaticWidth() {
        val sessionId = startWorkspace("header-reset")
        replaceDocument(sessionId, headerDocument(140.0))
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        showWorkspace(sessionId)

        val canvas = rule.onNodeWithTag("seq3-canvas-body")
        val markerTag = "seq3-header-width-hover-line-a"
        val workspace = rule.onNodeWithTag("seq3-workspace-root")
        workspace.performMouseInput { moveTo(Offset(4f, 4f)) }
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag(markerTag), useUnmergedTree = true).assertCountEquals(0)
        saveHeaderEvidence("header-width-normal.png", canvas.captureToImage())

        val handle = bounds("seq3-header-width-handle-a")
        val start = rootPoint(centerX(handle), centerY(handle))
        workspace.performMouseInput { moveTo(start) }
        rule.waitForIdle()
        rule.onNodeWithTag(markerTag, useUnmergedTree = true).assertIsDisplayed()
        saveHeaderEvidence("header-width-hover.png", canvas.captureToImage())

        val undoBefore = currentSession(sessionId).undoStack.size
        workspace.performMouseInput {
            doubleClick(start)
        }
        rule.waitForIdle()

        assertNull(
            currentSession(sessionId).document.lifelines.single { it.id == "a" }.headerWidthDp,
            "double-clicking a manually sized edge restores automatic width",
        )
        assertEquals(undoBefore + 1, currentSession(sessionId).undoStack.size)
    }

    @Test
    fun draggingOneRepeatedArrowMovesOnlyItsExactOccurrenceInOneUndoStep() {
        val sessionId = startWorkspace("canvas-row-drag")
        val document = repeatedRowsDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        showWorkspace(sessionId)

        val layout = Seq3RenderCache.layout(document)
        val rows = layout.rows.filterIsInstance<Seq3ArrowRow>()
        val source = rows.single { it.messageId == "repeat" && it.occurrenceEntryId == 101 }
        val originalOrder = rows.map { Seq3RowRef(it.messageId, it.occurrenceEntryId) }
        assertEquals(
            listOf(Seq3RowRef("repeat", 101), Seq3RowRef("other", 103), Seq3RowRef("repeat", 102)),
            originalOrder,
            "the fixture places two exact occurrences around another message",
        )
        val body = bounds("seq3-canvas-body")
        val pixelScale = rule.density.density * view.zoom
        val rowCenterX = ((source.fromX + source.toX) / 2.0 * pixelScale.toDouble()).toFloat()
        val start = rootPoint(body.left + rowCenterX, body.top + source.y.toFloat() * pixelScale)
        val lastRowY = rows.last().y.toFloat()
        val destination = rootPoint(
            body.left + rowCenterX,
            body.top + (lastRowY + 24f) * pixelScale,
        )
        val undoBefore = currentSession(sessionId).undoStack.size

        rule.onNodeWithTag("seq3-workspace-root").performMouseInput {
            moveTo(start)
            press()
            moveTo(destination, delayMillis = 100)
            release()
        }
        rule.waitForIdle()

        assertEquals(
            listOf(Seq3RowRef("other", 103), Seq3RowRef("repeat", 102), Seq3RowRef("repeat", 101)),
            currentSession(sessionId).document.rowOrder,
            "the dragged first occurrence moves after the other repeated occurrence without moving its sibling",
        )
        assertEquals(undoBefore + 1, currentSession(sessionId).undoStack.size)
        assertNull(view.rowReorderPreview)
    }

    @Test
    fun dragShowsTheInsertionLineWhileActiveAndRemovesItOnRelease() {
        val sessionId = startWorkspace("canvas-drop-line")
        val document = repeatedRowsDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        showWorkspace(sessionId)

        val rows = Seq3RenderCache.layout(document).rows.filterIsInstance<Seq3ArrowRow>()
        val source = rows.first()
        val body = bounds("seq3-canvas-body")
        val pixelScale = rule.density.density * view.zoom
        val rowCenterX = ((source.fromX + source.toX) / 2.0 * pixelScale.toDouble()).toFloat()
        val start = rootPoint(body.left + rowCenterX, body.top + source.y.toFloat() * pixelScale)
        val destination = rootPoint(body.left + rowCenterX, body.top + (rows.last().y.toFloat() + 24f) * pixelScale)
        val workspace = rule.onNodeWithTag("seq3-workspace-root")

        workspace.performMouseInput {
            moveTo(start)
            press()
            moveTo(destination, delayMillis = 100)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-reorder-drop-line", useUnmergedTree = true).assertIsDisplayed()
        // No fragment and not a collapsed summary: the line alone, no chip.
        rule.onAllNodes(hasTestTag("seq3-reorder-drop-chip"), useUnmergedTree = true).assertCountEquals(0)

        workspace.performMouseInput { release() }
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("seq3-reorder-drop-line"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun contextMenuMoveDownReordersTheRightClickedRowAndIsUndoable() {
        val sessionId = startWorkspace("canvas-move-menu")
        val document = repeatedRowsDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        showWorkspace(sessionId)

        val target = Seq3RenderCache.layout(document).rows
            .filterIsInstance<Seq3ArrowRow>()
            .single { it.messageId == "repeat" && it.occurrenceEntryId == FIRST_REPEAT_ENTRY_ID }
        val body = bounds("seq3-canvas-body")
        val clickAt = rootPoint(
            body.left + (target.fromX + target.toX).toFloat() / 2f * rule.density.density * view.zoom,
            body.top + target.y.toFloat() * rule.density.density * view.zoom,
        )
        val undoBefore = currentSession(sessionId).undoStack.size
        rule.onNodeWithTag("seq3-workspace-root").performMouseInput { rightClick(clickAt) }
        rule.waitForIdle()
        rule.onNodeWithText("Move", useUnmergedTree = true).assertIsDisplayed()
        // Move comes before "Group as" in the menu, so its ▶ trigger is the first one.
        rule.onAllNodesWithText("▶", useUnmergedTree = true)[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Down", useUnmergedTree = true).assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals(
            listOf(Seq3RowRef("other", OTHER_ENTRY_ID), Seq3RowRef("repeat", FIRST_REPEAT_ENTRY_ID), Seq3RowRef("repeat", SECOND_REPEAT_ENTRY_ID)),
            currentSession(sessionId).document.rowOrder,
            "Move > Down puts the first occurrence after the next drawn row",
        )
        assertEquals(undoBefore + 1, currentSession(sessionId).undoStack.size)
        assertNull(view.canvasContextMenuMessageId, "choosing an option closes the menu")

        assertTrue(state.seq3Sessions.undo(sessionId))
        assertEquals(emptyList(), currentSession(sessionId).document.rowOrder, "undo restores the chronological order")
    }

    @Test
    fun altArrowKeysMoveTheSelectedArrowAndTheSelectionFollowsIt() {
        val sessionId = startWorkspace("canvas-alt-arrows")
        val document = repeatedRowsDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        showWorkspace(sessionId)
        val selected = Seq3CanvasRowRef("repeat", FIRST_REPEAT_ENTRY_ID)
        view.selectedCanvasRows = setOf(selected)
        view.selectedOccurrenceMessageId = "repeat"
        view.selectedOccurrenceEntryId = FIRST_REPEAT_ENTRY_ID
        val workspace = rule.onNodeWithTag("seq3-workspace-root")

        fun drawn() = seq3DrawnRowRefs(currentSession(sessionId).document).map { it.occurrenceEntryId }

        workspace.performKeyInput { keyDown(Key.AltLeft); pressKey(Key.DirectionUp); keyUp(Key.AltLeft) }
        rule.waitForIdle()
        assertEquals(listOf(FIRST_REPEAT_ENTRY_ID, OTHER_ENTRY_ID, SECOND_REPEAT_ENTRY_ID), drawn(), "already first: Alt+Up changes nothing")

        workspace.performKeyInput { keyDown(Key.AltLeft); pressKey(Key.DirectionDown); keyUp(Key.AltLeft) }
        rule.waitForIdle()
        assertEquals(listOf(OTHER_ENTRY_ID, FIRST_REPEAT_ENTRY_ID, SECOND_REPEAT_ENTRY_ID), drawn())
        assertEquals(setOf(selected), view.selectedCanvasRows, "the selection follows the moved row")

        workspace.performKeyInput { keyDown(Key.AltLeft); pressKey(Key.DirectionUp); keyUp(Key.AltLeft) }
        rule.waitForIdle()
        assertEquals(listOf(FIRST_REPEAT_ENTRY_ID, OTHER_ENTRY_ID, SECOND_REPEAT_ENTRY_ID), drawn())

        workspace.performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        assertEquals(listOf(FIRST_REPEAT_ENTRY_ID, OTHER_ENTRY_ID, SECOND_REPEAT_ENTRY_ID), drawn(), "a plain arrow key does not move rows")
    }

    @Test
    fun canvasContextMenuAddsAfterTheRightClickedRepeatedOccurrence() {
        val sessionId = startWorkspace("canvas-add-after")
        val document = repeatedRowsDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        showWorkspace(sessionId)

        val target = Seq3RenderCache.layout(document).rows
            .filterIsInstance<Seq3ArrowRow>()
            .single { it.messageId == "repeat" && it.occurrenceEntryId == 102 }
        val body = bounds("seq3-canvas-body")
        val clickAt = rootPoint(
            body.left + (target.fromX + target.toX).toFloat() / 2f * rule.density.density * view.zoom,
            body.top + target.y.toFloat() * rule.density.density * view.zoom,
        )
        rule.onNodeWithTag("seq3-workspace-root").performMouseInput { rightClick(clickAt) }
        rule.waitForIdle()
        rule.onNodeWithText("Add message after this…", useUnmergedTree = true).assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals(Seq3RowRef("repeat", 102), view.pendingInsertRequest?.after)
        rule.onNodeWithText("Add message after this row", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun doubleClickingGroupLabelNearestItsDelayEditsTheGroupLabelOnly() {
        val sessionId = startWorkspace("group-near-delay")
        val document = groupAndDelayDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        showWorkspace(sessionId)

        val groupTag = "seq3-fragment-label-group"
        val delayTag = "seq3-delay-hit-delay"
        rule.onNodeWithTag(groupTag, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(delayTag, useUnmergedTree = true).assertIsDisplayed()
        val groupBounds = bounds(groupTag)
        val delayBounds = bounds(delayTag)
        val doubleClickAt = closestPointInGroupOutsideDelay(groupBounds, delayBounds)
        rule.onNodeWithTag("seq3-workspace-root").performMouseInput { doubleClick(rootPoint(doubleClickAt.x, doubleClickAt.y)) }
        rule.waitForIdle()

        val groupEditor = rule.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(groupTag)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        rule.onAllNodes(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(delayTag)),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        assertTrue(view.textFieldFocused, "the group editor receives keyboard focus on open")
        val undoBefore = currentSession(sessionId).undoStack.size
        groupEditor.performTextReplacement("checkout")
        groupEditor.performImeAction()
        rule.waitForIdle()

        assertEquals("checkout", currentSession(sessionId).document.fragments.single().label)
        assertEquals("processing delay", currentSession(sessionId).document.delays.single().label)
        assertEquals(undoBefore + 1, currentSession(sessionId).undoStack.size)
        assertFalse(view.textFieldFocused, "committing the group edit returns keyboard focus to the workspace")
    }

    @Test
    fun overlappingGroupHeaderWinsAndCanvasEditorsReplaceEachOtherAtFitWidth() {
        val sessionId = startWorkspace("editor-header-overlap")
        val document = overlappingHeaderDocument()
        val originalGroupLabel = document.fragments.single().label
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.FIT_WIDTH
        showWorkspace(sessionId)
        rule.waitForIdle()
        assertTrue(view.zoom in 0.5f..1f, "the fixture reproduces the low fit-width scale where the overlap is visible")

        val layout = Seq3RenderCache.layout(document)
        val fragment = layout.fragments.single()
        val firstRow = layout.rows.filterIsInstance<Seq3ArrowRow>().single { it.messageId == "repeat" && it.occurrenceEntryId == FIRST_REPEAT_ENTRY_ID }
        val body = bounds("seq3-canvas-body")
        val scale = rule.density.density * view.zoom
        val workspace = rule.onNodeWithTag("seq3-workspace-root")

        fun canvasPoint(x: Double, y: Double) = rootPoint(
            body.left + (x * scale).toFloat(),
            body.top + (y * scale).toFloat(),
        )

        val delay = layout.delays.single()
        val delayPoint = canvasPoint(delay.hitBox.x + delay.hitBox.width / 2, delay.hitBox.y + delay.hitBox.height / 2)
        workspace.performMouseInput { doubleClick(delayPoint) }
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-delay-editor-field-delay", useUnmergedTree = true).assertIsDisplayed()

        val messagePoint = canvasPoint((firstRow.fromX + firstRow.toX) / 2, firstRow.y)
        workspace.performMouseInput { doubleClick(messagePoint) }
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-message-label-editor-repeat", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("seq3-delay-editor-field-delay", useUnmergedTree = true).assertDoesNotExist()

        // This point is at the bottom-right side of the fragment header, horizontally over the
        // message label and vertically inside the message editor's six-unit top overhang. The
        // fragment header must win the real pointer hit even while that editor is open underneath.
        val headerHeight = fragment.box.height.coerceAtMost(28.0).coerceAtLeast(20.0)
        val overlapLeft = maxOf(fragment.box.x, firstRow.labelBox.x)
        val overlapRight = minOf(fragment.box.x + fragment.box.width, firstRow.labelBox.x + firstRow.labelBox.width)
        assertTrue(overlapRight > overlapLeft, "the message label must extend horizontally through the group header")
        val headerPoint = canvasPoint(overlapRight - 2.0, fragment.box.y + headerHeight - 2.0)
        workspace.performMouseInput { doubleClick(headerPoint) }
        rule.waitForIdle()

        val groupTag = "seq3-fragment-label-${fragment.fragmentId}"
        val groupEditorMatcher = hasSetTextAction() and hasAnyAncestor(hasTestTag(groupTag))
        val groupEditor = rule.onNode(groupEditorMatcher, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(view.textFieldFocused)
        rule.onNodeWithTag("seq3-message-label-editor-repeat", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("seq3-delay-editor-field-delay", useUnmergedTree = true).assertDoesNotExist()

        groupEditor.performTextReplacement("unsaved group draft")
        groupEditor.performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertEquals(originalGroupLabel, currentSession(sessionId).document.fragments.single().label)

        workspace.performMouseInput { doubleClick(headerPoint) }
        rule.waitForIdle()
        val reopenedGroupEditor = rule.onNode(groupEditorMatcher, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(
            originalGroupLabel,
            reopenedGroupEditor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
            "cancelling then reopening the group editor starts from the saved label, not its prior draft",
        )

        val undoBefore = currentSession(sessionId).undoStack.size
        reopenedGroupEditor.performTextReplacement("checkout")
        reopenedGroupEditor.performImeAction()
        rule.waitForIdle()
        assertEquals("checkout", currentSession(sessionId).document.fragments.single().label)
        assertEquals("delay", currentSession(sessionId).document.delays.single().label)
        assertEquals("request()", currentSession(sessionId).document.messages.single { it.id == "repeat" }.labelTemplate)
        assertEquals(undoBefore + 1, currentSession(sessionId).undoStack.size)
    }

    @Test
    fun delayEditorAndCommitButtonsFitTheirHitTargetAtFitWidthZoom() {
        val sessionId = startWorkspace("delay-editor-fit-width")
        val document = groupAndDelayDocument().copy(
            delays = listOf(Seq3Delay("delay", "repeat", "delay")),
        )
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.FIT_WIDTH
        showWorkspace(sessionId)
        rule.waitForIdle()

        val delayTag = "seq3-delay-hit-delay"
        val delayBounds = bounds(delayTag)
        val doubleClickAt = rootPoint(centerX(delayBounds), centerY(delayBounds))
        rule.onNodeWithTag("seq3-workspace-root").performMouseInput { doubleClick(doubleClickAt) }
        rule.waitForIdle()

        val editor = rule.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(delayTag)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        val editorFieldBounds = bounds("seq3-delay-editor-field-delay")
        val expectedFieldWidth = SEQ3_DELAY_EDITOR_FIELD_WIDTH_DP.toFloat() * rule.density.density * view.zoom
        assertTrue(
            editorFieldBounds.width >= expectedFieldWidth - 3f,
            "the full 90dp delay text field must fit inside its hit target at fit-width zoom; " +
                "field=${editorFieldBounds.width}px expectedAtLeast=${expectedFieldWidth}px",
        )
        assertTrue(view.textFieldFocused, "the delay editor receives focus when opened")

        editor.performTextReplacement("discard this draft")
        editor.performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertEquals("delay", currentSession(sessionId).document.delays.single().label, "Escape cancels the draft")
        assertFalse(view.textFieldFocused)

        rule.onNodeWithTag("seq3-workspace-root").performMouseInput { doubleClick(doubleClickAt) }
        rule.waitForIdle()
        val reopenedEditor = rule.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(delayTag)),
            useUnmergedTree = true,
        ).assertIsDisplayed()

        val undoBefore = currentSession(sessionId).undoStack.size
        reopenedEditor.performTextReplacement("wait for response")
        reopenedEditor.performImeAction()
        rule.waitForIdle()

        assertEquals("wait for response", currentSession(sessionId).document.delays.single().label)
        assertEquals(undoBefore + 1, currentSession(sessionId).undoStack.size)
        assertFalse(view.textFieldFocused, "committing the delay edit returns keyboard focus to the workspace")
    }

    private fun startWorkspace(tabId: String): String {
        tempRoot = createTempDirectory("$tabId-ui").toFile()
        val tab = mkTab(tabId, "$tabId.log", emptyList())
        state = AppState(
            File(tempRoot, "state.cache"),
            notesDir = File(tempRoot, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(tempRoot, "library.cache")),
        ).also {
            it.tabs = listOf(tab)
            it.activateTab(tab.id)
        }
        return requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
    }

    private fun replaceDocument(sessionId: String, document: Seq3Document) {
        assertTrue(state.seq3Sessions.applyCommand(sessionId, Seq3Command.ReplaceDocument(document)))
    }

    private fun currentSession(sessionId: String) =
        requireNotNull(state.seq3Sessions.sessions.singleOrNull { it.id == sessionId })

    private fun showWorkspace(sessionId: String, widthDp: Float = 1280f) {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(widthDp.dp, 900.dp)) {
                    Seq3Workspace(state, sessionId)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun centerX(bounds: Rect): Float = (bounds.left + bounds.right) / 2f

    private fun centerY(bounds: Rect): Float = (bounds.top + bounds.bottom) / 2f

    /** Converts a stable root-space dp point into Compose mouse input's physical root pixels. */
    private fun rootPoint(xPx: Float, yPx: Float): Offset {
        val rootBounds = bounds("seq3-workspace-root")
        return Offset(
            x = xPx - rootBounds.left,
            y = yPx - rootBounds.top,
        )
    }

    private fun saveHeaderEvidence(name: String, image: ImageBitmap) {
        val directory = File("build/seq3-interaction-evidence").apply { mkdirs() }
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        check(ImageIO.write(rendered, "png", File(directory, name)))
    }

    private fun headerDocument(width: Double) = Seq3Document(
        lifelines = listOf(
            Seq3Lifeline("a", "Client", setOf("Client"), 0, headerWidthDp = width),
            Seq3Lifeline("b", "Server", setOf("Server"), 1, headerWidthDp = width),
        ),
    )

    private fun repeatedRowsDocument() = Seq3Document(
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
                occurrences = listOf(
                    occurrence(FIRST_REPEAT_ENTRY_ID, FIRST_REPEAT_TIMESTAMP_MS),
                    occurrence(SECOND_REPEAT_ENTRY_ID, SECOND_REPEAT_TIMESTAMP_MS),
                ),
            ),
            Seq3Message(
                id = "other",
                match = Seq3Match("Server", "reply()"),
                fromLifelineId = "b",
                toLifelineId = "a",
                labelTemplate = "reply()",
                occurrences = listOf(occurrence(OTHER_ENTRY_ID, OTHER_TIMESTAMP_MS)),
            ),
        ),
    )

    private fun groupAndDelayDocument() = repeatedRowsDocument().copy(
        messages = repeatedRowsDocument().messages.take(2),
        fragments = listOf(
            Seq3Fragment(
                id = "group",
                kind = Seq3FragmentKind.GROUP,
                label = "transaction",
                messageIds = listOf("repeat", "other"),
            ),
        ),
        delays = listOf(Seq3Delay("delay", "repeat", "processing delay")),
    )

    private fun overlappingHeaderDocument() = groupAndDelayDocument().copy(
        lifelines = groupAndDelayDocument().lifelines + listOf(
            Seq3Lifeline("c", "NetworkSimulator", setOf("NetworkSimulator"), 2, headerWidthDp = 240.0),
            Seq3Lifeline("d", "PackageManager", setOf("PackageManager"), 3, headerWidthDp = 240.0),
        ),
        delays = listOf(Seq3Delay("delay", "repeat", "delay")),
    )

    private fun occurrence(entryId: Int, timestamp: Long) = Seq3Occurrence(
        entryId = entryId,
        timestampMillis = timestamp,
        rawTimestamp = "00:00:00.${timestamp.toString().padStart(3, '0')}",
        pid = 1,
        tid = 1,
        level = 'I',
        text = "message $entryId",
    )

    /** Pick the point in the tagged group hit area closest to the delay without hitting the delay. */
    private fun closestPointInGroupOutsideDelay(group: Rect, delay: Rect): Offset {
        val inset = rule.density.density
        val step = 3f * rule.density.density
        val xCandidates = generateSequence(group.left + inset) { it + step }
            .takeWhile { it < group.right - inset }
            .toList()
        val yCandidates = generateSequence(group.top + inset) { it + step }
            .takeWhile { it < group.bottom - inset }
            .toList()
        return xCandidates.asSequence().flatMap { x -> yCandidates.asSequence().map { y -> Offset(x, y) } }
            .filterNot { point -> point.x in delay.left..delay.right && point.y in delay.top..delay.bottom }
            .minByOrNull { point ->
                val dx = point.x - (delay.left + delay.right) / 2f
                val dy = point.y - (delay.top + delay.bottom) / 2f
                dx * dx + dy * dy
            } ?: error("The group label hit area has no point outside the delay hit area")
    }
}
