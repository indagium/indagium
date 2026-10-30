package com.indagium

import com.indagium.diagram3.Seq3AddResult
import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Capture
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3CustomMessageResult
import com.indagium.diagram3.Seq3CustomMessageSpec
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Filter
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
import com.indagium.diagram3.Seq3Sort
import com.indagium.diagram3.Seq3TextMetrics
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.addSeq3CustomMessage
import com.indagium.diagram3.addSeq3MessageFromSelection
import com.indagium.diagram3.applySeq3Command
import com.indagium.diagram3.fragmentContainsRow
import com.indagium.diagram3.layoutSeq3
import com.indagium.diagram3.moveSeq3Message
import com.indagium.diagram3.seq3DrawnRowRefs
import com.indagium.diagram3.seq3QueueExpandedRows
import com.indagium.diagram3.seq3QueueRows
import com.indagium.diagram3.seq3ResolveRowOrder
import com.indagium.diagram3.toMermaid
import com.indagium.diagram3.undoSeq3Command
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.seq3CanvasRowRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Seq3RowOrderTest {
    private companion object {
        const val FIRST_TIMESTAMP = 100L
        const val MIDDLE_TIMESTAMP = 200L
        const val LAST_TIMESTAMP = 300L
        const val HIDDEN_TIMESTAMP = 150L
        const val OUTSIDE_TIMESTAMP = 400L
    }

    private fun occurrence(id: Int, label: String, timestamp: Long) = Seq3Occurrence(
        entryId = id,
        timestampMillis = timestamp,
        rawTimestamp = "00:00:00.${timestamp.toString().padStart(3, '0')}",
        pid = 1,
        tid = 1,
        level = 'I',
        text = label,
        captureValues = mapOf("n" to label),
        elapsedMillis = timestamp,
    )

    private fun document(): Seq3Document {
        val repeated = Seq3Message(
            id = "m1",
            match = Seq3Match("A", "row {n}", listOf(Seq3Capture("n", Seq3CaptureSource.AUTHOR))),
            fromLifelineId = "A",
            toLifelineId = "B",
            labelTemplate = "row {n}",
            repeat = Seq3Repeat.EVERY,
            occurrences = listOf(
                occurrence(1, "first", FIRST_TIMESTAMP),
                occurrence(3, "last", LAST_TIMESTAMP),
            ),
        )
        val middle = Seq3Message(
            id = "m2",
            match = Seq3Match("A", "middle"),
            fromLifelineId = "A",
            toLifelineId = "B",
            labelTemplate = "middle",
            occurrences = listOf(occurrence(2, "middle", MIDDLE_TIMESTAMP)),
        )
        return Seq3Document(
            lifelines = listOf(Seq3Lifeline("A", "A", setOf("A"), 0), Seq3Lifeline("B", "B", setOf("B"), 1)),
            messages = listOf(repeated, middle),
        )
    }

    private val metrics = object : Seq3TextMetrics {
        override fun width(role: Seq3FontRole, text: String): Double = text.length * 7.0

        override fun lineHeight(role: Seq3FontRole): Double = 16.0
    }

    private fun renderedRows(document: Seq3Document): List<Seq3RowRef> =
        layoutSeq3(document, Seq3LayoutOptions(metrics)).rows
            .filterIsInstance<Seq3ArrowRow>()
            .map { Seq3RowRef(it.messageId, it.occurrenceEntryId) }

    @Test
    fun exactOccurrenceMoveIsSharedByCanvasEmitterAndQueueExpansion() {
        val original = document()
        assertEquals(
            listOf(Seq3RowRef("m1", 1), Seq3RowRef("m2", 2), Seq3RowRef("m1", 3)),
            renderedRows(original),
        )
        val before = original.messages.single { it.id == "m1" }.occurrences
        val result = applySeq3Command(
            original,
            Seq3Command.MoveRow(
                Seq3RowRef("m1", 3),
                Seq3InsertionPosition.BeforeRow(Seq3RowRef("m2", 2)),
            ),
        )
        assertTrue(result.applied)
        assertEquals(
            listOf(Seq3RowRef("m1", 1), Seq3RowRef("m1", 3), Seq3RowRef("m2", 2)),
            renderedRows(result.document),
        )
        assertEquals(before, result.document.messages.single { it.id == "m1" }.occurrences)
        assertEquals(listOf("row first", "row last", "middle"), result.document.toMermaid().lineSequence()
            .filter { "->>" in it }
            .map { it.substringAfter(": ") }
            .toList())

        val manual = result.document
        assertEquals(
            listOf(Seq3RowRef("m1", 1), Seq3RowRef("m1", 3), Seq3RowRef("m2", 2)),
            seq3QueueExpandedRows(manual),
        )
    }

    @Test
    fun collapsedEvidenceSummaryMoveMovesTheWholeParentInOneUndoableCommand() {
        val original = document().let { source ->
            source.copy(messages = source.messages.map { message ->
                if (message.id == "m1") message.copy(repeat = Seq3Repeat.COLLAPSE_ABOVE, repeatThreshold = 1) else message
            })
        }
        assertEquals(
            listOf(Seq3RowRef("m1", 1), Seq3RowRef("m2", 2)),
            renderedRows(original),
            "the repeated parent renders once while both evidence rows remain in the document",
        )
        val beforeOccurrences = original.messages.associate { it.id to it.occurrences }
        val result = applySeq3Command(
            original,
            Seq3Command.MoveRow(Seq3RowRef("m1"), Seq3InsertionPosition.End),
        )

        assertTrue(result.applied)
        assertEquals(listOf("m2", "m1"), result.document.messages.map { it.id })
        assertEquals(
            listOf(Seq3RowRef("m2", 2), Seq3RowRef("m1", 1), Seq3RowRef("m1", 3)),
            result.document.rowOrder,
        )
        assertEquals(beforeOccurrences, result.document.messages.associate { it.id to it.occurrences })
        assertEquals(original, undoSeq3Command(requireNotNull(result.undo)))
    }

    @Test
    fun queueParentsUseTheirFirstRepresentedRowAndKeepOccurrenceChildren() {
        val manual = document().copy(rowOrder = listOf(
            Seq3RowRef("m2", 2),
            Seq3RowRef("m1", 1),
            Seq3RowRef("m1", 3),
        ))
        assertEquals(listOf("m2", "m1"), seq3QueueRows(manual, Seq3Filter.ALL, sort = Seq3Sort.LOG_ORDER).map { it.id })
        assertEquals(listOf(Seq3RowRef("m2", 2), Seq3RowRef("m1", 1), Seq3RowRef("m1", 3)), seq3QueueExpandedRows(manual))
    }

    @Test
    fun staleOccurrenceReferencesDoNotReorderNullSummaryRows() {
        val manual = document().copy(rowOrder = listOf(
            Seq3RowRef("m1", 999),
            Seq3RowRef("m2", 2),
            Seq3RowRef("m1", 1),
            Seq3RowRef("m1", 3),
        ))
        assertEquals(
            listOf(Seq3RowRef("m2", 2), Seq3RowRef("m1")),
            seq3ResolveRowOrder(manual, listOf(Seq3RowRef("m1"), Seq3RowRef("m2", 2))),
        )
    }

    @Test
    fun wholeParentMoveWorksForGeneratedEvidenceAndKeepsItsRowsTogether() {
        val original = document()
        val moved = assertIs<com.indagium.diagram3.Seq3MessageEditResult.Updated>(
            moveSeq3Message(original, "m1", Seq3InsertionPosition.End),
        ).document
        assertEquals(listOf("m2", "m1"), moved.messages.map { it.id })
        assertEquals(listOf(Seq3RowRef("m2", 2), Seq3RowRef("m1", 1), Seq3RowRef("m1", 3)), moved.rowOrder)
        assertEquals(original.messages.associate { it.id to it.occurrences }, moved.messages.associate { it.id to it.occurrences })
    }

    @Test
    fun customRowsInsertAtAnExactOccurrenceOrAfterTheParentsLastOccurrence() {
        val original = document()
        val afterExact = assertIs<Seq3CustomMessageResult.Added>(
            addSeq3CustomMessage(
                original,
                Seq3CustomMessageSpec("A", "B", "custom", position = Seq3InsertionPosition.AfterRow(Seq3RowRef("m1", 1))),
            ),
        )
        assertEquals(
            listOf(Seq3RowRef("m1", 1), Seq3RowRef(afterExact.newMessageId), Seq3RowRef("m2", 2), Seq3RowRef("m1", 3)),
            afterExact.document.rowOrder,
        )

        val afterParent = assertIs<Seq3CustomMessageResult.Added>(
            addSeq3CustomMessage(
                original,
                Seq3CustomMessageSpec("A", "B", "custom", position = Seq3InsertionPosition.AfterMessage("m1")),
            ),
        )
        assertEquals(
            listOf(Seq3RowRef("m1", 1), Seq3RowRef("m2", 2), Seq3RowRef("m1", 3), Seq3RowRef(afterParent.newMessageId)),
            afterParent.document.rowOrder,
        )
    }

    @Test
    fun selectedLogRowsUseTheSameExactInsertionPointInOneAddResult() {
        val original = document()
        val selected = listOf(LogEntry(4, "00:00:00.250", LogLevel.I, "A", "selected work"))
        val added = assertIs<Seq3AddResult.Added>(
            addSeq3MessageFromSelection(original, selected, Seq3InsertionPosition.AfterRow(Seq3RowRef("m2", 2))),
        )
        assertEquals(
            listOf(Seq3RowRef("m1", 1), Seq3RowRef("m2", 2), Seq3RowRef(added.newMessageId, 4), Seq3RowRef("m1", 3)),
            added.document.rowOrder,
        )
    }

    @Test
    fun fragmentMembershipIsInheritedOnlyAtAnInteriorBoundaryIncludingNestedGroups() {
        val original = document().copy(
            fragments = listOf(
                Seq3Fragment("outer", Seq3FragmentKind.GROUP, "outer", listOf("m1", "m2")),
                Seq3Fragment("inner", Seq3FragmentKind.OPT, "inner", listOf("m1", "m2"), occurrenceRefs = listOf(
                    Seq3OccurrenceRef("m1", 1), Seq3OccurrenceRef("m2", 2),
                )),
            ),
        )
        val inside = assertIs<Seq3CustomMessageResult.Added>(
            addSeq3CustomMessage(original, Seq3CustomMessageSpec("A", "B", "inside", position = Seq3InsertionPosition.AfterRow(Seq3RowRef("m1", 1)))),
        )
        assertTrue(inside.document.fragments.all { inside.newMessageId in it.messageIds })

        val betweenParentRows = assertIs<Seq3CustomMessageResult.Added>(
            addSeq3CustomMessage(original, Seq3CustomMessageSpec("A", "B", "outside", position = Seq3InsertionPosition.AfterMessage("m2"))),
        )
        assertEquals(setOf("outer"), betweenParentRows.document.fragments.filter { betweenParentRows.newMessageId in it.messageIds }.map { it.id }.toSet())

        val afterFinalRow = assertIs<Seq3CustomMessageResult.Added>(
            addSeq3CustomMessage(original, Seq3CustomMessageSpec("A", "B", "outside", position = Seq3InsertionPosition.AfterMessage("m1"))),
        )
        assertTrue(afterFinalRow.document.fragments.none { afterFinalRow.newMessageId in it.messageIds })
    }

    @Test
    fun atIndexFragmentInheritanceMapsAcrossHiddenParents() {
        val base = document()
        val hidden = Seq3Message(
            id = "hidden",
            match = Seq3Match("A", "hidden"),
            fromLifelineId = "A",
            toLifelineId = "B",
            labelTemplate = "hidden",
            visibility = Seq3Visibility.HIDDEN,
            occurrences = listOf(occurrence(4, "hidden", HIDDEN_TIMESTAMP)),
        )
        val outside = Seq3Message(
            id = "outside",
            match = Seq3Match("A", "outside"),
            fromLifelineId = "A",
            toLifelineId = "B",
            labelTemplate = "outside",
            occurrences = listOf(occurrence(5, "outside", OUTSIDE_TIMESTAMP)),
        )
        val grouped = base.copy(
            messages = listOf(base.messages[0], hidden, base.messages[1], outside),
            fragments = listOf(Seq3Fragment("outer", Seq3FragmentKind.GROUP, "outer", listOf("m1", "m2"))),
        )

        val inserted = assertIs<Seq3CustomMessageResult.Added>(
            addSeq3CustomMessage(
                grouped,
                Seq3CustomMessageSpec("A", "B", "inside", position = Seq3InsertionPosition.AtIndex(2)),
            ),
        )
        assertTrue(inserted.newMessageId in inserted.document.fragments.single().messageIds)
    }

    // ── Fragment membership follows the drop ─────────────────────────────────────────────────────
    //
    // Chronological rows of `interleaved()`: a1 x2 b3 x4 c5 x6 d7 (x repeats three times).

    private fun message(
        id: String,
        entries: List<Pair<Int, Long>>,
        repeat: Seq3Repeat = Seq3Repeat.EVERY,
        repeatThreshold: Int = 3,
    ) = Seq3Message(
        id = id,
        match = Seq3Match("A", id),
        fromLifelineId = "A",
        toLifelineId = "B",
        labelTemplate = id,
        repeat = repeat,
        repeatThreshold = repeatThreshold,
        occurrences = entries.map { (entryId, timestamp) -> occurrence(entryId, id, timestamp) },
    )

    @Suppress("MagicNumber") // Fixture entry ids and timestamps, chosen to interleave the messages.
    private fun interleaved(vararg fragments: Seq3Fragment): Seq3Document = Seq3Document(
        lifelines = listOf(Seq3Lifeline("A", "A", setOf("A"), 0), Seq3Lifeline("B", "B", setOf("B"), 1)),
        messages = listOf(
            message("a", listOf(1 to 100L)),
            message("x", listOf(2 to 200L, 4 to 400L, 6 to 600L)),
            message("b", listOf(3 to 300L)),
            message("c", listOf(5 to 500L)),
            message("d", listOf(7 to 700L)),
        ),
        fragments = fragments.toList(),
    )

    /** The drawn arrows whose vertical centre lies inside the fragment's laid-out box. */
    private fun boxRows(document: Seq3Document, fragmentId: String): List<Seq3RowRef> {
        val layout = layoutSeq3(document, Seq3LayoutOptions(metrics))
        val box = layout.fragments.single { it.fragmentId == fragmentId }.box
        return layout.rows.filterIsInstance<Seq3ArrowRow>()
            .filter { it.y >= box.y && it.y <= box.y + box.height }
            .map { Seq3RowRef(it.messageId, it.occurrenceEntryId) }
    }

    private fun move(document: Seq3Document, command: Seq3Command): Seq3Document {
        val result = applySeq3Command(document, command)
        assertTrue(result.applied, result.reason)
        return result.document
    }

    private fun fragment(id: String, messageIds: List<String>, refs: List<Seq3OccurrenceRef> = emptyList()) =
        Seq3Fragment(id, Seq3FragmentKind.LOOP, id, messageIds, occurrenceRefs = refs)

    @Test
    fun movingTheLastMemberOutOfAFragmentShrinksItsBox() {
        val original = interleaved(fragment("f", listOf("a", "b", "c")))
        assertEquals(
            listOf(Seq3RowRef("a", 1), Seq3RowRef("x", 2), Seq3RowRef("b", 3), Seq3RowRef("x", 4), Seq3RowRef("c", 5)),
            boxRows(original, "f"),
        )
        val moved = move(original, Seq3Command.MoveRow(Seq3RowRef("c", 5), Seq3InsertionPosition.AfterRow(Seq3RowRef("d", 7))))
        assertEquals(listOf("a", "b"), moved.fragments.single().messageIds)
        assertEquals(listOf(Seq3RowRef("a", 1), Seq3RowRef("x", 2), Seq3RowRef("b", 3)), boxRows(moved, "f"))
        assertEquals(Seq3RowRef("b", 3), boxRows(moved, "f").last())
    }

    @Test
    fun movingARowBetweenTwoMembersAddsItToTheFragment() {
        // Marquee-style fragment: only x4 of the repeated message x is inside.
        val original = interleaved(fragment("f", listOf("a", "b", "c", "x"), listOf(Seq3OccurrenceRef("x", 4))))
        assertEquals(
            listOf(Seq3RowRef("a", 1), Seq3RowRef("x", 2), Seq3RowRef("b", 3), Seq3RowRef("x", 4), Seq3RowRef("c", 5)),
            boxRows(original, "f"),
        )
        // d is a whole message, so joining by template id is enough.
        val template = move(original, Seq3Command.MoveRow(Seq3RowRef("d", 7), Seq3InsertionPosition.BeforeRow(Seq3RowRef("x", 4))))
        assertEquals(listOf("a", "b", "c", "d", "x"), template.fragments.single().messageIds.sortedBy { it })
        assertTrue(fragmentContainsRow(template.fragments.single(), Seq3RowRef("d", 7)))
        assertEquals(Seq3RowRef("c", 5), boxRows(template, "f").last())
        assertTrue(Seq3RowRef("d", 7) in boxRows(template, "f"))

        // x6 is one occurrence of a message the fragment holds by exact refs: it gets its own ref.
        val exact = move(original, Seq3Command.MoveRow(Seq3RowRef("x", 6), Seq3InsertionPosition.BeforeRow(Seq3RowRef("x", 4))))
        assertEquals(listOf(Seq3OccurrenceRef("x", 4), Seq3OccurrenceRef("x", 6)), exact.fragments.single().occurrenceRefs)
        assertTrue(Seq3RowRef("x", 6) in boxRows(exact, "f"))
        assertFalse(fragmentContainsRow(exact.fragments.single(), Seq3RowRef("x", 2)))
    }

    @Test
    fun aTemplateLevelMemberMovingOneOccurrenceOutMaterializesRefsForTheOthers() {
        val original = interleaved(fragment("f", listOf("a", "x", "b")))
        assertEquals(Seq3RowRef("x", 6), boxRows(original, "f").last())
        val moved = move(original, Seq3Command.MoveRow(Seq3RowRef("x", 6), Seq3InsertionPosition.AfterRow(Seq3RowRef("d", 7))))
        val result = moved.fragments.single()
        assertEquals(listOf(Seq3OccurrenceRef("x", 2), Seq3OccurrenceRef("x", 4)), result.occurrenceRefs)
        assertTrue("x" in result.messageIds)
        assertTrue(fragmentContainsRow(result, Seq3RowRef("x", 2)))
        assertTrue(fragmentContainsRow(result, Seq3RowRef("x", 4)))
        assertFalse(fragmentContainsRow(result, Seq3RowRef("x", 6)))
        assertEquals(
            listOf(Seq3RowRef("a", 1), Seq3RowRef("x", 2), Seq3RowRef("b", 3), Seq3RowRef("x", 4)),
            boxRows(moved, "f"),
        )
    }

    @Test
    fun aFragmentsOnlyMemberMovingOutKeepsTheFragmentAndTravelsWithIt() {
        val original = interleaved(fragment("f", listOf("d")))
        val moved = move(original, Seq3Command.MoveRow(Seq3RowRef("d", 7), Seq3InsertionPosition.Start))
        assertEquals(original.fragments, moved.fragments)
        assertEquals(listOf(Seq3RowRef("d", 7)), boxRows(moved, "f"))
    }

    @Test
    fun aWholeTemplateMoveAcrossAFragmentBoundaryDoesNotStretchTheBox() {
        val original = interleaved(fragment("f", listOf("a", "x", "b")))
        val moved = move(original, Seq3Command.MoveMessage("x", Seq3InsertionPosition.End))
        assertEquals(listOf("a", "b"), moved.fragments.single().messageIds)
        assertTrue(moved.fragments.single().occurrenceRefs.isEmpty())
        assertEquals(listOf(Seq3RowRef("a", 1), Seq3RowRef("b", 3)), boxRows(moved, "f"))

        // Dropping the whole template between two members pulls all its rows in at once.
        val joining = interleaved(fragment("f", listOf("a", "b", "c")))
        val inside = move(joining, Seq3Command.MoveMessage("x", Seq3InsertionPosition.BeforeMessage("b")))
        assertTrue("x" in inside.fragments.single().messageIds)
        assertTrue(inside.fragments.single().occurrenceRefs.isEmpty())
        assertEquals(
            listOf(
                Seq3RowRef("a", 1), Seq3RowRef("x", 2), Seq3RowRef("x", 4), Seq3RowRef("x", 6),
                Seq3RowRef("b", 3), Seq3RowRef("c", 5),
            ),
            boxRows(inside, "f"),
        )
    }

    @Test
    fun resetRowOrderRestoresChronologyAndUndoesCleanly() {
        val original = interleaved()
        assertFalse(applySeq3Command(original, Seq3Command.ResetRowOrder).applied, "nothing to reset")

        val ordered = move(original, Seq3Command.MoveRow(Seq3RowRef("d", 7), Seq3InsertionPosition.Start))
        assertTrue(ordered.rowOrder.isNotEmpty())
        assertEquals(Seq3RowRef("d", 7), renderedRows(ordered).first())

        val reset = applySeq3Command(ordered, Seq3Command.ResetRowOrder)
        assertTrue(reset.applied)
        assertTrue(reset.document.rowOrder.isEmpty())
        assertEquals(renderedRows(original), renderedRows(reset.document))
        assertEquals("Reset message order", reset.undo?.label)
        assertEquals(ordered, undoSeq3Command(requireNotNull(reset.undo)))
    }

    @Test
    fun drawnRowRefsMatchTheLayoutRowsIncludingFirstLastAndCollapsedMessages() {
        val base = interleaved()
        val document = base.copy(
            messages = base.messages + listOf(
                message("firstLast", listOf(10 to 150L, 12 to 250L, 14 to 350L), repeat = Seq3Repeat.FIRST_LAST),
                message("collapsed", listOf(11 to 160L, 13 to 260L), repeat = Seq3Repeat.COLLAPSE_ABOVE, repeatThreshold = 1),
            ),
        )

        fun layoutRefs(doc: Seq3Document) = layoutSeq3(doc, Seq3LayoutOptions(metrics)).rows.map { seq3CanvasRowRef(doc, it) }

        val chronological = layoutRefs(document)
        assertTrue(Seq3RowRef("firstLast") in chronological, "the FIRST_LAST elision is a summary row")
        assertTrue(Seq3RowRef("collapsed") in chronological, "the collapsed message is a summary row")
        assertEquals(chronological, seq3DrawnRowRefs(document))

        val manual = move(
            document,
            Seq3Command.MoveRow(Seq3RowRef("d", 7), Seq3InsertionPosition.BeforeRow(Seq3RowRef("firstLast", 10))),
        )
        assertEquals(layoutRefs(manual), seq3DrawnRowRefs(manual))
        val movedSummary = move(
            manual,
            Seq3Command.MoveRow(Seq3RowRef("collapsed"), Seq3InsertionPosition.End),
        )
        assertEquals(layoutRefs(movedSummary), seq3DrawnRowRefs(movedSummary))
        assertEquals(Seq3RowRef("collapsed"), seq3DrawnRowRefs(movedSummary).last())
    }
}
