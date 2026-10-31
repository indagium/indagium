package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Repeat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The user flow: select several arrows on the diagram, right-click one of them, Group as > loop.
 *  Every selected arrow has to end up in the new fragment, not only the right-clicked one. */
@OptIn(ExperimentalTestApi::class)
class Seq3GroupAsUiTest {
    private companion object {
        const val WAIT_MS = 3_000L
        const val SPACING_MS = 1_000L
    }

    @get:Rule
    val rule = createComposeRule()

    private lateinit var root: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) {
            state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
            state.close()
        }
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun marqueeSelectThreeArrowsRightClickOneAndGroupAsLoopGroupsAllThree() {
        val sessionId = startWorkspace("group-marquee")
        val document = singleOccurrenceDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId, view)
        val rows = arrows(document)

        marquee(rows.take(3))
        assertEquals(setOf("m1", "m2", "m3"), view.selectedCanvasRows.map { it.messageId }.toSet(), "the marquee selected three arrows")

        rightClickArrow(rows[1])
        groupAsLoop()

        val fragment = currentSession(sessionId).document.fragments.single()
        assertEquals(Seq3FragmentKind.LOOP, fragment.kind)
        assertEquals(setOf("m1", "m2", "m3"), fragmentMessageIds(fragment), "all three selected arrows are grouped: $fragment")
    }

    @Test
    fun marqueeSelectThreeArrowsOfARepeatedMessageThenGroupAsLoopGroupsAllThree() {
        val sessionId = startWorkspace("group-marquee-repeat")
        val document = repeatedDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId, view)
        val rows = arrows(document)

        marquee(rows.take(3))
        assertEquals(3, view.selectedCanvasRows.size)

        rightClickArrow(rows[1])
        groupAsLoop()

        val fragment = currentSession(sessionId).document.fragments.single()
        assertEquals(setOf(101, 102, 103), fragment.occurrenceRefs.map { it.entryId }.toSet(), "all three arrows are grouped: $fragment")
    }

    @Test
    fun ctrlClickThreeArrowsRightClickOneAndGroupAsLoopGroupsAllThree() {
        val sessionId = startWorkspace("group-ctrl-click")
        val document = singleOccurrenceDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId, view)
        val rows = arrows(document)

        val workspace = rule.onNodeWithTag("seq3-workspace-root")
        workspace.performMouseInput { click(pointOn(rows[0])) }
        rule.waitForIdle()
        for (row in rows.subList(1, 3)) {
            workspace.performKeyInput { keyDown(Key.CtrlLeft) }
            workspace.performMouseInput { click(pointOn(row)) }
            workspace.performKeyInput { keyUp(Key.CtrlLeft) }
            rule.waitForIdle()
        }
        assertEquals(setOf("m1", "m2", "m3"), view.selectedCanvasRows.map { it.messageId }.toSet(), "three arrows are selected")

        rightClickArrow(rows[1])
        assertEquals(3, view.selectedCanvasRows.size, "right-clicking inside the selection keeps it")
        groupAsLoop()

        val fragment = currentSession(sessionId).document.fragments.single()
        assertEquals(setOf("m1", "m2", "m3"), fragmentMessageIds(fragment), "all three selected arrows are grouped: $fragment")
    }

    @Test
    fun ctrlClickThreeArrowsOfARepeatedMessageThenGroupAsLoopGroupsAllThree() {
        val sessionId = startWorkspace("group-ctrl-click-repeat")
        val document = repeatedDocument()
        replaceDocument(sessionId, document)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId, view)
        val rows = arrows(document)

        val workspace = rule.onNodeWithTag("seq3-workspace-root")
        workspace.performMouseInput { click(pointOn(rows[0])) }
        rule.waitForIdle()
        for (row in rows.subList(1, 3)) {
            workspace.performKeyInput { keyDown(Key.MetaLeft) }
            workspace.performMouseInput { click(pointOn(row)) }
            workspace.performKeyInput { keyUp(Key.MetaLeft) }
            rule.waitForIdle()
        }
        rightClickArrow(rows[1])
        groupAsLoop()

        val fragment = currentSession(sessionId).document.fragments.single()
        assertEquals(setOf(101, 102, 103), fragment.occurrenceRefs.map { it.entryId }.toSet(), "all three arrows are grouped: $fragment")
    }

    private fun fragmentMessageIds(fragment: com.indagium.diagram3.Seq3Fragment): Set<String> =
        (fragment.messageIds + fragment.occurrenceRefs.map { it.messageId }).toSet()

    private fun arrows(document: Seq3Document): List<Seq3ArrowRow> =
        Seq3RenderCache.layout(document).rows.filterIsInstance<Seq3ArrowRow>()

    private fun bodyBounds(): Rect = rule.onNodeWithTag("seq3-canvas-body", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun rootOrigin(): Offset =
        rule.onNodeWithTag("seq3-workspace-root", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.topLeft

    /** Root-relative mouse position of a layout point (the zoom is pinned to 1). */
    private fun pointAt(xUnits: Double, yUnits: Double): Offset {
        val density = rule.density.density
        val body = bodyBounds()
        return Offset(
            body.left + xUnits.toFloat() * density,
            body.top + yUnits.toFloat() * density,
        ) - rootOrigin()
    }

    private fun pointOn(row: Seq3ArrowRow): Offset = pointAt((row.fromX + row.toX) / 2.0, row.y)

    /** Drags a rubber band from empty background left of the arrows to just past the last row's arrow. */
    private fun marquee(selected: List<Seq3ArrowRow>) {
        val left = selected.minOf { minOf(it.fromX, it.toX) } - 24.0
        val right = selected.maxOf { maxOf(it.fromX, it.toX) } + 24.0
        val start = pointAt(left, selected.first().y - 14.0)
        val end = pointAt(right, selected.last().y + 6.0)
        rule.onNodeWithTag("seq3-workspace-root").performMouseInput {
            moveTo(start)
            press()
            moveTo((start + end) / 2f, delayMillis = 50)
            moveTo(end, delayMillis = 50)
            release()
        }
        rule.waitForIdle()
    }

    private fun rightClickArrow(row: Seq3ArrowRow) {
        rule.onNodeWithTag("seq3-workspace-root").performMouseInput { rightClick(pointOn(row)) }
        rule.waitForIdle()
    }

    private fun groupAsLoop() {
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText("Group as", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("seq3-canvas-ctx-group-as", useUnmergedTree = true).performClick()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText("loop", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("loop", useUnmergedTree = true).performClick()
        rule.waitForIdle()
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
        return requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
    }

    private fun replaceDocument(sessionId: String, document: Seq3Document) {
        assertTrue(state.seq3Sessions.applyCommand(sessionId, Seq3Command.ReplaceDocument(document)))
    }

    private fun currentSession(sessionId: String) =
        requireNotNull(state.seq3Sessions.sessions.singleOrNull { it.id == sessionId })

    private fun showWorkspace(sessionId: String, view: Seq3ViewState) {
        view.zoomMode = Seq3ZoomMode.MANUAL
        view.zoom = 1f
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(1280.dp, 900.dp)) {
                    Seq3Workspace(state, sessionId)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun occurrence(entryId: Int, timestamp: Long) = Seq3Occurrence(
        entryId = entryId,
        timestampMillis = timestamp,
        rawTimestamp = "00:00:00.${timestamp.toString().padStart(3, '0')}",
        pid = 1,
        tid = 1,
        level = 'I',
        text = "message $entryId",
    )

    private val lifelines = listOf(
        Seq3Lifeline("a", "Client", setOf("Client"), 0, headerWidthDp = 140.0),
        Seq3Lifeline("b", "Server", setOf("Server"), 1, headerWidthDp = 140.0),
    )

    /** Four different arrows, one occurrence each. */
    private fun singleOccurrenceDocument() = Seq3Document(
        lifelines = lifelines,
        messages = (1..4).map { index ->
            Seq3Message(
                id = "m$index",
                match = Seq3Match("Client", "call$index()"),
                fromLifelineId = "a",
                toLifelineId = "b",
                labelTemplate = "call$index()",
                occurrences = listOf(occurrence(100 + index, index * SPACING_MS)),
            )
        },
    )

    /** One repeated message drawn as four separate arrows. */
    private fun repeatedDocument() = Seq3Document(
        lifelines = lifelines,
        messages = listOf(
            Seq3Message(
                id = "repeat",
                match = Seq3Match("Client", "request()"),
                fromLifelineId = "a",
                toLifelineId = "b",
                labelTemplate = "request()",
                repeat = Seq3Repeat.EVERY,
                occurrences = (1..4).map { occurrence(100 + it, it * SPACING_MS) },
            ),
        ),
    )
}
