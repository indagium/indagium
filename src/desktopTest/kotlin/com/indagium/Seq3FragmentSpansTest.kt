package com.indagium

import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3FontRole
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3InsertionPosition
import com.indagium.diagram3.Seq3LayoutOptions
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3OccurrenceRef
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3RowRef
import com.indagium.diagram3.Seq3TextMetrics
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.applySeq3Command
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.layoutSeq3
import com.indagium.diagram3.normalizeSeq3FragmentSpans
import com.indagium.diagram3.seq3DrawnRowRefs
import com.indagium.model.AnnBlock
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.mkTab
import com.indagium.ui.seq3FragmentMessageCount
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [normalizeSeq3FragmentSpans]: a fragment holds exactly the drawn rows its box covers. */
class Seq3FragmentSpansTest {
    private companion object {
        const val LABEL_STRIP = 40.0
    }

    private val metrics = object : Seq3TextMetrics {
        override fun width(role: Seq3FontRole, text: String): Double = text.length * 7.0

        override fun lineHeight(role: Seq3FontRole): Double = 16.0
    }

    private fun occurrence(entryId: Int, text: String) = Seq3Occurrence(
        entryId = entryId,
        timestampMillis = entryId * 100L,
        rawTimestamp = "00:00:00.${(entryId * 100).toString().padStart(3, '0')}",
        pid = 1,
        tid = 1,
        level = 'I',
        text = text,
        elapsedMillis = entryId * 100L,
    )

    private fun message(id: String, vararg entries: Int, visibility: Seq3Visibility = Seq3Visibility.VISIBLE) = Seq3Message(
        id = id,
        match = Seq3Match("A", id),
        fromLifelineId = "A",
        toLifelineId = "B",
        labelTemplate = id,
        repeat = Seq3Repeat.EVERY,
        visibility = visibility,
        occurrences = entries.map { occurrence(it, id) },
    )

    private fun document(messages: List<Seq3Message>, fragments: List<Seq3Fragment>) = Seq3Document(
        lifelines = listOf(Seq3Lifeline("A", "A", setOf("A"), 0), Seq3Lifeline("B", "B", setOf("B"), 1)),
        messages = messages,
        fragments = fragments,
    )

    private fun fragment(
        id: String,
        ids: List<String> = emptyList(),
        refs: List<Seq3OccurrenceRef> = emptyList(),
        visibility: Seq3Visibility = Seq3Visibility.VISIBLE,
    ) = Seq3Fragment(id, Seq3FragmentKind.LOOP, id, ids, occurrenceRefs = refs, visibility = visibility)

    /** Five single-arrow messages a..e, drawn in that order. */
    private fun fiveMessages() = listOf(message("a", 1), message("b", 2), message("c", 3), message("d", 4), message("e", 5))

    private fun rowsInBox(document: Seq3Document, fragmentId: String): List<Seq3RowRef> {
        val layout = layoutSeq3(document, Seq3LayoutOptions(metrics))
        val box = layout.fragments.single { it.fragmentId == fragmentId }.box
        // The box reaches 49 units above its first spanned row (half a row plus the label strip), which is
        // beyond the 42-unit pitch to the previous row: only rows below the label strip are inside.
        return layout.rows.filterIsInstance<Seq3ArrowRow>()
            .filter { it.y >= box.y + LABEL_STRIP && it.y <= box.y + box.height }
            .map { Seq3RowRef(it.messageId, it.occurrenceEntryId) }
    }

    private fun held(document: Seq3Document, fragmentId: String): List<Seq3RowRef> {
        val fragment = document.fragments.single { it.id == fragmentId }
        return seq3DrawnRowRefs(document).filter { com.indagium.diagram3.fragmentContainsRow(fragment, it) }
    }

    @Test
    fun scatteredMembershipBecomesEveryDrawnRowFromFirstToLast() {
        // Members a, b and e: the box has always spanned a..e, so c and d were inside it without being members.
        val scattered = document(fiveMessages(), listOf(fragment("f", listOf("a", "b", "e"))))

        val normalized = normalizeSeq3FragmentSpans(scattered)

        assertEquals(listOf("a", "b", "c", "d", "e"), normalized.fragments.single().messageIds)
        assertEquals(rowsInBox(normalized, "f"), held(normalized, "f"), "membership is what the box draws")
        assertEquals(5, held(normalized, "f").size)
        assertEquals(5, seq3FragmentMessageCount(normalized, normalized.fragments.single()))
    }

    @Test
    fun aCleanDocumentIsReturnedUntouchedAndNormalizingIsIdempotent() {
        val clean = document(fiveMessages(), listOf(fragment("f", listOf("b", "c", "d"))))
        assertSame(clean, normalizeSeq3FragmentSpans(clean), "nothing to fix: the very same document")

        val scattered = document(fiveMessages(), listOf(fragment("f", listOf("a", "e"))))
        val once = normalizeSeq3FragmentSpans(scattered)
        assertSame(once, normalizeSeq3FragmentSpans(once))
    }

