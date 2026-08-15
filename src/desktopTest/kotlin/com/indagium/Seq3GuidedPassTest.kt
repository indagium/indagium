package com.indagium

import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3State
import com.indagium.diagram3.advanceSeq3GuidedPass
import com.indagium.diagram3.applySeq3GuidedSelfCall
import com.indagium.diagram3.applySeq3GuidedTarget
import com.indagium.diagram3.seq3GuidedContext
import com.indagium.diagram3.suggestSeq3Target
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.SEQ3_MAX_KEYED_LIFELINES
import com.indagium.ui.newSeq3LifelineName
import com.indagium.ui.seq3GuidedLifelineForKey
import com.indagium.ui.startSeq3GuidedPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guided pass (design spec §05) — the UI-side mapping helpers plus the engine transitions the
 *  mode drives. Composition itself is untested here, matching the rest of this repo. */
class Seq3GuidedPassTest {
    private fun lifeline(id: String, ordinal: Int) = Seq3Lifeline(id, id, setOf(id), ordinal)

    private fun occurrence(entryId: Int, tid: Int = 7) =
        Seq3Occurrence(entryId, entryId * 10L, "14:22:0$entryId.000", 100, tid, 'I', "msg $entryId")

    private fun message(id: String, from: String, to: String?, occurrences: List<Seq3Occurrence>) = Seq3Message(
        id = id,
        match = Seq3Match(from, "template $id"),
        fromLifelineId = from,
        toLifelineId = to,
        labelTemplate = "template $id",
        occurrences = occurrences,
    )

    private fun document(vararg messages: Seq3Message) = Seq3Document(
        lifelines = listOf(lifeline("A", 0), lifeline("B", 1), lifeline("C", 2)),
        messages = messages.toList(),
    )

    @Test
    fun startsAPassOverEveryNeedsTargetMessageInLogOrder() {
        val doc = document(
            message("m1", "A", "B", listOf(occurrence(1))),
            message("m2", "A", null, listOf(occurrence(2))),
            message("m3", "B", null, listOf(occurrence(3))),
        )
        val pass = assertNotNull(startSeq3GuidedPass(doc))
        assertEquals(listOf("m2", "m3"), pass.messageIds)
        assertEquals(2, pass.totalAtStart)
        assertEquals("m2", pass.currentMessageId)
    }

    @Test
    fun thereIsNoSuchThingAsAnEmptyPass() {
        // The amber banner that launches the pass disappears at zero, so entering one with nothing
        // unresolved must be unrepresentable rather than an empty screen.
        assertNull(startSeq3GuidedPass(document(message("m1", "A", "B", listOf(occurrence(1))))))
    }

    @Test
    fun progressCountsCompletedAgainstTheTotalAtStart() {
        val doc = document(
            message("m1", "A", null, listOf(occurrence(1))),
            message("m2", "B", null, listOf(occurrence(2))),
        )
        val pass = assertNotNull(startSeq3GuidedPass(doc))
        assertEquals(0, pass.completedCount)
        val resolved = applySeq3GuidedTarget(doc, "m1", "C")
        val next = assertNotNull(advanceSeq3GuidedPass(resolved, pass))
        assertEquals(1, next.completedCount)
        assertEquals(2, next.totalAtStart, "total must stay stable as the queue shrinks")
    }

    @Test
    fun acceptingATargetMarksTheMessageEditedSoRegenerationWontUndoIt() {
        val doc = document(message("m1", "A", null, listOf(occurrence(1))))
        val after = applySeq3GuidedTarget(doc, "m1", "B")
        val message = after.messages.single()
        assertEquals("B", message.toLifelineId)
        assertEquals(Seq3Authoring.EDITED, message.authoring)
        assertEquals(Seq3State.EDITED, message.state)
    }

    @Test
    fun applyToAllCoversEveryOccurrence() {
        val doc = document(message("m1", "A", null, listOf(occurrence(1), occurrence(2), occurrence(3))))
        val after = applySeq3GuidedTarget(doc, "m1", "B", applyToAllOccurrences = true)
        assertEquals(1, after.messages.size, "applying to all keeps one message, never splits it")
        assertEquals(3, after.messages.single().occurrences.size)
        assertEquals("B", after.messages.single().toLifelineId)
    }

    @Test
    fun applyingToOneOccurrenceSplitsAndLeavesTheRestUnresolved() {
        val doc = document(message("m1", "A", null, listOf(occurrence(1), occurrence(2), occurrence(3))))
        val after = applySeq3GuidedTarget(doc, "m1", "B", applyToAllOccurrences = false)
        assertEquals(2, after.messages.size)
        val remaining = after.messages.first { it.id == "m1" }
        val resolved = after.messages.first { it.id != "m1" }
        assertEquals(2, remaining.occurrences.size)
        assertNull(remaining.toLifelineId, "the unresolved remainder still needs a target")
        assertEquals(1, resolved.occurrences.size)
        assertEquals("B", resolved.toLifelineId)
    }

