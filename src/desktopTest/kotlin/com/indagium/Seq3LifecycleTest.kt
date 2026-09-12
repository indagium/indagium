package com.indagium

import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3CustomMessageSpec
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3InsertionPosition
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3LifecycleViolationKind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3RegenDecision
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.applySeq3Command
import com.indagium.diagram3.reviewSeq3Regeneration
import com.indagium.diagram3.seq3LifecycleViolations
import com.indagium.diagram3.withSeq3RegenDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Seq3LifecycleTest {
    private fun lifelines() = listOf(
        Seq3Lifeline("A", "A", setOf("A"), 0),
        Seq3Lifeline("B", "B", setOf("B"), 1),
    )

    private fun lifelinesWithC() = lifelines() + Seq3Lifeline("C", "C", setOf("C"), 2)

    private fun message(id: String, kind: Seq3Kind, from: String, to: String, timestamp: Long) = Seq3Message(
        id = id,
        match = Seq3Match(from, id),
        fromLifelineId = from,
        toLifelineId = to,
        labelTemplate = id,
        kind = kind,
        occurrences = listOf(Seq3Occurrence(id.hashCode(), timestamp, "10:00:00.000", 1, 1, 'I', id)),
    )

    private fun legalDocument() = Seq3Document(
        lifelines = lifelines(),
        messages = listOf(
            message("create", Seq3Kind.CREATE, "A", "B", 10),
            message("work", Seq3Kind.CALL, "B", "A", 20),
            message("destroy", Seq3Kind.DESTROY, "A", "B", 30),
        ),
    )

    @Test
    fun validatorUsesVisibleChronologicalInteractionsForALegalLifecycle() {
        assertTrue(seq3LifecycleViolations(legalDocument()).isEmpty())
    }

    @Test
    fun everyVisibleCreateEmissionCountsEvenWhenItBelongsToOneQueueMessage() {
        val repeatedCreate = message("create", Seq3Kind.CREATE, "A", "B", 10).copy(
            repeat = Seq3Repeat.EVERY,
            occurrences = listOf(
                Seq3Occurrence(1, 10, "10:00:00.010", 1, 1, 'I', "first"),
                Seq3Occurrence(2, 20, "10:00:00.020", 1, 1, 'I', "second"),
            ),
        )
        val violations = seq3LifecycleViolations(Seq3Document(lifelines = lifelines(), messages = listOf(repeatedCreate)))

        assertTrue(violations.any {
            it.kind == Seq3LifecycleViolationKind.DUPLICATE_CREATE && it.targetLifelineId == "B" && it.count == 2
        })
    }

    @Test
    fun lateCreateKindChangeIsRejectedAtomically() {
        val document = Seq3Document(
            lifelines = lifelines(),
            messages = listOf(
                message("before", Seq3Kind.CALL, "A", "B", 10),
                message("candidate", Seq3Kind.CALL, "A", "B", 20),
            ),
        )

        val result = applySeq3Command(
            document,
            Seq3Command.Bulk(setOf("candidate"), Seq3BulkAction.SetKind(Seq3Kind.CREATE)),
        )

        assertFalse(result.applied)
        assertEquals(document, result.document, "a lifecycle rejection must not partially edit the document")
        assertTrue(result.reason!!.contains("CREATE for B must be its first visible interaction"))
    }

    @Test
    fun customInsertionAndReplacementCannotIntroduceDuplicateCreate() {
        val document = legalDocument().copy(messages = legalDocument().messages.dropLast(1))
        val custom = applySeq3Command(
            document,
            Seq3Command.AddCustomMessage(
                Seq3CustomMessageSpec("A", "B", "again", timestampMillis = 25, kind = Seq3Kind.CREATE),
            ),
        )
        assertFalse(custom.applied)
        assertEquals(document, custom.document)
        assertTrue(custom.reason!!.contains("visible CREATE"))

        val replacement = document.copy(messages = document.messages + message("again", Seq3Kind.CREATE, "A", "B", 25))
        val replaced = applySeq3Command(document, Seq3Command.ReplaceDocument(replacement))
        assertFalse(replaced.applied)
        assertEquals(document, replaced.document)
    }

    @Test
    fun timestampAndVisibilityEditsAreCheckedAtTheSameCommandBoundary() {
        val timestampDocument = legalDocument().copy(
            messages = legalDocument().messages.map {
                if (it.id == "destroy") it.copy(authoring = Seq3Authoring.EDITED, occurrences = emptyList(), manualTimestampMillis = 30) else it
            },
        )
        val movedDestroy = applySeq3Command(timestampDocument, Seq3Command.SetMessageTimestamp("destroy", 15))
        assertFalse(movedDestroy.applied)
        assertEquals(timestampDocument, movedDestroy.document, "an early destroy must leave the entire document unchanged")
        assertTrue(movedDestroy.reason!!.contains("DESTROY for B must be its last visible interaction"))

        val hiddenLegacyInteraction = legalDocument().copy(
            messages = legalDocument().messages.map {
                if (it.id == "work") it.copy(visibility = Seq3Visibility.HIDDEN) else it
            },
        )
        val shown = applySeq3Command(
            hiddenLegacyInteraction,
            Seq3Command.Bulk(setOf("work"), Seq3BulkAction.Show),
        )
        assertTrue(shown.applied, "showing the existing legal middle interaction is still legal")

        val hiddenBeforeCreate = Seq3Document(
            lifelines = lifelines(),
            messages = listOf(
                message("before", Seq3Kind.CALL, "A", "B", 10).copy(visibility = Seq3Visibility.HIDDEN),
                message("create", Seq3Kind.CREATE, "A", "B", 20),
            ),
        )
        val showBefore = applySeq3Command(hiddenBeforeCreate, Seq3Command.Bulk(setOf("before"), Seq3BulkAction.Show))
        assertFalse(showBefore.applied)
        assertTrue(showBefore.reason!!.contains("CREATE for B must be its first visible interaction"))
    }

    @Test
    fun endpointChangesCannotAddAnInteractionAfterDestroy() {
        val document = Seq3Document(
            lifelines = lifelinesWithC(),
            messages = listOf(
                message("create", Seq3Kind.CREATE, "A", "B", 10),
                message("destroy", Seq3Kind.DESTROY, "A", "B", 20),
                message("after", Seq3Kind.CALL, "A", "C", 30),
            ),
        )
        assertTrue(seq3LifecycleViolations(document).isEmpty(), "destroy is initially B's last interaction")

        val result = applySeq3Command(
            document,
            Seq3Command.Bulk(setOf("after"), Seq3BulkAction.SetTo("B")),
        )

        assertFalse(result.applied)
        assertEquals(document, result.document, "endpoint edits must be rejected as one atomic command")
        assertTrue(result.reason!!.contains("DESTROY for B must be its last visible interaction"))
    }

    @Test
    fun reorderingCustomMessagesCannotMoveDestroyBeforeItsLastInteraction() {
        fun custom(id: String, kind: Seq3Kind, from: String, to: String) = Seq3Message(
            id = id,
            match = Seq3Match(from, id),
            fromLifelineId = from,
            toLifelineId = to,
            labelTemplate = id,
            kind = kind,
            manualTimestampMillis = 100,
        )
        val document = Seq3Document(
            lifelines = lifelines(),
            // Equal author timestamps deliberately make the durable queue order the visible
            // chronology tie-breaker.  In this original order, DESTROY is correctly last.
            messages = listOf(
                custom("create", Seq3Kind.CREATE, "A", "B"),
                custom("work", Seq3Kind.CALL, "B", "A"),
                custom("destroy", Seq3Kind.DESTROY, "A", "B"),
            ),
        )
        assertTrue(seq3LifecycleViolations(document).isEmpty())

        val result = applySeq3Command(
            document,
            // Moving the middle interaction after DESTROY leaves CREATE first while making
            // DESTROY visibly early, isolating the destroy-last rule from CREATE's own rule.
            Seq3Command.MoveMessage("work", Seq3InsertionPosition.End),
        )

        assertFalse(result.applied)
        assertEquals(document, result.document, "a rejected order change must not move the queue row")
        assertTrue(result.reason!!.contains("DESTROY for B must be its last visible interaction"))
    }

    @Test
    fun replacementAndRegenerationCannotIntroduceAnEarlyDestroy() {
        val document = legalDocument()
        val invalidReplacement = message("work-replacement", Seq3Kind.CALL, "B", "A", 40)

        val replaced = applySeq3Command(
            document,
            Seq3Command.ReplaceMessage("work", invalidReplacement),
        )
        assertFalse(replaced.applied)
        assertEquals(document, replaced.document, "ReplaceMessage must reject its complete candidate atomically")
        assertTrue(replaced.reason!!.contains("DESTROY for B must be its last visible interaction"))

        // The regenerated row changes its endpoint as well as retaining the original evidence id,
        // so the reviewed-regeneration matcher presents it as CHANGED and accepts this proposal.
        val fresh = document.copy(
            messages = document.messages.map { current ->
                if (current.id == "work") message("fresh-work", Seq3Kind.CALL, "A", "B", 40) else current
            },
        )
        var review = reviewSeq3Regeneration(document, fresh)
        review = withSeq3RegenDecision(review, "cur:work", Seq3RegenDecision.ACCEPT)

        val regenerated = applySeq3Command(document, Seq3Command.ApplyRegeneration(review))
        assertFalse(regenerated.applied)
        assertEquals(document, regenerated.document, "regeneration must not partially apply invalid reviewed rows")
        assertTrue(regenerated.reason!!.contains("DESTROY for B must be its last visible interaction"))
    }

    @Test
    fun legacyViolationDoesNotBlockAnUnrelatedCommand() {
        val legacy = Seq3Document(
            lifelines = lifelines(),
            messages = listOf(
                message("before", Seq3Kind.CALL, "A", "B", 10),
                message("late-create", Seq3Kind.CREATE, "A", "B", 20),
            ),
        )
        assertTrue(seq3LifecycleViolations(legacy).any { it.kind == Seq3LifecycleViolationKind.CREATE_NOT_FIRST_INTERACTION })

        val result = applySeq3Command(legacy, Seq3Command.RenameLifeline("A", "Renamed A"))

        assertTrue(result.applied, "unchanged legacy lifecycle violations must not lock the document")
        assertEquals("Renamed A", result.document.lifelines.single { it.id == "A" }.name)
    }
}