    @Test
    fun aRepeatedMessageOnlyPartlyInsideIsHeldByExactRowsAndOneFullyInsideByItsId() {
        // x has arrows at entries 1, 3 and 5; the fragment runs from x1 to x3, so x5 is outside.
        val document = document(
            listOf(message("x", 1, 3, 5), message("y", 2), message("z", 4)),
            listOf(fragment("f", ids = listOf("x"), refs = listOf(Seq3OccurrenceRef("x", 1), Seq3OccurrenceRef("x", 3)))),
        )

        val normalized = normalizeSeq3FragmentSpans(document)

        val result = normalized.fragments.single()
        assertEquals(listOf("x", "y"), result.messageIds)
        assertEquals(listOf(Seq3OccurrenceRef("x", 1), Seq3OccurrenceRef("x", 3)), result.occurrenceRefs, "x5 is outside, so x is held by exact rows")
        assertEquals(listOf(Seq3RowRef("x", 1), Seq3RowRef("y", 2), Seq3RowRef("x", 3)), held(normalized, "f"))
        assertEquals(rowsInBox(normalized, "f"), held(normalized, "f"))
    }

    @Test
    fun aNestedChildStaysInsideItsParentAndTheParentIsNotWidenedByTheChild() {
        // parent: b..d. child: scattered members c and e, so its raw span c..e pokes out past the parent's end.
        val parent = fragment("parent", listOf("b", "d"))
        val child = fragment("child", listOf("c", "e"))
        val document = document(fiveMessages(), listOf(parent, child))

        val normalized = normalizeSeq3FragmentSpans(document)

        val byId = normalized.fragments.associateBy { it.id }
        assertEquals(listOf("b", "c", "d"), byId.getValue("parent").messageIds, "the parent keeps its own span")
        assertEquals(listOf("c", "d"), byId.getValue("child").messageIds, "the child is clamped to the end of its parent")
        assertEquals(rowsInBox(normalized, "parent"), held(normalized, "parent"))
        assertEquals(rowsInBox(normalized, "child"), held(normalized, "child"))
    }

    @Test
    fun aChildInsideItsParentWithGapsIsFilledWithoutTouchingTheParent() {
        val parent = fragment("parent", listOf("a", "b", "c", "d", "e"))
        val child = fragment("child", listOf("b", "d"))
        val normalized = normalizeSeq3FragmentSpans(document(fiveMessages(), listOf(parent, child)))

        assertEquals(parent, normalized.fragments.first { it.id == "parent" })
        assertEquals(listOf("b", "c", "d"), normalized.fragments.first { it.id == "child" }.messageIds)
    }

    @Test
    fun hiddenRowsAreSkippedLikeTheLayoutDoesAndStayHeld() {
        // c is hidden: it is not drawn, so it is neither counted nor does it widen anything.
        val messages = listOf(message("a", 1), message("b", 2), message("c", 3, visibility = Seq3Visibility.HIDDEN), message("d", 4), message("e", 5))
        val document = document(messages, listOf(fragment("f", listOf("a", "c", "d"))))

        val normalized = normalizeSeq3FragmentSpans(document)

        val result = normalized.fragments.single()
        assertEquals(listOf("a", "b", "c", "d"), result.messageIds, "b is drawn between a and d; the hidden c it already held stays held")
        assertEquals(listOf(Seq3RowRef("a", 1), Seq3RowRef("b", 2), Seq3RowRef("d", 4)), held(normalized, "f"))
        assertEquals(3, seq3FragmentMessageCount(normalized, result), "the hidden arrow is not counted")
        assertEquals(rowsInBox(normalized, "f"), held(normalized, "f"))
    }

    @Test
    fun aHiddenOccurrenceRefIsKeptSoUnhidingPutsItBack() {
        val hiddenOccurrence = occurrence(3, "x").copy(visibility = Seq3Visibility.HIDDEN)
        val x = message("x", 1, 2).copy(occurrences = listOf(occurrence(1, "x"), occurrence(2, "x"), hiddenOccurrence))
        val document = document(
            listOf(x, message("y", 4)),
            listOf(fragment("f", ids = listOf("x", "y"), refs = listOf(Seq3OccurrenceRef("x", 1), Seq3OccurrenceRef("x", 3)))),
        )

        val normalized = normalizeSeq3FragmentSpans(document)

        val refs = normalized.fragments.single().occurrenceRefs
        assertTrue(Seq3OccurrenceRef("x", 3) in refs, "the hidden arrow's reference survives")
        assertTrue(Seq3OccurrenceRef("x", 2) in refs, "the drawn arrow between the members joined")
        assertEquals(listOf(Seq3RowRef("x", 1), Seq3RowRef("x", 2), Seq3RowRef("y", 4)), held(normalized, "f"))
    }

