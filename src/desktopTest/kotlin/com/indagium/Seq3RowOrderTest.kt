package com.indagium

import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3AddResult
import com.indagium.diagram3.Seq3CustomMessageResult
import com.indagium.diagram3.Seq3CustomMessageSpec
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
import com.indagium.diagram3.Seq3Sort
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3RowRef
import com.indagium.diagram3.Seq3TextMetrics
import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Capture
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Filter
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.addSeq3CustomMessage
import com.indagium.diagram3.applySeq3Command
import com.indagium.diagram3.layoutSeq3
import com.indagium.diagram3.addSeq3MessageFromSelection
import com.indagium.diagram3.moveSeq3Message
import com.indagium.diagram3.seq3QueueExpandedRows
import com.indagium.diagram3.seq3QueueRows
import com.indagium.diagram3.seq3ResolveRowOrder
import com.indagium.diagram3.toMermaid
import com.indagium.diagram3.undoSeq3Command
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