    @Test
    fun makeItASelfCallTargetsItsOwnLifeline() {
        val doc = document(message("m1", "A", null, listOf(occurrence(1))))
        val message = applySeq3GuidedSelfCall(doc, "m1").messages.single()
        assertEquals("A", message.toLifelineId)
        assertEquals(Seq3Kind.SELF, message.kind)
        assertEquals(Seq3Authoring.EDITED, message.authoring)
    }

    @Test
    fun pickingAMessagesOwnFromLifelineAsItsTargetAutoFlipsKindToSelf() {
        // Bug fix: applySeq3GuidedTarget let a user pick `to == from` without ever becoming a
        // self-call — the arrow would render as a straight line to itself. Same rule
        // applySeq3GuidedSelfCall already applies explicitly, now enforced here too.
        val doc = document(message("m1", "A", null, listOf(occurrence(1))))
        val message = applySeq3GuidedTarget(doc, "m1", "A").messages.single()
        assertEquals("A", message.toLifelineId)
        assertEquals(Seq3Kind.SELF, message.kind)
        assertEquals(Seq3Authoring.EDITED, message.authoring)
    }

    @Test
    fun pickingADifferentLifelineAsTargetLeavesKindAlone() {
        val doc = document(message("m1", "A", null, listOf(occurrence(1))))
        val message = applySeq3GuidedTarget(doc, "m1", "B").messages.single()
        assertEquals("B", message.toLifelineId)
        assertEquals(Seq3Kind.CALL, message.kind)
    }

    @Test
    fun aSkippedRowIsNotRevisitedInThisPassButStaysUnresolved() {
        val doc = document(
            message("m1", "A", null, listOf(occurrence(1))),
            message("m2", "B", null, listOf(occurrence(2))),
        )
        val pass = assertNotNull(startSeq3GuidedPass(doc))
        // Skip == advance with the document unchanged.
        val next = assertNotNull(advanceSeq3GuidedPass(doc, pass))
        assertEquals("m2", next.currentMessageId)
        assertTrue(doc.messages.first { it.id == "m1" }.state == Seq3State.NEEDS_TARGET)
    }

    @Test
    fun numberKeysMapOntoTheFirstNineLifelinesOnly() {
        val doc = document(message("m1", "A", null, listOf(occurrence(1))))
        assertEquals("A", seq3GuidedLifelineForKey(doc, 1)?.id)
        assertEquals("C", seq3GuidedLifelineForKey(doc, 3)?.id)
        assertNull(seq3GuidedLifelineForKey(doc, 4), "past the end maps to nothing, never wraps")
        val many = doc.copy(lifelines = (1..12).map { lifeline("L$it", it) })
        assertNull(seq3GuidedLifelineForKey(many, SEQ3_MAX_KEYED_LIFELINES + 1))
        assertEquals("L9", seq3GuidedLifelineForKey(many, SEQ3_MAX_KEYED_LIFELINES)?.id)
    }

    @Test
    fun suggestionIsTheNextDistinctTagOnTheSameThreadAndIsNeverApplied() {
        val entries = listOf(
            LogEntry(1, "14:22:01.000", LogLevel.I, "A", "start", pid = 100, tid = 7),
            LogEntry(2, "14:22:01.010", LogLevel.I, "B", "handoff", pid = 100, tid = 7),
        )
        val doc = document(message("m1", "A", null, listOf(occurrence(1))))
        val suggestion = suggestSeq3Target(doc.messages.single(), doc, entries)
        assertEquals("B", suggestion?.id)
        // The suggestion is advisory only — the document is untouched until something applies it.
        assertNull(doc.messages.single().toLifelineId)
        assertEquals(Seq3Authoring.AUTO, doc.messages.single().authoring)
    }

    @Test
    fun noSuggestionWhenTheThreadDoesNotMatch() {
        val entries = listOf(
            LogEntry(1, "14:22:01.000", LogLevel.I, "A", "start", pid = 100, tid = 7),
            LogEntry(2, "14:22:01.010", LogLevel.I, "B", "other thread", pid = 100, tid = 9),
        )
        val doc = document(message("m1", "A", null, listOf(occurrence(1))))
        assertNull(suggestSeq3Target(doc.messages.single(), doc, entries))
    }

    @Test
    fun surroundingLinesCentreOnTheFirstOccurrence() {
        val entries = (1..3).map { LogEntry(it, "14:22:0$it.000", LogLevel.I, "T$it", "line $it", pid = 1, tid = 1) }
        val doc = document(message("m1", "A", null, listOf(occurrence(2))))
        val context = seq3GuidedContext(doc.messages.single(), entries)
        assertEquals(1, context.previous?.id)
        assertEquals(2, context.current?.id)
        assertEquals(3, context.next?.id)
    }

    @Test
    fun newLifelineNameNeverCollidesWithAnExistingOne() {
        val doc = document().copy(lifelines = listOf(lifeline("A", 0)).plus(Seq3Lifeline("x", "Lifeline 2", emptySet(), 1)))
        assertEquals("Lifeline 3", newSeq3LifelineName(doc))
    }
}