    @Test
    fun hiddenFragmentsAndFragmentsWithoutADrawnMemberAreLeftAlone() {
        val hidden = fragment("hidden", listOf("a", "e"), visibility = Seq3Visibility.HIDDEN)
        val empty = fragment("empty", listOf("gone"))
        val document = document(fiveMessages(), listOf(hidden, empty))
        assertSame(document, normalizeSeq3FragmentSpans(document))
    }

    @Test
    fun theCommandChokePointNormalizesAnOldScatteredDocumentOnTheNextEdit() {
        val scattered = document(fiveMessages(), listOf(fragment("f", listOf("a", "e"))))

        val result = applySeq3Command(scattered, Seq3Command.SetDocumentTheme("GRUVBOX"))

        assertTrue(result.applied)
        assertEquals(listOf("a", "b", "c", "d", "e"), result.document.fragments.single().messageIds)
    }

    @Test
    fun groupingScatteredSelectionDrawsAndHoldsTheSameRows() {
        val base = document(fiveMessages(), emptyList())
        val grouped = fragment("g", listOf("a", "c"))

        val result = applySeq3Command(base, Seq3Command.Bulk(setOf("a", "c"), com.indagium.diagram3.Seq3BulkAction.Group(grouped)))

        assertTrue(result.applied, result.reason)
        assertEquals(rowsInBox(result.document, "g"), held(result.document, "g"))
        assertEquals(3, seq3FragmentMessageCount(result.document, result.document.fragments.single()))
    }

    @Test
    fun movingARowOutOfTheMiddleOrTheEdgeKeepsBoxMembershipAndCountInAgreement() {
        val base = normalizeSeq3FragmentSpans(document(fiveMessages(), listOf(fragment("f", listOf("b", "c", "d")))))
        val start = base.fragments.single()
        assertEquals(3, seq3FragmentMessageCount(base, start))

        // Out of the middle (c to after e): b and d are both still members, so c leaves and the box shrinks to b..d.
        val middle = applySeq3Command(base, Seq3Command.MoveRow(Seq3RowRef("c", 3), Seq3InsertionPosition.AfterRow(Seq3RowRef("e", 5))))
        assertTrue(middle.applied, middle.reason)
        assertEquals(rowsInBox(middle.document, "f"), held(middle.document, "f"))
        assertEquals(held(middle.document, "f").size, seq3FragmentMessageCount(middle.document, middle.document.fragments.single()))
        assertEquals(2, held(middle.document, "f").size)

        // Out of the edge (d to after e): the box shrinks to b..c.
        val edge = applySeq3Command(base, Seq3Command.MoveRow(Seq3RowRef("d", 4), Seq3InsertionPosition.AfterRow(Seq3RowRef("e", 5))))
        assertTrue(edge.applied, edge.reason)
        assertEquals(rowsInBox(edge.document, "f"), held(edge.document, "f"))
        assertEquals(2, seq3FragmentMessageCount(edge.document, edge.document.fragments.single()))

        // And into the middle from outside: e to between b and c grows it by one.
        val joined = applySeq3Command(base, Seq3Command.MoveRow(Seq3RowRef("e", 5), Seq3InsertionPosition.BeforeRow(Seq3RowRef("c", 3))))
        assertTrue(joined.applied, joined.reason)
        assertEquals(rowsInBox(joined.document, "f"), held(joined.document, "f"))
        assertEquals(4, seq3FragmentMessageCount(joined.document, joined.document.fragments.single()))
    }

    @Test
    fun openingAnOldSavedDiagramNormalizesItsFragmentsAndLeavesItClean() {
        val scattered = document(fiveMessages(), listOf(fragment("f", listOf("a", "c", "e"))))
        val root = createTempDirectory("indagium-seq3-spans").toFile()
        try {
            val tab = mkTab("log", "sample.log", emptyList())
            val note = AnnBlock.Note("n1", encodeSeq3Note(scattered, Seq3Dialect.MERMAID))
            val state = AppState(
                File(root, "state.cache"),
                notesDir = File(root, "notes"),
                diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
            ).also {
                it.tabs = listOf(tab.copy(annotations = tab.annotations.copy(blocks = tab.annotations.blocks + note)))
                it.activateTab(tab.id)
            }

            val sessionId = assertNotNull(state.seq3Sessions.beginEdit("log", "n1"))

            val session = state.seq3Sessions.sessions.single { it.id == sessionId }
            assertEquals(listOf("a", "b", "c", "d", "e"), session.document.fragments.single().messageIds)
            assertEquals(false, session.dirty, "opening a diagram does not mark it edited")
            assertEquals(5, seq3FragmentMessageCount(session.document, session.document.fragments.single()))
            state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
            state.close()
        } finally {
            root.deleteRecursively()
        }
    }
}
