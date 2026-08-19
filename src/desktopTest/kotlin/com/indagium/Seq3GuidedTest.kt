package com.indagium

import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3State
import com.indagium.diagram3.advanceSeq3GuidedPass
import com.indagium.diagram3.applySeq3Command
import com.indagium.diagram3.applySeq3GuidedNewLifeline
import com.indagium.diagram3.applySeq3GuidedSelfCall
import com.indagium.diagram3.applySeq3GuidedTarget
import com.indagium.diagram3.beginSeq3GuidedPass
import com.indagium.diagram3.seq3GuidedContext
import com.indagium.diagram3.suggestSeq3Target
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3GuidedTest {
    private fun entry(id: Int, ts: String, tag: String, msg: String, pid: Int = 1, tid: Int = 1) = LogEntry(id, ts, LogLevel.I, tag, msg, pid, tid)

    private fun occ(id: Int) = Seq3Occurrence(id, 100L, "10:00:00.000", 1, 1, 'I', "text $id")

    private fun needsTargetMessage(id: String, from: String, entryId: Int) =
        Seq3Message(id, Seq3Match(from, "$id-label"), from, null, "$id-label", occurrences = listOf(occ(entryId)))

    // Producer(1) -> Consumer(2) -> Producer(3), same pid/tid — a real same-thread handoff chain.
    // Other(10) is unrelated (different thread), a distractor the suggestion must skip.
    private fun entries() = listOf(
        entry(1, "10:00:00.000", "Producer", "start"),
        entry(2, "10:00:00.010", "Consumer", "handle"),
        entry(3, "10:00:00.020", "Producer", "finish"),
        entry(10, "10:00:00.050", "Other", "unrelated", pid = 9, tid = 9),
    )

    private fun documentWith(vararg messages: Seq3Message) = Seq3Document(
        lifelines = listOf(
            Seq3Lifeline("Producer", "Producer", setOf("Producer"), 0),
            Seq3Lifeline("Consumer", "Consumer", setOf("Consumer"), 1),
        ),
        messages = messages.toList(),
    )

    // ── beginSeq3GuidedPass / advanceSeq3GuidedPass ─────────────────────────────────────────────

    @Test
    fun beginCollectsEveryNeedsTargetMessageInLogOrder() {
        val doc = documentWith(needsTargetMessage("m1", "Producer", 1), needsTargetMessage("m2", "Producer", 2))
        val state = beginSeq3GuidedPass(doc)
        assertNotNull(state)
        assertEquals(listOf("m1", "m2"), state.messageIds)
        assertEquals(2, state.totalAtStart)
    }

    @Test
    fun beginReturnsNullWhenNothingNeedsATarget() {
        val resolved = needsTargetMessage("m1", "Producer", 1).copy(toLifelineId = "Consumer")
        assertNull(beginSeq3GuidedPass(documentWith(resolved)))
    }

    @Test
    fun advanceDropsTheCurrentEntryWhetherResolvedOrSkipped() {
        val doc = documentWith(needsTargetMessage("m1", "Producer", 1), needsTargetMessage("m2", "Producer", 2))
        val state = beginSeq3GuidedPass(doc)!!

        val resolvedDoc = applySeq3GuidedTarget(doc, "m1", "Consumer")
        val afterResolve = advanceSeq3GuidedPass(resolvedDoc, state)
        assertNotNull(afterResolve)
        assertEquals("m2", afterResolve.currentMessageId)
        assertEquals(2, afterResolve.totalAtStart, "progress denominator stays fixed for the whole pass")

        val afterSkip = advanceSeq3GuidedPass(resolvedDoc, afterResolve) // "m2" was neither resolved nor removed: a Skip
        assertNull(afterSkip, "skipping the last remaining message completes the pass")
    }

    // ── suggestSeq3Target ────────────────────────────────────────────────────────────────────

    @Test
    fun suggestsTheNextDistinctTagOnTheSameThread() {
        val doc = documentWith(needsTargetMessage("m1", "Producer", 1))
        val suggestion = suggestSeq3Target(doc.messages.single(), doc, entries())
        assertEquals("Consumer", suggestion?.id)
    }

    @Test
    fun suggestionNeverCrossesToADifferentThread() {
        val onlyOtherThread = listOf(
            entry(1, "10:00:00.000", "Producer", "start", pid = 1, tid = 1),
            entry(10, "10:00:00.005", "Other", "unrelated", pid = 9, tid = 9),
        )
        val doc = Seq3Document(
            lifelines = listOf(Seq3Lifeline("Producer", "Producer", setOf("Producer"), 0), Seq3Lifeline("Other", "Other", setOf("Other"), 1)),
            messages = listOf(needsTargetMessage("m1", "Producer", 1)),
        )
        assertNull(suggestSeq3Target(doc.messages.single(), doc, onlyOtherThread))
    }

    @Test
    fun suggestionIsNeverAutoApplied() {
        val doc = documentWith(needsTargetMessage("m1", "Producer", 1))
        suggestSeq3Target(doc.messages.single(), doc, entries())
        // The document is a plain immutable data class — calling suggestSeq3Target on it cannot
        // itself have changed anything, but assert the invariant explicitly for the record.
        assertEquals(null, doc.messages.single().toLifelineId)
        assertEquals(Seq3State.NEEDS_TARGET, doc.messages.single().state)
    }

    // ── seq3GuidedContext ────────────────────────────────────────────────────────────────────

    @Test
    fun contextReturnsThePreviousCurrentAndNextLines() {
        val doc = documentWith(needsTargetMessage("m1", "Consumer", 2))
        val context = seq3GuidedContext(doc.messages.single(), entries())
        assertEquals(1, context.previous?.id)
        assertEquals(2, context.current?.id)
        assertEquals(3, context.next?.id)
    }

    // ── applySeq3GuidedTarget ────────────────────────────────────────────────────────────────

    @Test
    fun acceptingATargetMarksTheMessageEdited() {
        val doc = documentWith(needsTargetMessage("m1", "Producer", 1))
        val result = applySeq3GuidedTarget(doc, "m1", "Consumer")
        val m1 = result.messages.single()
        assertEquals("Consumer", m1.toLifelineId)
        assertEquals(Seq3Authoring.EDITED, m1.authoring)
    }

    @Test
    fun applyToAllOccurrencesCoversEveryOccurrence() {
        val message = Seq3Message(
            "m1", Seq3Match("Producer", "tpl"), "Producer", null, "tpl",
            occurrences = listOf(occ(1), occ(2), occ(3)),
        )
        val doc = documentWith(message)
        val result = applySeq3GuidedTarget(doc, "m1", "Consumer", applyToAllOccurrences = true)
        val resolved = result.messages.single()
        assertEquals("Consumer", resolved.toLifelineId)
        assertEquals(3, resolved.occurrences.size, "checked 'apply to all' must resolve every occurrence in place")
    }

    @Test
    fun applyingToOnlyTheFirstOccurrenceSplitsOffARemainderStillNeedingATarget() {
        val message = Seq3Message(
            "m1", Seq3Match("Producer", "tpl"), "Producer", null, "tpl",
            occurrences = listOf(occ(1), occ(2), occ(3)),
        )
        val doc = documentWith(message)
        val result = applySeq3GuidedTarget(doc, "m1", "Consumer", applyToAllOccurrences = false)

        val resolved = result.messages.first { it.toLifelineId == "Consumer" }
        assertEquals(listOf(1), resolved.occurrences.map { it.entryId })
        val remainder = result.messages.first { it.id == "m1" }
        assertEquals(listOf(2, 3), remainder.occurrences.map { it.entryId })
        assertNull(remainder.toLifelineId)
    }

    // ── Make it a self-call / New lifeline ───────────────────────────────────────────────────

    @Test
    fun makeItASelfCallTargetsItsOwnLifeline() {
        val doc = documentWith(needsTargetMessage("m1", "Producer", 1))
        val result = applySeq3GuidedSelfCall(doc, "m1")
        val m1 = result.messages.single()
        assertEquals("Producer", m1.toLifelineId)
        assertEquals(Seq3Kind.SELF, m1.kind)
    }

    @Test
    fun newLifelineIsAddedWithoutChoosingItAsTheTarget() {
        val doc = documentWith(needsTargetMessage("m1", "Producer", 1))
        val newLifeline = Seq3Lifeline("Database", "Database", setOf("Database"), 2)
        val result = applySeq3GuidedNewLifeline(doc, "m1", newLifeline)
        assertTrue(result.lifelines.any { it.id == "Database" })
        assertNull(result.messages.single().toLifelineId)
    }

    // ── item 5 (WP5): "＋ New lifeline" hits the same empty-tagIds bug WP1 fixed for AddLifeline ──

    @Test
    fun newLifelineWithNoTagIdsIsDefaultedToARepresentedTagFromItsOwnName() {
        val doc = documentWith(needsTargetMessage("m1", "Producer", 1))
        // The exact shape ui.Seq3GuidedPass's "＋ New lifeline" button builds: tagIds = emptySet().
        val newLifeline = Seq3Lifeline(id = "seq3-lifeline-3-database", name = "Database", tagIds = emptySet(), ordinal = 2)
        val result = applySeq3GuidedNewLifeline(doc, "m1", newLifeline)
        assertEquals(setOf("Database"), result.lifelines.single { it.id == "seq3-lifeline-3-database" }.tagIds)
    }

    @Test
    fun aGuidedNewLifelineMergesWithMultipleRepresentedTagsAfterwards() {
        val doc = documentWith(needsTargetMessage("m1", "Producer", 1))
        val newLifeline = Seq3Lifeline(id = "seq3-lifeline-3-database", name = "Database", tagIds = emptySet(), ordinal = 2)
        val withNewLifeline = applySeq3GuidedNewLifeline(doc, "m1", newLifeline)

        val merged = applySeq3Command(
            withNewLifeline,
            Seq3Command.MergeLifelines(keepLifelineId = "Producer", mergedLifelineId = "seq3-lifeline-3-database"),
        )
        assertTrue(merged.applied)
        val keep = merged.document.lifelines.single { it.id == "Producer" }
        assertTrue(keep.tagIds.size > 1, "expected a folded multi-tag lifeline, got ${keep.tagIds}")
        assertEquals(setOf("Producer", "Database"), keep.tagIds)
    }
}
