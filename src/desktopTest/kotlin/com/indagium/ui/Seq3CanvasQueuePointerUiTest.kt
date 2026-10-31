package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3RowRef
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3CanvasQueuePointerUiTest {
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
    fun clickReleaseThenMouseHoverNeverLeavesCanvasMarqueeOrReorderPreview() {
        val sessionId = startWorkspace("canvas-release-hover")
        val document = diagramDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        showWorkspace(sessionId)

        val row = Seq3RenderCache.layout(document).rows.filterIsInstance<Seq3ArrowRow>().first()
        val body = bounds("seq3-canvas-body")
        val scale = rule.density.density * view.zoom
        val messageX = body.left + ((row.fromX + row.toX) / 2.0 * scale).toFloat()
        val messageY = body.top + (row.y * scale).toFloat()
        val hoverPoint = rootPoint(body.left + 35f * scale, (body.top + (row.y + 80.0) * scale).toFloat())
        val messagePoint = rootPoint(messageX, messageY)
        val workspace = rule.onNodeWithTag("seq3-workspace-root")

        workspace.performMouseInput {
            moveTo(messagePoint)
            press()
            release()
        }
        workspace.performMouseInput { moveTo(hoverPoint) }
        rule.waitForIdle()

        assertNull(view.canvasSelectionRect, "hover after release must never turn stale press state into a marquee")
        assertNull(view.rowReorderPreview, "hover after release must never restore a row drag preview")
        assertEquals(document.rowOrder, currentSession(sessionId).document.rowOrder)
    }

    @Test
    fun canvasArrowLabelFollowsPhysicalPointerBeforeDropAndDoesNotMutateDocument() {
        val sessionId = startWorkspace("canvas-live-row-follow")
        val document = diagramDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        showWorkspace(sessionId)

        val row = Seq3RenderCache.layout(document).rows.filterIsInstance<Seq3ArrowRow>().first()
        val labelTag = "seq3-canvas-label-${row.messageId}-summary"
        val originalLabelBounds = bounds(labelTag)
        val body = bounds("seq3-canvas-body")
        val scale = rule.density.density * view.zoom
        val pointer = rootPoint(
            body.left + ((row.fromX + row.toX) / 2.0 * scale).toFloat(),
            body.top + (row.y * scale).toFloat(),
        )
        val deltaPx = 24f
        val workspace = rule.onNodeWithTag("seq3-workspace-root")
        workspace.performMouseInput {
            moveTo(pointer)
            press()
            moveTo(pointer + Offset(0f, deltaPx), delayMillis = 100)
        }
        rule.waitForIdle()

        val movedLabelBounds = bounds(labelTag)
        assertTrue(view.rowReorderPreview?.active == true, "crossing drag slop activates a preview")
        assertEquals(deltaPx, centerY(movedLabelBounds) - centerY(originalLabelBounds), 2.5f)
        assertEquals(document.rowOrder, currentSession(sessionId).document.rowOrder, "preview remains uncommitted")

        workspace.performMouseInput { release() }
        rule.waitForIdle()
        assertNull(view.rowReorderPreview)
        assertEquals(document.rowOrder, currentSession(sessionId).document.rowOrder)
    }

    @Test
    fun sequenceRowTracksSmallPointerMovementBeforeAnyOrderCommit() {
        val sessionId = startWorkspace("queue-live-row-follow")
        val document = diagramDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.messagesMode = Seq3MessagesMode.SEQUENCE
        showWorkspace(sessionId)

        val rowId = seq3SequenceRowKey(Seq3RowRef(document.messages.first().id))
        val rowTag = "seq3-sequence-row-$rowId"
        val handle = bounds("seq3-sequence-drag-$rowId")
        val start = rootPoint(centerX(handle), centerY(handle))
        val workspace = rule.onNodeWithTag("seq3-workspace-root")
        val activationDeltaPx = 24f
        workspace.performMouseInput {
            moveTo(start)
            press()
            moveTo(start + Offset(0f, activationDeltaPx), delayMillis = 100)
        }
        rule.waitForIdle()

        assertEquals(rowId, view.queueReorderDraggedId)
        val afterActivation = centerY(bounds(rowTag))
        val incrementalDeltaPx = 8f * rule.density.density
        workspace.performMouseInput {
            moveTo(start + Offset(0f, activationDeltaPx + incrementalDeltaPx), delayMillis = 100)
        }
        rule.waitForIdle()
        val afterSmallMovement = centerY(bounds(rowTag))
        assertEquals(incrementalDeltaPx, afterSmallMovement - afterActivation, 2.5f)
        assertEquals(document.messages.map { it.id }, currentSession(sessionId).document.messages.map { it.id })

        workspace.performMouseInput { release() }
        rule.waitForIdle()
        assertNull(view.queueReorderDraggedId)
        assertEquals(0f, view.queueReorderOffsetY)
        assertEquals(document.messages.map { it.id }, currentSession(sessionId).document.messages.map { it.id })
        assertEquals(document.rowOrder, currentSession(sessionId).document.rowOrder, "a drag that never crossed a neighbour moves nothing")
    }

    @Test
    fun heldSequenceDragAtViewportEdgeKeepsAutoscrollingAndKeepsDraggedRowVirtualized() {
        val sessionId = startWorkspace("queue-held-edge-scroll")
        // Enough rows that the list has room to keep scrolling under the held pointer.
        val document = diagramDocument(messageCount = 200)
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.messagesMode = Seq3MessagesMode.SEQUENCE
        showWorkspace(sessionId)

        val rowId = seq3SequenceRowKey(Seq3RowRef(document.messages.first().id))
        val handle = bounds("seq3-sequence-drag-$rowId")
        val viewport = bounds("seq3-sequence-list")
        val start = rootPoint(centerX(handle), centerY(handle))
        val root = bounds("seq3-workspace-root")
        val edgeTarget = Offset(
            x = start.x,
            y = viewport.bottom - 18f * rule.density.density - root.top,
        )
        val workspace = rule.onNodeWithTag("seq3-workspace-root")
        workspace.performMouseInput {
            moveTo(start)
            press()
            moveTo(edgeTarget, delayMillis = 100)
        }
        rule.waitForIdle()
        assertEquals(rowId, view.queueReorderDraggedId)
        // The list keeps its own order while a row is lifted, so the held edge scroll is not cut short by
        // the list re-anchoring on the dragged row's new slot: by the time the clock is idle it has run on
        // well past the pointer's own travel.
        val pointerTravel = edgeTarget.y - start.y
        rule.waitUntil(1_500) { view.queueReorderOffsetY > pointerTravel + 8f }
        // The row's own slot has scrolled far out of the viewport by now, yet the row is still the one
        // under the held pointer (the node is unique: there is no second copy of it).
        val dragged = rule.onAllNodesWithTag("seq3-sequence-row-$rowId", useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        val pointerRootY = edgeTarget.y + root.top
        assertTrue(pointerRootY in dragged.top..dragged.bottom, "the dragged row $dragged is under the pointer at $pointerRootY")
        assertEquals(document.messages.map { it.id }, currentSession(sessionId).document.messages.map { it.id })

        workspace.performMouseInput { release() }
        rule.waitForIdle()
        assertNull(view.queueReorderDraggedId)
    }

    @Test
    fun canvasClickTakesKeyboardOwnershipFromDirtySourceDraft() {
        val sessionId = startWorkspace("source-focus-canvas")
        val document = diagramDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        showWorkspace(sessionId)

        rule.onNodeWithTag("seq3-source-edit").performClick()
        val source = rule.onNodeWithTag("seq3-source-editor")
        source.performTextReplacement("${view.sourceDraft}\n' keep this draft")
        rule.waitForIdle()
        assertTrue(view.textFieldFocused)
        assertTrue(view.sourceDraftDirty)
        state.seq3Sessions.updateTitle(sessionId, "Undo this visual change")
        assertTrue(view.sourceDraftStale)

        val row = Seq3RenderCache.layout(currentSession(sessionId).document).rows.filterIsInstance<Seq3ArrowRow>().first()
        val body = bounds("seq3-canvas-body")
        val scale = rule.density.density * view.zoom
        val click = rootPoint(
            body.left + ((row.fromX + row.toX) / 2.0 * scale).toFloat(),
            body.top + (row.y * scale).toFloat(),
        )
        rule.onNodeWithTag("seq3-workspace-root").performMouseInput {
            moveTo(click)
            press()
            release()
        }
        rule.waitForIdle()
        assertFalse(view.textFieldFocused, "canvas-owned pointer gestures move keyboard ownership off the SOURCE tile")

        rule.onNodeWithTag("seq3-workspace-root").performKeyInput {
            withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) }
        }
        rule.waitForIdle()
        assertEquals("", currentSession(sessionId).document.title, "undo is routed to diagram history after the canvas click")
        assertTrue(view.sourceDraft.contains("' keep this draft"), "canvas focus changes do not discard a dirty source draft")
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

    private fun showWorkspace(sessionId: String) {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(
                    Modifier.size(1280.dp, 900.dp).onPreviewKeyEvent { handleDiagramUndoKey(it, state) },
                ) {
                    Seq3Workspace(state, sessionId)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun centerY(bounds: Rect): Float = (bounds.top + bounds.bottom) / 2f

    private fun centerX(bounds: Rect): Float = (bounds.left + bounds.right) / 2f

    private fun rootPoint(x: Float, y: Float): Offset {
        val rootBounds = bounds("seq3-workspace-root")
        return Offset(x - rootBounds.left, y - rootBounds.top)
    }

    private fun diagramDocument(messageCount: Int = 2) = Seq3Document(
        lifelines = listOf(
            Seq3Lifeline("a", "Client", setOf("Client"), 0),
            Seq3Lifeline("b", "Server", setOf("Server"), 1),
        ),
        messages = (0 until messageCount).map { index ->
            val id = "m${index + 1}"
            val from = if (index % 2 == 0) "a" else "b"
            val to = if (from == "a") "b" else "a"
            val owner = if (from == "a") "Client" else "Server"
            val label = if (index % 2 == 0) "send${index + 1}()" else "reply${index + 1}()"
            Seq3Message(
                id = id,
                match = Seq3Match(owner, label),
                fromLifelineId = from,
                toLifelineId = to,
                labelTemplate = label,
                kind = if (index % 2 == 0) Seq3Kind.CALL else Seq3Kind.RETURN,
            )
        },
        rowOrder = (0 until messageCount).map { Seq3RowRef("m${it + 1}") },
    )
}
