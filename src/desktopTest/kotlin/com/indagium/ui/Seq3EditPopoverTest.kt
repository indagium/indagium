package com.indagium.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.indagium.diagram3.Seq3Box
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Repeat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3EditPopoverTest {
    private fun occurrence(entryId: Int, timestamp: Long) = Seq3Occurrence(entryId, timestamp, "00:00:0$timestamp", 1, 1, 'I', "message $entryId")

    private fun document() = Seq3Document(
        lifelines = listOf(
            Seq3Lifeline("a", "Client", setOf("Client"), 0),
            Seq3Lifeline("b", "Server", setOf("Server"), 1),
        ),
        messages = listOf(
            Seq3Message(
                id = "repeat",
                match = Seq3Match("Client", "request({id})"),
                fromLifelineId = "a",
                toLifelineId = "b",
                labelTemplate = "request({id})",
                repeat = Seq3Repeat.EVERY,
                occurrences = listOf(occurrence(1, 1), occurrence(2, 2)),
            ),
            Seq3Message(
                id = "other",
                match = Seq3Match("Server", "reply()"),
                fromLifelineId = "b",
                toLifelineId = "a",
                labelTemplate = "reply()",
                occurrences = listOf(occurrence(3, 3)),
            ),
        ),
        fragments = listOf(Seq3Fragment("group", Seq3FragmentKind.GROUP, "transaction", listOf("repeat", "other"))),
        delays = listOf(Seq3Delay("delay", "repeat", "waiting")),
        notes = listOf(Seq3Note("note", "remember this", listOf("other"), x = 40.0, y = 40.0, width = 180.0, height = 40.0)),
    )

    @Test
    fun everyEditorKindResolvesItsElementBoxAndSeedFromTheLiveLayout() {
        val doc = document()
        val layout = Seq3RenderCache.layout(doc)

        fun spec(kind: Seq3CanvasEditorKind, id: String, entry: Int? = null) =
            seq3EditSpec(doc, layout, Seq3CanvasEditorTarget(kind, id, entry))

        assertEquals("transaction", spec(Seq3CanvasEditorKind.FRAGMENT_LABEL, "group")?.seed)
        assertEquals("waiting", spec(Seq3CanvasEditorKind.DELAY_LABEL, "delay")?.seed)
        assertEquals("remember this", spec(Seq3CanvasEditorKind.NOTE_TEXT, "note")?.seed)
        assertEquals("Client", spec(Seq3CanvasEditorKind.LIFELINE_NAME, "a")?.seed)
        assertEquals(layout.lifelines.single { it.lifelineId == "a" }.header, spec(Seq3CanvasEditorKind.LIFELINE_NAME, "a")?.box)
        val fragmentBox = layout.fragments.single().box
        val header = assertNotNull(spec(Seq3CanvasEditorKind.FRAGMENT_LABEL, "group")).box
        assertEquals(fragmentBox.x, header.x)
        assertTrue(header.height in 20.0..28.0, "the fragment editor anchors to its header strip, not the whole box")
        assertNull(spec(Seq3CanvasEditorKind.DELAY_LABEL, "missing"))
        assertNull(spec(Seq3CanvasEditorKind.MESSAGE_LABEL, "repeat", entry = 999), "an arrow that is not drawn has nothing to anchor to")
    }

    @Test
    fun aMessageLabelIsSeededFromTheTemplateAndCountsTheArrowsSharingIt() {
        val doc = document()
        val layout = Seq3RenderCache.layout(doc)
        val shared = assertNotNull(seq3EditSpec(doc, layout, Seq3CanvasEditorTarget(Seq3CanvasEditorKind.MESSAGE_LABEL, "repeat", 2)))
        assertEquals("request({id})", shared.seed, "the template with its capture slot, not one occurrence's rendered value")
        assertEquals(2, shared.arrowCount)
        assertTrue(shared.canRenameOnlyThisArrow)
        assertEquals("Renames all 2 arrows of this message", seq3SharedLabelNote(shared.arrowCount))

        val single = assertNotNull(seq3EditSpec(doc, layout, Seq3CanvasEditorTarget(Seq3CanvasEditorKind.MESSAGE_LABEL, "other", 3)))
        assertEquals(1, single.arrowCount)
        assertFalse(single.canRenameOnlyThisArrow)
        assertNull(seq3SharedLabelNote(single.arrowCount))
    }

    @Test
    fun aCollapsedMessageDrawsOneArrowSoItNeitherNamesNorSplits() {
        val collapsed = document().messages.first().copy(
            repeat = Seq3Repeat.COLLAPSE_ABOVE,
            repeatThreshold = 2,
            occurrences = listOf(occurrence(1, 1), occurrence(2, 2), occurrence(4, 4)),
        )
        val doc = document().let { it.copy(messages = listOf(collapsed) + it.messages.drop(1)) }
        val layout = Seq3RenderCache.layout(doc)
        val spec = assertNotNull(seq3EditSpec(doc, layout, Seq3CanvasEditorTarget(Seq3CanvasEditorKind.MESSAGE_LABEL, "repeat", 1)))
        assertEquals(1, spec.arrowCount)
        assertFalse(spec.canRenameOnlyThisArrow)
    }

    @Test
    fun editCommandsKeepTheCommitCommandsOfTheOldInlineEditors() {
        fun command(kind: Seq3CanvasEditorKind, id: String, text: String) =
            seq3EditCommand(Seq3CanvasEditorTarget(kind, id), text)

        assertEquals(
            Seq3Command.Bulk(setOf("m"), Seq3BulkAction.SetLabel("x")),
            command(Seq3CanvasEditorKind.MESSAGE_LABEL, "m", "x"),
        )
        assertEquals(
            Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetFragmentLabel("f", "x")),
            command(Seq3CanvasEditorKind.FRAGMENT_LABEL, "f", "x"),
        )
        assertEquals(
            Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetDelayLabel("d", "x")),
            command(Seq3CanvasEditorKind.DELAY_LABEL, "d", "x"),
        )
        assertEquals(
            Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetNoteText("n", "x")),
            command(Seq3CanvasEditorKind.NOTE_TEXT, "n", "x"),
        )
        assertEquals(Seq3Command.RenameLifeline("l", "x"), command(Seq3CanvasEditorKind.LIFELINE_NAME, "l", "x"))
        Seq3CanvasEditorKind.entries.forEach { assertNull(command(it, "id", "   "), "blank text saves nothing for $it") }
    }

    @Test
    fun renameOnlyThisArrowNeedsAMessageTargetWithAnOccurrenceAndNonBlankText() {
        val target = Seq3CanvasEditorTarget(Seq3CanvasEditorKind.MESSAGE_LABEL, "repeat", 2)
        assertEquals(Seq3Command.MoveOccurrenceOutAndRename("repeat", 2, "retry"), seq3RenameOnlyThisArrowCommand(target, "retry"))
        assertNull(seq3RenameOnlyThisArrowCommand(target, " "))
        assertNull(seq3RenameOnlyThisArrowCommand(target.copy(occurrenceEntryId = null), "retry"))
        assertNull(seq3RenameOnlyThisArrowCommand(Seq3CanvasEditorTarget(Seq3CanvasEditorKind.DELAY_LABEL, "d", 2), "retry"))
    }

    @Test
    fun elementRectScalesByDensityAndZoomAndSubtractsTheScroll() {
        val rect = seq3ElementRectPx(Seq3Box(100.0, 50.0, 80.0, 20.0), density = 2f, zoom = 0.5f, scrollXPx = 30, scrollYPx = 10)
        assertEquals(Rect(70f, 40f, 150f, 60f), rect)
        val zoomed = seq3ElementRectPx(Seq3Box(100.0, 50.0, 80.0, 20.0), density = 2f, zoom = 1.5f, scrollXPx = 0, scrollYPx = 0)
        assertEquals(Rect(300f, 150f, 540f, 210f), zoomed)
    }

    @Test
    fun popoverWidthIsTheMinimumUnlessTheElementIsWiderAndNeverExceedsTheViewport() {
        assertEquals(280, seq3EditPopoverWidthPx(anchorWidthPx = 90f, minWidthPx = 280, maxWidthPx = 1000))
        assertEquals(280, seq3EditPopoverWidthPx(anchorWidthPx = 90f * 3, minWidthPx = 280, maxWidthPx = 1000), "zoom alone does not change it")
        assertEquals(420, seq3EditPopoverWidthPx(anchorWidthPx = 420f, minWidthPx = 280, maxWidthPx = 1000))
        assertEquals(500, seq3EditPopoverWidthPx(anchorWidthPx = 900f, minWidthPx = 280, maxWidthPx = 500))
    }

    @Test
    fun popoverSitsJustAboveTheElementAndFallsBackBelowOrInsideTheViewport() {
        val viewport = IntSize(1000, 600)
        val popup = IntSize(280, 70)
        val above = seq3EditPopoverOffset(Rect(100f, 300f, 200f, 320f), popup, viewport, gapPx = 4, marginPx = 6)
        assertEquals(IntOffset(100, 226), above, "bottom edge is one gap above the element's top")
        val nearTop = seq3EditPopoverOffset(Rect(100f, 30f, 200f, 50f), popup, viewport, gapPx = 4, marginPx = 6)
        assertEquals(IntOffset(100, 54), nearTop, "no room above: just below the element")
        val nearRight = seq3EditPopoverOffset(Rect(950f, 300f, 990f, 320f), popup, viewport, gapPx = 4, marginPx = 6)
        assertEquals(714, nearRight.x, "pushed left so the whole popover stays visible")
        val offLeft = seq3EditPopoverOffset(Rect(-200f, 300f, -100f, 320f), popup, viewport, gapPx = 4, marginPx = 6)
        assertEquals(6, offLeft.x)
    }
}
